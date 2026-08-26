package net.weero.measix.pilot.data.datastore

import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SettingsCommitTest {
    @Test
    fun `commit normalizes before persistence and publishes only the committed local shadow`() = runTest {
        val events = mutableListOf<String>()
        val proposed = Settings(
            themeId = "proposed",
            providers = emptyList(),
            assistants = listOf(Assistant(description = "  routing\n description  ")),
            searchServices = emptyList(),
            ttsProviders = emptyList(),
            defaultTTSPlaybackSpeed = 3.0f,
        )
        var published: Settings? = null

        val committed = commitSettings(
            proposed = proposed,
            persist = { prepared ->
                events += "persist"
                assertEquals("proposed", prepared.themeId)
                assertEquals("routing description", prepared.assistants.single().description)
                assertEquals(1, prepared.searchServices.size)
                assertEquals(prepared.searchServices.single().id, prepared.selectedSearchServiceId)
                assertEquals(2.0f, prepared.defaultTTSPlaybackSpeed)
            },
            publish = {
                events += "publish"
                published = it
            },
        )

        val actualPublished = requireNotNull(published)
        assertEquals(listOf("persist", "publish"), events)
        assertEquals(actualPublished, committed)
        assertEquals("proposed", actualPublished.themeId)
        assertEquals(actualPublished.searchServices.single().id, actualPublished.selectedSearchServiceId)
        assertEquals(2.0f, actualPublished.defaultTTSPlaybackSpeed)
        assertFalse(actualPublished.init)
        assertTrue(actualPublished.providers.isEmpty())
    }

    @Test
    fun `persistence failure never publishes an in-memory settings snapshot`() {
        var published = false

        assertThrows(IllegalStateException::class.java) {
            runTest {
                commitSettings(
                    proposed = Settings(themeId = "must-not-publish"),
                    persist = { throw IllegalStateException("disk failure") },
                    publish = { published = true },
                )
            }
        }

        assertFalse(published)
    }

    @Test
    fun `managed lock protects the local shadow even when a managed record masks it`() {
        val assistantId = Uuid.random()
        val local = Settings(
            assistants = listOf(Assistant(id = assistantId, name = "local", description = "local shadow")),
        )
        val managed = ManagedConfigurationSnapshot(
            state = ManagedConfigurationState.ACTIVE,
            generation = 7,
            overlay = ManagedSettingsOverlay(
                records = Settings(assistants = listOf(Assistant(id = assistantId, name = "managed"))),
                access = SettingsAccessIndex(
                    lockReasons = mapOf("records/assistants/$assistantId" to "Managed assistant is read-only"),
                ),
            ),
        )
        val effective = EffectiveSettingsResolver.resolve(local, managed, revision = 1)

        assertEquals("managed", effective.settings.assistants.first { it.id == assistantId }.name)
        val error = assertThrows(SettingsLockedException::class.java) {
            requireLocalSettingsWriteAllowed(
                currentLocal = local,
                currentEffective = effective,
                proposedLocal = local.copy(
                    assistants = listOf(Assistant(id = assistantId, name = "changed local shadow")),
                ),
            )
        }

        assertEquals("records/assistants/$assistantId", error.path)
    }

    @Test
    fun `effective source index distinguishes local managed and built in values`() {
        val localAssistant = Assistant(id = Uuid.random(), name = "local")
        val local = Settings(
            providers = emptyList(),
            assistants = listOf(localAssistant),
            searchServices = emptyList(),
            ttsProviders = emptyList(),
        )
        val builtIn = EffectiveSettingsResolver.resolve(
            local = local,
            managed = ManagedConfigurationSnapshot(ManagedConfigurationState.ABSENT),
            revision = 1,
            explicitLocalDefaults = emptySet(),
        )

        assertEquals(
            SettingsValueSource.BUILT_IN,
            builtIn.access.sourceOf(ManagedConfigurationRecordKind.PROVIDER, DEFAULT_PROVIDERS.first().id),
        )
        assertEquals(
            SettingsValueSource.LOCAL,
            builtIn.access.sourceOf(ManagedConfigurationRecordKind.ASSISTANT, localAssistant.id),
        )
        assertEquals(SettingsValueSource.BUILT_IN, builtIn.access.sourceOfDefault("defaults/chatModelId"))
    }

    @Test
    fun `collection lock is surfaced for every covered record and blocks reorder`() {
        val first = Assistant(id = Uuid.random(), name = "first")
        val second = Assistant(id = Uuid.random(), name = "second")
        val local = Settings(assistants = listOf(first, second))
        val effective = EffectiveSettingsResolver.resolve(
            local = local,
            managed = ManagedConfigurationSnapshot(
                state = ManagedConfigurationState.ACTIVE,
                overlay = ManagedSettingsOverlay(
                    records = Settings(),
                    access = SettingsAccessIndex(lockReasons = mapOf("records/assistants" to "Order is managed")),
                ),
            ),
            revision = 1,
        )

        assertEquals("Order is managed", effective.access.reasonFor("records/assistants/${first.id}"))
        val error = assertThrows(SettingsLockedException::class.java) {
            requireLocalSettingsWriteAllowed(
                currentLocal = local,
                currentEffective = effective,
                proposedLocal = local.copy(assistants = listOf(second, first)),
            )
        }

        assertEquals("records/assistants", error.path)
    }

}
