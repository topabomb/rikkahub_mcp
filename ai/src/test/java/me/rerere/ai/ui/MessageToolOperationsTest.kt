package me.rerere.ai.ui

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageToolOperationsTest {

    @Test
    fun `getTools returns only Tool parts`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Hello"),
                UIMessagePart.Tool(
                    toolCallId = "tc1",
                    toolName = "search_web",
                    input = """{"query":"test"}"""
                ),
                UIMessagePart.Tool(
                    toolCallId = "tc2",
                    toolName = "eval_javascript",
                    input = """{"code":"1+1"}"""
                )
            )
        )
        val tools = message.getTools()
        assertEquals(2, tools.size)
        assertEquals("search_web", tools[0].toolName)
        assertEquals("eval_javascript", tools[1].toolName)
    }

    @Test
    fun `getTools returns empty list when no tools`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Hello"),
                UIMessagePart.Reasoning("thinking...")
            )
        )
        assertTrue(message.getTools().isEmpty())
    }

    @Test
    fun `isExecuted is true when tool has output`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "tc1",
            toolName = "search_web",
            input = """{"query":"test"}""",
            output = listOf(UIMessagePart.Text("result"))
        )
        assertTrue(tool.isExecuted)
    }

    @Test
    fun `isExecuted is false when tool has no output`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "tc1",
            toolName = "search_web",
            input = """{"query":"test"}"""
        )
        assertFalse(tool.isExecuted)
    }

    @Test
    fun `isPending is true when approval state is Pending`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "tc1",
            toolName = "ask_user",
            input = """{"question":"?"}""",
            approvalState = ToolApprovalState.Pending
        )
        assertTrue(tool.isPending)
    }

    @Test
    fun `isPending is false when approval state is Auto`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "tc1",
            toolName = "search_web",
            input = """{"query":"test"}""",
            approvalState = ToolApprovalState.Auto
        )
        assertFalse(tool.isPending)
    }

    @Test
    fun `canResumeExecution is true for unexecuted approved tool`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "tc1",
            toolName = "ask_user",
            input = """{"question":"?"}""",
            approvalState = ToolApprovalState.Approved
        )
        assertTrue(tool.canResumeExecution)
    }

    @Test
    fun `canResumeExecution is true for unexecuted denied tool`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "tc1",
            toolName = "ask_user",
            input = """{"question":"?"}""",
            approvalState = ToolApprovalState.Denied("no")
        )
        assertTrue(tool.canResumeExecution)
    }

    @Test
    fun `canResumeExecution is true for unexecuted answered tool`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "tc1",
            toolName = "ask_user",
            input = """{"question":"?"}""",
            approvalState = ToolApprovalState.Answered("yes")
        )
        assertTrue(tool.canResumeExecution)
    }

    @Test
    fun `canResumeExecution is false for executed tool`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "tc1",
            toolName = "search_web",
            input = """{"query":"test"}""",
            output = listOf(UIMessagePart.Text("result")),
            approvalState = ToolApprovalState.Approved
        )
        assertFalse(tool.canResumeExecution)
    }

    @Test
    fun `canResumeExecution is false for pending tool`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "tc1",
            toolName = "ask_user",
            input = """{"question":"?"}""",
            approvalState = ToolApprovalState.Pending
        )
        assertFalse(tool.canResumeExecution)
    }

    @Test
    fun `canResumeExecution is false for auto tool`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "tc1",
            toolName = "search_web",
            input = """{"query":"test"}""",
            approvalState = ToolApprovalState.Auto
        )
        assertFalse(tool.canResumeExecution)
    }

    @Test
    fun `inputAsJson parses valid JSON`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "tc1",
            toolName = "search_web",
            input = """{"query":"hello world"}"""
        )
        val json = tool.inputAsJson()
        assertTrue(json is kotlinx.serialization.json.JsonObject)
        val obj = json as kotlinx.serialization.json.JsonObject
        val queryValue = obj["query"]
        assertTrue(queryValue is JsonPrimitive)
        assertEquals("hello world", (queryValue as JsonPrimitive).content)
    }

    @Test
    fun `inputAsJson handles blank input`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "tc1",
            toolName = "search_web",
            input = ""
        )
        val json = tool.inputAsJson()
        assertTrue(json is kotlinx.serialization.json.JsonObject)
    }

    @Test
    fun `tool copy with updated approval state`() {
        val original = UIMessagePart.Tool(
            toolCallId = "tc1",
            toolName = "ask_user",
            input = """{"question":"?"}""",
            approvalState = ToolApprovalState.Auto
        )
        val updated = original.copy(approvalState = ToolApprovalState.Pending)
        assertTrue(updated.isPending)
        assertFalse(updated.isExecuted)
    }

    @Test
    fun `tool copy with output marks as executed`() {
        val original = UIMessagePart.Tool(
            toolCallId = "tc1",
            toolName = "eval_javascript",
            input = """{"code":"1+1"}"""
        )
        val executed = original.copy(
            output = listOf(UIMessagePart.Text("2"))
        )
        assertTrue(executed.isExecuted)
        assertFalse(executed.canResumeExecution)
    }
}
