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

    // ==================== finishPendingTools 回归测试 ====================
    // 回归: finishPendingTools 曾对所有 !isExecuted 的工具调用 transform,
    // 导致超时中断的 Auto/Approved 工具被误标记为 "Denied: Generation cancelled by user"。
    // 修复后 finishPendingTools 只处理 Pending 状态的工具。

    @Test
    fun `finishPendingTools should only process Pending tools`() {
        val pendingTool = UIMessagePart.Tool(
            toolCallId = "pending",
            toolName = "ask_user",
            input = """{"question":"?"}""",
            approvalState = ToolApprovalState.Pending
        )
        val autoTool = UIMessagePart.Tool(
            toolCallId = "auto",
            toolName = "search_web",
            input = """{"query":"test"}""",
            approvalState = ToolApprovalState.Auto  // 未执行但非 Pending（超时中断场景）
        )

        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(pendingTool, autoTool)
        )

        val updated = message.finishPendingTools { tool ->
            tool.copy(
                output = listOf(UIMessagePart.Text("cancelled")),
                approvalState = ToolApprovalState.Denied("cancelled")
            )
        }

        val updatedPending = updated.parts.filterIsInstance<UIMessagePart.Tool>().find { it.toolCallId == "pending" }!!
        val updatedAuto = updated.parts.filterIsInstance<UIMessagePart.Tool>().find { it.toolCallId == "auto" }!!

        // Pending 工具应被处理
        assertTrue(updatedPending.isExecuted)
        assertTrue(updatedPending.approvalState is ToolApprovalState.Denied)

        // Auto 工具不应被处理（保留原状态）
        assertFalse(updatedAuto.isExecuted)
        assertTrue(updatedAuto.approvalState is ToolApprovalState.Auto)
    }

    @Test
    fun `finishPendingTools should not process executed tools`() {
        val executedTool = UIMessagePart.Tool(
            toolCallId = "executed",
            toolName = "search_web",
            input = """{"query":"test"}""",
            output = listOf(UIMessagePart.Text("result")),
            approvalState = ToolApprovalState.Pending  // Pending 但已执行
        )

        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(executedTool)
        )

        val updated = message.finishPendingTools { tool ->
            tool.copy(
                output = listOf(UIMessagePart.Text("should not happen")),
                approvalState = ToolApprovalState.Denied("should not happen")
            )
        }

        val tool = updated.parts.filterIsInstance<UIMessagePart.Tool>().first()
        assertEquals("result", (tool.output.first() as UIMessagePart.Text).text)
        assertTrue(tool.approvalState is ToolApprovalState.Pending)
    }

    @Test
    fun `finishPendingTools should return same message when no changes`() {
        val executedTool = UIMessagePart.Tool(
            toolCallId = "executed",
            toolName = "search_web",
            input = """{"query":"test"}""",
            output = listOf(UIMessagePart.Text("result"))
        )

        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(executedTool)
        )

        val updated = message.finishPendingTools { tool ->
            tool.copy(output = listOf(UIMessagePart.Text("should not happen")))
        }

        // 没有 Pending 未执行的工具，应返回原消息
        assertEquals(message, updated)
    }

    // ==================== finishInterruptedTools 回归测试 ====================
    // finishInterruptedTools 处理非 Pending 但 !isExecuted 的工具（超时/异常中断场景）

    @Test
    fun `finishInterruptedTools should process Auto tools without output`() {
        val autoTool = UIMessagePart.Tool(
            toolCallId = "auto",
            toolName = "mcp__space__bash",
            input = """{"command":"ls"}""",
            approvalState = ToolApprovalState.Auto  // 超时中断，output 为空
        )

        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(autoTool)
        )

        val updated = message.finishInterruptedTools { tool ->
            tool.copy(
                output = listOf(UIMessagePart.Text("""{"status":"interrupted"}"""))
            )
        }

        val tool = updated.parts.filterIsInstance<UIMessagePart.Tool>().first()
        assertTrue(tool.isExecuted)
        // approvalState 应保持不变（不标记为 Denied）
        assertTrue(tool.approvalState is ToolApprovalState.Auto)
    }

    @Test
    fun `finishInterruptedTools should process Approved tools without output`() {
        val approvedTool = UIMessagePart.Tool(
            toolCallId = "approved",
            toolName = "workspace_shell",
            input = """{"command":"rm -rf /"}""",
            approvalState = ToolApprovalState.Approved  // 用户已批准但执行被中断
        )

        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(approvedTool)
        )

        val updated = message.finishInterruptedTools { tool ->
            tool.copy(
                output = listOf(UIMessagePart.Text("""{"status":"interrupted"}"""))
            )
        }

        val tool = updated.parts.filterIsInstance<UIMessagePart.Tool>().first()
        assertTrue(tool.isExecuted)
        assertTrue(tool.approvalState is ToolApprovalState.Approved)
    }

    @Test
    fun `finishInterruptedTools should NOT process Pending tools`() {
        val pendingTool = UIMessagePart.Tool(
            toolCallId = "pending",
            toolName = "ask_user",
            input = """{"question":"?"}""",
            approvalState = ToolApprovalState.Pending
        )

        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(pendingTool)
        )

        val updated = message.finishInterruptedTools { tool ->
            tool.copy(
                output = listOf(UIMessagePart.Text("should not happen"))
            )
        }

        val tool = updated.parts.filterIsInstance<UIMessagePart.Tool>().first()
        assertFalse(tool.isExecuted)
        assertTrue(tool.approvalState is ToolApprovalState.Pending)
    }

    @Test
    fun `finishInterruptedTools should NOT process executed tools`() {
        val executedTool = UIMessagePart.Tool(
            toolCallId = "executed",
            toolName = "search_web",
            input = """{"query":"test"}""",
            output = listOf(UIMessagePart.Text("result")),
            approvalState = ToolApprovalState.Auto
        )

        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(executedTool)
        )

        val updated = message.finishInterruptedTools { tool ->
            tool.copy(output = listOf(UIMessagePart.Text("should not happen")))
        }

        assertEquals(message, updated)
    }

    @Test
    fun `finishInterruptedTools and finishPendingTools should be complementary`() {
        // 同一条消息中同时有 Pending 和 Auto 中断的工具
        val pendingTool = UIMessagePart.Tool(
            toolCallId = "pending",
            toolName = "ask_user",
            input = """{"question":"?"}""",
            approvalState = ToolApprovalState.Pending
        )
        val autoTool = UIMessagePart.Tool(
            toolCallId = "auto",
            toolName = "mcp__space__bash",
            input = """{"command":"ls"}""",
            approvalState = ToolApprovalState.Auto  // 超时中断
        )

        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(pendingTool, autoTool)
        )

        // 先处理 Pending → 只处理 pendingTool
        val afterPending = message.finishPendingTools { tool ->
            tool.copy(
                output = listOf(UIMessagePart.Text("cancelled by user")),
                approvalState = ToolApprovalState.Denied("cancelled")
            )
        }

        // 再处理中断 → 只处理 autoTool
        val afterInterrupted = afterPending.finishInterruptedTools { tool ->
            tool.copy(
                output = listOf(UIMessagePart.Text("""{"status":"interrupted"}"""))
            )
        }

        val finalPending = afterInterrupted.parts.filterIsInstance<UIMessagePart.Tool>().find { it.toolCallId == "pending" }!!
        val finalAuto = afterInterrupted.parts.filterIsInstance<UIMessagePart.Tool>().find { it.toolCallId == "auto" }!!

        // pendingTool: 被标记为 Denied
        assertTrue(finalPending.isExecuted)
        assertTrue(finalPending.approvalState is ToolApprovalState.Denied)

        // autoTool: 被标记为中断，但 approvalState 保持 Auto（不是 Denied）
        assertTrue(finalAuto.isExecuted)
        assertTrue(finalAuto.approvalState is ToolApprovalState.Auto)
    }
}
