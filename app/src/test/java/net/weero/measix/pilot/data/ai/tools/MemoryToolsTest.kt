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
import net.weero.measix.pilot.data.repository.MemoryNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MemoryToolsTest {
    @Test
    fun `missing current namespace record has the same clear edit and delete failure`() = runTest {
        val tool = buildMemoryTools(
            onCreation = { AssistantMemory(id = 1, content = it) },
            onUpdate = { id, _ -> throw MemoryNotFoundException(id) },
            onDelete = { id -> throw MemoryNotFoundException(id) },
        ).single()
        listOf("edit", "delete").forEach { action ->
            val args = buildJsonObject {
                put("action", action)
                put("id", 111)
                if (action == "edit") put("content", "new")
            }
            val failure = try {
                tool.execute(args)
                throw AssertionError("expected failure")
            } catch (error: ToolExecutionFailure) {
                error
            }
            val result = Json.parseToJsonElement((failure.output.single() as UIMessagePart.Text).text).jsonObject
            assertEquals(setOf("status", "reason", "detail"), result.keys)
            assertEquals("failed", result.getValue("status").jsonPrimitive.content)
            assertEquals("memory_not_found_in_namespace", result.getValue("reason").jsonPrimitive.content)
            assertEquals(
                "Memory 111 does not exist in the current namespace. Do not retry this ID unchanged.",
                result.getValue("detail").jsonPrimitive.content,
            )
        }
    }

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
