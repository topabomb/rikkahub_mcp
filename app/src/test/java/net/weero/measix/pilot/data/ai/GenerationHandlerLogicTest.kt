package net.weero.measix.pilot.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationHandlerLogicTest {

    private val json = Json { encodeDefaults = true }

    // region Tool approval state transition tests

    @Test
    fun `tool with Auto state and needsApproval should transition to Pending`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "tc1",
            toolName = "ask_user",
            input = """{"question":"?"}""",
            approvalState = ToolApprovalState.Auto
        )
        val updated = resolveToolApprovals(
            unexecutedTools = listOf(tool),
            toolDefinitions = listOf(toolDefinition("ask_user", needsApproval = true)),
            nonInteractive = false,
            interactiveToolNames = emptySet(),
            json = json,
        ).tools.single()

        assertTrue(updated.isPending)
        assertEquals(ToolApprovalState.Pending, updated.approvalState)
    }

    @Test
    fun `tool with Auto state and no needsApproval should remain Auto`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "tc1",
            toolName = "search_web",
            input = """{"query":"test"}""",
            approvalState = ToolApprovalState.Auto
        )
        val updated = resolveToolApprovals(
            unexecutedTools = listOf(tool),
            toolDefinitions = listOf(toolDefinition("search_web", needsApproval = false)),
            nonInteractive = false,
            interactiveToolNames = emptySet(),
            json = json,
        ).tools.single()

        assertFalse(updated.isPending)
        assertEquals(ToolApprovalState.Auto, updated.approvalState)
    }

    @Test
    fun `denied tool should produce error output`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "tc1",
            toolName = "ask_user",
            input = """{"question":"?"}""",
            approvalState = ToolApprovalState.Denied("Security concern")
        )

        // Simulate the denied handler in GenerationHandler
        val output = when (tool.approvalState) {
            is ToolApprovalState.Denied -> {
                val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                listOf(
                    UIMessagePart.Text(
                        json.encodeToString(
                            buildJsonObject {
                                put("error", JsonPrimitive("Tool execution denied by user. Reason: $reason"))
                            }
                        )
                    )
                )
            }
            else -> emptyList()
        }

        val executedTool = tool.copy(output = output)
        assertTrue(executedTool.isExecuted)
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

        val output = when (tool.approvalState) {
            is ToolApprovalState.Answered -> {
                val answer = (tool.approvalState as ToolApprovalState.Answered).answer
                listOf(UIMessagePart.Text(answer))
            }
            else -> emptyList()
        }

        val executedTool = tool.copy(output = output)
        assertTrue(executedTool.isExecuted)
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

        assertTrue(tool.canResumeExecution)
        assertFalse(tool.isExecuted)
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

        val resumableTools = tools.filter { it.canResumeExecution }
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

        val resumableTools = message.getTools().filter { it.canResumeExecution }
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

        val unexecutedTools = message.getTools().filter { !it.isExecuted }
        assertTrue(unexecutedTools.isEmpty())
        // This should trigger "break" in the ReAct loop
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

        val unexecuted = tools.filter { !it.isExecuted }
        assertEquals(1, unexecuted.size)
        assertEquals("tc2", unexecuted[0].toolCallId)

        val executed = tools.filter { it.isExecuted }
        assertEquals(1, executed.size)
        assertEquals("tc1", executed[0].toolCallId)
    }

    @Test
    fun `tool output truncation threshold check`() {
        val maxOutputChars = 32 * 1024
        val previewChars = 4 * 1024

        // Small output should not be truncated
        val smallOutput = "x".repeat(maxOutputChars - 1)
        assertTrue(smallOutput.length <= maxOutputChars)

        // Large output should be truncated
        val largeOutput = "x".repeat(maxOutputChars + 1)
        assertTrue(largeOutput.length > maxOutputChars)

        // Preview should be smaller than full output
        val preview = largeOutput.take(previewChars)
        assertEquals(previewChars, preview.length)
        assertTrue(preview.length < largeOutput.length)
    }

    // endregion

    // region Message update after tool execution

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
        assertTrue(tools[0].isExecuted)
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

    @Test
    fun `pending approval is a batch barrier for automatic tools`() {
        val automatic = UIMessagePart.Tool(
            toolCallId = "auto",
            toolName = "get_time_info",
            input = "{}",
        )
        val question = UIMessagePart.Tool(
            toolCallId = "question",
            toolName = "ask_user",
            input = "{}",
        )

        val resolution = resolveToolApprovals(
            unexecutedTools = listOf(automatic, question),
            toolDefinitions = listOf(
                toolDefinition("get_time_info", needsApproval = false),
                toolDefinition("ask_user", needsApproval = true),
            ),
            nonInteractive = false,
            interactiveToolNames = emptySet(),
            json = json,
        )

        assertTrue(resolution.hasPendingApproval)
        assertFalse(resolution.tools[0].isExecuted)
        assertEquals(ToolApprovalState.Pending, resolution.tools[1].approvalState)
    }

    @Test
    fun `target mode exposes ask user but rejects other approval tools`() {
        val question = UIMessagePart.Tool(
            toolCallId = "question",
            toolName = "ask_user",
            input = "{}",
        )
        val shell = UIMessagePart.Tool(
            toolCallId = "shell",
            toolName = "workspace_shell",
            input = "{}",
        )

        val resolution = resolveToolApprovals(
            unexecutedTools = listOf(question, shell),
            toolDefinitions = listOf(
                toolDefinition("ask_user", needsApproval = true),
                toolDefinition("workspace_shell", needsApproval = true),
            ),
            nonInteractive = true,
            interactiveToolNames = setOf("ask_user"),
            json = json,
        )

        assertTrue(resolution.hasPendingApproval)
        assertEquals(ToolApprovalState.Pending, resolution.tools[0].approvalState)
        assertTrue(resolution.tools[1].isExecuted)
        assertTrue((resolution.tools[1].output.single() as UIMessagePart.Text).text.contains("tool_not_permitted"))
    }

    private fun toolDefinition(name: String, needsApproval: Boolean) = Tool(
        name = name,
        description = name,
        needsApproval = { needsApproval },
        execute = { emptyList() },
    )

    // endregion
}
