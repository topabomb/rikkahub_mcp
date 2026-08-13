package me.rerere.tts.controller

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.tts.model.PlaybackState
import me.rerere.tts.model.PlaybackStatus
import me.rerere.tts.model.TTSResponse
import me.rerere.tts.provider.TTSManager
import me.rerere.tts.provider.TTSProviderSetting
import java.util.UUID

private const val TAG = "TtsController"

/**
 * TTS 控制器（重构版）
 * - 负责文本分片、预取合成、排队播放与状态上报
 * - 对外 API 与原版兼容
 *
 * 一个非空 sessionId 对应一轮 Master turn 独占的共享队列。
 * 新 session 总是替换旧队列；同 session 是否替换只由调用方的队列策略决定。
 */
class TtsController(
    context: Context,
    private val ttsManager: TTSManager
) {
    // 协程作用域
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // 组件
    private val chunker = TextChunker(maxChunkLength = 160)
    private val synthesizer = TtsSynthesizer(ttsManager)
    private val audio = AudioPlayer(context)

    // Provider & 作业
    private var currentProvider: TTSProviderSetting? = null
    private var workerJob: Job? = null
    private val workerOwner = PlaybackOwner()
    private var isPaused = false
    private var isPreparingChunk = false

    // 队列与缓存（基于稳定 ID）
    private val playbackQueue = TurnPlaybackQueue()
    private val cache = java.util.concurrent.ConcurrentHashMap<UUID, kotlinx.coroutines.Deferred<TTSResponse>>()
    private var lastPrefetchedIndex: Int = -1

    // 行为参数
    private val chunkDelayMs = 120L
    private val prefetchCount = 4

    // 状态流（保留与旧版兼容的 StateFlow）
    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _currentChunk = MutableStateFlow(0)
    val currentChunk: StateFlow<Int> = _currentChunk.asStateFlow()

    private val _totalChunks = MutableStateFlow(0)
    val totalChunks: StateFlow<Int> = _totalChunks.asStateFlow()

    // 统一播放状态（融合音频播放 + 分片进度）
    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    // source-aware 播放来源追踪
    // 每次 speak() 携带一个 source 标记，controller 在播放跨越 source 边界时发射新 source。
    // 这样 UI 层的 activeSource 始终反映"当前正在播放的音频来源"，而非"最近入队的来源"。
    private val _activeSource = MutableStateFlow<Any?>(null)
    val activeSource: StateFlow<Any?> = _activeSource.asStateFlow()

    init {
        // 同步底层播放器状态到统一状态，并补充分片信息
        scope.launch {
            audio.playbackState.collectLatest { audioState ->
                // 本 turn 还有待播 chunk 时不要把 chunk 间隙发布成 Ended。
                val effectiveStatus = when {
                    isPaused -> PlaybackStatus.Paused
                    isPreparingChunk -> PlaybackStatus.Buffering
                    audioState.status == PlaybackStatus.Ended && playbackQueue.hasPending() -> PlaybackStatus.Playing
                    else -> audioState.status
                }
                _playbackState.update {
                    audioState.copy(
                        currentChunkIndex = _currentChunk.value,
                        totalChunks = _totalChunks.value,
                        status = if (!_isAvailable.value) PlaybackStatus.Idle else effectiveStatus
                    )
                }
            }
        }
    }

    /** 选择/取消选择 Provider */
    fun setProvider(provider: TTSProviderSetting?) {
        currentProvider = provider
        _isAvailable.update { provider != null }
        if (provider == null) stop()
    }

    /**
     * 朗读文本
     * - queueSessionId 变化：跨 Master turn，清空旧队列并由新 turn 独占
     * - queueSessionId 相同且 replaceWithinSession=true：同 turn 内也替换（顺序播放关闭）
     * - queueSessionId 相同且 replaceWithinSession=false：同 turn 内追加（顺序播放开启）
     * - source: 本次朗读的来源标记，controller 在播放到该批 chunk 时发射 sourceChange
     */
    fun speak(
        text: String,
        replaceWithinSession: Boolean = true,
        source: Any? = null,
        queueSessionId: String? = null,
    ) {
        if (text.isBlank()) return
        val provider = currentProvider
        if (provider == null) {
            _error.update { "No TTS provider selected" }
            return
        }

        val newChunks = chunker.split(text)
        if (newChunks.isEmpty()) return

        val replaceQueue = playbackQueue.requiresReplacement(
            incomingSessionId = queueSessionId,
            replaceWithinSession = replaceWithinSession,
        )
        if (replaceQueue) {
            internalReset()
            _currentChunk.update { 0 }
        }
        playbackQueue.append(newChunks, queueSessionId, source)
        // 队列自然播放结束后仍保留 session 所有权；同一 turn 的迟到工具调用继续追加，
        // 直到新 turn、手动播放、stop 或 Provider 切换显式替换。
        _totalChunks.update { playbackQueue.totalChunkCount() }
        _error.update { null }

        val hasActiveWorker = workerJob?.isActive == true
        _playbackState.update {
            it.copy(
                currentChunkIndex = _currentChunk.value,
                totalChunks = _totalChunks.value,
                status = when {
                    isPaused -> PlaybackStatus.Paused
                    hasActiveWorker -> it.status
                    else -> PlaybackStatus.Buffering
                }
            )
        }

        if (!hasActiveWorker) startWorker()
        prefetchFrom((_currentChunk.value).coerceAtLeast(0))
    }

    private fun internalReset() {
        // Reset current session while keeping provider availability
        // 先撤销旧 worker 的终态写入权，避免它在新队列建立前隐藏工具栏或清空头像。
        workerOwner.invalidate()
        workerJob?.cancel()
        audio.stop()
        audio.clear()
        isPaused = false
        isPreparingChunk = false
        playbackQueue.clear()
        cache.values.forEach { it.cancel(CancellationException("Reset")) }
        cache.clear()
        lastPrefetchedIndex = -1
        _currentChunk.update { 0 }
        _totalChunks.update { 0 }
        _error.update { null }
        _playbackState.update { PlaybackState(status = PlaybackStatus.Idle) }
        _activeSource.value = null
    }

    /** 暂停播放（保留进度） */
    fun pause() {
        isPaused = true
        audio.pause()
        _playbackState.update { it.copy(status = PlaybackStatus.Paused) }
    }

    /** 恢复播放 */
    fun resume() {
        isPaused = false
        audio.resume()
        // AudioPlayer 会在真实恢复后发布 Playing；若暂停发生在合成阶段，这里应先回到 Buffering。
        _playbackState.update { it.copy(status = PlaybackStatus.Buffering) }
    }

    /** 快进当前音频 */
    fun fastForward(ms: Long = 5_000) {
        audio.seekBy(ms)
    }

    /** 设置播放速度 */
    fun setSpeed(speed: Float) {
        audio.setSpeed(speed)
    }

    /** 跳过下一段（不打断当前正在播放） */
    fun skipNext() {
        if (playbackQueue.hasPending()) {
            playbackQueue.poll()
        }
    }

    /** 停止并清空状态 */
    fun stop() {
        // 先使当前 worker 失去终态写入权，再触发取消。
        workerOwner.invalidate()
        workerJob?.cancel()
        audio.stop()
        audio.clear()
        isPaused = false
        isPreparingChunk = false
        playbackQueue.clear()
        cache.values.forEach { it.cancel(CancellationException("Stopped")) }
        cache.clear()
        lastPrefetchedIndex = -1
        _isSpeaking.update { false }
        _currentChunk.update { 0 }
        _totalChunks.update { 0 }
        _playbackState.update { PlaybackState(status = PlaybackStatus.Idle) }
        _activeSource.value = null
    }

    /** 释放资源 */
    fun dispose() {
        stop()
        scope.cancel()
        audio.release()
    }

    // region 内部：播放调度
    private fun startWorker() {
        val provider = currentProvider
        if (provider == null) {
            _error.update { "No TTS provider selected" }
            return
        }

        val ownershipToken = workerOwner.claim()
        workerJob = scope.launch {
            _isSpeaking.update { true }
            try {
                while (isActive) {
                    if (isPaused) {
                        delay(80)
                        continue
                    }

                    val queuedChunk = playbackQueue.poll() ?: break
                    val chunk = queuedChunk.chunk
                    _activeSource.value = queuedChunk.source
                    isPreparingChunk = true

                    // 更新状态（1-based）
                    _currentChunk.update { chunk.index + 1 }
                    _totalChunks.update { playbackQueue.totalChunkCount() }
                    _playbackState.update {
                        it.copy(
                            currentChunkIndex = _currentChunk.value,
                            totalChunks = _totalChunks.value,
                            positionMs = 0L,
                            durationMs = 0L,
                            status = if (isPaused) PlaybackStatus.Paused else PlaybackStatus.Buffering,
                        )
                    }

                    // 预取下一窗口
                    prefetchFrom(chunk.index + 1)

                    val response = try {
                        awaitOrCreate(chunk, provider)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.e(TAG, "Synthesis error", e)
                        isPreparingChunk = false
                        _error.update { e.message ?: "TTS synthesis error" }
                        continue
                    }

                    // 用户可能在合成等待期间点击暂停；不得让刚完成合成的音频绕过工具栏开始播放。
                    while (isPaused && isActive) {
                        delay(80)
                    }

                    // 播放
                    isPreparingChunk = false
                    try {
                        audio.play(response)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.e(TAG, "Playback error", e)
                        _error.update { e.message ?: "Audio playback error" }
                    }

                    if (playbackQueue.hasPending()) delay(chunkDelayMs)
                }
            } finally {
                // flush 会取消旧 worker 并立即启动新 worker。旧 finally 可能迟到，
                // 只有仍拥有当前队列的 worker 才能发布终态或清空来源。
                if (workerOwner.owns(ownershipToken)) {
                    isPreparingChunk = false
                    _isSpeaking.update { false }
                    if (!playbackQueue.hasPending()) {
                        _playbackState.update { it.copy(status = PlaybackStatus.Ended) }
                        _activeSource.value = null
                    }
                }
            }
        }
    }

    private fun prefetchFrom(startIndex: Int) {
        val provider = currentProvider ?: return
        val begin = startIndex.coerceAtLeast(lastPrefetchedIndex + 1)
        val endExclusive = (begin + prefetchCount).coerceAtMost(playbackQueue.totalChunkCount())
        if (begin >= endExclusive) return

        for (i in begin until endExclusive) {
            val chunk = playbackQueue.chunkAt(i) ?: continue
            cache.computeIfAbsent(chunk.id) {
                scope.async(Dispatchers.IO) { synthesizer.synthesize(provider, chunk) }
            }
        }
        lastPrefetchedIndex = endExclusive - 1
    }

    private suspend fun awaitOrCreate(chunk: TtsChunk, provider: TTSProviderSetting): TTSResponse {
        val deferred = cache.computeIfAbsent(chunk.id) {
            scope.async(Dispatchers.IO) { synthesizer.synthesize(provider, chunk) }
        }
        return try {
            deferred.await()
        } finally {
            // 可按需保留缓存（此处保留，便于重播/重试）
        }
    }
    // endregion
}
