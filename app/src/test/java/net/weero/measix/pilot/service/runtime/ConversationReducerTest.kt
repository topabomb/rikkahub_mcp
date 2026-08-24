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
    private fun handle(conversationId: Uuid, assistantMessageId: Uuid) = TurnHandle(
        conversationId = conversationId,
        epoch = 1,
        turnId = Uuid.random(),
        assistantMessageId = assistantMessageId,
    )

    private fun assistant(id: Uuid, parts: List<UIMessagePart> = listOf(UIMessagePart.Text("hi"))): UIMessage =
        UIMessage(id = id, role = MessageRole.ASSISTANT, parts = parts)

    private fun user(id: Uuid): UIMessage =
        UIMessage(id = id, role = MessageRole.USER, parts = listOf(UIMessagePart.Text("q")))

    // ---- BeginTurn 新槽追加 / resume 幂等 ----

    @Test
    fun `empty conversation appends single assistant node`() {
        val c = Conversation.ofId(Uuid.random())
        val assistantId = Uuid.random()
        val r = ConversationReducer.reduce(c.toSnapshot(), StartTurn(Uuid.random(), assistantId, resume = false))
        assertEquals(1, r.nodes.size)
        assertEquals(assistantId, r.nodes[0].messages.single().id)
        assertEquals(MessageRole.ASSISTANT, r.nodes[0].messages.single().role)
    }

    @Test
    fun `resume on same assistant message is idempotent`() {
        val assistantId = Uuid.random()
        val node = MessageNode.of(assistant(assistantId))
        val c = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(node))
        val r = ConversationReducer.reduce(c.toSnapshot(), StartTurn(Uuid.random(), assistantId, resume = true))
        // 幂等：不新增节点，节点数不变
        assertEquals(1, r.nodes.size)
        assertEquals(1, r.nodes[0].messages.size)
    }

    @Test
    fun `resume uses the selected assistant variant`() {
        val selectedId = Uuid.random()
        val unselectedId = Uuid.random()
        val node = MessageNode(
            id = Uuid.random(),
            messages = listOf(assistant(selectedId), assistant(unselectedId)),
            selectIndex = 0,
        )
        val conversation = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(node))

        val reduced = ConversationReducer.reduce(
            conversation.toSnapshot(),
            StartTurn(Uuid.random(), selectedId, resume = true),
        )

        assertSame(node, reduced.nodes.single())
        assertEquals(0, reduced.nodes.single().selectIndex)
    }

    @Test
    fun `non-resume with terminal assistant appends new node`() {
        val assistantId = Uuid.random()
        val finished = assistant(assistantId).copy(
            terminalStatus = MessageTerminalStatus.INCOMPLETE,
            terminalReason = "user_stop",
        )
        val c = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(MessageNode.of(finished)))
        val r = ConversationReducer.reduce(c.toSnapshot(), StartTurn(Uuid.random(), assistantId, resume = false))
        // 旧节点已终态 → 追加新节点
        assertEquals(2, r.nodes.size)
        assertEquals(assistantId, r.nodes[1].messages.single().id)
    }

    @Test
    fun `pin toggle does not invalidate search metadata`() {
        val old = Conversation.ofId(Uuid.random()).toSnapshot().header
        val updated = ConversationReducer.reduceHeader(old, TogglePinned)
        val mutation = ConversationMutationBuilder.buildHeader(old, updated)

        assertTrue(updated.isPinned)
        assertEquals(false, mutation.searchMetadataChanged)
        assertEquals(true, mutation.headerPatch?.isPinned)
    }

    // ---- Structural sharing ----

    @Test
    fun `untouched nodes keep identical references`() {
        val n0 = MessageNode.of(user(Uuid.random()))
        val n1 = MessageNode.of(assistant(Uuid.random()))
        val n2 = MessageNode.of(user(Uuid.random()))
        val c = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(n0, n1, n2))
        // 只更新 header（title），不触碰节点
        val r = ConversationReducer.reduce(c.toSnapshot(), UpdateHeader(title = "New Title"))
        assertEquals(3, r.nodes.size)
        assertSame(n0, r.nodes[0])
        assertSame(n1, r.nodes[1])
        assertSame(n2, r.nodes[2])
        assertEquals("New Title", r.header.title)
    }

    @Test
    fun `delayed title and suggestion patches preserve completed tool output`() {
        val completedTool = UIMessagePart.Tool(
            toolCallId = "tool-1",
            toolName = "generate_image",
            input = "{}",
            output = listOf(UIMessagePart.Image("file:///result.png")),
        )
        val node = MessageNode.of(assistant(Uuid.random(), listOf(completedTool)))
        val snapshot = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(node)).toSnapshot()

        val updated = ConversationReducer.reduce(
            snapshot,
            UpdateHeader(title = "Generated title", suggestions = listOf("Next")),
        )

        assertSame(node, updated.nodes.single())
        assertEquals(completedTool, updated.nodes.single().currentMessage.parts.single())
        assertEquals("Generated title", updated.header.title)
        assertEquals(listOf("Next"), updated.header.chatSuggestions)
    }

    @Test
    fun `structural sharing across node replacement`() {
        val n0 = MessageNode.of(user(Uuid.random()))
        val n1 = MessageNode.of(assistant(Uuid.random()))
        val c = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(n0, n1))
        val r = ConversationReducer.reduce(c.toSnapshot(), AppendUserMessage(user(Uuid.random())))
        // 原节点引用不变，仅末尾追加
        assertSame(n0, r.nodes[0])
        assertSame(n1, r.nodes[1])
        assertEquals(3, r.nodes.size)
    }

    // ---- FinalizeTurn terminalization ----

    @Test
    fun `finalize marks assistant terminal for failure status`() {
        val assistantId = Uuid.random()
        val node = MessageNode.of(assistant(assistantId))
        val c = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(node))
        val r = ConversationReducer.reduce(
            c.toSnapshot(),
            FinalizeTurn(handle(c.id, assistantId), null, TurnExecutionStatus.FAILED, "boom", closeInterruptedTools = false),
        )
        val msg = r.nodes[0].messages.single()
        assertEquals(MessageTerminalStatus.FAILED, msg.terminalStatus)
        assertEquals("boom", msg.terminalReason)
    }

    @Test
    fun `finalize completed keeps terminal null`() {
        val assistantId = Uuid.random()
        val node = MessageNode.of(assistant(assistantId))
        val c = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(node))
        val r = ConversationReducer.reduce(
            c.toSnapshot(),
            FinalizeTurn(handle(c.id, assistantId), null, TurnExecutionStatus.COMPLETED, null, closeInterruptedTools = false),
        )
        assertNull(r.nodes[0].messages.single().terminalStatus)
    }

    @Test
    fun `regeneration failure before a chunk keeps the previous assistant variant`() {
        val conversationId = Uuid.random()
        val original = assistant(Uuid.random(), listOf(UIMessagePart.Text("completed answer")))
        val node = MessageNode.of(original)
        val base = Conversation.ofId(conversationId).copy(messageNodes = listOf(node)).toSnapshot()
        val replacementId = Uuid.random()
        val started = ConversationReducer.reduce(base, StartTurn(Uuid.random(), replacementId, resume = false))

        val failed = ConversationReducer.reduce(
            started,
            FinalizeTurn(
                handle(conversationId, replacementId),
                messages = null,
                terminalStatus = TurnExecutionStatus.FAILED,
                terminalReason = "provider_failed",
                closeInterruptedTools = false,
            ),
        )

        assertEquals(2, failed.nodes.single().messages.size)
        assertEquals(original, failed.nodes.single().messages.first())
        assertEquals(replacementId, failed.nodes.single().currentMessage.id)
        assertEquals(MessageTerminalStatus.FAILED, failed.nodes.single().currentMessage.terminalStatus)
    }

    @Test
    fun `finalize with closeInterruptedTools closes pending tools`() {
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
            c.toSnapshot(),
            FinalizeTurn(handle(c.id, assistantId), null, TurnExecutionStatus.INTERRUPTED, null, closeInterruptedTools = true),
        )
        val closedTool = r.nodes[0].messages.single().parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals(MessageTerminalStatus.INTERRUPTED, r.nodes[0].messages.single().terminalStatus)
        // interrupt 语义：写入 interrupted output（isExecuted=true），保留原 approvalState
        assertTrue(closedTool.isExecuted)
        val output = closedTool.output.filterIsInstance<UIMessagePart.Text>().single().text
        assertTrue(output.contains("interrupted"))
    }

    @Test
    fun `historical nodes stay identical across FinalizeTurn`() {
        val finishedReasoning = UIMessagePart.Reasoning(
            reasoning = "already done",
            finishedAt = kotlin.time.Instant.fromEpochMilliseconds(1),
        )
        val histUser = MessageNode.of(user(Uuid.random()))
        val histAssistant = MessageNode.of(
            assistant(Uuid.random(), listOf(finishedReasoning, UIMessagePart.Text("old"))),
        )
        val activeId = Uuid.random()
        val active = MessageNode.of(
            assistant(
                activeId,
                listOf(
                    UIMessagePart.Reasoning(reasoning = "live", finishedAt = null),
                    UIMessagePart.Text("new"),
                ),
            ),
        )
        val c = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(histUser, histAssistant, active))
        val r = ConversationReducer.reduce(
            c.toSnapshot(),
            FinalizeTurn(handle(c.id, activeId), null, TurnExecutionStatus.COMPLETED, null, closeInterruptedTools = false),
        )
        assertSame(histUser, r.nodes[0])
        assertSame(histAssistant, r.nodes[1])
        assertNotSame(active, r.nodes[2])
        val liveReasoning = r.nodes[2].messages.single().parts.filterIsInstance<UIMessagePart.Reasoning>().single()
        assertTrue(liveReasoning.finishedAt != null)
    }

    // ---- Tree operations ----

    @Test
    fun `deleteMessage removes the owning node`() {
        val target = user(Uuid.random())
        val c = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(
            MessageNode.of(target),
            MessageNode.of(assistant(Uuid.random())),
        ))
        val r = ConversationReducer.reduce(c.toSnapshot(), DeleteMessage(target.id))
        assertEquals(1, r.nodes.size)
    }

    @Test
    fun `selectNodeVariant switches variant`() {
        val nodeId = Uuid.random()
        val v0 = user(Uuid.random())
        val v1 = user(Uuid.random())
        val node = MessageNode(id = nodeId, messages = listOf(v0, v1), selectIndex = 0)
        val c = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(node))
        val r = ConversationReducer.reduce(c.toSnapshot(), SelectNodeVariant(nodeId, 1))
        assertEquals(1, r.nodes[0].selectIndex)
        assertSame(v0, r.nodes[0].messages[0])
        assertSame(v1, r.nodes[0].messages[1])
    }

    @Test
    fun `truncateToNodeIndex shrinks tree`() {
        val n0 = MessageNode.of(user(Uuid.random()))
        val n1 = MessageNode.of(assistant(Uuid.random()))
        val n2 = MessageNode.of(user(Uuid.random()))
        val c = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(n0, n1, n2))
        val r = ConversationReducer.reduce(c.toSnapshot(), TruncateToNodeIndex(1))
        assertEquals(2, r.nodes.size)
        assertSame(n0, r.nodes[0])
        assertSame(n1, r.nodes[1])
    }

    @Test
    fun `orphaned execution recovery refuses to close a turn whose assistant message still exists`() {
        val conversationId = Uuid.random()
        val assistantId = Uuid.random()
        val snapshot = Conversation.ofId(conversationId).copy(
            messageNodes = listOf(MessageNode.of(assistant(assistantId))),
        ).toSnapshot()

        val failure = runCatching {
            ConversationReducer.reduce(
                snapshot,
                ReconcileOrphanedTurnExecution(
                    turnId = Uuid.random(),
                    assistantMessageId = assistantId,
                    terminalReason = "owner_message_missing",
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `updateToolApproval marks tool approval`() {
        val assistantId = Uuid.random()
        val tool = UIMessagePart.Tool(
            toolCallId = "t1",
            toolName = "shell",
            input = "{}",
            approvalState = ToolApprovalState.Pending,
        )
        val node = MessageNode.of(assistant(assistantId, listOf(tool)))
        val c = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(node))
        val r = ConversationReducer.reduce(c.toSnapshot(), UpdateToolApproval(assistantId, 0, ToolApprovalState.Denied("no")))
        val updated = r.nodes[0].messages.single().parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals(ToolApprovalState.Denied("no"), updated.approvalState)
    }
}
