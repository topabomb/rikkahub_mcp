package net.weero.measix.pilot.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Durable message-part semantics used by the generation loop. Gate behaviour itself is owned by
 * ToolCallRuntimeGateTest; this file keeps only the message/ordinal data-model invariants.
 */
class GenerationLoopLogicTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun `denied tool should produce error output`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "tc1",
            toolName = "ask_user",
            input = """{"question":"?"}""",
            approvalState = ToolApprovalState.Denied("Security concern")
        )

        val reason = (tool.approvalState as ToolApprovalState.Denied).reason
        val output = listOf(
            UIMessagePart.Text(
                json.encodeToString(
                    buildJsonObject {
                        put("error", JsonPrimitive("Tool execution denied by user. Reason: $reason"))
                    }
                )
            )
        )

        val executedTool = tool.copy(output = output)
        assertTrue(executedTool.hasReplayResult)
        val outputText = (executedTool.output[0] as UIMessagePart.Text).text
        assertTrue(outputText.contains("Security concern"))
        assertTrue(outputText.contains("denied"))
    }

    @Test
    fun `denied tool with blank reason should use default message`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "tc1",
            toolName = "ask_user",
            input = """{"question":"?"}""",
            approvalState = ToolApprovalState.Denied("")
        )

        val reason = (tool.approvalState as ToolApprovalState.Denied).reason
        val message = "Tool execution denied by user. Reason: ${reason.ifBlank { "No reason provided" }}"
        assertTrue(message.contains("No reason provided"))
    }

    @Test
    fun `answered tool should produce answer output`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "tc1",
            toolName = "ask_user",
            input = """{"question":"What is your name?"}""",
            approvalState = ToolApprovalState.Answered("My name is Alice")
        )

        val answer = (tool.approvalState as ToolApprovalState.Answered).answer
        val executedTool = tool.copy(output = listOf(UIMessagePart.Text(answer)))
        assertTrue(executedTool.hasReplayResult)
        assertEquals("My name is Alice", (executedTool.output[0] as UIMessagePart.Text).text)
    }

    @Test
    fun `approved tool should be ready for execution`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "tc1",
            toolName = "workspace_shell",
            input = """{"command":"ls"}""",
            approvalState = ToolApprovalState.Approved
        )

        assertTrue(tool.canResumeResultAssembly)
        assertFalse(tool.hasReplayResult)
        assertFalse(tool.isPending)
    }

    @Test
    fun `pending tools detection in message`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("I'll search for that"),
                UIMessagePart.Tool(
                    toolCallId = "tc1",
                    toolName = "search_web",
                    input = """{"query":"test"}""",
                    approvalState = ToolApprovalState.Auto
                ),
                UIMessagePart.Tool(
                    toolCallId = "tc2",
                    toolName = "ask_user",
                    input = """{"question":"Which one?"}""",
                    approvalState = ToolApprovalState.Pending
                )
            )
        )

        val tools = message.getTools()
        assertEquals(2, tools.size)

        val pendingTools = tools.filter { it.isPending }
        assertEquals(1, pendingTools.size)
        assertEquals("tc2", pendingTools[0].toolCallId)

        val resumableTools = tools.filter { it.canResumeResultAssembly }
        assertTrue(resumableTools.isEmpty()) // Auto can't resume, Pending can't resume
    }

    @Test
    fun `resumable tools detection after approval`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "tc1",
                    toolName = "ask_user",
                    input = """{"question":"?"}""",
                    approvalState = ToolApprovalState.Approved
                ),
                UIMessagePart.Tool(
                    toolCallId = "tc2",
                    toolName = "ask_user",
                    input = """{"question":"?"}""",
                    approvalState = ToolApprovalState.Denied("no")
                )
            )
        )

        val resumableTools = message.getTools().filter { it.canResumeResultAssembly }
        assertEquals(2, resumableTools.size)
    }

    @Test
    fun `no tool calls should indicate generation end`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Here is the answer: 42")
            )
        )

        val toolsAwaitingReplayResult = message.getTools().filter { !it.hasReplayResult }
        assertTrue(toolsAwaitingReplayResult.isEmpty())
    }

    @Test
    fun `mixed executed and unexecuted tools`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "tc1",
                    toolName = "search_web",
                    input = """{"query":"test"}""",
                    output = listOf(UIMessagePart.Text("result"))
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

        val unexecuted = tools.filter { !it.hasReplayResult }
        assertEquals(1, unexecuted.size)
        assertEquals("tc2", unexecuted[0].toolCallId)

        val executed = tools.filter { it.hasReplayResult }
        assertEquals(1, executed.size)
        assertEquals("tc1", executed[0].toolCallId)
    }

    @Test
    fun `update message parts with executed tools`() {
        val originalMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Let me help"),
                UIMessagePart.Tool(
                    toolCallId = "tc1",
                    toolName = "search_web",
                    input = """{"query":"test"}"""
                )
            )
        )

        val executedTool = UIMessagePart.Tool(
            toolCallId = "tc1",
            toolName = "search_web",
            input = """{"query":"test"}""",
            output = listOf(UIMessagePart.Text("search results"))
        )

        val updatedMessage = originalMessage.replaceToolsAtOrdinals(mapOf(0 to executedTool))
        val tools = updatedMessage.getTools()
        assertEquals(1, tools.size)
        assertTrue(tools[0].hasReplayResult)
        assertEquals("search results", (tools[0].output[0] as UIMessagePart.Text).text)
    }

    @Test
    fun `multiple tool execution uses ordinal even when provider ids repeat`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "duplicate",
                    toolName = "search_web",
                    input = """{"query":"test1"}"""
                ),
                UIMessagePart.Tool(
                    toolCallId = "duplicate",
                    toolName = "search_web",
                    input = """{"query":"test2"}"""
                )
            )
        )

        val executedTools = message.getTools().mapIndexed { ordinal, tool ->
            tool.copy(output = listOf(UIMessagePart.Text("result${ordinal + 1}")))
        }

        val updatedMessage = message.replaceToolsAtOrdinals(executedTools.withIndex().associate { it.index to it.value })
        assertEquals("result1", (updatedMessage.getTools()[0].output.single() as UIMessagePart.Text).text)
        assertEquals("result2", (updatedMessage.getTools()[1].output.single() as UIMessagePart.Text).text)
    }
}
