package net.weero.measix.pilot.data.ai.tools

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.put
import me.rerere.ai.core.ToolAttachmentResolution
import me.rerere.ai.core.ToolExecutionContext
import me.rerere.ai.core.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Test

class FrozenToolSetTest {
    @Test
    fun `assembly evaluates schema once and preserves ordered one-to-one names`() {
        var evaluations = 0
        val first = Tool(
            name = "first",
            description = "one",
            parameters = {
                evaluations++
                buildJsonObject { put("revision", evaluations) }
            },
            execute = { emptyList() },
        )
        val second = Tool(name = "second", description = "two", execute = { emptyList() })

        val frozen = freezeToolSet(listOf(first, second))

        assertEquals(1, evaluations)
        assertEquals(listOf("first", "second"), frozen.definitions.map { it.name })
        assertEquals(listOf("first", "second"), frozen.bindingsByName.keys.toList())
        assertEquals("1", frozen.definitions.first().parameters?.get("revision").toString())
        assertNotSame(first, frozen.bindingsByName.getValue("first"))
    }

    @Test
    fun `frozen definition bytes remain stable after live schema changes until the next START`() {
        var revision = 1
        val tool = Tool(
            name = "dynamic",
            description = "stable",
            parameters = { buildJsonObject { put("revision", revision) } },
            systemPromptContribution = "stable prompt",
            execute = { emptyList() },
        )
        val turn = freezeToolSet(listOf(tool))
        val firstStepBytes = Json.encodeToString(turn.definitions)

        revision = 2
        val laterStepBytes = Json.encodeToString(turn.definitions)
        val nextTurnBytes = Json.encodeToString(freezeToolSet(listOf(tool)).definitions)

        assertEquals(firstStepBytes, laterStepBytes)
        assertEquals(false, firstStepBytes == nextTurnBytes)
    }

    @Test
    fun `frozen definition stays stable while same-name binding executes live closure`() = runTest {
        var executionValue = "first"
        val frozen = freezeToolSet(listOf(Tool(
            name = "live_execution",
            description = "stable",
            execute = { listOf(me.rerere.ai.ui.UIMessagePart.Text(executionValue)) },
        )))
        val definitionBytes = Json.encodeToString(frozen.definitions)
        executionValue = "second"
        val context = ToolExecutionContext(
            messageId = kotlin.uuid.Uuid.random(),
            toolOrdinal = 0,
            toolCallId = "call",
            reportMetadata = { _, _ -> },
            resolveAttachments = { ToolAttachmentResolution() },
            reportChildConversation = {},
            registerUnpublishedResource = {},
        )

        val output = frozen.bindingsByName.getValue("live_execution").execute(
            context,
            JsonObject(emptyMap()),
        )

        assertEquals("second", (output.single() as me.rerere.ai.ui.UIMessagePart.Text).text)
        assertEquals(definitionBytes, Json.encodeToString(frozen.definitions))
    }

    @Test
    fun `freeze detaches schema from a mutable backing map`() {
        val backing = mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
            "revision" to JsonPrimitive(1),
        )
        val frozen = freezeToolSet(listOf(Tool(
            name = "mutable",
            description = "schema",
            parameters = { JsonObject(backing) },
            execute = { emptyList() },
        )))
        val before = Json.encodeToString(frozen.definitions)

        backing["revision"] = JsonPrimitive(2)

        assertEquals(before, Json.encodeToString(frozen.definitions))
        assertEquals(JsonPrimitive(1), frozen.definitions.single().parameters?.get("revision"))
    }

    @Test
    fun `assembly rejects blank and duplicate names before any request`() {
        assertThrows(IllegalArgumentException::class.java) {
            freezeToolSet(listOf(Tool(name = "", description = "bad", execute = { emptyList() })))
        }
        assertThrows(IllegalArgumentException::class.java) {
            freezeToolSet(
                listOf(
                    Tool(name = "dup", description = "one", execute = { emptyList() }),
                    Tool(name = "dup", description = "two", execute = { emptyList() }),
                ),
            )
        }
    }
}
