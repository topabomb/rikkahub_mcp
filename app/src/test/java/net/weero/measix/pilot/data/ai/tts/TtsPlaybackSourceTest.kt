package net.weero.measix.pilot.data.ai.tts

import kotlin.uuid.Uuid
import net.weero.measix.pilot.data.ai.tools.local.TtsToolPlaybackContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TtsPlaybackSourceTest {
    @Test
    fun `context converts to source without changing turn identity`() {
        val assistantId = Uuid.random()
        val context = TtsToolPlaybackContext(
            sessionId = "master-turn-42",
            assistantId = assistantId,
            assistantName = "Android Analyzer",
            sourceType = TtsPlaybackSource.SourceType.SUB_ASSISTANT,
        )

        assertEquals(
            TtsPlaybackSource(
                assistantId = assistantId,
                assistantName = "Android Analyzer",
                type = TtsPlaybackSource.SourceType.SUB_ASSISTANT,
            ),
            context.toPlaybackSource(),
        )
    }

    @Test
    fun `master and target sources can share queue session while retaining UI identity`() {
        val sessionId = "master-turn-1"
        val masterContext = TtsToolPlaybackContext(
            sessionId = sessionId,
            assistantId = Uuid.random(),
            assistantName = "Master",
            sourceType = TtsPlaybackSource.SourceType.NORMAL,
        )
        val targetContext = TtsToolPlaybackContext(
            sessionId = sessionId,
            assistantId = Uuid.random(),
            assistantName = "Target",
            sourceType = TtsPlaybackSource.SourceType.SUB_ASSISTANT,
        )
        val master = masterContext.toPlaybackSource()
        val target = targetContext.toPlaybackSource()

        assertEquals(masterContext.sessionId, targetContext.sessionId)
        assertNotEquals(master.assistantId, target.assistantId)
        assertNotEquals(master.type, target.type)
    }

    @Test
    fun `different master turns have different queue identities`() {
        val assistantId = Uuid.random()
        val first = TtsToolPlaybackContext(
            sessionId = "turn-1",
            assistantId = assistantId,
            assistantName = "Master",
            sourceType = TtsPlaybackSource.SourceType.NORMAL,
        )
        val second = first.copy(sessionId = "turn-2")

        assertNotEquals(first.sessionId, second.sessionId)
        assertNotEquals(first, second)
    }
}
