package net.weero.measix.pilot.data.ai.tools

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.tools.local.LocalToolOption
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.service.AssistantDeletionResult
import net.weero.measix.pilot.service.AssistantManagementService
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class AssistantManageToolTest {

    private val json: Json = JsonInstant
    private val callerId = Uuid.random()
    private val targetId = Uuid.random()

    private fun createFactory(
        assistants: List<Assistant>,
        created: Assistant? = null,
        updated: Assistant? = null,
        deleted: AssistantDeletionResult? = null,
    ): AssistantToolFactory {
        val settings = Settings(assistants = assistants, assistantId = callerId)
        val settingsFlow = MutableStateFlow(settings)
        val settingsStore = mockk<SettingsStore>()
        every { settingsStore.settingsFlow } returns settingsFlow
        every { settingsStore.settingsFlow.value } returns settingsFlow.value

        val managementService = mockk<AssistantManagementService>()
        if (created != null) {
            coEvery { managementService.createAssistant(any(), any(), any(), any()) } returns
                Result.success(created)
        }
        if (updated != null) {
            coEvery {
                managementService.updateAssistant(any(), any(), any(), any(), any())
            } returns Result.success(updated)
        }
        if (deleted != null) {
            coEvery { managementService.deleteAssistant(any(), any()) } returns Result.success(deleted)
        }

        return AssistantToolFactory(
            settingsStore = settingsStore,
            assistantManagementService = managementService,
            json = json,
            delegationCoordinator = null,
        )
    }

    private fun caller() = Assistant(
        id = callerId,
        name = "Caller",
        localTools = listOf(LocalToolOption.AssistantManagement),
        allowedSubAssistantIds = setOf(targetId),
    )

    private fun target() = Assistant(
        id = targetId,
        name = "Target",
        description = "Routes research",
        allowAsSubAssistant = true,
    )

    private fun manageTool(factory: AssistantToolFactory, caller: Assistant): Tool =
        factory.buildTools(caller, Uuid.random()).single { it.name == "assistant_manage" }

    private fun parseResult(parts: List<UIMessagePart>): JsonObject =
        json.parseToJsonElement((parts.first() as UIMessagePart.Text).text).jsonObject

    @Test
    fun `CREATE does not need approval but update and delete do`() {
        val tool = manageTool(createFactory(listOf(caller(), target())), caller())
        assertFalse(
            tool.needsApproval(
                buildJsonObject { put("action", "CREATE") },
            ),
        )
        assertTrue(
            tool.needsApproval(
                buildJsonObject {
                    put("action", "UPDATE")
                    put("assistant_id", targetId.toString())
                },
            ),
        )
        assertTrue(
            tool.needsApproval(
                buildJsonObject {
                    put("action", "DELETE")
                    put("assistant_id", targetId.toString())
                },
            ),
        )
        assertTrue(tool.needsApproval(buildJsonObject { }))
        assertTrue(tool.needsApproval(buildJsonObject { put("action", "RENAME") }))
    }

    @Test
    fun `CREATE result is action and id only`() = runTest {
        val created = Assistant(
            id = Uuid.random(),
            name = "New helper",
            description = "Should not appear",
        )
        val caller = caller()
        val result = parseResult(
            manageTool(createFactory(listOf(caller, target()), created = created), caller).execute(
                buildJsonObject {
                    put("action", "CREATE")
                    put("name", "New helper")
                    put("description", "Should not appear")
                    put("instructions", "Be brief.")
                },
            ),
        )
        assertEquals("create", result["action"]!!.jsonPrimitive.content)
        assertEquals(created.id.toString(), result["id"]!!.jsonPrimitive.content)
        assertFalse(result.containsKey("assistant"))
        assertFalse(result.containsKey("name"))
        assertFalse(result.containsKey("description"))
    }

    @Test
    fun `UPDATE result is action and id only`() = runTest {
        val caller = caller()
        val existing = target()
        val result = parseResult(
            manageTool(
                createFactory(listOf(caller, existing), updated = existing.copy(name = "Renamed")),
                caller,
            ).execute(
                buildJsonObject {
                    put("action", "UPDATE")
                    put("assistant_id", targetId.toString())
                    put("name", "Renamed")
                },
            ),
        )
        assertEquals("update", result["action"]!!.jsonPrimitive.content)
        assertEquals(targetId.toString(), result["id"]!!.jsonPrimitive.content)
        assertFalse(result.containsKey("assistant"))
        assertFalse(result.containsKey("name"))
    }

    @Test
    fun `DELETE result is action and id`() = runTest {
        val caller = caller()
        val existing = target()
        val result = parseResult(
            manageTool(
                createFactory(
                    listOf(caller, existing),
                    deleted = AssistantDeletionResult(existing, cleanupPending = false),
                ),
                caller,
            ).execute(
                buildJsonObject {
                    put("action", "DELETE")
                    put("assistant_id", targetId.toString())
                },
            ),
        )
        assertEquals("delete", result["action"]!!.jsonPrimitive.content)
        assertEquals(targetId.toString(), result["id"]!!.jsonPrimitive.content)
        assertFalse(result.containsKey("assistant"))
        assertFalse(result.containsKey("name"))
        assertFalse(result.containsKey("cleanup_pending"))
    }
}
