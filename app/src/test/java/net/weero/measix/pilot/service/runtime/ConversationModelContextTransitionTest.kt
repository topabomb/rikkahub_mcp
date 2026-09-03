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
import net.weero.measix.pilot.service.ConversationDisclosureSnapshotService
import net.weero.measix.pilot.service.DisclosureContentException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * model-context entry 的原子命令语义验收（权威方案 §17.3、§17.5、§7.5、§14.2）：
 * insert-once、baseline 判等、regenerate 排除旧 owner、variant 删除与截断收口。
 * 全部经由纯 Transition，不触碰 IO。
 */
class ConversationModelContextTransitionTest {

    private val turnFinishedAt = LocalDateTime(2026, 1, 2, 3, 4, 5)

    private fun userNode(text: String) = MessageNode.of(UIMessage.user(text))

    private fun plan(snapshot: ConversationAggregateSnapshot, command: ConversationCommand): ConversationMutation {
        val change = ConversationTransition.plan(snapshot, command, snapshot.header.updateAt)
        return ((change as ConversationChange.Durable).write as ConversationWrite.Mutate).mutation
    }

    private fun startAt(
        current: ConversationAggregateSnapshot,
        assistantMessageId: Uuid,
        candidate: String,
    ): ConversationAggregateSnapshot {
        val command = ConversationTransition.buildStartTurnCommand(
            current = current,
            turnId = Uuid.random(),
            modelContextCandidate = candidate,
            assistantMessageId = assistantMessageId,
        )
        return ConversationTransition.apply(current, command)
    }

    private fun finalize(snapshot: ConversationAggregateSnapshot, assistantMessageId: Uuid): ConversationAggregateSnapshot {
        val active = requireNotNull(snapshot.activeTurn) { "no active turn to finalize" }
        return ConversationTransition.apply(
            snapshot,
            FinalizeTurn(
                handle = TurnHandle(
                    conversationId = snapshot.conversationId,
                    epoch = active.epoch,
                    turnId = active.turnId,
                    assistantMessageId = assistantMessageId,
                ),
                messages = null,
                terminalStatus = TurnExecutionStatus.COMPLETED,
                terminalReason = null,
                closeInterruptedTools = false,
                finishedAt = turnFinishedAt,
            ),
        )
    }

    @Test
    fun `first START inserts the candidate and the entry is anchored to its causal USER`() {
        val user = userNode("first")
        val base = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(user)).toSnapshot()
        val candidate = stableCandidate(1)
        val assistantId = Uuid.random()

        val mutation = plan(base, ConversationTransition.buildStartTurnCommand(
            current = base,
            turnId = Uuid.random(),
            modelContextCandidate = candidate,
            assistantMessageId = assistantId,
        ))

        val inserted = mutation.insertedModelContextEntries.single()
        assertEquals(assistantId, inserted.ownerMessageId)
        assertEquals(user.currentMessage.id, inserted.anchorMessageId)
        assertEquals(candidate, inserted.content)
        assertTrue(mutation.deletedModelContextEntries.isEmpty())
    }

    @Test
    fun `unchanged candidate on the next START does not append a row`() {
        val candidate = stableCandidate(1)
        var snapshot = Conversation.ofId(Uuid.random())
            .copy(messageNodes = listOf(userNode("u1"))).toSnapshot()
        val firstAssistant = Uuid.random()
        snapshot = startAt(snapshot, firstAssistant, candidate)
        snapshot = finalize(snapshot, firstAssistant)

        snapshot = ConversationTransition.apply(snapshot, AppendUserMessage(UIMessage.user("u2")))
        val command = ConversationTransition.buildStartTurnCommand(
            current = snapshot,
            turnId = Uuid.random(),
            modelContextCandidate = candidate,
            assistantMessageId = Uuid.random(),
        )
        val mutation = plan(snapshot, command)
        val after = ConversationTransition.apply(snapshot, command)

        assertTrue("identical wire content must not append", mutation.insertedModelContextEntries.isEmpty())
        assertEquals(1, after.modelContextEntries.size)
    }

    @Test
    fun `changed candidate appends a complete baseline owned by the new assistant variant`() {
        var snapshot = Conversation.ofId(Uuid.random())
            .copy(messageNodes = listOf(userNode("u1"))).toSnapshot()
        val firstAssistant = Uuid.random()
        snapshot = startAt(snapshot, firstAssistant, stableCandidate(1))
        snapshot = finalize(snapshot, firstAssistant)

        snapshot = ConversationTransition.apply(snapshot, AppendUserMessage(UIMessage.user("u2")))
        val secondAssistant = Uuid.random()
        val changed = stableCandidate(2)
        snapshot = startAt(snapshot, secondAssistant, changed)

        assertEquals(2, snapshot.modelContextEntries.size)
        val latest = snapshot.modelContextEntries.last()
        assertEquals(secondAssistant, latest.ownerMessageId)
        assertEquals(changed, latest.content)
    }

    @Test
    fun `regenerate on an open assistant excludes the replaced owner so identical content is re-appended`() {
        val candidate = stableCandidate(1)
        var snapshot = Conversation.ofId(Uuid.random())
            .copy(messageNodes = listOf(userNode("u1"))).toSnapshot()
        val original = Uuid.random()
        snapshot = startAt(snapshot, original, candidate)
        assertEquals(1, snapshot.modelContextEntries.size)

        // 未收口的旧 Assistant variant 正是 baseline owner；regenerate 不复制 USER，
        // 旧 owner 先退出目标分支，相同 live content 相对更早 baseline 判定为变化，
        // 由新 owner 重新落一条，不会丢基线（§7.5）。
        val replacement = Uuid.random()
        val command = ConversationTransition.buildStartTurnCommand(
            current = snapshot,
            turnId = Uuid.random(),
            modelContextCandidate = candidate,
            assistantMessageId = replacement,
        )
        assertEquals(
            listOf(snapshot.nodes.first().currentMessage.id),
            command.expectedSelectedPrefixMessageIds,
        )

        snapshot = ConversationTransition.apply(snapshot, command)
        assertEquals(2, snapshot.modelContextEntries.size)
        assertEquals(replacement, snapshot.modelContextEntries.last().ownerMessageId)
        assertEquals(candidate, snapshot.modelContextEntries.last().content)
    }

    @Test
    fun `edit USER then START re-anchors the baseline on the target branch to the edited variant`() {
        val candidate = stableCandidate(1)
        val userNode = userNode("u1")
        var snapshot = Conversation.ofId(Uuid.random())
            .copy(messageNodes = listOf(userNode)).toSnapshot()
        val original = Uuid.random()
        snapshot = startAt(snapshot, original, candidate)
        snapshot = finalize(snapshot, original)

        // 编辑后发送：结构命令先提交新 USER variant，再在变换后的目标分支上计算 baseline。
        val editedUser = UIMessage.user("u1 edited")
        snapshot = ConversationTransition.apply(snapshot, EditMessageVariant(userNode.id, editedUser))
        val replacement = Uuid.random()
        val command = ConversationTransition.buildStartTurnCommand(
            current = snapshot,
            turnId = Uuid.random(),
            modelContextCandidate = candidate,
            assistantMessageId = replacement,
        )
        assertEquals(editedUser.id, command.anchorMessageId)

        snapshot = ConversationTransition.apply(snapshot, command)
        // 旧 entry（anchor=被替换前的 USER variant）不再适用于目标分支：相同 live content
        // 也必须由新 owner 重新落一条完整 baseline，绝不丢基线（§7.5）。
        assertEquals(2, snapshot.modelContextEntries.size)
        val latest = snapshot.modelContextEntries.last()
        assertEquals(replacement, latest.ownerMessageId)
        assertEquals(editedUser.id, latest.anchorMessageId)
        assertEquals(candidate, latest.content)
    }

    @Test
    fun `edit-and-resend truncates later history then re-anchors START on the new USER variant`() {
        var snapshot = Conversation.ofId(Uuid.random())
            .copy(messageNodes = listOf(userNode("u1"))).toSnapshot()
        val firstAssistant = Uuid.random()
        val firstCandidate = stableCandidate(1)
        snapshot = finalize(startAt(snapshot, firstAssistant, firstCandidate), firstAssistant)
        snapshot = ConversationTransition.apply(snapshot, AppendUserMessage(UIMessage.user("u2")))
        val laterAssistant = Uuid.random()
        snapshot = finalize(startAt(snapshot, laterAssistant, stableCandidate(2)), laterAssistant)

        val firstUserNode = snapshot.nodes.first()
        val truncated = ConversationTransition.apply(snapshot, TruncateToNodeIndex(0))
        assertTrue(truncated.modelContextEntries.isEmpty())
        assertEquals(1, truncated.nodes.size)

        val editedUser = UIMessage.user("u1 edited")
        val edited = ConversationTransition.apply(
            truncated,
            EditMessageVariant(firstUserNode.id, editedUser),
        )
        assertTrue(
            "pure edit after truncate must not invent a context row",
            edited.modelContextEntries.isEmpty(),
        )

        val replacement = Uuid.random()
        val regeneratedCandidate = stableCandidate(3)
        val started = startAt(edited, replacement, regeneratedCandidate)
        val inserted = started.modelContextEntries.single()
        assertEquals(replacement, inserted.ownerMessageId)
        assertEquals(editedUser.id, inserted.anchorMessageId)
        assertEquals(regeneratedCandidate, inserted.content)
        assertEquals(firstUserNode.id, started.nodes.first().id)
        assertEquals(editedUser.id, started.nodes.first().currentMessage.id)
    }

    @Test
    fun `pure USER edit without START keeps historical entries and does not insert`() {
        val candidate = stableCandidate(1)
        val userNode = userNode("u1")
        var snapshot = Conversation.ofId(Uuid.random())
            .copy(messageNodes = listOf(userNode)).toSnapshot()
        val original = Uuid.random()
        snapshot = finalize(startAt(snapshot, original, candidate), original)

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
        val assistantId = Uuid.random()
        snapshot = finalize(startAt(snapshot, assistantId, stableCandidate(1)), assistantId)
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
        snapshot = startAt(snapshot, firstAssistant, stableCandidate(1))
        val secondAssistant = Uuid.random()
        snapshot = startAt(snapshot, secondAssistant, stableCandidate(2))
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
            content = stableCandidate(1),
        )
        val secondEntry = firstEntry.copy(
            ownerMessageId = second.id,
            content = stableCandidate(2),
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
        val assistantId = Uuid.random()
        snapshot = startAt(snapshot, assistantId, stableCandidate(1))
        // 因果 anchor 是 owner 之前最后一条 USER variant：v2。
        assertEquals(newAnchor.id, snapshot.modelContextEntries.single().anchorMessageId)

        // 删除仍被引用的 anchor variant → entry 以 anchor 消失收口。
        val mutation = plan(snapshot, DeleteMessage(newAnchor.id))
        assertEquals(listOf(assistantId), mutation.deletedModelContextEntries.map { it.ownerMessageId })
        val after = ConversationTransition.apply(snapshot, DeleteMessage(newAnchor.id))
        assertTrue(after.modelContextEntries.isEmpty())
    }

    @Test
    fun `truncating the tree prunes entries and reports the owner ids for deletion`() {
        var snapshot = Conversation.ofId(Uuid.random())
            .copy(messageNodes = listOf(userNode("u1"))).toSnapshot()
        val firstAssistant = Uuid.random()
        snapshot = startAt(snapshot, firstAssistant, stableCandidate(1))
        snapshot = finalize(snapshot, firstAssistant)
        snapshot = ConversationTransition.apply(snapshot, AppendUserMessage(UIMessage.user("u2")))
        val secondAssistant = Uuid.random()
        snapshot = startAt(snapshot, secondAssistant, stableCandidate(2))

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
    fun `historical Assistant regenerate truncates later history before planning its new owner`() {
        var snapshot = Conversation.ofId(Uuid.random())
            .copy(messageNodes = listOf(userNode("u1"))).toSnapshot()
        val firstAssistant = Uuid.random()
        val firstCandidate = stableCandidate(1)
        snapshot = finalize(startAt(snapshot, firstAssistant, firstCandidate), firstAssistant)
        snapshot = ConversationTransition.apply(snapshot, AppendUserMessage(UIMessage.user("u2")))
        val secondAssistant = Uuid.random()
        snapshot = finalize(startAt(snapshot, secondAssistant, stableCandidate(2)), secondAssistant)

        val historicalAssistantNode = snapshot.nodes[1]
        val truncated = ConversationTransition.apply(
            snapshot,
            TruncateToNodeIndex(nodeIndexInclusive = 1),
        )
        val regeneratedOwner = Uuid.random()
        val regeneratedCandidate = stableCandidate(3)
        val regenerated = startAt(truncated, regeneratedOwner, regeneratedCandidate)

        assertEquals(2, regenerated.nodes.size)
        assertEquals(2, regenerated.nodes[1].messages.size)
        assertEquals(historicalAssistantNode.id, regenerated.nodes[1].id)
        assertEquals(regeneratedOwner, regenerated.nodes[1].currentMessage.id)
        val regeneratedEntry = regenerated.modelContextEntries.single { it.ownerMessageId == regeneratedOwner }
        assertEquals(snapshot.nodes[0].id, regeneratedEntry.anchorNodeId)
        assertEquals(snapshot.nodes[0].currentMessage.id, regeneratedEntry.anchorMessageId)
        assertEquals(listOf(firstCandidate, regeneratedCandidate), regenerated.modelContextEntries.map { it.content })
    }

    @Test
    fun `deleting the owner node prunes via existence and reports its entry for deletion`() {
        var snapshot = Conversation.ofId(Uuid.random())
            .copy(messageNodes = listOf(userNode("u1"))).toSnapshot()
        val assistantId = Uuid.random()
        snapshot = startAt(snapshot, assistantId, stableCandidate(1))
        snapshot = finalize(snapshot, assistantId)

        val assistantNode = snapshot.nodes.last()
        val mutation = plan(snapshot, DeleteMessage(assistantNode.currentMessage.id))
        assertEquals(listOf(assistantId), mutation.deletedModelContextEntries.map { it.ownerMessageId })
        assertEquals(listOf(assistantNode.id), mutation.deletedNodeIds)
    }

    @Test
    fun `a malformed candidate cannot enter the durable command`() {
        val snapshot = Conversation.ofId(Uuid.random())
            .copy(messageNodes = listOf(userNode("u1"))).toSnapshot()
        val command = ConversationTransition.buildStartTurnCommand(
            current = snapshot,
            turnId = Uuid.random(),
            modelContextCandidate = "{\"type\":\"user_request\"}",
        )
        assertThrows(DisclosureContentException::class.java) {
            ConversationTransition.apply(snapshot, command)
        }
    }

    @Test
    fun `start turn carries the canonical candidate verbatim into the inserted entry`() {
        val snapshot = Conversation.ofId(Uuid.random())
            .copy(messageNodes = listOf(userNode("u1"))).toSnapshot()
        val candidate = stableCandidate(7)
        val after = startAt(snapshot, Uuid.random(), candidate)
        assertEquals(candidate, after.modelContextEntries.single().content)
        assertEquals(1, ConversationDisclosureSnapshotService.requireCanonical(after.modelContextEntries.single().content))
    }
}
