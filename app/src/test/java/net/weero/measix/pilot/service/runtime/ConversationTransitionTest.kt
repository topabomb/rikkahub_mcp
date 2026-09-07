package net.weero.measix.pilot.service.runtime

import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * ConversationTransition 的 header 与 tree 结构权威测试：header mutation、节点增删改与
 * variant 切换、structural sharing、标题 CAS。
 * 核心：reducer 零 IO、纯函数、未被触及节点保持同一实例引用（structural sharing）。
 */
internal class ConversationTransitionTest : ConversationTransitionTestBase() {
    @Test
    fun `pin toggle does not invalidate search metadata`() {
        val old = Conversation.ofId(Uuid.random()).toSnapshot().header
        val updated = ConversationTransition.applyHeader(old, TogglePinned)
        val mutation = (ConversationTransition.planHeader(old, TogglePinned, old.updateAt).write).mutation

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
        val r = ConversationTransition.apply(c.toSnapshot(), UpdateHeader(title = "New Title"))
        assertEquals(3, r.nodes.size)
        assertSame(n0, r.nodes[0])
        assertSame(n1, r.nodes[1])
        assertSame(n2, r.nodes[2])
        assertEquals("New Title", r.header.title)
    }

    @Test
    fun `structural sharing across node replacement`() {
        val n0 = MessageNode.of(user(Uuid.random()))
        val n1 = MessageNode.of(assistant(Uuid.random()))
        val c = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(n0, n1))
        val r = ConversationTransition.apply(c.toSnapshot(), AppendUserMessage(user(Uuid.random())))
        // 原节点引用不变，仅末尾追加
        assertSame(n0, r.nodes[0])
        assertSame(n1, r.nodes[1])
        assertEquals(3, r.nodes.size)
    }

    @Test
    fun `first user append commits local title in the same mutation`() {
        val snapshot = Conversation.ofId(Uuid.random()).toSnapshot()
        val command = AppendUserMessage(user(Uuid.random()), initialTitle = "Immediate local title")
        val result = ConversationTransition.apply(snapshot, command)

        assertEquals("Immediate local title", result.header.title)
        assertEquals(1, result.nodes.size)
        val mutate = (
            ConversationTransition.plan(snapshot, command, snapshot.header.updateAt) as ConversationChange.Durable
            ).write as ConversationWrite.Mutate
        assertEquals("Immediate local title", mutate.mutation.headerPatch?.title)
        assertEquals(1, mutate.mutation.upsertedNodes.size)
    }

    @Test
    fun `later append cannot replace an established title`() {
        val snapshot = Conversation.ofId(Uuid.random())
            .copy(title = "Established title")
            .toSnapshot()
        val result = ConversationTransition.apply(
            snapshot,
            AppendUserMessage(user(Uuid.random()), initialTitle = "Another local title"),
        )

        assertEquals("Established title", result.header.title)
    }

    @Test
    fun `model title CAS cannot overwrite a title changed after generation began`() {
        val local = Conversation.ofId(Uuid.random()).copy(title = "Local").toSnapshot()
        val accepted = ConversationTransition.apply(local, UpdateTitleIfCurrent("Local", "Model"))
        val manual = ConversationTransition.apply(local, UpdateHeader(title = "Manual"))
        val rejected = ConversationTransition.apply(manual, UpdateTitleIfCurrent("Local", "Model"))

        assertEquals("Model", accepted.header.title)
        assertEquals("Manual", rejected.header.title)
        assertSame(manual, rejected)
    }

    // ---- Tree operations ----

    @Test
    fun `deleteMessage removes the owning node`() {
        val target = user(Uuid.random())
        val c = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(
            MessageNode.of(target),
            MessageNode.of(assistant(Uuid.random())),
        ))
        val r = ConversationTransition.apply(c.toSnapshot(), DeleteMessage(target.id))
        assertEquals(1, r.nodes.size)
    }

    @Test
    fun `selectNodeVariant switches variant`() {
        val nodeId = Uuid.random()
        val v0 = user(Uuid.random())
        val v1 = user(Uuid.random())
        val node = MessageNode(id = nodeId, messages = listOf(v0, v1), selectIndex = 0)
        val c = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(node))
        val r = ConversationTransition.apply(c.toSnapshot(), SelectNodeVariant(nodeId, 1))
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
        val r = ConversationTransition.apply(c.toSnapshot(), TruncateToNodeIndex(1))
        assertEquals(2, r.nodes.size)
        assertSame(n0, r.nodes[0])
        assertSame(n1, r.nodes[1])
    }
}
