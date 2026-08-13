package me.rerere.tts.controller

import java.util.concurrent.ConcurrentLinkedQueue

internal data class QueuedTtsChunk(
    val chunk: TtsChunk,
    val source: Any?,
)

/**
 * 一次只归一个 Master turn 所有的播放队列。
 *
 * sessionId 决定队列边界；每个 chunk 直接绑定 UI 来源，避免另维护易错位的来源分段索引。
 * 已完成的 chunk 在本 turn 内保留索引，以便迟到的同 turn 调用继续追加且预取索引稳定。
 */
internal class TurnPlaybackQueue {
    private val pending = ConcurrentLinkedQueue<QueuedTtsChunk>()
    private val all = mutableListOf<QueuedTtsChunk>()

    var sessionId: String? = null
        private set

    fun requiresReplacement(incomingSessionId: String?, replaceWithinSession: Boolean): Boolean =
        replaceWithinSession || sessionId != incomingSessionId

    fun append(chunks: List<TtsChunk>, incomingSessionId: String?, source: Any?) {
        val startIndex = all.size
        val entries = chunks.mapIndexed { offset, chunk ->
            QueuedTtsChunk(
                chunk = chunk.copy(index = startIndex + offset),
                source = source,
            )
        }
        all.addAll(entries)
        pending.addAll(entries)
        sessionId = incomingSessionId
    }

    fun poll(): QueuedTtsChunk? = pending.poll()

    fun hasPending(): Boolean = pending.isNotEmpty()

    fun pendingSize(): Int = pending.size

    fun chunkAt(index: Int): TtsChunk? = all.getOrNull(index)?.chunk

    fun totalChunkCount(): Int = all.size

    fun clear() {
        pending.clear()
        all.clear()
        sessionId = null
    }
}
