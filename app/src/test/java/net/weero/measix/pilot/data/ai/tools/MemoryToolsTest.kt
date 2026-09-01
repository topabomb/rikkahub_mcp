package net.weero.measix.pilot.data.ai.tools

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.ToolExecutionFailure
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.model.AssistantMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MemoryToolsTest {
    @Test
    fun `revoked memory tool returns stable error without mutating repository`() = runTest {
        var mutated = false
        val tool = buildMemoryTools(
            onCreation = {
                mutated = true
                AssistantMemory(id = 1, content = it)
            },
            onUpdate = { id, content ->
                mutated = true
                AssistantMemory(id = id, content = content)
            },
            onDelete = { mutated = true },
            isStillAllowed = { false },
        ).single()

        val failure = try {
            tool.execute(
                buildJsonObject {
                    put("action", "create")
                    put("content", "must not be stored")
                }
            )
            throw AssertionError("expected ToolExecutionFailure")
        } catch (error: ToolExecutionFailure) {
            error
        }
        val payload = (failure.output.single() as UIMessagePart.Text).text
        val resultObject = Json.parseToJsonElement(payload).jsonObject
        assertEquals("failed", resultObject["status"]?.jsonPrimitive?.content)
        assertEquals("tool_not_permitted", resultObject["reason"]?.jsonPrimitive?.content)
        assertFalse(mutated)
    }
}
