package net.weero.measix.pilot.data.ai.tts

import net.weero.measix.pilot.data.ai.tools.local.TtsToolPlaybackContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class TtsPlaybackSourceTest {

    private fun source(
        sessionId: String = "session-1",
        assistantId: Uuid? = Uuid.random(),
        name: String = "Test Assistant",
        type: TtsPlaybackSource.SourceType = TtsPlaybackSource.SourceType.SUB_ASSISTANT,
    ) = TtsPlaybackSource(sessionId, assistantId, name, type)

    // ---- computeEffectiveFlush: 同一 session ----

    @Test
    fun `same session with flushCalled true flushes`() {
        val s = source()
        assertTrue(
            TtsPlaybackSource.computeEffectiveFlush(
                flushCalled = true,
                currentSource = s,
                incomingSource = s,
            )
        )
    }

    @Test
    fun `same session with flushCalled false does not flush`() {
        val s = source()
        assertFalse(
            TtsPlaybackSource.computeEffectiveFlush(
                flushCalled = false,
                currentSource = s,
                incomingSource = s,
            )
        )
    }

    // ---- computeEffectiveFlush: 不同 session 强制 flush ----

    @Test
    fun `different session forces flush even when flushCalled is false`() {
        val current = source(sessionId = "session-1")
        val incoming = source(sessionId = "session-2")
        assertTrue(
            TtsPlaybackSource.computeEffectiveFlush(
                flushCalled = false,
                currentSource = current,
                incomingSource = incoming,
            )
        )
    }

    @Test
    fun `different session forces flush regardless of flushCalled`() {
        val current = source(sessionId = "old")
        val incoming = source(sessionId = "new")
        assertTrue(
            TtsPlaybackSource.computeEffectiveFlush(
                flushCalled = false,
                currentSource = current,
                incomingSource = incoming,
            )
        )
    }

    // ---- computeEffectiveFlush: 来源类型变化 ----

    @Test
    fun `incoming source when no current source flushes`() {
        assertTrue(
            TtsPlaybackSource.computeEffectiveFlush(
                flushCalled = false,
                currentSource = null,
                incomingSource = source(),
            )
        )
    }

    @Test
    fun `null incoming when current source exists flushes`() {
        assertTrue(
            TtsPlaybackSource.computeEffectiveFlush(
                flushCalled = false,
                currentSource = source(),
                incomingSource = null,
            )
        )
    }

    @Test
    fun `both null with flushCalled false does not flush`() {
        assertFalse(
            TtsPlaybackSource.computeEffectiveFlush(
                flushCalled = false,
                currentSource = null,
                incomingSource = null,
            )
        )
    }

    @Test
    fun `both null with flushCalled true flushes`() {
        assertTrue(
            TtsPlaybackSource.computeEffectiveFlush(
                flushCalled = true,
                currentSource = null,
                incomingSource = null,
            )
        )
    }

    // ---- computeEffectiveFlush: Master/Target 来源切换 ----

    @Test
    fun `master to target source switch forces flush`() {
        val masterSource = source(
            sessionId = "master-gen-1",
            type = TtsPlaybackSource.SourceType.NORMAL,
        )
        val targetSource = source(
            sessionId = "target-gen-1",
            type = TtsPlaybackSource.SourceType.SUB_ASSISTANT,
        )
        assertTrue(
            TtsPlaybackSource.computeEffectiveFlush(
                flushCalled = false,
                currentSource = masterSource,
                incomingSource = targetSource,
            )
        )
    }

    @Test
    fun `target to master source switch forces flush`() {
        val targetSource = source(
            sessionId = "target-gen-1",
            type = TtsPlaybackSource.SourceType.SUB_ASSISTANT,
        )
        val masterSource = source(
            sessionId = "master-gen-2",
            type = TtsPlaybackSource.SourceType.NORMAL,
        )
        assertTrue(
            TtsPlaybackSource.computeEffectiveFlush(
                flushCalled = false,
                currentSource = targetSource,
                incomingSource = masterSource,
            )
        )
    }

    // ---- computeEffectiveFlush: 同一 turn 内 Master/Target 共享 sessionId ----

    @Test
    fun `same sessionId master to target does not force flush`() {
        // 设计文档 §7.5：同一 turn 内 Master 和 Target 共享 sessionId，
        // 来源类型从 NORMAL 切换到 SUB_ASSISTANT 不触发 flush
        val sharedSession = "turn-session-1"
        val masterSource = source(
            sessionId = sharedSession,
            type = TtsPlaybackSource.SourceType.NORMAL,
        )
        val targetSource = source(
            sessionId = sharedSession,
            type = TtsPlaybackSource.SourceType.SUB_ASSISTANT,
        )
        assertFalse(
            TtsPlaybackSource.computeEffectiveFlush(
                flushCalled = false,
                currentSource = masterSource,
                incomingSource = targetSource,
            )
        )
    }

    @Test
    fun `same sessionId target to master does not force flush`() {
        val sharedSession = "turn-session-1"
        val targetSource = source(
            sessionId = sharedSession,
            type = TtsPlaybackSource.SourceType.SUB_ASSISTANT,
        )
        val masterSource = source(
            sessionId = sharedSession,
            type = TtsPlaybackSource.SourceType.NORMAL,
        )
        assertFalse(
            TtsPlaybackSource.computeEffectiveFlush(
                flushCalled = false,
                currentSource = targetSource,
                incomingSource = masterSource,
            )
        )
    }

    @Test
    fun `same sessionId target to target does not force flush`() {
        val sharedSession = "turn-session-1"
        val targetASource = source(
            sessionId = sharedSession,
            type = TtsPlaybackSource.SourceType.SUB_ASSISTANT,
        )
        val targetBSource = source(
            sessionId = sharedSession,
            type = TtsPlaybackSource.SourceType.SUB_ASSISTANT,
        )
        assertFalse(
            TtsPlaybackSource.computeEffectiveFlush(
                flushCalled = false,
                currentSource = targetASource,
                incomingSource = targetBSource,
            )
        )
    }

    @Test
    fun `same sessionId flushCalled true still flushes`() {
        val sharedSession = "turn-session-1"
        val masterSource = source(
            sessionId = sharedSession,
            type = TtsPlaybackSource.SourceType.NORMAL,
        )
        val targetSource = source(
            sessionId = sharedSession,
            type = TtsPlaybackSource.SourceType.SUB_ASSISTANT,
        )
        assertTrue(
            TtsPlaybackSource.computeEffectiveFlush(
                flushCalled = true,
                currentSource = masterSource,
                incomingSource = targetSource,
            )
        )
    }

    // ---- TtsPlaybackSource 属性与相等性 ----

    @Test
    fun `sources with same properties are equal`() {
        val id = Uuid.random()
        val s1 = TtsPlaybackSource("session-1", id, "Assistant", TtsPlaybackSource.SourceType.SUB_ASSISTANT)
        val s2 = TtsPlaybackSource("session-1", id, "Assistant", TtsPlaybackSource.SourceType.SUB_ASSISTANT)
        assertEquals(s1, s2)
        assertEquals(s1.hashCode(), s2.hashCode())
    }

    @Test
    fun `sources with different session ids are not equal`() {
        val id = Uuid.random()
        val s1 = TtsPlaybackSource("session-1", id, "Assistant", TtsPlaybackSource.SourceType.SUB_ASSISTANT)
        val s2 = TtsPlaybackSource("session-2", id, "Assistant", TtsPlaybackSource.SourceType.SUB_ASSISTANT)
        assertNotEquals(s1, s2)
    }

    @Test
    fun `source type distinguishes normal from sub-assistant`() {
        val normal = source(type = TtsPlaybackSource.SourceType.NORMAL)
        val subAssistant = source(type = TtsPlaybackSource.SourceType.SUB_ASSISTANT)
        assertNotEquals(normal.type, subAssistant.type)
    }

    // ---- TtsToolPlaybackContext 到 TtsPlaybackSource 的转换 ----

    @Test
    fun `TtsToolPlaybackContext converts to TtsPlaybackSource preserving all fields`() {
        val assistantId = Uuid.random()
        val context = TtsToolPlaybackContext(
            sessionId = "gen-session-42",
            assistantId = assistantId,
            assistantName = "Android Analyzer",
            sourceType = TtsPlaybackSource.SourceType.SUB_ASSISTANT,
        )
        val source = context.toPlaybackSource()
        assertEquals("gen-session-42", source.sessionId)
        assertEquals(assistantId, source.assistantId)
        assertEquals("Android Analyzer", source.assistantName)
        assertEquals(TtsPlaybackSource.SourceType.SUB_ASSISTANT, source.type)
    }

    @Test
    fun `TtsToolPlaybackContext with null assistantId converts correctly`() {
        val context = TtsToolPlaybackContext(
            sessionId = "session",
            assistantId = null,
            assistantName = "Unknown",
            sourceType = TtsPlaybackSource.SourceType.NORMAL,
        )
        val source = context.toPlaybackSource()
        assertEquals(null, source.assistantId)
        assertEquals(TtsPlaybackSource.SourceType.NORMAL, source.type)
    }
}
