package net.weero.measix.pilot.service.runtime


import kotlinx.datetime.LocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.ConversationModelContextApplicability
import net.weero.measix.pilot.data.model.ConversationModelContextEntry
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.testkit.sampledModelResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 支持的历史 model-context row 在编辑、variant、删除与截断后的保全及适用性。
 * 新 START 只创建请求 owner；新内容写入由 ConversationContextTransitionTest 保护。
 * 全部经由纯 Transition，不触碰 IO。
 */
class ConversationModelContextTransitionTest {

    private val turnFinishedAt = LocalDateTime(2026, 1, 2, 3, 4, 5)

    @Test
    fun `START adds owner without creating or replacing context entries`() {
        val base = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(userNode("first"))).toSnapshot()
        val first = Uuid.random()
        val command = TurnTransition.buildStartTurnCommand(base, Uuid.random(), assistantMessageId = first)
        val mutation = plan(base, command)
        assertTrue(mutation.insertedModelContextEntries.isEmpty())
        assertTrue(mutation.deletedModelContextEntries.isEmpty())
        assertTrue(ConversationTransition.apply(base, command).contextAdmissions.isEmpty())
        val history = finalize(withHistoricalTurn(base, first, stableCandidate(1)), first)
        val second = ConversationTransition.apply(history, AppendUserMessage(UIMessage.user("next")))
        val next = ConversationTransition.apply(second, TurnTransition.buildStartTurnCommand(second, Uuid.random()))
        assertEquals(history.modelContextEntries, next.modelContextEntries)
        assertTrue(next.contextAdmissions.isEmpty())
    }

    private fun userNode(text: String) = MessageNode.of(UIMessage.user(text))

    private fun plan(snapshot: ConversationAggregateSnapshot, command: ConversationCommand): ConversationMutation {
        val change = ConversationTransition.plan(snapshot, command, snapshot.header.updateAt)
        return ((change as ConversationChange.Durable).write as ConversationWrite.Mutate).mutation
    }

    private fun withHistoricalTurn(
        current: ConversationAggregateSnapshot,
        assistantMessageId: Uuid,
        candidate: String,
    ): ConversationAggregateSnapshot {
        val command = TurnTransition.buildStartTurnCommand(
            current = current,
            turnId = Uuid.random(),
            assistantMessageId = assistantMessageId,
        )
        val started = ConversationTransition.apply(current, command)
        // Supported legacy history fixture: modern START never creates this disclosure row.
        val historical = ConversationModelContextEntry(
            ownerNodeId = command.assistantNodeId,
            ownerMessageId = command.assistantMessageId,
            anchorNodeId = command.anchorNodeId,
            anchorMessageId = command.anchorMessageId,
            payload = disclosurePayload(candidate),
        )
        return started.copy(modelContextEntries = started.modelContextEntries + historical)
    }

    private fun finalize(snapshot: ConversationAggregateSnapshot, assistantMessageId: Uuid): ConversationAggregateSnapshot {
        val assistant = snapshot.currentMessages().single { it.id == assistantMessageId }
        val step = assistant.parts.filterIsInstance<UIMessagePart.Step>().last()
        val sampled = TurnTransition.recordModelResult(assistant, step.stepId, sampledModelResult("stop"))
        return ConversationTransition.apply(
            snapshot,
            FinalizeTurn(
                handle = TurnHandle(
                    conversationId = snapshot.conversationId,
                    epoch = 0,
                    turnId = Uuid.random(),
                    assistantMessageId = assistantMessageId,
                ),
                assistantMessage = sampled.copy(parts = sampled.parts + UIMessagePart.Text("answer")),
                terminalStatus = TurnExecutionStatus.COMPLETED,
                terminalReason = null,
                finishedAt = turnFinishedAt,
            ),
        )
    }

    @Test
    fun `pure USER edit without START keeps historical entries and does not insert`() {
        val candidate = stableCandidate(1)
        val userNode = userNode("u1")
        var snapshot = Conversation.ofId(Uuid.random())
            .copy(messageNodes = listOf(userNode)).toSnapshot()
        val original = Uuid.random()
        snapshot = finalize(withHistoricalTurn(snapshot, original, candidate), original)

        val mutation = plan(snapshot, EditMessageVariant(userNode.id, UIMessage.user("u1 edited")))
        assertTrue(mutation.insertedModelContextEntries.isEmpty())
        assertTrue(mutation.deletedModelContextEntries.isEmpty())
        val after = ConversationTransition.apply(
            snapshot,
            EditMessageVariant(userNode.id, UIMessage.user("u1 edited")),
        )
        assertEquals(snapshot.modelContextEntries, after.modelContextEntries)
    }

    @Test
    fun `assistant edit does not change model context rows`() {
        var snapshot = Conversation.ofId(Uuid.random())
            .copy(messageNodes = listOf(userNode("u1"))).toSnapshot()
        val assistantMessageId = Uuid.random()
        snapshot = finalize(withHistoricalTurn(snapshot, assistantMessageId, stableCandidate(1)), assistantMessageId)
        val assistantNode = snapshot.nodes.last()

        val mutation = plan(
            snapshot,
            EditMessageVariant(assistantNode.id, UIMessage.assistant("rewritten")),
        )
        assertTrue(mutation.insertedModelContextEntries.isEmpty())
        assertTrue(mutation.deletedModelContextEntries.isEmpty())
        val after = ConversationTransition.apply(
            snapshot,
            EditMessageVariant(assistantNode.id, UIMessage.assistant("rewritten")),
        )
        assertEquals(snapshot.modelContextEntries, after.modelContextEntries)
    }

    @Test
    fun `switching assistant variant keeps durable rows and re-anchors applicability`() {
        var snapshot = Conversation.ofId(Uuid.random())
            .copy(messageNodes = listOf(userNode("u1"))).toSnapshot()
        val firstAssistant = Uuid.random()
        snapshot = withHistoricalTurn(snapshot, firstAssistant, stableCandidate(1))
        val secondAssistant = Uuid.random()
        snapshot = withHistoricalTurn(snapshot, secondAssistant, stableCandidate(2))
        val assistantNode = snapshot.nodes.last()
        assertEquals(2, assistantNode.messages.size)
        assertEquals(2, snapshot.modelContextEntries.size)

        // 纯选择切换不写任何 context delta；两份 durable row 都保留。
        val selectFirst = SelectNodeVariant(assistantNode.id, 0)
        val mutation = plan(snapshot, selectFirst)
        assertTrue(
            mutation.insertedModelContextEntries.isEmpty() &&
                mutation.deletedModelContextEntries.isEmpty(),
        )
        val after = ConversationTransition.apply(snapshot, selectFirst)
        assertEquals(snapshot.modelContextEntries, after.modelContextEntries)
        // 切回旧 Assistant variant 后：它拥有的历史 baseline 重新适用，新 owner 的退出。
        val branch = after.nodes.map { it.currentMessage }
        val byOwner = after.modelContextEntries.associateBy { it.ownerMessageId }
        assertTrue(
            ConversationModelContextApplicability.applicable(
                byOwner.getValue(firstAssistant),
                branch,
            ),
        )
        assertTrue(
            !ConversationModelContextApplicability.applicable(
                byOwner.getValue(secondAssistant),
                branch,
            ),
        )
    }

    @Test
    fun `remapForClone keeps unselected owner baselines on the copied tree`() {
        val user = UIMessage.user("u1")
        val first = UIMessage.assistant("a1")
        val second = UIMessage.assistant("a2")
        val userNode = MessageNode.of(user)
        val assistantNode = MessageNode(messages = listOf(first, second), selectIndex = 1)
        val firstEntry = ConversationModelContextEntry(
            ownerNodeId = assistantNode.id,
            ownerMessageId = first.id,
            anchorNodeId = userNode.id,
            anchorMessageId = user.id,
            payload = disclosurePayload(stableCandidate(1)),
        )
        val secondEntry = firstEntry.copy(
            ownerMessageId = second.id,
            payload = disclosurePayload(stableCandidate(2)),
        )
        val nodeIdMap = mapOf(userNode.id to Uuid.random(), assistantNode.id to Uuid.random())
        val cloned = listOf(
            userNode.copy(id = nodeIdMap.getValue(userNode.id)),
            assistantNode.copy(id = nodeIdMap.getValue(assistantNode.id)),
        )

        val remapped = ConversationModelContextApplicability.remapForClone(
            entries = listOf(firstEntry, secondEntry),
            nodeIdMap = nodeIdMap,
            messageIdMap = emptyMap(),
            clonedNodes = cloned,
        )

        assertEquals(setOf(first.id, second.id), remapped.map { it.ownerMessageId }.toSet())
        val selectedBranch = cloned.map { it.currentMessage }
        val byOwner = remapped.associateBy { it.ownerMessageId }
        assertTrue(ConversationModelContextApplicability.applicable(byOwner.getValue(second.id), selectedBranch))
        assertTrue(!ConversationModelContextApplicability.applicable(byOwner.getValue(first.id), selectedBranch))
        val switched = cloned.map { node ->
            if (node.id == nodeIdMap.getValue(assistantNode.id)) node.copy(selectIndex = 0) else node
        }.map { it.currentMessage }
        assertTrue(ConversationModelContextApplicability.applicable(byOwner.getValue(first.id), switched))
    }

    @Test
    fun `deleting an anchor user variant prunes the entry that referenced it`() {
        val oldAnchor = UIMessage(id = Uuid.random(), role = MessageRole.USER, parts = listOf(UIMessagePart.Text("v1")))
        val newAnchor = UIMessage(id = Uuid.random(), role = MessageRole.USER, parts = listOf(UIMessagePart.Text("v2")))
        var snapshot = Conversation.ofId(Uuid.random())
            .copy(messageNodes = listOf(MessageNode(messages = listOf(oldAnchor, newAnchor), selectIndex = 1)))
            .toSnapshot()
        val assistantMessageId = Uuid.random()
        snapshot = withHistoricalTurn(snapshot, assistantMessageId, stableCandidate(1))
        // 因果 anchor 是 owner 之前最后一条 USER variant：v2。
        assertEquals(newAnchor.id, snapshot.modelContextEntries.single().anchorMessageId)

        // 删除仍被引用的 anchor variant → entry 以 anchor 消失收口。
        val mutation = plan(snapshot, DeleteMessage(newAnchor.id))
        assertEquals(listOf(assistantMessageId), mutation.deletedModelContextEntries.map { it.ownerMessageId })
        val after = ConversationTransition.apply(snapshot, DeleteMessage(newAnchor.id))
        assertTrue(after.modelContextEntries.isEmpty())
    }

    @Test
    fun `truncating the tree prunes entries and reports the owner ids for deletion`() {
        var snapshot = Conversation.ofId(Uuid.random())
            .copy(messageNodes = listOf(userNode("u1"))).toSnapshot()
        val firstAssistant = Uuid.random()
        snapshot = withHistoricalTurn(snapshot, firstAssistant, stableCandidate(1))
        snapshot = finalize(snapshot, firstAssistant)
        snapshot = ConversationTransition.apply(snapshot, AppendUserMessage(UIMessage.user("u2")))
        val secondAssistant = Uuid.random()
        snapshot = withHistoricalTurn(snapshot, secondAssistant, stableCandidate(2))

        // 截断到第一条 USER：两次 START 的 owner 与第二条 anchor 全部退出。
        val mutation = plan(snapshot, TruncateToNodeIndex(0))
        assertEquals(
            setOf(firstAssistant, secondAssistant),
            mutation.deletedModelContextEntries.map { it.ownerMessageId }.toSet(),
        )
        val after = ConversationTransition.apply(snapshot, TruncateToNodeIndex(0))
        assertTrue(after.modelContextEntries.isEmpty())
    }

    @Test
    fun `deleting the owner node prunes via existence and reports its entry for deletion`() {
        var snapshot = Conversation.ofId(Uuid.random())
            .copy(messageNodes = listOf(userNode("u1"))).toSnapshot()
        val assistantMessageId = Uuid.random()
        snapshot = withHistoricalTurn(snapshot, assistantMessageId, stableCandidate(1))
        snapshot = finalize(snapshot, assistantMessageId)

        val assistantNode = snapshot.nodes.last()
        val mutation = plan(snapshot, DeleteMessage(assistantNode.currentMessage.id))
        assertEquals(listOf(assistantMessageId), mutation.deletedModelContextEntries.map { it.ownerMessageId })
        assertEquals(listOf(assistantNode.id), mutation.deletedNodeIds)
    }

}
