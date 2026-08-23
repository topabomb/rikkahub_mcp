package net.weero.measix.pilot.service.runtime

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.MessageTerminalStatus
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * ConversationReducer 权威测试。
 * 核心：reducer 零 IO、纯函数、未被触及节点保持同一实例引用（structural sharing）。
 */
class ConversationReducerTest {

    private fun assistant(id: Uuid, parts: List<UIMessagePart> = listOf(UIMessagePart.Text("hi"))): UIMessage =
        UIMessage(id = id, role = MessageRole.ASSISTANT, parts = parts)

    private fun user(id: Uuid): UIMessage =
        UIMessage(id = id, role = MessageRole.USER, parts = listOf(UIMessagePart.Text("q")))

    // ---- BeginTurn 新槽追加 / resume 幂等 ----

    @Test
    fun `R1 empty conversation appends single assistant node`() {
        val c = Conversation.ofId(Uuid.random())
        val assistantId = Uuid.random()
        val r = ConversationReducer.reduce(c, BeginTurn(Uuid.random(), assistantId, null, resume = false, onStart = true))
        assertEquals(1, r.messageNodes.size)
        assertEquals(assistantId, r.messageNodes[0].messages.single().id)
        assertEquals(MessageRole.ASSISTANT, r.messageNodes[0].messages.single().role)
    }

    @Test
    fun `R1 resume on same assistant message is idempotent`() {
        val assistantId = Uuid.random()
        val node = MessageNode.of(assistant(assistantId))
        val c = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(node))
        val r = ConversationReducer.reduce(c, BeginTurn(Uuid.random(), assistantId, null, resume = true, onStart = true))
        // 幂等：不新增节点，节点数不变
        assertEquals(1, r.messageNodes.size)
        assertEquals(1, r.messageNodes[0].messages.size)
    }

    @Test
    fun `R1 non-resume with terminal assistant appends new node`() {
        val assistantId = Uuid.random()
        val finished = assistant(assistantId).copy(
            terminalStatus = MessageTerminalStatus.INCOMPLETE,
            terminalReason = "user_stop",
        )
        val c = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(MessageNode.of(finished)))
        val r = ConversationReducer.reduce(c, BeginTurn(Uuid.random(), assistantId, null, resume = false, onStart = true))
        // 旧节点已终态 → 追加新节点
        assertEquals(2, r.messageNodes.size)
        assertEquals(assistantId, r.messageNodes[1].messages.single().id)
    }

    // ---- R-2：structural sharing ----

    @Test
    fun `R2 untouched nodes keep identical references`() {
        val n0 = MessageNode.of(user(Uuid.random()))
        val n1 = MessageNode.of(assistant(Uuid.random()))
        val n2 = MessageNode.of(user(Uuid.random()))
        val c = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(n0, n1, n2))
        // 只更新 header（title），不触碰节点
        val r = ConversationReducer.reduce(c, UpdateHeader(title = "New Title"))
        assertEquals(3, r.messageNodes.size)
        assertSame(n0, r.messageNodes[0])
        assertSame(n1, r.messageNodes[1])
        assertSame(n2, r.messageNodes[2])
        assertEquals("New Title", r.title)
    }

    @Test
    fun `R2 structural sharing across node replacement`() {
        val n0 = MessageNode.of(user(Uuid.random()))
        val n1 = MessageNode.of(assistant(Uuid.random()))
        val c = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(n0, n1))
        val r = ConversationReducer.reduce(c, AppendUserMessage(user(Uuid.random())))
        // 原节点引用不变，仅末尾追加
        assertSame(n0, r.messageNodes[0])
        assertSame(n1, r.messageNodes[1])
        assertEquals(3, r.messageNodes.size)
    }

    // ---- R-3：FinalizeTurn 终态收口 ----

    @Test
    fun `R3 finalize marks assistant terminal for failure status`() {
        val assistantId = Uuid.random()
        val node = MessageNode.of(assistant(assistantId))
        val c = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(node))
        val r = ConversationReducer.reduce(
            c,
            FinalizeTurn(Uuid.random(), assistantId, null, TurnExecutionStatus.FAILED, "boom", closeInterruptedTools = false),
        )
        val msg = r.messageNodes[0].messages.single()
        assertEquals(MessageTerminalStatus.FAILED, msg.terminalStatus)
        assertEquals("boom", msg.terminalReason)
    }

    @Test
    fun `R3 finalize completed keeps terminal null`() {
        val assistantId = Uuid.random()
        val node = MessageNode.of(assistant(assistantId))
        val c = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(node))
        val r = ConversationReducer.reduce(
            c,
            FinalizeTurn(Uuid.random(), assistantId, null, TurnExecutionStatus.COMPLETED, null, closeInterruptedTools = false),
        )
        assertNull(r.messageNodes[0].messages.single().terminalStatus)
    }

    @Test
    fun `R3 finalize with closeInterruptedTools closes pending tools`() {
        val assistantId = Uuid.random()
        val tool = UIMessagePart.Tool(
            toolCallId = "t1",
            toolName = "shell",
            input = "{}",
            approvalState = ToolApprovalState.Pending,
        )
        val node = MessageNode.of(assistant(assistantId, listOf(UIMessagePart.Text("pre"), tool)))
        val c = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(node))
        val r = ConversationReducer.reduce(
            c,
            FinalizeTurn(Uuid.random(), assistantId, null, TurnExecutionStatus.INTERRUPTED, null, closeInterruptedTools = true),
        )
        val closedTool = r.messageNodes[0].messages.single().parts.filterIsInstance<UIMessagePart.Tool>().single()
        // interrupt 语义：写入 interrupted output（isExecuted=true），保留原 approvalState
        assertTrue(closedTool.isExecuted)
        val output = closedTool.output.filterIsInstance<UIMessagePart.Text>().single().text
        assertTrue(output.contains("interrupted"))
    }

    // ---- R-4：树操作 ----

    @Test
    fun `R4 deleteMessage removes the owning node`() {
        val target = user(Uuid.random())
        val c = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(
            MessageNode.of(target),
            MessageNode.of(assistant(Uuid.random())),
        ))
        val r = ConversationReducer.reduce(c, DeleteMessage(target.id))
        assertEquals(1, r.messageNodes.size)
    }

    @Test
    fun `R4 selectNodeVariant switches variant`() {
        val nodeId = Uuid.random()
        val v0 = user(Uuid.random())
        val v1 = user(Uuid.random())
        val node = MessageNode(id = nodeId, messages = listOf(v0, v1), selectIndex = 0)
        val c = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(node))
        val r = ConversationReducer.reduce(c, SelectNodeVariant(nodeId, 1))
        assertEquals(1, r.messageNodes[0].selectIndex)
        assertSame(v0, r.messageNodes[0].messages[0])
        assertSame(v1, r.messageNodes[0].messages[1])
    }

    @Test
    fun `R4 truncateToNodeIndex shrinks tree`() {
        val n0 = MessageNode.of(user(Uuid.random()))
        val n1 = MessageNode.of(assistant(Uuid.random()))
        val n2 = MessageNode.of(user(Uuid.random()))
        val c = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(n0, n1, n2))
        val r = ConversationReducer.reduce(c, TruncateToNodeIndex(1))
        assertEquals(2, r.messageNodes.size)
        assertSame(n0, r.messageNodes[0])
        assertSame(n1, r.messageNodes[1])
    }

    @Test
    fun `R4 updateToolApproval marks tool approval`() {
        val assistantId = Uuid.random()
        val tool = UIMessagePart.Tool(
            toolCallId = "t1",
            toolName = "shell",
            input = "{}",
            approvalState = ToolApprovalState.Pending,
        )
        val node = MessageNode.of(assistant(assistantId, listOf(tool)))
        val c = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(node))
        val r = ConversationReducer.reduce(c, UpdateToolApproval(assistantId, 0, ToolApprovalState.Denied("no")))
        val updated = r.messageNodes[0].messages.single().parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals(ToolApprovalState.Denied("no"), updated.approvalState)
    }
}
