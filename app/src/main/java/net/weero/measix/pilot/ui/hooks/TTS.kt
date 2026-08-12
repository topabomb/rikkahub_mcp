package net.weero.measix.pilot.ui.hooks

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import me.rerere.tts.model.PlaybackState
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.getSelectedTTSProvider
import net.weero.measix.pilot.utils.stripMarkdown
import me.rerere.tts.model.TTSResponse
import me.rerere.tts.provider.TTSManager
import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.tts.controller.TtsController
import org.koin.compose.koinInject
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private const val TAG = "TTS"

/**
 * Composable function to remember and manage custom TTS state.
 * Uses user-configured TTS providers instead of system TTS.
 */
@Composable
fun rememberCustomTtsState(): CustomTtsState {
    val context = LocalContext.current
    val settingsStore = koinInject<SettingsStore>()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()

    // Remember the CustomTtsState instance across recompositions
    val ttsState = remember {
        CustomTtsStateImpl(
            context = context.applicationContext,
            settingsStore = settingsStore
        )
    }

    // Update the provider when settings change
    DisposableEffect(
        settings.selectedTTSProviderId,
        settings.ttsProviders,
        settings.defaultTTSPlaybackSpeed,
    ) {
        ttsState.updateProvider(settings.getSelectedTTSProvider())
        ttsState.setSpeed(settings.defaultTTSPlaybackSpeed)
        onDispose { }
    }

    // Cleanup resources when the state is disposed
    DisposableEffect(ttsState) {
        onDispose {
            ttsState.cleanup()
        }
    }

    return ttsState
}

/**
 * Interface defining the public API of our custom TTS state holder.
 */
interface CustomTtsState {
    /** Flow indicating if the TTS provider is available and ready. */
    val isAvailable: StateFlow<Boolean>

    /** Flow indicating if the TTS is currently speaking. */
    val isSpeaking: StateFlow<Boolean>

    /** Flow holding any error message. */
    val error: StateFlow<String?>

    /** Flow indicating current chunk being processed (index) */
    val currentChunk: StateFlow<Int>

    /** Flow indicating total chunks in queue */
    val totalChunks: StateFlow<Int>

    /** Unified playback state (status, position, duration, speed, etc.) */
    val playbackState: StateFlow<PlaybackState>

    /**
     * 设计文档 §7.5 — 当前活跃播放来源。
     * null 表示无音频播放或无 session 的手动朗读。
     */
    val activeSource: StateFlow<net.weero.measix.pilot.data.ai.tts.TtsPlaybackSource?>

    /**
     * Speaks the given text using the selected TTS provider.
     * Long texts will be automatically chunked and queued.
     */
    fun speak(text: String, flushCalled: Boolean = true)

    /**
     * 设计文档 §7.5 — 带来源的 speak。
     * 来源切换时（activeSession != incomingSession）强制 flush。
     */
    fun speakWithSource(
        text: String,
        flushCalled: Boolean,
        source: net.weero.measix.pilot.data.ai.tts.TtsPlaybackSource?,
    )

    /** Stops the current speech and clears the queue. */
    fun stop()

    /** Pauses the current playback. */
    fun pause()

    /** Resumes the paused playback. */
    fun resume()

    /** Skips to the next chunk in the queue. */
    fun skipNext()

    /** Fast forward current playback by [ms]. */
    fun fastForward(ms: Long = 5_000)

    /** Set playback [speed]. */
    fun setSpeed(speed: Float)

    /** Cleanup resources. */
    fun cleanup()
}

/**
 * Internal implementation of CustomTtsState.
 */
private class CustomTtsStateImpl(
    private val context: Context,
    private val settingsStore: SettingsStore
) : CustomTtsState, KoinComponent {

    private val ttsManager by inject<TTSManager>()
    private val controller by lazy { me.rerere.tts.controller.TtsController(context, ttsManager) }

    private val scope = CoroutineScope(Dispatchers.Main)
    private var currentJob: Job? = null

    override val isAvailable: StateFlow<Boolean> get() = controller.isAvailable
    override val isSpeaking: StateFlow<Boolean> get() = controller.isSpeaking
    override val error: StateFlow<String?> get() = controller.error
    override val currentChunk: StateFlow<Int> get() = controller.currentChunk
    override val totalChunks: StateFlow<Int> get() = controller.totalChunks
    override val playbackState: StateFlow<PlaybackState> get() = controller.playbackState

    // 设计文档 §7.5 — 瞬态 activeSource
    private val _activeSource = MutableStateFlow<net.weero.measix.pilot.data.ai.tts.TtsPlaybackSource?>(null)
    override val activeSource: StateFlow<net.weero.measix.pilot.data.ai.tts.TtsPlaybackSource?> get() = _activeSource

    init {
        // 设计文档 §7.5：队列自然播放完毕、播放错误时清空 activeSource，
        // 避免控制条在无音频时继续显示旧 Target。
        scope.launch {
            controller.playbackState.collect { state ->
                if (state.status == me.rerere.tts.model.PlaybackStatus.Ended) {
                    _activeSource.value = null
                }
            }
        }
        scope.launch {
            controller.error.collect { err ->
                if (err != null) {
                    _activeSource.value = null
                }
            }
        }
    }

    fun updateProvider(provider: TTSProviderSetting?) {
        // 设计文档 §7.5：Provider 切换时清空 activeSource
        _activeSource.value = null
        controller.setProvider(provider)
    }

    override fun speak(text: String, flushCalled: Boolean) {
        // 无 source 的调用，沿用现有行为并清除子助手头像
        speakWithSource(text, flushCalled, null)
    }

    override fun speakWithSource(
        text: String,
        flushCalled: Boolean,
        source: net.weero.measix.pilot.data.ai.tts.TtsPlaybackSource?,
    ) {
        val processed = text.stripMarkdown()

        // 设计文档 §7.5 — 来源切换仲裁
        // 使用提取的纯函数计算最终 flush，避免 JVM 测试复制生产算法
        val currentSource = _activeSource.value
        val effectiveFlush = net.weero.measix.pilot.data.ai.tts.TtsPlaybackSource.computeEffectiveFlush(
            flushCalled = flushCalled,
            currentSource = currentSource,
            incomingSource = source,
        )

        // 更新 activeSource
        _activeSource.value = source

        controller.speak(processed, effectiveFlush)
    }

    override fun stop() {
        controller.stop()
        _activeSource.value = null
    }

    override fun pause() {
        controller.pause()
        Log.d("CustomTtsState", "TTS paused")
    }

    override fun resume() {
        controller.resume()
        Log.d("CustomTtsState", "TTS resumed")
    }

    override fun skipNext() {
        controller.skipNext()
    }

    override fun fastForward(ms: Long) {
        controller.fastForward(ms)
    }

    override fun setSpeed(speed: Float) {
        controller.setSpeed(speed)
    }

    override fun cleanup() {
        // 设计文档 §7.5：dispose 时清空 activeSource
        _activeSource.value = null
        controller.dispose()
        currentJob = null
    }
}
