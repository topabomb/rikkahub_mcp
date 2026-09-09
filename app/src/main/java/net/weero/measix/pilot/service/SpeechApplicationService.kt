package net.weero.measix.pilot.service

import android.content.Context
import net.weero.measix.pilot.R
import java.util.concurrent.atomic.AtomicBoolean
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.asr.*
import me.rerere.asr.providers.HttpAsrController
import me.rerere.asr.providers.RealtimeAsrController
import me.rerere.common.configuration.ConfigurationReference
import me.rerere.tts.controller.*
import me.rerere.tts.model.*
import me.rerere.tts.provider.*
import net.weero.measix.pilot.data.ai.tools.local.TtsToolPlaybackContext
import net.weero.measix.pilot.data.ai.tts.TtsPlaybackSource
import net.weero.measix.pilot.data.configuration.*
import net.weero.measix.pilot.data.datastore.ExecutionConfigurationSnapshot
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.utils.stripMarkdown
import okhttp3.OkHttpClient
import kotlin.uuid.Uuid

/** Opaque immutable execution inputs; queue identity and UI source are separate from resource authority. */
class SpeechCapture internal constructor(
    internal val selection: RealmSelection,
    internal val category: ConfigurationCategory,
    internal val reference: ConfigurationReference?,
    internal val userTts: TTSProviderSetting?,
    internal val userAsr: ASRProviderSetting?,
    internal val enterpriseTts: EnterpriseTtsResource?,
    internal val enterpriseAsr: EnterpriseAsrResource?,
    internal val version: EnterpriseAppliedVersion?,
    internal val interactionId: String,
    internal val requireOwner: () -> Unit,
    internal val stopParent: suspend () -> Unit,
    internal val guidance: String,
    internal val unavailable: Boolean,
    internal val sequential: Boolean,
) {
    private val terminated = AtomicBoolean(false)
    internal fun terminate(): Boolean = terminated.compareAndSet(false, true)
    internal fun requireActive() { check(!terminated.get()) { "speech_interaction_terminated" } }
}

interface SpeechPlayback {
    val isAvailable: StateFlow<Boolean>
    val isSpeaking: StateFlow<Boolean>
    val error: StateFlow<String?>
    val currentChunk: StateFlow<Int>
    val totalChunks: StateFlow<Int>
    val playbackState: StateFlow<PlaybackState>
    val activeSource: StateFlow<TtsPlaybackSource?>
    fun speak(page: ConversationCommandTarget, text: String)
    fun speak(selection: RealmSelection, text: String)
    fun speak(context: TtsToolPlaybackContext, text: String, replaceWithinSession: Boolean)
    fun stop()
    fun pause()
    fun resume()
    fun skipNext()
    fun fastForward(ms: Long = 5_000)
    fun setSpeed(speed: Float)
}

interface SpeechRecognition {
    val state: StateFlow<ASRState>
    fun start(page: ConversationCommandTarget, onTranscriptChange: (String) -> Unit)
    fun stop()
    fun cancel(page: ConversationCommandTarget)
}

/** One owner for app speech, resource admission, hardware lifetime and original binding cleanup. */
internal class SpeechApplicationService(
    private val context: Context,
    private val settings: SettingsStore,
    private val sessions: EnterpriseSessionController,
    private val queries: ConfigurationQueryService,
    private val synchronization: EnterpriseSynchronizationService,
    private val manager: TTSManager,
    private val transport: EnterpriseSpeechTransport,
    private val sockets: OkHttpClient,
    private val scope: CoroutineScope,
    private val player: TtsController,
) {
    private val commands = Mutex()
    private val synthesizer = TtsSynthesizer(manager)
    private val available = MutableStateFlow(false)
    private val failure = MutableStateFlow<String?>(null)
    private val asrState = MutableStateFlow(ASRState())
    private var tts: Playback? = null
    private var asr: Recognition? = null
    private var asrAvailable = false

    private class Playback(val capture: SpeechCapture, val queueId: String, var bindings: EnterpriseBindingLease?) {
        var cleanup: TtsPlaybackCleanup? = null
        var revoked = false
    }
    private class Recognition(val capture: SpeechCapture, val page: ConversationCommandTarget, var bindings: EnterpriseBindingLease?) {
        val deliveries = SupervisorJob()
        var controller: ASRController? = null
        var projection: Job? = null
        var cleanup: AsrCleanup? = null
        var revoked = false
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val audioFocus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAcceptsDelayedFocusGain(false).build()

    val playback: SpeechPlayback = object : SpeechPlayback {
        override val isAvailable = available.asStateFlow()
        override val isSpeaking get() = player.isSpeaking
        override val error = failure.asStateFlow()
        override val currentChunk get() = player.currentChunk
        override val totalChunks get() = player.totalChunks
        override val playbackState get() = player.playbackState
        @Suppress("UNCHECKED_CAST")
        override val activeSource get() = player.activeSource as StateFlow<TtsPlaybackSource?>
        override fun speak(page: ConversationCommandTarget, text: String) = submit {
            enqueue(capturePage(page.selection, ConfigurationCategory.TTS, page::requireOpen), Uuid.random().toString(), text, true, null)
        }
        override fun speak(selection: RealmSelection, text: String) = submit {
            enqueue(capturePage(selection, ConfigurationCategory.TTS), Uuid.random().toString(), text, true, null)
        }
        override fun speak(context: TtsToolPlaybackContext, text: String, replaceWithinSession: Boolean) = submit {
            enqueue(context.capture, context.sessionId, text, replaceWithinSession, context.toPlaybackSource())
        }
        override fun stop() = submit { commands.withLock { tts?.capture?.terminate(); closePlayback() } }
        override fun pause() = submit { commands.withLock { player.pause() } }
        override fun resume() = submit { commands.withLock { player.resume() } }
        override fun skipNext() = submit { commands.withLock { player.skipNext() } }
        override fun fastForward(ms: Long) = submit { commands.withLock { player.fastForward(ms) } }
        override fun setSpeed(speed: Float) = submit { player.setSpeed(speed) }
    }

    val recognition: SpeechRecognition = object : SpeechRecognition {
        override val state = asrState.asStateFlow()
        override fun start(page: ConversationCommandTarget, onTranscriptChange: (String) -> Unit) = submitRecognition {
            startRecognition(page, onTranscriptChange)
        }
        override fun stop() = submitRecognition { commands.withLock { asr?.controller?.stop() } }
        override fun cancel(page: ConversationCommandTarget) = submitRecognition {
            commands.withLock { if (asr?.page === page) closeRecognition() }
        }
    }

    init {
        scope.launch(Dispatchers.Main.immediate) {
            queries.observeCurrent().collect { configuration ->
                available.value = configuration.selection(ResourceSelectionSlot.TTS).isAvailable
                asrAvailable = configuration.selection(ResourceSelectionSlot.ASR).isAvailable
                if (asr == null) asrState.value = ASRState(isAvailable = asrAvailable)
                val playing = tts
                val recording = asr
                val stopTts = playing != null && playing.capture.reference?.let { !configuration.access(ConfigurationCategory.TTS, it).canExecute } != false
                val stopAsr = recording != null && recording.capture.reference?.let { !configuration.access(ConfigurationCategory.ASR, it).canExecute } != false
                if (stopTts || stopAsr) commands.withLock {
                    if (stopTts && tts === playing) closePlayback()
                    if (stopAsr && asr === recording) closeRecognition()
                }
            }
        }
        scope.launch(Dispatchers.Main.immediate) {
            player.playbackState.collect { state ->
                val original = tts
                if (original != null && state.status in setOf(PlaybackStatus.Ended, PlaybackStatus.Error)) {
                    submit { commands.withLock { if (tts === original) closePlayback() } }
                }
            }
        }
        scope.launch(Dispatchers.Main.immediate) { player.error.collect { if (it != null) failure.value = it } }
    }

    internal fun captureTurn(captured: CapturedModelConfiguration, access: RealmAccess, stopParent: suspend () -> Unit): SpeechCapture =
        freeze(RealmSelection(access, captured.selectionRevision), ConfigurationCategory.TTS,
            ExecutionConfigurationSnapshot(captured.userSettings, captured.configuration, captured.model.userRevision),
            captured.model.enterpriseVersion, "int_${captured.interactionId}", {}, stopParent)

    private suspend fun capturePage(selection: RealmSelection, category: ConfigurationCategory, owner: () -> Unit = {}): SpeechCapture {
        queries.requireSelection(selection)
        return sessions.withSelectedRealmSelection(selection) {
            owner()
            settings.withExecutionConfiguration(selection.access.scope, sessions.state.value) { snapshot ->
                freeze(selection, category, snapshot, (sessions.state.value as? EnterpriseState.Available)?.manifest?.applied,
                    "int_${Uuid.random()}", owner, {})
            }
        }
    }

    private fun freeze(selection: RealmSelection, category: ConfigurationCategory, snapshot: ExecutionConfigurationSnapshot,
        version: EnterpriseAppliedVersion?, interactionId: String, owner: () -> Unit, stopParent: suspend () -> Unit): SpeechCapture {
        val selected = snapshot.configuration.selection(if (category == ConfigurationCategory.TTS) ResourceSelectionSlot.TTS else ResourceSelectionSlot.ASR)
        val reference = selected.reference
        val userTts = snapshot.userSettings.ttsProviders.find { it.id == reference }?.copyProvider()
        val userAsr = snapshot.userSettings.asrProviders.find { it.id == reference }?.copyProvider()
        val managed = reference as? ConfigurationReference.Enterprise
        return SpeechCapture(selection, category, reference, userTts, userAsr,
            managed?.let { snapshot.configuration.enterpriseConfiguration?.tts?.find { value -> value.id == it.id } },
            managed?.let { snapshot.configuration.enterpriseConfiguration?.asr?.find { value -> value.id == it.id } },
            version.takeIf { selection.access is RealmAccess.Enterprise }, interactionId, owner, stopParent,
            userTts?.takeIf { selected.isAvailable }?.let(manager::getPromptGuidance).orEmpty(), !selected.isAvailable, snapshot.userSettings.displaySetting.ttsToolSequentialPlayback)
    }

    /** Completion acknowledges queue acceptance, while the queue retains its own binding through playback. */
    internal suspend fun enqueue(capture: SpeechCapture, queueId: String, text: String, replace: Boolean, source: TtsPlaybackSource?) =
        withContext(Dispatchers.Main.immediate) {
            require(text.isNotBlank()) { "speech_text_required" }
            commands.withLock {
                admit(capture) { }
                val existing = tts
                if (existing != null && existing.capture === capture && existing.queueId == queueId && !replace && !existing.revoked) {
                    admit(capture) { check(tts === existing && !existing.revoked); player.speak(text.stripMarkdown(), false, source, queueId) }
                    return@withLock
                }
                closePlayback()
                val original = Playback(capture, queueId, null).also { tts = it }
                try {
                    captureBinding(capture) { original.bindings = it }
                    admit(capture) {
                        check(tts === original && !original.revoked)
                        player.setSession(TtsPlaybackSession(
                            synthesize = { chunk -> synthesize(original, chunk) },
                            admitPlayback = { start -> admit(capture) { check(tts === original && !original.revoked); start() } },
                        ))
                        player.speak(text.stripMarkdown(), false, source, queueId)
                    }
                    failure.value = null
                } catch (error: Throwable) {
                    try { withContext(NonCancellable) { closePlayback() } }
                    catch (cleanup: Throwable) { if (cleanup !== error) error.addSuppressed(cleanup) }
                    throw error
                }
            }
        }

    private suspend fun synthesize(original: Playback, chunk: TtsChunk): TTSResponse = try {
        val capture = original.capture
        var personal: TTSProviderSetting? = null
        admit(capture) { latest ->
            check(tts === original && !original.revoked)
            if (capture.userTts != null) personal = ttsCredentials(capture.userTts, latest.userSettings.ttsProviders.single { it.id == capture.reference })
        }
        if (capture.enterpriseTts != null) transport.synthesize(target(capture, original.bindings), capture.enterpriseTts, chunk.text)
        else synthesizer.synthesize(requireNotNull(personal) { "speech_resource_unavailable" }, chunk)
    } catch (barrier: ManagedSnapshotRequired) {
        handleBarrier(original.capture)
        throw barrier
    }

    private suspend fun startRecognition(page: ConversationCommandTarget, deliver: (String) -> Unit) {
        val capture = capturePage(page.selection, ConfigurationCategory.ASR, page::requireOpen)
        commands.withLock {
            admit(capture) { }
            closeRecognition()
            val original = Recognition(capture, page, null).also { asr = it }
            try {
                captureBinding(capture) { original.bindings = it }
                admit(capture) { latest ->
                    check(asr === original && !original.revoked)
                    check(audioManager.requestAudioFocus(audioFocus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "speech_audio_focus_unavailable" }
                    val recordingAdmission: suspend (() -> Unit) -> Unit = { start -> admit(capture) {
                        check(asr === original && !original.revoked); start()
                    } }
                    original.controller = if (capture.enterpriseAsr != null) HttpAsrController(context, recordingAdmission,
                        transcribe = { file ->
                            try {
                                admit(capture) { check(asr === original && !original.revoked) }
                                transport.transcribe(target(capture, original.bindings), capture.enterpriseAsr, file)
                            } catch (barrier: ManagedSnapshotRequired) { handleBarrier(capture); throw barrier }
                        }, admitTranscript = recordingAdmission)
                    else RealtimeAsrController(context, sockets, asrCredentials(requireNotNull(capture.userAsr),
                        latest.userSettings.asrProviders.single { it.id == capture.reference }), recordingAdmission)
                    original.controller!!.start { text ->
                        scope.launch(original.deliveries + Dispatchers.Main.immediate) {
                            try {
                                admit(capture) { if (asr === original && !original.revoked) deliver(text) }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) {
                                if (asr === original && !original.revoked) asrState.value = ASRState(
                                    status = ASRStatus.Error, isAvailable = asrAvailable, errorMessage = context.getString(R.string.speech_operation_unavailable))
                            }
                        }
                    }
                    original.projection = scope.launch(Dispatchers.Main) {
                        original.controller!!.state.collect { state ->
                            if (asr === original && !original.revoked) {
                                asrState.value = state
                                if (!state.isRecording) audioManager.abandonAudioFocusRequest(audioFocus)
                                if (state.status in setOf(ASRStatus.Idle, ASRStatus.Error)) submit {
                                    commands.withLock {
                                        if (asr === original && !original.revoked) {
                                            original.deliveries.children.toList().joinAll()
                                            closeRecognition(state)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (error: Throwable) {
                try { withContext(NonCancellable) { closeRecognition() } }
                catch (cleanup: Throwable) { if (cleanup !== error) error.addSuppressed(cleanup) }
                throw error
            }
        }
    }

    private suspend fun captureBinding(capture: SpeechCapture, retain: (EnterpriseBindingLease) -> Unit) {
        admit(capture) { }
        if (capture.reference !is ConfigurationReference.Enterprise) return
        val binding = sessions.captureBindings(capture.selection.access as RealmAccess.Enterprise)
        retain(binding)
        check(binding.version == capture.version) { "enterprise_configuration_changed_during_speech_capture" }
    }

    private fun target(capture: SpeechCapture, bindings: EnterpriseBindingLease?): EnterpriseSpeechTarget {
        val reference = capture.reference as ConfigurationReference.Enterprise
        val lease = requireNotNull(bindings)
        return EnterpriseSpeechTarget(capture.selection.access as RealmAccess.Enterprise, reference,
            lease.binding(reference.id), lease.version, capture.interactionId)
    }

    private suspend fun <T> admit(capture: SpeechCapture, accept: (ExecutionConfigurationSnapshot) -> T): T =
        sessions.withSelectedRealmSelection(capture.selection) {
            currentCoroutineContext().ensureActive()
            capture.requireActive()
            capture.requireOwner()
            check(!capture.unavailable) { "speech_selection_unavailable" }
            if (capture.selection.access is RealmAccess.Enterprise) {
                check((sessions.state.value as? EnterpriseState.Available)?.manifest?.phase == EnterpriseSessionPhase.READY) { "enterprise_session_not_ready" }
            }
            settings.withExecutionConfiguration(capture.selection.access.scope, sessions.state.value) { latest ->
                check(latest.configuration.access(capture.category, requireNotNull(capture.reference)).canExecute) { "speech_resource_unavailable" }
                withContext(Dispatchers.Main.immediate) {
                    sessions.requirePublishedSelection(capture.selection)
                    capture.requireActive()
                    capture.requireOwner()
                    accept(latest)
                }
            }
        }

    /** Called under Session admission: revoke and stop synchronously, never wait for the command mutex. */
    suspend fun revoke(access: RealmAccess) = withContext(Dispatchers.Main.immediate) {
        tts?.takeIf { it.capture.selection.access == access }?.let(::stopPlayback)
        asr?.takeIf { it.capture.selection.access == access }?.let(::stopRecognition)
    }

    suspend fun closeRealm(access: RealmAccess) = withContext(Dispatchers.Main.immediate) {
        commands.withLock {
            if (tts?.capture?.selection?.access == access) closePlayback()
            if (asr?.capture?.selection?.access == access) closeRecognition()
        }
    }

    private fun stopPlayback(original: Playback) {
        original.revoked = true
        if (original.cleanup == null) original.cleanup = player.stop()
    }
    private suspend fun closePlayback() {
        val original = tts ?: return
        stopPlayback(original)
        withContext(NonCancellable) { original.cleanup!!.awaitClosed(); original.bindings?.release() }
        if (tts === original) { tts = null; player.setSession(null) }
    }
    private fun stopRecognition(original: Recognition) {
        original.revoked = true
        original.projection?.cancel()
        original.deliveries.cancel()
        if (original.cleanup == null) original.cleanup = original.controller?.dispose()
        audioManager.abandonAudioFocusRequest(audioFocus)
    }
    private suspend fun closeRecognition(terminal: ASRState? = null) {
        val original = asr ?: return
        stopRecognition(original)
        withContext(NonCancellable) { original.projection?.join(); original.deliveries.join(); original.cleanup?.awaitClosed(); original.bindings?.release() }
        if (asr === original) { asr = null; asrState.value = terminal?.copy(isAvailable = asrAvailable) ?: ASRState(isAvailable = asrAvailable) }
    }
    private fun handleBarrier(capture: SpeechCapture) {
        if (!capture.terminate()) return
        submit {
            tts?.takeIf { it.capture === capture }?.let(::stopPlayback)
            asr?.takeIf { it.capture === capture }?.let(::stopRecognition)
            // This accepted barrier owns termination; a failed file/lease cleanup must not skip parent stop or sync.
            withContext(NonCancellable) {
                var failure: Exception? = null
                suspend fun attempt(action: suspend () -> Unit) {
                    try { action() } catch (error: Exception) {
                        if (failure == null) failure = error else if (error !== failure) failure!!.addSuppressed(error)
                    }
                }
                attempt { capture.stopParent() }
                attempt { commands.withLock {
                    if (tts?.capture === capture) closePlayback()
                    if (asr?.capture === capture) closeRecognition()
                } }
                attempt { synchronization.synchronize(capture.selection.access as RealmAccess.Enterprise) }
                failure?.let { throw it }
            }
            failure.value = context.getString(R.string.speech_operation_unavailable)
        }
    }

    private fun submit(action: suspend () -> Unit) {
        scope.launch(Dispatchers.Main.immediate) {
            try { action() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failure.value = context.getString(R.string.speech_operation_unavailable) }
        }
    }
    private fun submitRecognition(action: suspend () -> Unit) = submit {
        try { action() } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            asrState.value = ASRState(status = ASRStatus.Error, isAvailable = asrAvailable, errorMessage = context.getString(R.string.speech_operation_unavailable))
            throw error
        }
    }

    private fun ttsCredentials(frozen: TTSProviderSetting, current: TTSProviderSetting): TTSProviderSetting = when (frozen) {
        is TTSProviderSetting.OpenAI -> { check(current is TTSProviderSetting.OpenAI && frozen.baseUrl == current.baseUrl); frozen.copy(apiKey = current.apiKey) }
        is TTSProviderSetting.Gemini -> { check(current is TTSProviderSetting.Gemini && frozen.baseUrl == current.baseUrl); frozen.copy(apiKey = current.apiKey) }
        is TTSProviderSetting.MiMo -> { check(current is TTSProviderSetting.MiMo && frozen.baseUrl == current.baseUrl); frozen.copy(apiKey = current.apiKey) }
        is TTSProviderSetting.SystemTTS -> { check(current is TTSProviderSetting.SystemTTS); frozen }
    }
    private fun asrCredentials(frozen: ASRProviderSetting, current: ASRProviderSetting): ASRProviderSetting = when (frozen) {
        is ASRProviderSetting.OpenAIRealtime -> { check(current is ASRProviderSetting.OpenAIRealtime && frozen.websocketUrl == current.websocketUrl); frozen.copy(apiKey = current.apiKey) }
        is ASRProviderSetting.DashScope -> { check(current is ASRProviderSetting.DashScope && frozen.websocketUrl == current.websocketUrl); frozen.copy(apiKey = current.apiKey) }
    }
}
