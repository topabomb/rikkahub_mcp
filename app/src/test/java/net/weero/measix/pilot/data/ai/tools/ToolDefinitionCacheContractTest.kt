package net.weero.measix.pilot.data.ai.tools

import me.rerere.ai.core.Tool
import net.weero.measix.pilot.data.ai.mcp.McpCommonOptions
import net.weero.measix.pilot.data.ai.mcp.McpServerConfig
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.uuid.Uuid

/**
 * 缓存契约：工具定义只在 START 装配一次，且定义里不得出现任何 live disclosure。
 *
 * 断言对象是**真实工具**（`buildMemoryTools` / `createSearchTools`）与其真实冻结产物
 * `FrozenToolDefinition` 的序列化 bytes——只有被实际读取的 live 源才能证明解耦，
 * 因此这里刻意用"改变 Settings 内容后重新装配"而不是伪造一个不读取任何 live 源的闭包。
 */
class ToolDefinitionCacheContractTest {
    private fun bytes(set: FrozenToolSet): String = JsonInstant.encodeToString(set.definitions)

    private fun settingsWith(
        assistants: List<Assistant>,
        mcpServers: List<McpServerConfig>,
    ): Settings = Settings(
        chatModelId = Uuid.parse("00000000-0000-0000-0000-000000000401"),
        fastModelId = Uuid.parse("00000000-0000-0000-0000-000000000402"),
        imageGenerationModelId = Uuid.parse("00000000-0000-0000-0000-000000000403"),
        compressModelId = Uuid.parse("00000000-0000-0000-0000-000000000404"),
        assistants = assistants,
        mcpServers = mcpServers,
    )

    private fun mcpServer(name: String, url: String) = McpServerConfig.StreamableHTTPServer(
        commonOptions = McpCommonOptions(name = name),
        url = url,
    )

    private fun assistant(id: Uuid, name: String, tag: Uuid) = Assistant(
        id = id,
        name = name,
        description = "$name description",
        systemPrompt = "$name system prompt",
        tags = listOf(tag),
    )

    private fun realToolSet(settings: Settings): List<Tool> = buildList {
        addAll(
            buildMemoryTools(
                onCreation = { error("definition assembly must not execute") },
                onUpdate = { _, _ -> error("definition assembly must not execute") },
                onDelete = { error("definition assembly must not execute") },
            ),
        )
        addAll(createSearchTools(settings))
    }

    @Test
    fun `real definitions ignore assistant and MCP catalog content across assemblies`() {
        val tag = Uuid.random()
        val baseline = settingsWith(
            assistants = listOf(assistant(Uuid.random(), "Baseline Agent", tag)),
            mcpServers = listOf(mcpServer("baseline-server", "https://baseline.example/mcp")),
        )
        val changed = settingsWith(
            assistants = listOf(
                assistant(Uuid.random(), "Renamed Agent", tag),
                assistant(Uuid.random(), "Created Agent", tag),
                assistant(Uuid.random(), "Another Agent", tag),
            ),
            mcpServers = listOf(
                mcpServer("renamed-server", "https://other.example/mcp"),
                mcpServer("added-server", "https://added.example/mcp"),
            ),
        )

        assertEquals(bytes(freezeToolSet(realToolSet(baseline))), bytes(freezeToolSet(realToolSet(changed))))
    }

    @Test
    fun `frozen definitions carry no assistant MCP catalog or calendar disclosure`() {
        val assistantId = Uuid.random()
        val tag = Uuid.random()
        val settings = settingsWith(
            assistants = listOf(assistant(assistantId, "Disclosure Sentinel Agent", tag)),
            mcpServers = listOf(mcpServer("sentinel-catalog-server", "https://sentinel.example/mcp")),
        )

        val serialized = bytes(freezeToolSet(realToolSet(settings)))

        assertFalse(serialized.contains("Disclosure Sentinel Agent"))
        assertFalse(serialized.contains(assistantId.toString()))
        assertFalse(serialized.contains("sentinel-catalog-server"))
        assertFalse(serialized.contains("sentinel.example"))
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        assertFalse("$serialized must not embed the current date", serialized.contains(today))
    }

    @Test
    fun `memory definitions stay stable when live namespace permission is revoked`() {
        val allowed = freezeToolSet(buildMemoryTools(
            onCreation = { error("not used") },
            onUpdate = { _, _ -> error("not used") },
            onDelete = { error("not used") },
        ))
        val revoked = freezeToolSet(buildMemoryTools(
            onCreation = { error("not used") },
            onUpdate = { _, _ -> error("not used") },
            onDelete = { error("not used") },
            isStillAllowed = { false },
        ))

        assertEquals(bytes(allowed), bytes(revoked))
    }
}
