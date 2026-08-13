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

    // ---- turn-level 共享 sessionId + playbackState ----

    @Test
    fun `turn-level shared state master first then target appends`() {
        // 模拟 ChatService 创建 turn-level context，Master 和 Target 共享
        val turnContext = TtsToolPlaybackContext(
            sessionId = "turn-1",
            assistantId = null,
            assistantName = "Master",
            sourceType = net.weero.measix.pilot.data.ai.tts.TtsPlaybackSource.SourceType.NORMAL,
        )
        // Master TTS 第一次调用：flush
        assertTrue(turnContext.playbackState.prepare("master-1", sequentialEnabled = true).flush)

        // Target 派生 context：复用 sessionId + playbackState
        val targetContext = TtsToolPlaybackContext(
            sessionId = turnContext.sessionId,
            assistantId = null,
            assistantName = "Target",
            sourceType = net.weero.measix.pilot.data.ai.tts.TtsPlaybackSource.SourceType.SUB_ASSISTANT,
            playbackState = turnContext.playbackState,
        )
        // Target TTS 第一次调用：不 flush（因为 Master 已消费 hasSpoken）
        assertFalse(targetContext.playbackState.prepare("target-1", sequentialEnabled = true).flush)
        // Target TTS 第二次调用：不 flush
        assertFalse(targetContext.playbackState.prepare("target-2", sequentialEnabled = true).flush)
    }

    @Test
    fun `turn-level shared state target first then master appends`() {
        val turnContext = TtsToolPlaybackContext(
            sessionId = "turn-2",
            assistantId = null,
            assistantName = "Master",
            sourceType = net.weero.measix.pilot.data.ai.tts.TtsPlaybackSource.SourceType.NORMAL,
        )
        // Target 派生 context 先执行 TTS
        val targetContext = TtsToolPlaybackContext(
            sessionId = turnContext.sessionId,
            assistantId = null,
            assistantName = "Target",
            sourceType = net.weero.measix.pilot.data.ai.tts.TtsPlaybackSource.SourceType.SUB_ASSISTANT,
            playbackState = turnContext.playbackState,
        )
        // Target TTS 第一次调用：flush
        assertTrue(targetContext.playbackState.prepare("target-1", sequentialEnabled = true).flush)
        // Master TTS 调用：不 flush
        assertFalse(turnContext.playbackState.prepare("master-1", sequentialEnabled = true).flush)
    }

    @Test
    fun `turn-level multiple targets all share same playback state`() {
        val turnContext = TtsToolPlaybackContext(
            sessionId = "turn-3",
            assistantId = null,
            assistantName = "Master",
            sourceType = net.weero.measix.pilot.data.ai.tts.TtsPlaybackSource.SourceType.NORMAL,
        )
        // Master flush
        assertTrue(turnContext.playbackState.prepare("m1", sequentialEnabled = true).flush)

        // Target A
        val targetA = TtsToolPlaybackContext(
            sessionId = turnContext.sessionId,
            assistantId = null,
            assistantName = "A",
            sourceType = net.weero.measix.pilot.data.ai.tts.TtsPlaybackSource.SourceType.SUB_ASSISTANT,
            playbackState = turnContext.playbackState,
        )
        assertFalse(targetA.playbackState.prepare("a1", sequentialEnabled = true).flush)

        // Target B（同一 turn 的另一个子助手）
        val targetB = TtsToolPlaybackContext(
            sessionId = turnContext.sessionId,
            assistantId = null,
            assistantName = "B",
            sourceType = net.weero.measix.pilot.data.ai.tts.TtsPlaybackSource.SourceType.SUB_ASSISTANT,
            playbackState = turnContext.playbackState,
        )
        assertFalse(targetB.playbackState.prepare("b1", sequentialEnabled = true).flush)

        // Master again
        assertFalse(turnContext.playbackState.prepare("m2", sequentialEnabled = true).flush)
    }

    @Test
    fun `turn-level sequential disabled still flushes every call`() {
        val turnContext = TtsToolPlaybackContext(
            sessionId = "turn-4",
            assistantId = null,
            assistantName = "Master",
            sourceType = net.weero.measix.pilot.data.ai.tts.TtsPlaybackSource.SourceType.NORMAL,
        )
        val targetContext = TtsToolPlaybackContext(
            sessionId = turnContext.sessionId,
            assistantId = null,
            assistantName = "Target",
            sourceType = net.weero.measix.pilot.data.ai.tts.TtsPlaybackSource.SourceType.SUB_ASSISTANT,
            playbackState = turnContext.playbackState,
        )
        // 顺序开关关闭时，每次都 flush
        assertTrue(turnContext.playbackState.prepare("m1", sequentialEnabled = false).flush)
        assertTrue(targetContext.playbackState.prepare("t1", sequentialEnabled = false).flush)
        assertTrue(turnContext.playbackState.prepare("m2", sequentialEnabled = false).flush)
    }

    @Test
    fun `different turns have independent playback states`() {
        val turn1 = TtsToolPlaybackContext(
            sessionId = "turn-a",
            assistantId = null,
            assistantName = "Master",
            sourceType = net.weero.measix.pilot.data.ai.tts.TtsPlaybackSource.SourceType.NORMAL,
        )
        val turn2 = TtsToolPlaybackContext(
            sessionId = "turn-b",
            assistantId = null,
            assistantName = "Master",
            sourceType = net.weero.measix.pilot.data.ai.tts.TtsPlaybackSource.SourceType.NORMAL,
        )
        // turn 1 first call flushes
        assertTrue(turn1.playbackState.prepare("a1", sequentialEnabled = true).flush)
        // turn 2 first call also flushes (different turn = different state)
        assertTrue(turn2.playbackState.prepare("b1", sequentialEnabled = true).flush)
    }
}
