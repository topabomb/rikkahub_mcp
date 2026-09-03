package net.weero.measix.pilot.data.model

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import kotlin.uuid.Uuid

/**
 * Durable 的模型上下文条目：模型必须看到、但不属于用户可见对话事实的数据（本轮即 Disclosure
 * Snapshot）在一次 `START` 请求位置上的完整 baseline。
 *
 * 它是 append-only 的会话事实，随消息历史一起被裁剪、Fork 和删除；不是 `MessageNode`，
 * 也不是隐藏 `UIMessage`，因此不进入 `message_node.messages`、FTS、UI 投影或备份 mapper。
 *
 * 身份模型（权威方案 §5.1）：
 * - [ownerNodeId] / [ownerMessageId] —— 拥有本条目的那次 durable Assistant request variant。
 *   owner variant 被选中、或仍是 active turn owner 时，条目才适用于请求；切换 Assistant
 *   variant 不会读到旧 owner 的条目。
 * - [anchorNodeId] / [anchorMessageId] —— 该请求实际使用的因果 USER variant。条目的逻辑
 *   请求位置由 anchor 决定（投影到它之前），与 owner Assistant 在 UI 历史中的显示位置无关。
 * - [content] —— 交付给模型的精确 canonical UTF-8 文本。旧条目永不请求时重建；内容协议
 *   （`type` / `format`）只存在于该 JSON envelope 内，表与 domain 不重复保存 kind / format。
 *
 * 两个 message ID 位于 `message_node.messages` JSON 内，数据库无法对 JSON 内 variant 建
 * FK，因此归属与角色由 Repository mapper 装载时校验并 fail-closed（权威方案 §12.3）。
 */
internal data class ConversationModelContextEntry(
    val ownerNodeId: Uuid,
    val ownerMessageId: Uuid,
    val anchorNodeId: Uuid,
    val anchorMessageId: Uuid,
    val content: String,
)

/**
 * 模型上下文条目的唯一适用谓词（权威方案 §5.1、§8.2）。
 *
 * START 判等、请求组装、Fork 过滤和裁剪必须共用这一个判定，不得各自猜测：
 *  - owner Assistant variant 在给定 selected branch 上（active owner 由调用方作为分支末条
 *    传入，因此同样被覆盖）；
 *  - anchor USER variant 也在同一 selected branch，且结构位置早于 owner；
 *  - anchor 是 owner 的因果 USER —— 即 owner 之前最后一条真实 USER turn。
 *
 * 任一条件不成立都明确不适用；不基于 role 或列表位置做 fallback 推断。
 */
internal object ConversationModelContextApplicability {

    /** [branchMessages] 是 selected 分支的有序消息序列（含 active Assistant 末条）。 */
    fun applicable(entry: ConversationModelContextEntry, branchMessages: List<UIMessage>): Boolean {
        val ownerIndex = branchMessages.indexOfFirst { it.id == entry.ownerMessageId }
        if (ownerIndex < 0 || branchMessages[ownerIndex].role != MessageRole.ASSISTANT) return false
        val anchorIndex = branchMessages.indexOfFirst { it.id == entry.anchorMessageId }
        if (anchorIndex < 0 || anchorIndex >= ownerIndex) return false
        if (branchMessages[anchorIndex].role != MessageRole.USER) return false
        // 因果 USER：owner 之前最后一条 USER 必须就是 anchor。
        return branchMessages.subList(0, ownerIndex).indexOfLast { it.role == MessageRole.USER } == anchorIndex
    }

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
     * Fork / Child clone 的唯一 entry 映射（权威方案 §14.1、§14.2）。
     *
     * 不得假设 Master 与 Child 的 clone 路径永远保留 message ID：node 必须显式在
     * [nodeIdMap] 中（复制不到的 node 直接落选）；message 未在 [messageIdMap] 声明时按
     * clone 路径"保留原 id"处理。
     *
     * 适用性在**被复制的整棵节点树**上判定，而不是只看当前 selected `currentMessages`：
     * 被复制但当时未选中的 Assistant owner 仍要带走它的 baseline，否则 Fork 后切回该
     * variant 会丢掉历史基线。判定时把该 owner / anchor variant 临时放到对应 node 上，
     * 再交给同一个 [applicable] 因果谓词；Fork 点之后因 node 不在 map 中而自然落选。
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
                ownerNodeId = ownerNodeId,
                ownerMessageId = messageIdMap[entry.ownerMessageId] ?: entry.ownerMessageId,
                anchorNodeId = anchorNodeId,
                anchorMessageId = messageIdMap[entry.anchorMessageId] ?: entry.anchorMessageId,
            )
            mapped.takeIf { applicableOnCopiedTree(it, clonedNodes) }
        }
    }

    /**
     * 在已复制的 node 树上评估 [applicable]：owner / anchor variant 只要还在对应 node 的
     * messages 里，就按“选中它们”构造 branch，其它 node 保持原 selectIndex。
     */
    fun applicableOnCopiedTree(
        entry: ConversationModelContextEntry,
        nodes: List<MessageNode>,
    ): Boolean {
        val ownerNodeIndex = nodes.indexOfFirst { it.id == entry.ownerNodeId }
        if (ownerNodeIndex < 0) return false
        val anchorNodeIndex = nodes.indexOfFirst { it.id == entry.anchorNodeId }
        if (anchorNodeIndex < 0) return false
        val branch = nodes.mapIndexed { index, node ->
            when (index) {
                ownerNodeIndex -> node.messages.firstOrNull { it.id == entry.ownerMessageId } ?: return false
                anchorNodeIndex -> node.messages.firstOrNull { it.id == entry.anchorMessageId } ?: return false
                else -> node.currentMessage
            }
        }
        return applicable(entry, branch)
    }
}
