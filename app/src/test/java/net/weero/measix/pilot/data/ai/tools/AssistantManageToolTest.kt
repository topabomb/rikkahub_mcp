package net.weero.measix.pilot.data.ai.tools

import io.mockk.Called
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolArgumentsException
import me.rerere.ai.core.ToolExecutionFailure
import me.rerere.ai.core.ToolInteractionRequirement
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.tools.local.LocalToolOption
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.toEffectiveSettingsSnapshot
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
        val effectiveSettings = MutableStateFlow(settings.toEffectiveSettingsSnapshot())
        val settingsStore = mockk<SettingsStore>()
        every { settingsStore.effectiveSettings } returns effectiveSettings

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
            subAssistantRunCoordinator = mockk(relaxed = true),
            toolSetFactory = mockk(relaxed = true),
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

    private suspend fun failureResult(tool: Tool, arguments: kotlinx.serialization.json.JsonElement): JsonObject {
        val failure = try {
            tool.execute(arguments)
            throw AssertionError("expected ToolExecutionFailure")
        } catch (error: ToolExecutionFailure) {
            error
        }
        return parseResult(failure.output)
    }

    @Test
    fun `CREATE does not need approval but update and delete do`() {
        val tool = manageTool(createFactory(listOf(caller(), target())), caller())
        assertEquals(
            ToolInteractionRequirement.None,
            tool.interactionRequirement(
                buildJsonObject {
                    put("action", "CREATE")
                    put("name", "Helper")
                    put("description", "Research")
                    put("instructions", "Find evidence")
                },
            ),
        )
        assertEquals(
            ToolInteractionRequirement.Approval,
            tool.interactionRequirement(
                buildJsonObject {
                    put("action", "UPDATE")
                    put("assistant_id", targetId.toString())
                    put("name", "Renamed")
                },
            ),
        )
        assertEquals(
            ToolInteractionRequirement.Approval,
            tool.interactionRequirement(
                buildJsonObject {
                    put("action", "DELETE")
                    put("assistant_id", targetId.toString())
                },
            ),
        )
        assertEquals(
            ToolInteractionRequirement.None,
            tool.interactionRequirement(buildJsonObject { }),
        )
        assertEquals(
            ToolInteractionRequirement.None,
            tool.interactionRequirement(buildJsonObject { put("action", "RENAME") }),
        )
    }


    @Test
    fun `invalid arguments are rejected before settings or management access`() = runTest {
        val settingsStore = mockk<SettingsStore>()
        val service = mockk<AssistantManagementService>()
        val factory = AssistantToolFactory(
            settingsStore, service, json, mockk(), mockk(),
        )
        val tool = manageTool(factory, caller())
        val invalid = listOf(
            """{}""",
            """{"action":"RENAME"}""",
            """{"action":true}""",
            """{"action":"CREATE"}""",
            """{"action":"CREATE","name":"n","description":42,"instructions":"i"}""",
            """{"action":"CREATE","name":"n","description":"d","instructions":"  "}""",
            """{"action":"UPDATE","assistant_id":"$targetId"}""",
            """{"action":"UPDATE","assistant_id":"wrong","name":"new"}""",
            """{"action":"UPDATE","assistant_id":"$targetId","name":null}""",
            """{"action":"UPDATE","assistant_id":"$targetId","instructions":[]}""",
            """{"action":"DELETE"}""",
            """{"action":"DELETE","assistant_id":false}""",
            """{"action":"DELETE","assistant_id":"$targetId","description":{}}""",
        )
        for (raw in invalid) {
            val args = json.parseToJsonElement(raw)
            val expected = requireNotNull(tool.validateArguments(args))
            assertEquals("invalid_arguments", expected["error"]!!.jsonPrimitive.content)
            val executionFailure = failureResult(tool, args)
            assertEquals("failed", executionFailure["status"]!!.jsonPrimitive.content)
            assertEquals("invalid_arguments", executionFailure["reason"]!!.jsonPrimitive.content)
            assertFalse(expected.containsKey("type"))
            assertEquals(ToolInteractionRequirement.None, tool.interactionRequirement(args.jsonObject))
            try {
                tool.parseArguments(raw, json)
                throw AssertionError("invalid input must not reach approval")
            } catch (failure: ToolArgumentsException) {
                val replay = parseResult(failure.output)
                assertEquals(expected, JsonObject(replay.filterKeys { it != "type" }))
                assertEquals("error", replay["type"]!!.jsonPrimitive.content)
            }
        }
        verify { settingsStore wasNot Called }
        verify { service wasNot Called }
    }

    @Test
    fun `pure validator accepts unknown target while execution rechecks dynamic access`() = runTest {
        val tool = manageTool(createFactory(listOf(caller())), caller())
        val args = buildJsonObject {
            put("action", "DELETE")
            put("assistant_id", targetId.toString())
        }

        assertEquals(null, tool.validateArguments(args))
        assertEquals(ToolInteractionRequirement.Approval, tool.interactionRequirement(args))
        assertEquals("target_not_allowed", failureResult(tool, args)["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun `parser normalizes supported fields using existing description semantics`() {
        val args = json.parseToJsonElement(
            """{"action":"CREATE","name":"  Helper  ","description":"a  b","instructions":"  Work carefully  "}""",
        )
        val parsed = requireNotNull(parseAssistantManageArguments(args))

        assertEquals("Helper", parsed.name)
        assertEquals("a b", parsed.description)
        assertEquals("Work carefully", parsed.instructions)
    }

    @Test
    fun `management result cancellation is rethrown`() = runTest {
        val cancellation = CancellationException("stop mutation")
        val settingsStore = mockk<SettingsStore>()
        every { settingsStore.effectiveSettings } returns MutableStateFlow(
            Settings(assistants = listOf(caller(), target())).toEffectiveSettingsSnapshot(),
        )
        val service = mockk<AssistantManagementService>()
        coEvery { service.deleteAssistant(any(), any()) } returns Result.failure(cancellation)
        val tool = manageTool(AssistantToolFactory(settingsStore, service, json, mockk(), mockk()), caller())
        try {
            tool.execute(buildJsonObject {
                put("action", "DELETE")
                put("assistant_id", targetId.toString())
            })
            throw AssertionError("expected cancellation")
        } catch (actual: CancellationException) {
            assertTrue(actual === cancellation)
        }
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
