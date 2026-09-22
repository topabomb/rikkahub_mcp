package me.rerere.tts.provider.providers

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.rerere.common.android.appTempFolder
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.SystemTtsParameterPolicy
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderSetting
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "SystemTTSProvider"
private const val SYNTHESIS_TIMEOUT_MS = 30_000L

/** Stable failure surfaced by the speech application owner instead of escaping an Android callback. */
internal class SystemTtsException(
    val reason: String,
    detail: String,
    cause: Throwable? = null,
) : IllegalStateException("$reason: $detail", cause)

internal interface SystemTtsProgressListener {
    fun onDone(utteranceId: String)
    fun onError(utteranceId: String, errorCode: Int)
    fun onStop(utteranceId: String, interrupted: Boolean)
}

/** Small testable boundary around vendor TextToSpeech implementations. */
internal interface SystemTtsEngine {
    val name: String
    fun setLanguage(locale: Locale): Int
    fun setSpeechRate(rate: Float): Int
    fun setPitch(pitch: Float): Int
    fun setProgressListener(listener: SystemTtsProgressListener): Int
    fun synthesizeToFile(text: String, file: File, utteranceId: String): Int
    fun shutdown()
}

internal fun interface SystemTtsEngineFactory {
    fun create(onInit: (Int) -> Unit): SystemTtsEngine
}

private class AndroidSystemTtsEngine(private val delegate: TextToSpeech) : SystemTtsEngine {
    override val name: String = delegate.defaultEngine ?: "system-default"

    override fun setLanguage(locale: Locale): Int = delegate.setLanguage(locale)

    override fun setSpeechRate(rate: Float): Int = delegate.setSpeechRate(rate)

    override fun setPitch(pitch: Float): Int = delegate.setPitch(pitch)

    override fun setProgressListener(listener: SystemTtsProgressListener): Int =
        delegate.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                utteranceId?.let(listener::onDone)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                utteranceId?.let { listener.onError(it, TextToSpeech.ERROR) }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                utteranceId?.let { listener.onError(it, errorCode) }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                utteranceId?.let { listener.onStop(it, interrupted) }
            }
        })

    override fun synthesizeToFile(text: String, file: File, utteranceId: String): Int =
        delegate.synthesizeToFile(text, Bundle(), file, utteranceId)

    override fun shutdown() = delegate.shutdown()
}

/**
 * Serializes system synthesis because vendor engines frequently do not tolerate concurrent clients.
 * Remote providers keep their existing controller prefetch behavior.
 */
internal class SystemTtsSynthesisCoordinator(
    private val timeoutMs: Long = SYNTHESIS_TIMEOUT_MS,
) {
    private val synthesis = Mutex()

    suspend fun synthesize(
        factory: SystemTtsEngineFactory,
        setting: TTSProviderSetting.SystemTTS,
        text: String,
        locale: Locale,
        audioFile: File,
    ): ByteArray = synthesis.withLock {
        var engine: SystemTtsEngine? = null
        var failure: Throwable? = null
        try {
            SystemTtsParameterPolicy.requireValid(setting.speechRate.toDouble(), setting.pitch.toDouble())
            try {
                withTimeout(timeoutMs) {
                    val initialized = awaitInitializedEngine(factory) { created -> engine = created }
                    configure(initialized, setting, locale)
                    awaitSynthesis(initialized, text, audioFile)
                    if (!audioFile.isFile || audioFile.length() <= 0L) {
                        throw SystemTtsException(
                            reason = "system_tts_empty_output",
                            detail = "Engine ${requireNotNull(engine).name} completed without audio data",
                        )
                    }
                    audioFile.readBytes()
                }
            } catch (timeout: TimeoutCancellationException) {
                throw SystemTtsException(
                    reason = "system_tts_timeout",
                    detail = "System TTS did not finish within ${timeoutMs}ms",
                    cause = timeout,
                )
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val current = engine
            var terminalShutdownFailure: SystemTtsException? = null
            if (current != null) {
                try {
                    current.shutdown()
                } catch (shutdownFailure: Exception) {
                    val wrapped = SystemTtsException(
                        reason = "system_tts_shutdown_failed",
                        detail = "Engine ${current.name} failed to release",
                        cause = shutdownFailure,
                    )
                    if (failure != null) requireNotNull(failure).addSuppressed(wrapped) else terminalShutdownFailure = wrapped
                }
            }
            if (!audioFile.delete() && audioFile.exists()) {
                Log.w(TAG, "Unable to delete temporary system TTS output: ${audioFile.name}")
            }
            terminalShutdownFailure?.let { throw it }
        }
    }

    private suspend fun awaitInitializedEngine(
        factory: SystemTtsEngineFactory,
        retain: (SystemTtsEngine) -> Unit,
    ): SystemTtsEngine {
        var created: SystemTtsEngine? = null
        val status = suspendCancellableCoroutine<Int> { continuation ->
            val completed = AtomicBoolean(false)
            continuation.invokeOnCancellation { completed.set(true) }
            try {
                created = factory.create { result ->
                    if (completed.compareAndSet(false, true) && continuation.isActive) {
                        continuation.resume(result)
                    }
                }
                requireNotNull(created).also(retain)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (completed.compareAndSet(false, true) && continuation.isActive) {
                    continuation.resumeWithException(
                        SystemTtsException("system_tts_initialization_failed", "Unable to create the system TTS engine", error)
                    )
                }
            }
        }
        val engine = created ?: throw SystemTtsException(
            reason = "system_tts_initialization_failed",
            detail = "System TTS returned without an engine instance",
        )
        if (status != TextToSpeech.SUCCESS) {
            throw SystemTtsException(
                reason = "system_tts_initialization_failed",
                detail = "Engine ${engine.name} returned status $status",
            )
        }
        return engine
    }

    private fun configure(engine: SystemTtsEngine, setting: TTSProviderSetting.SystemTTS, locale: Locale) {
        val language = engineCall("system_tts_language_failed", engine) { engine.setLanguage(locale) }
        if (language < TextToSpeech.LANG_AVAILABLE) {
            throw SystemTtsException(
                reason = "system_tts_language_unavailable",
                detail = "Engine ${engine.name} does not support locale ${locale.toLanguageTag()} (status=$language)",
            )
        }
        requireSuccess("system_tts_speech_rate_failed", engine) { engine.setSpeechRate(setting.speechRate) }
        requireSuccess("system_tts_pitch_failed", engine) { engine.setPitch(setting.pitch) }
    }

    private suspend fun awaitSynthesis(engine: SystemTtsEngine, text: String, audioFile: File) {
        val expectedUtteranceId = UUID.randomUUID().toString()
        suspendCancellableCoroutine<Unit> { continuation ->
            val completed = AtomicBoolean(false)
            continuation.invokeOnCancellation { completed.set(true) }
            fun completeSuccess() {
                if (completed.compareAndSet(false, true) && continuation.isActive) continuation.resume(Unit)
            }
            fun completeFailure(error: Throwable) {
                if (completed.compareAndSet(false, true) && continuation.isActive) {
                    continuation.resumeWithException(error)
                }
            }
            val listener = object : SystemTtsProgressListener {
                override fun onDone(utteranceId: String) {
                    if (utteranceId != expectedUtteranceId) return
                    completeSuccess()
                }

                override fun onError(utteranceId: String, errorCode: Int) {
                    if (utteranceId != expectedUtteranceId) return
                    completeFailure(
                        SystemTtsException(
                            reason = "system_tts_synthesis_failed",
                            detail = "Engine ${engine.name} reported error code $errorCode",
                        )
                    )
                }

                override fun onStop(utteranceId: String, interrupted: Boolean) {
                    if (utteranceId != expectedUtteranceId) return
                    completeFailure(
                        SystemTtsException(
                            reason = "system_tts_synthesis_stopped",
                            detail = "Engine ${engine.name} stopped synthesis (interrupted=$interrupted)",
                        )
                    )
                }
            }
            val listenerResult = engineCall("system_tts_listener_failed", engine) {
                engine.setProgressListener(listener)
            }
            if (listenerResult != TextToSpeech.SUCCESS) {
                completeFailure(
                    SystemTtsException(
                        reason = "system_tts_listener_failed",
                        detail = "Engine ${engine.name} rejected the progress listener (status=$listenerResult)",
                    )
                )
                return@suspendCancellableCoroutine
            }
            val startResult = engineCall("system_tts_synthesis_start_failed", engine) {
                engine.synthesizeToFile(text, audioFile, expectedUtteranceId)
            }
            if (startResult != TextToSpeech.SUCCESS) {
                completeFailure(
                    SystemTtsException(
                        reason = "system_tts_synthesis_start_failed",
                        detail = "Engine ${engine.name} rejected synthesis (status=$startResult)",
                    )
                )
            }
        }
    }

    private inline fun engineCall(reason: String, engine: SystemTtsEngine, call: () -> Int): Int = try {
        call()
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        throw SystemTtsException(reason, "Engine ${engine.name} threw ${error.javaClass.simpleName}: ${error.message}", error)
    }

    private inline fun requireSuccess(reason: String, engine: SystemTtsEngine, call: () -> Int) {
        val status = engineCall(reason, engine, call)
        if (status != TextToSpeech.SUCCESS) {
            throw SystemTtsException(reason, "Engine ${engine.name} returned status $status")
        }
    }
}

class SystemTTSProvider : TTSProvider<TTSProviderSetting.SystemTTS> {
    private val coordinator = SystemTtsSynthesisCoordinator()

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.SystemTTS,
        request: TTSRequest,
    ): Flow<AudioChunk> = flow {
        val audioFile = File.createTempFile("tts_", ".wav", context.appTempFolder)
        val audioData = try {
            withContext(Dispatchers.Main.immediate) {
                coordinator.synthesize(
                    factory = SystemTtsEngineFactory { onInit ->
                        var delegate: TextToSpeech? = null
                        delegate = TextToSpeech(context.applicationContext) { status -> onInit(status) }
                        AndroidSystemTtsEngine(requireNotNull(delegate))
                    },
                    setting = providerSetting,
                    text = request.text,
                    locale = Locale.getDefault(),
                    audioFile = audioFile,
                )
            }
        } finally {
            if (!audioFile.delete() && audioFile.exists()) {
                Log.w(TAG, "Unable to delete temporary system TTS output: ${audioFile.name}")
            }
        }
        emit(
            AudioChunk(
                data = audioData,
                format = AudioFormat.WAV,
                isLast = true,
                metadata = mapOf(
                    "provider" to "system",
                    "speechRate" to providerSetting.speechRate.toString(),
                    "pitch" to providerSetting.pitch.toString(),
                ),
            )
        )
    }
}
