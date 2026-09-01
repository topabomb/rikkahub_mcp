package net.weero.measix.pilot.data.ai.tools

import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.every
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionFailure
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.tools.local.LocalToolOption
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.toEffectiveSettingsSnapshot
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.service.AssistantManagementService
import net.weero.measix.pilot.service.MemoryItem
import net.weero.measix.pilot.service.MemoryListResult
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class AssistantInspectToolTest {

    private val json: Json = JsonInstant
    private val callerId = Uuid.random()
    private val targetId = Uuid.random()

    private fun createFactory(
        assistants: List<Assistant>,
        memoryResult: Result<MemoryListResult>? = null,
        toolSetFactory: GenerationToolSetFactory = mockk(relaxed = true),
    ): AssistantToolFactory {
        val caller = assistants.find { it.id == callerId } ?: Assistant(id = callerId, name = "Caller")
        val settings = Settings(
            assistants = assistants,
            assistantId = callerId,
        )
        val effectiveSettings = MutableStateFlow(settings.toEffectiveSettingsSnapshot())
        val settingsStore = mockk<SettingsStore>()
        every { settingsStore.effectiveSettings } returns effectiveSettings

        val managementService = mockk<AssistantManagementService>()
        if (memoryResult != null) {
            coEvery { managementService.listAssistantMemory(any()) } returns memoryResult
        }

        return AssistantToolFactory(
            settingsStore = settingsStore,
            assistantManagementService = managementService,
            json = json,
            delegationCoordinator = mockk(relaxed = true),
            toolSetFactory = toolSetFactory,
        )
    }

    private fun accessibleTarget(
        enableMemory: Boolean = true,
        useGlobalMemory: Boolean = false,
        description: String = "Routes research",
        systemPrompt: String = "You research first.",
        enabledSkills: Set<String> = emptySet(),
    ): Assistant = Assistant(
        id = targetId,
        name = "Target",
        description = description,
        systemPrompt = systemPrompt,
        allowAsSubAssistant = true,
        enableMemory = enableMemory,
        useGlobalMemory = useGlobalMemory,
        enabledSkills = enabledSkills,
    )

    private fun caller(allowed: Set<Uuid> = setOf(targetId)): Assistant = Assistant(
        id = callerId,
        name = "Caller",
        localTools = listOf(LocalToolOption.AssistantManagement),
        allowedSubAssistantIds = allowed,
    )

    private fun inspectTool(factory: AssistantToolFactory, caller: Assistant): Tool {
        val tools = factory.buildTools(caller, Uuid.random())
        return tools.single { it.name == "assistant_inspect" }
    }

    private fun parseResult(parts: List<UIMessagePart>): JsonObject =
        json.parseToJsonElement((parts.first() as UIMessagePart.Text).text).jsonObject

    private suspend fun failureResult(tool: Tool, arguments: JsonObject): JsonObject {
        val failure = try {
            tool.execute(arguments)
            throw AssertionError("expected ToolExecutionFailure")
        } catch (error: ToolExecutionFailure) {
            error
        }
        return parseResult(failure.output)
    }

    private fun args(
        assistantId: Uuid = targetId,
        sections: List<String>? = null,
    ) = buildJsonObject {
        put("assistant_id", assistantId.toString())
        if (sections != null) {
            put("sections", buildJsonArray {
                sections.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
            })
        }
    }

    @Test
    fun `description guides usage and mentions default profile`() {
        val caller = caller()
        val tool = inspectTool(createFactory(listOf(caller, accessibleTarget())), caller)
        assertTrue(tool.description.contains("Inspect"))
        assertTrue(tool.description.contains("before updating or deleting"))
        assertTrue(tool.description.contains("profile by default"))
    }

    @Test
    fun `omitted sections returns profile only`() = runTest {
        val target = accessibleTarget()
        val caller = caller()
        val parts = inspectTool(createFactory(listOf(caller, target)), caller).execute(args())
        val result = parseResult(parts)

        assertEquals(targetId.toString(), result["id"]!!.jsonPrimitive.content)
        assertFalse(result.containsKey("assistant"))
        val profile = result["profile"]!!.jsonObject
        assertEquals("Target", profile["name"]!!.jsonPrimitive.content)
        assertEquals("Routes research", profile["description"]!!.jsonPrimitive.content)
        assertEquals("You research first.", profile["instructions"]!!.jsonPrimitive.content)
        assertFalse(result.containsKey("tools"))
        assertFalse(result.containsKey("skills"))
        assertFalse(result.containsKey("memory"))
    }

    @Test
    fun `empty or unknown sections fall back to profile`() = runTest {
        val target = accessibleTarget()
        val caller = caller()
        val tool = inspectTool(createFactory(listOf(caller, target)), caller)

        val empty = parseResult(tool.execute(args(sections = emptyList())))
        assertTrue(empty.containsKey("profile"))
        assertFalse(empty.containsKey("tools"))

        val unknown = parseResult(tool.execute(args(sections = listOf("notes", "ALL"))))
        assertTrue(unknown.containsKey("profile"))
        assertFalse(unknown.containsKey("memory"))
    }

    @Test
    fun `non-string section items are ignored`() = runTest {
        val target = accessibleTarget()
        val caller = caller()
        val tool = inspectTool(createFactory(listOf(caller, target)), caller)
        val result = parseResult(
            tool.execute(
                buildJsonObject {
                    put("assistant_id", targetId.toString())
                    put("sections", buildJsonArray {
                        add(buildJsonObject { put("x", "tools") })
                        add(kotlinx.serialization.json.JsonPrimitive("skills"))
                    })
                },
            ),
        )
        assertTrue(result.containsKey("skills"))
        assertFalse(result.containsKey("profile"))
        assertFalse(result.containsKey("tools"))
    }

    @Test
    fun `tools section lists names without descriptions and adds memory_tool`() = runTest {
        val target = accessibleTarget(enableMemory = true)
        val caller = caller()
        val toolSetFactory = mockk<GenerationToolSetFactory>()
        every { toolSetFactory.captureMcpCapabilities(any()) } returns
            net.weero.measix.pilot.data.ai.mcp.TurnMcpCapabilitySnapshot.EMPTY
        coEvery {
            toolSetFactory.buildTools(
                assistant = any(),
                conversationId = any(),
                settings = any(),
                capabilityModel = any(),
                workspaceCwd = any(),
                runMode = ToolSetRunMode.TARGET,
                ttsPlaybackContext = any(),
                additionalToolsBeforeMcp = any(),
                mcpCapabilities = any(),
                onInvalidMcpServerNames = any(),
            )
        } returns listOf(
            Tool(name = "search_web", description = "must not leak", execute = { emptyList() }),
            Tool(name = "get_time_info", description = "hidden", execute = { emptyList() }),
        )
        val parts = inspectTool(
            createFactory(listOf(caller, target), toolSetFactory = toolSetFactory),
            caller,
        ).execute(args(sections = listOf("tools")))
        val result = parseResult(parts)
        val names = result["tools"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("search_web", "get_time_info", "memory_tool"), names)
        assertFalse(result.containsKey("profile"))
        assertFalse(parts.single().let { (it as UIMessagePart.Text).text }.contains("must not leak"))
    }

    @Test
    fun `skills section lists mounted names`() = runTest {
        val target = accessibleTarget(enabledSkills = setOf("review", "search"))
        val caller = caller()
        val result = parseResult(
            inspectTool(createFactory(listOf(caller, target)), caller)
                .execute(args(sections = listOf("skills"))),
        )
        val names = result["skills"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("review", "search"), names)
    }

    @Test
    fun `memory section nests local rows`() = runTest {
        val target = accessibleTarget()
        val caller = caller()
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
        val result = parseResult(
            inspectTool(factory, caller).execute(args(sections = listOf("memory"))),
        )
        val memory = result["memory"]!!.jsonObject
        assertEquals("local", memory["active"]!!.jsonPrimitive.content)
        assertEquals(2, memory["rows"]!!.jsonArray.size)
        assertEquals("Uses JDK 17", memory["rows"]!!.jsonArray[0].jsonArray[1].jsonPrimitive.content)
        assertFalse(result.containsKey("active_memory"))
    }

    @Test
    fun `global memory scope returns empty rows`() = runTest {
        val target = accessibleTarget(useGlobalMemory = true)
        val caller = caller()
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
        val memory = parseResult(
            inspectTool(factory, caller).execute(args(sections = listOf("memory"))),
        )["memory"]!!.jsonObject
        assertEquals("global", memory["active"]!!.jsonPrimitive.content)
        assertEquals(0, memory["rows"]!!.jsonArray.size)
    }

    @Test
    fun `multiple requested sections appear together`() = runTest {
        val target = accessibleTarget(enabledSkills = setOf("demo"))
        val caller = caller()
        val result = parseResult(
            inspectTool(createFactory(listOf(caller, target)), caller)
                .execute(args(sections = listOf("profile", "skills"))),
        )
        assertTrue(result.containsKey("profile"))
        assertTrue(result.containsKey("skills"))
        assertFalse(result.containsKey("tools"))
        assertFalse(result.containsKey("memory"))
    }

    @Test
    fun `target is caller returns target_is_caller`() = runTest {
        val caller = caller(allowed = emptySet())
        val result = failureResult(
            inspectTool(createFactory(listOf(caller)), caller), args(assistantId = callerId),
        )
        assertEquals("target_is_caller", result["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun `target not allowed returns target_not_allowed`() = runTest {
        val target = accessibleTarget()
        val caller = caller(allowed = emptySet())
        val result = failureResult(inspectTool(createFactory(listOf(caller, target)), caller), args())
        assertEquals("target_not_allowed", result["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun `caller missing returns tool_not_permitted`() = runTest {
        val target = accessibleTarget()
        val otherCaller = Assistant(
            id = Uuid.random(),
            name = "Other",
            localTools = listOf(LocalToolOption.AssistantManagement),
            allowedSubAssistantIds = setOf(targetId),
        )
        val factory = createFactory(listOf(otherCaller, target))
        val caller = caller()
        val result = failureResult(inspectTool(factory, caller), args())
        assertEquals("tool_not_permitted", result["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tool disabled returns tool_not_permitted`() = runTest {
        val target = accessibleTarget()
        val caller = Assistant(
            id = callerId,
            name = "Caller",
            localTools = emptyList(),
            allowedSubAssistantIds = setOf(targetId),
        )
        val factory = createFactory(listOf(caller, target))
        val tool = factory.buildTools(
            caller.copy(localTools = listOf(LocalToolOption.AssistantManagement)),
            Uuid.random(),
        ).single { it.name == "assistant_inspect" }
        val result = failureResult(tool, args())
        assertEquals("tool_not_permitted", result["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun `globally visible target is accessible without explicit allow`() = runTest {
        val target = accessibleTarget().copy(isSubAssistantGloballyVisible = true)
        val caller = caller(allowed = emptySet())
        val result = parseResult(
            inspectTool(createFactory(listOf(caller, target)), caller).execute(args()),
        )
        assertEquals("Target", result["profile"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `missing assistant_id returns invalid_arguments`() = runTest {
        val caller = caller()
        val result = failureResult(
            inspectTool(createFactory(listOf(caller)), caller), buildJsonObject { },
        )
        assertEquals("invalid_arguments", result["reason"]!!.jsonPrimitive.content)
    }
}
