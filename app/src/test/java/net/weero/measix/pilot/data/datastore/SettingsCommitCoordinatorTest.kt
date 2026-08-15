package net.weero.measix.pilot.data.datastore

import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.data.model.Assistant
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCommitCoordinatorTest {
    @Test
    fun `policy receives source before persistence normalization and publish follows persist`() = runTest {
        val events = mutableListOf<String>()
        val proposed = Settings(
            themeId = "proposed",
            assistants = listOf(Assistant(description = "  routing\n description  ")),
            searchServices = emptyList(),
            searchServiceSelected = 42,
            defaultTTSPlaybackSpeed = 3.0f,
        )
        var published: Settings? = null
        val policy = SettingsWritePolicy { _, candidate, source ->
            events += "policy"
            assertEquals(SettingsWriteSource.MARKETPLACE_IMPORT, source)
            assertEquals("  routing\n description  ", candidate.assistants.single().description)
            candidate.copy(themeId = "accepted")
        }

        val committed = commitSettings(
            current = Settings(themeId = "current"),
            proposed = proposed,
            source = SettingsWriteSource.MARKETPLACE_IMPORT,
            policy = policy,
            persist = { prepared ->
                events += "persist"
                assertEquals("accepted", prepared.themeId)
                assertEquals("routing description", prepared.assistants.single().description)
                assertEquals(1, prepared.searchServices.size)
                assertEquals(0, prepared.searchServiceSelected)
                assertEquals(2.0f, prepared.defaultTTSPlaybackSpeed)
            },
            publish = {
                events += "publish"
                published = it
            },
        )

        val actualPublished = requireNotNull(published)
        assertEquals(listOf("policy", "persist", "publish"), events)
        assertEquals(actualPublished, committed)
        assertEquals("accepted", actualPublished.themeId)
        assertEquals(0, actualPublished.searchServiceSelected)
        assertEquals(2.0f, actualPublished.defaultTTSPlaybackSpeed)
        assertFalse(actualPublished.init)
        assertTrue(actualPublished.providers.any { it.id == DEFAULT_PROVIDERS.first().id })
    }

    @Test
    fun `persistence failure never publishes an in-memory settings snapshot`() {
        var published = false

        assertThrows(IllegalStateException::class.java) {
            runTest {
                commitSettings(
                    current = Settings(),
                    proposed = Settings(themeId = "must-not-publish"),
                    source = SettingsWriteSource.LOCAL,
                    policy = SettingsWritePolicy.AllowAll,
                    persist = { throw IllegalStateException("disk failure") },
                    publish = { published = true },
                )
            }
        }

        assertFalse(published)
    }

    @Test
    fun `backup restore source preserves tombstones through write preparation`() {
        val pending = PendingAssistantDeletion(
            assistantId = Uuid.random(),
        )
        val current = Settings(themeId = "current", pendingAssistantDeletions = listOf(pending))
        val proposed = Settings(themeId = "backup").withInternalStateFrom(current)

        val prepared = prepareSettingsForWrite(
            current = current,
            proposed = proposed,
            source = SettingsWriteSource.BACKUP_RESTORE,
            policy = SettingsWritePolicy.AllowAll,
        )

        assertEquals("backup", prepared.themeId)
        assertEquals(listOf(pending), prepared.pendingAssistantDeletions)
    }
}
