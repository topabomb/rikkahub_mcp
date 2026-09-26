package net.weero.measix.pilot.data.model

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import java.util.UUID
import kotlin.uuid.Uuid

/**
 * Immutable application content and its original source, owned by a durable message variant.
 * The causal USER anchor identifies the creating turn; each request admission records the actual
 * placement separately. Preset/summary attribution is owned and anchored by its own message.
 * Message variants and Steps are transcript facts, so logical relationships are validated on load.
 */
internal data class ConversationModelContextEntry(
    val ownerNodeId: Uuid,
    val ownerMessageId: Uuid,
    val anchorNodeId: Uuid,
    val anchorMessageId: Uuid,
    val payload: ConversationContextPayload,
    val occurrence: Int = 0,
    val stepId: Uuid? = null,
    val id: Uuid = contextEntryIdentity(ownerNodeId, ownerMessageId, occurrence),
) {
    init { require(occurrence >= 0) { "invalid_context_occurrence" } }
}

/** Identity depends on the owning occurrence, not mutable text or a process-local UUID allocation. */
internal fun contextEntryIdentity(nodeId: Uuid, messageId: Uuid, occurrence: Int): Uuid =
    Uuid.parse(UUID.nameUUIDFromBytes("conversation-context/$nodeId/$messageId/$occurrence".encodeToByteArray()).toString())

/**
 * 模型上下文条目的唯一适用谓词。
 *
 * 请求组装与历史来源查询共用 selected branch 判定：
 *  - owner Assistant variant 在给定 selected branch 上（active owner 由调用方作为分支末条
 *    传入，因此同样被覆盖）；
 *  - anchor USER variant 也在同一 selected branch，且结构位置早于 owner；
 *  - anchor 是 owner 之前最后一条 USER；预置/摘要来源则锚定自身。
 *
 * 任一条件不成立都明确不适用；不基于 role 或列表位置做 fallback 推断。
 */
internal object ConversationModelContextApplicability {

    /** [branchMessages] 是 selected 分支的有序消息序列（含 active Assistant 末条）。 */
    fun applicable(entry: ConversationModelContextEntry, branchMessages: List<UIMessage>): Boolean {
        val ownerIndex = branchMessages.indexOfFirst { it.id == entry.ownerMessageId }
        val owner = branchMessages.getOrNull(ownerIndex)
        val previousUser = if (ownerIndex < 0) null else
            branchMessages.subList(0, ownerIndex).lastOrNull { it.role == MessageRole.USER }?.id
        return applicable(entry, owner, previousUser)
    }

    /** One selected-branch pass supports many entries without changing the applicability predicate. */
    fun index(branchMessages: List<UIMessage>): SelectedBranchIndex = SelectedBranchIndex(branchMessages)

    class SelectedBranchIndex internal constructor(branchMessages: List<UIMessage>) {
        private val messages = LinkedHashMap<Uuid, UIMessage>(branchMessages.size)
        private val previousUsers = HashMap<Uuid, Uuid>(branchMessages.size)
        val selectedMessageIds: Set<Uuid> get() = messages.keys
        init {
            var previousUser: Uuid? = null
            branchMessages.forEach { message ->
                messages[message.id] = message
                previousUser?.let { previousUsers[message.id] = it }
                if (message.role == MessageRole.USER) previousUser = message.id
            }
        }
        fun previousUser(messageId: Uuid): Uuid? = previousUsers[messageId]
        fun applicable(entry: ConversationModelContextEntry): Boolean =
            ConversationModelContextApplicability.applicable(entry, messages[entry.ownerMessageId], previousUsers[entry.ownerMessageId])
    }

    private fun applicable(entry: ConversationModelContextEntry, owner: UIMessage?, previousUser: Uuid?): Boolean =
        if (entry.payload.source is ConversationContextSource.Preset || entry.payload.source is ConversationContextSource.HistorySummary) {
            owner != null && entry.ownerMessageId == entry.anchorMessageId
        } else owner?.role == MessageRole.ASSISTANT && previousUser == entry.anchorMessageId

    /** 分支上仍存在的条目（不要求 selected）：node/variant 存在性收口，供 Transition 剪枝。 */
    fun stillExists(
        entry: ConversationModelContextEntry,
        nodes: List<MessageNode>,
    ): Boolean {
        val ownerNode = nodes.firstOrNull { it.id == entry.ownerNodeId } ?: return false
        val anchorNode = nodes.firstOrNull { it.id == entry.anchorNodeId } ?: return false
        return ownerNode.messages.any { it.id == entry.ownerMessageId } &&
            anchorNode.messages.any { it.id == entry.anchorMessageId }
    }

    /**
     * Fork / Child clone 的唯一 entry 映射。
     *
     * 不得假设 Master 与 Child 的 clone 路径永远保留 message ID：node 必须显式在
     * [nodeIdMap] 中（复制不到的 node 直接落选）；message 未在 [messageIdMap] 声明时按
     * clone 路径"保留原 id"处理。
     *
     * 复制保留整棵节点树中仍存在的 owner/anchor，包括未选 variant；请求适用性由查询时
     * 的 selected branch 决定。Fork 点之后因 node 不在 map 中而自然落选。
     */
    fun remapForClone(
        entries: List<ConversationModelContextEntry>,
        nodeIdMap: Map<Uuid, Uuid>,
        messageIdMap: Map<Uuid, Uuid>,
        clonedNodes: List<MessageNode>,
    ): List<ConversationModelContextEntry> {
        if (entries.isEmpty()) return emptyList()
        return entries.mapNotNull { entry ->
            val ownerNodeId = nodeIdMap[entry.ownerNodeId] ?: return@mapNotNull null
            val anchorNodeId = nodeIdMap[entry.anchorNodeId] ?: return@mapNotNull null
            val mapped = entry.copy(
                id = contextEntryIdentity(ownerNodeId, messageIdMap[entry.ownerMessageId] ?: entry.ownerMessageId, entry.occurrence),
                ownerNodeId = ownerNodeId,
                ownerMessageId = messageIdMap[entry.ownerMessageId] ?: entry.ownerMessageId,
                anchorNodeId = anchorNodeId,
                anchorMessageId = messageIdMap[entry.anchorMessageId] ?: entry.anchorMessageId,
                payload = entry.payload.copy(source = entry.payload.source.remapLocators(nodeIdMap, messageIdMap),
                    body = (entry.payload.body as? ConversationContextBody.MessageReference)?.let { body ->
                        ConversationContextBody.MessageReference(ContextMessageLocator(
                            nodeIdMap.getValue(body.message.nodeId), messageIdMap[body.message.messageId] ?: body.message.messageId))
                    } ?: entry.payload.body),
            )
            mapped.takeIf { stillExists(it, clonedNodes) }
        }
    }


}

private fun ConversationContextSource.remapLocators(
    nodes: Map<Uuid, Uuid>, messages: Map<Uuid, Uuid>,
): ConversationContextSource {
    fun ContextMessageLocator.remap() = copy(nodeId = nodes[nodeId] ?: nodeId, messageId = messages[messageId] ?: messageId)
    return when (this) {
        is ConversationContextSource.MessageTime -> copy(message = message.remap(), previous = previous?.remap())
        is ConversationContextSource.Attachment -> copy(message = message.remap())
        else -> this
    }
}
