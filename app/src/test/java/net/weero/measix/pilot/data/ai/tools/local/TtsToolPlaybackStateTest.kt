package net.weero.measix.pilot.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsToolPlaybackStateTest {
    @Test
    fun `sequential playback flushes first call and appends later calls`() {
        val state = TtsToolPlaybackState()

        assertTrue(state.prepare("first", sequentialEnabled = true).flush)
        assertFalse(state.prepare("second", sequentialEnabled = true).flush)
        assertFalse(state.prepare("third", sequentialEnabled = true).flush)
    }

    @Test
    fun `disabled sequential playback always flushes`() {
        val state = TtsToolPlaybackState()

        assertTrue(state.prepare("first", sequentialEnabled = false).flush)
        assertTrue(state.prepare("second", sequentialEnabled = false).flush)
    }

    @Test
    fun `playback state is isolated between generation runs`() {
        val firstGeneration = TtsToolPlaybackState()
        val secondGeneration = TtsToolPlaybackState()

        assertTrue(firstGeneration.prepare("first", sequentialEnabled = true).flush)
        assertFalse(firstGeneration.prepare("second", sequentialEnabled = true).flush)
        assertTrue(secondGeneration.prepare("first", sequentialEnabled = true).flush)
    }

    @Test
    fun `blank text is rejected before it can consume playback state`() {
        val state = TtsToolPlaybackState()

        assertThrows(IllegalStateException::class.java) {
            state.prepare("   ", sequentialEnabled = true)
        }
        val request = state.prepare("hello", sequentialEnabled = true)
        assertTrue(request.flush)
        assertEquals("hello", request.text)
    }

    @Test
    fun `context-bound state persists across simulated tool rebuilds`() {
        // 模拟 Target Run 的 toolProvider 跨 LLM step 重建 Tool 的场景：
        // context 只创建一次，state 随 context 跨 step 复用
        val context = TtsToolPlaybackContext(
            sessionId = "gen-1",
            assistantId = null,
            assistantName = "Target",
            sourceType = net.weero.measix.pilot.data.ai.tts.TtsPlaybackSource.SourceType.SUB_ASSISTANT,
        )
        // step 1: 第一次调用 flush
        assertTrue(context.playbackState.prepare("step1-text", sequentialEnabled = true).flush)
        // step 2: 重建 Tool 后复用同一 context，不 flush
        assertFalse(context.playbackState.prepare("step2-text", sequentialEnabled = true).flush)
        // step 3: 仍然不 flush
        assertFalse(context.playbackState.prepare("step3-text", sequentialEnabled = true).flush)
    }

    @Test
    fun `separate contexts have independent playback states`() {
        val ctx1 = TtsToolPlaybackContext(
            sessionId = "gen-1",
            assistantId = null,
            assistantName = "A",
            sourceType = net.weero.measix.pilot.data.ai.tts.TtsPlaybackSource.SourceType.SUB_ASSISTANT,
        )
        val ctx2 = TtsToolPlaybackContext(
            sessionId = "gen-2",
            assistantId = null,
            assistantName = "B",
            sourceType = net.weero.measix.pilot.data.ai.tts.TtsPlaybackSource.SourceType.SUB_ASSISTANT,
        )
        // ctx1 first call flushes
        assertTrue(ctx1.playbackState.prepare("a1", sequentialEnabled = true).flush)
        // ctx2 is a different generation, first call also flushes
        assertTrue(ctx2.playbackState.prepare("b1", sequentialEnabled = true).flush)
        // ctx1 second call does not flush
        assertFalse(ctx1.playbackState.prepare("a2", sequentialEnabled = true).flush)
    }
}
