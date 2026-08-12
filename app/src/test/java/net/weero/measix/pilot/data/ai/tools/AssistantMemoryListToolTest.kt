package net.weero.measix.pilot.data.ai.tools

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.tools.local.LocalToolOption
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.service.AssistantManagementService
import net.weero.measix.pilot.service.MemoryItem
import net.weero.measix.pilot.service.MemoryListResult
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class AssistantMemoryListToolTest {

    private val json: Json = JsonInstant
    private val callerId = Uuid.random()
    private val targetId = Uuid.random()

    private fun createFactory(
        assistants: List<Assistant>,
        callerTools: Set<LocalToolOption> = setOf(LocalToolOption.AssistantManagement),
        memoryResult: Result<MemoryListResult>? = null,
    ): AssistantToolFactory {
        val caller = assistants.find { it.id == callerId } ?: Assistant(id = callerId, name = "Caller")
        val settings = Settings(
            assistants = assistants,
            assistantId = callerId,
        )
        val settingsFlow = MutableStateFlow(settings)
        val settingsStore = mockk<SettingsStore>()
        every { settingsStore.settingsFlow } returns settingsFlow
        every { settingsStore.settingsFlow.value } returns settingsFlow.value

        val managementService = mockk<AssistantManagementService>()
        if (memoryResult != null) {
            coEvery { managementService.listAssistantMemory(any()) } returns memoryResult
        }

        return AssistantToolFactory(
            settingsStore = settingsStore,
            assistantManagementService = managementService,
            json = json,
            subAssistantCoordinator = null,
        )
    }

    private fun buildArgs(assistantId: Uuid): JsonObject =
        buildJsonObject { put("assistant_id", assistantId.toString()) }

    private fun extractResultText(parts: List<UIMessagePart>): String =
        (parts.first() as UIMessagePart.Text).text

    private fun parseResult(parts: List<UIMessagePart>): JsonObject =
        json.parseToJsonElement(extractResultText(parts)).jsonObject

    @Test
    fun `local memory returns memories with header and rows`() = runTest {
        val target = Assistant(
            id = targetId,
            name = "Target",
            allowAsSubAssistant = true,
            enableMemory = true,
            useGlobalMemory = false,
        )
        val caller = Assistant(
            id = callerId,
            name = "Caller",
            localTools = callerTools(),
            allowedSubAssistantIds = setOf(targetId),
        )
        val factory = createFactory(
            assistants = listOf(caller, target),
            memoryResult = Result.success(
                MemoryListResult(
                    assistantId = targetId.toString(),
                    assistantName = "Target",
                    delegatedMemoryScope = "local",
                    memories = listOf(
                        MemoryItem(1, "Uses JDK 17"),
                        MemoryItem(2, "Gradle 8"),
                    ),
                )
            ),
        )

        val tools = factory.buildTools(caller, Uuid.random())
        val memoryListTool = tools.find { it.name == "assistant_memory_list" }!!
        val parts = memoryListTool.execute(buildArgs(targetId))

        val result = parseResult(parts)
        assertEquals("local", result["active_memory"]!!.jsonPrimitive.content)
        val rows = result["rows"]!!
        assertEquals(2, rows.jsonArray.size)
        val firstRow = rows.jsonArray[0].jsonArray
        assertEquals(1, firstRow[0].jsonPrimitive.content.toInt())
        assertEquals("Uses JDK 17", firstRow[1].jsonPrimitive.content)
    }

    @Test
    fun `global memory scope returns empty rows`() = runTest {
        val target = Assistant(
            id = targetId,
            name = "Target",
            allowAsSubAssistant = true,
            enableMemory = true,
            useGlobalMemory = true,
        )
        val caller = Assistant(
            id = callerId,
            name = "Caller",
            localTools = callerTools(),
            allowedSubAssistantIds = setOf(targetId),
        )
        val factory = createFactory(
            assistants = listOf(caller, target),
            memoryResult = Result.success(
                MemoryListResult(
                    assistantId = targetId.toString(),
                    assistantName = "Target",
                    delegatedMemoryScope = "global",
                    memories = emptyList(),
                )
            ),
        )

        val tools = factory.buildTools(caller, Uuid.random())
        val memoryListTool = tools.find { it.name == "assistant_memory_list" }!!
        val parts = memoryListTool.execute(buildArgs(targetId))

        val result = parseResult(parts)
        assertEquals("global", result["active_memory"]!!.jsonPrimitive.content)
        val rows = result["rows"]!!
        assertEquals(0, rows.jsonArray.size)
        // header 仍然存在
        assertTrue(result.containsKey("header"))
    }

    @Test
    fun `disabled memory returns empty rows`() = runTest {
        val target = Assistant(
            id = targetId,
            name = "Target",
            allowAsSubAssistant = true,
            enableMemory = false,
        )
        val caller = Assistant(
            id = callerId,
            name = "Caller",
            localTools = callerTools(),
            allowedSubAssistantIds = setOf(targetId),
        )
        val factory = createFactory(
            assistants = listOf(caller, target),
            memoryResult = Result.success(
                MemoryListResult(
                    assistantId = targetId.toString(),
                    assistantName = "Target",
                    delegatedMemoryScope = "disabled",
                    memories = emptyList(),
                )
            ),
        )

        val tools = factory.buildTools(caller, Uuid.random())
        val memoryListTool = tools.find { it.name == "assistant_memory_list" }!!
        val parts = memoryListTool.execute(buildArgs(targetId))

        val result = parseResult(parts)
        assertEquals("disabled", result["active_memory"]!!.jsonPrimitive.content)
        assertEquals(0, result["rows"]!!.jsonArray.size)
    }

    @Test
    fun `target is caller returns target_is_caller`() = runTest {
        val caller = Assistant(
            id = callerId,
            name = "Caller",
            localTools = callerTools(),
        )
        val factory = createFactory(assistants = listOf(caller))

        val tools = factory.buildTools(caller, Uuid.random())
        val memoryListTool = tools.find { it.name == "assistant_memory_list" }!!
        val parts = memoryListTool.execute(buildArgs(callerId))

        val result = parseResult(parts)
        assertEquals("target_is_caller", result["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `target not allowed returns target_not_allowed`() = runTest {
        val target = Assistant(
            id = targetId,
            name = "Target",
            allowAsSubAssistant = true,
            enableMemory = true,
        )
        // caller does NOT include targetId in allowedSubAssistantIds
        val caller = Assistant(
            id = callerId,
            name = "Caller",
            localTools = callerTools(),
            allowedSubAssistantIds = emptySet(),
        )
        val factory = createFactory(assistants = listOf(caller, target))

        val tools = factory.buildTools(caller, Uuid.random())
        val memoryListTool = tools.find { it.name == "assistant_memory_list" }!!
        val parts = memoryListTool.execute(buildArgs(targetId))

        val result = parseResult(parts)
        assertEquals("target_not_allowed", result["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `caller missing returns tool_not_permitted`() = runTest {
        val target = Assistant(
            id = targetId,
            name = "Target",
            allowAsSubAssistant = true,
        )
        // caller not in settings (different ID)
        val otherCaller = Assistant(
            id = Uuid.random(),
            name = "Other",
            localTools = callerTools(),
            allowedSubAssistantIds = setOf(targetId),
        )
        val factory = createFactory(assistants = listOf(otherCaller, target))

        val caller = Assistant(
            id = callerId,
            name = "Caller",
            localTools = callerTools(),
        )
        val tools = factory.buildTools(caller, Uuid.random())
        val memoryListTool = tools.find { it.name == "assistant_memory_list" }!!
        val parts = memoryListTool.execute(buildArgs(targetId))

        val result = parseResult(parts)
        assertEquals("tool_not_permitted", result["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tool disabled returns tool_not_permitted`() = runTest {
        val target = Assistant(
            id = targetId,
            name = "Target",
            allowAsSubAssistant = true,
            enableMemory = true,
        )
        // caller does NOT have AssistantManagement enabled
        val caller = Assistant(
            id = callerId,
            name = "Caller",
            localTools = emptyList(),
            allowedSubAssistantIds = setOf(targetId),
        )
        // Build tools with AssistantManagement enabled to get the tool,
        // but settings will have it disabled
        val factory = createFactory(
            assistants = listOf(caller, target),
        )

        val tools = factory.buildTools(
            caller.copy(localTools = callerTools()),
            Uuid.random(),
        )
        val memoryListTool = tools.find { it.name == "assistant_memory_list" }!!
        // Execute with the real caller (without AssistantManagement)
        val parts = memoryListTool.execute(buildArgs(targetId))

        val result = parseResult(parts)
        assertEquals("tool_not_permitted", result["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `globally visible target is accessible without explicit allow`() = runTest {
        val target = Assistant(
            id = targetId,
            name = "Global Target",
            allowAsSubAssistant = true,
            isSubAssistantGloballyVisible = true,
            enableMemory = true,
            useGlobalMemory = false,
        )
        val caller = Assistant(
            id = callerId,
            name = "Caller",
            localTools = callerTools(),
            allowedSubAssistantIds = emptySet(), // no explicit allow
        )
        val factory = createFactory(
            assistants = listOf(caller, target),
            memoryResult = Result.success(
                MemoryListResult(
                    assistantId = targetId.toString(),
                    assistantName = "Global Target",
                    delegatedMemoryScope = "local",
                    memories = listOf(MemoryItem(10, "Some memory")),
                )
            ),
        )

        val tools = factory.buildTools(caller, Uuid.random())
        val memoryListTool = tools.find { it.name == "assistant_memory_list" }!!
        val parts = memoryListTool.execute(buildArgs(targetId))

        val result = parseResult(parts)
        assertEquals("local", result["active_memory"]!!.jsonPrimitive.content)
        assertEquals(1, result["rows"]!!.jsonArray.size)
    }

    @Test
    fun `invalid arguments returns invalid_arguments`() = runTest {
        val caller = Assistant(
            id = callerId,
            name = "Caller",
            localTools = callerTools(),
        )
        val factory = createFactory(assistants = listOf(caller))

        val tools = factory.buildTools(caller, Uuid.random())
        val memoryListTool = tools.find { it.name == "assistant_memory_list" }!!

        // Missing assistant_id
        val parts = memoryListTool.execute(buildJsonObject { })

        val result = parseResult(parts)
        assertEquals("invalid_arguments", result["error"]!!.jsonPrimitive.content)
    }

    private fun callerTools(): List<LocalToolOption> = listOf(LocalToolOption.AssistantManagement)
}
