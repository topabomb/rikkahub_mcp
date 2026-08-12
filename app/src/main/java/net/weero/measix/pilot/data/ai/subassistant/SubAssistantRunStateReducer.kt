package net.weero.measix.pilot.data.ai.subassistant

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 串行维护单次子助手调用的完整 metadata 快照。
 *
 * 设计文档 §7.2 / §9.1 要求：
 * - 同一 run 的 metadata 只能由 Reducer 按 copy 语义更新并输出完整快照；
 *   phase、preview 和终态不能各自重新构造对象，否则会丢失 previous_run_id、Child link 或已写入字段；
 * - active_tool_name 只在 phase == tool_executing 时保留；进入其他 phase 或任一终态时清空；
 * - Reducer 忽略终态之后迟到的 phase/preview；
 * - 状态单向转换：终态后不可回到 running。
 */
class SubAssistantRunStateReducer(
    private val initial: SubAssistantCallMetadata,
) {
    private val mutex = Mutex()
    @Volatile
    private var current: SubAssistantCallMetadata = initial

    /**
     * 获取当前完整快照（线程安全）。
     */
    fun snapshot(): SubAssistantCallMetadata = current

    /**
     * 更新 phase。
     * 终态后忽略；active_tool_name 只在 TOOL_EXECUTING 时保留。
     */
    suspend fun updatePhase(
        phase: SubAssistantCallPhase,
        activeToolName: String? = null,
    ): SubAssistantCallMetadata = mutex.withLock {
        if (current.state.isTerminal()) return@withLock current
        val resolvedActiveToolName = if (phase == SubAssistantCallPhase.TOOL_EXECUTING) {
            activeToolName
        } else {
            null
        }
        current = current.copy(
            phase = phase,
            activeToolName = resolvedActiveToolName,
        )
        current
    }

    /**
     * 更新 preview。
     * 终态后忽略。
     */
    suspend fun updatePreview(preview: String?): SubAssistantCallMetadata = mutex.withLock {
        if (current.state.isTerminal()) return@withLock current
        if (preview == current.preview) return@withLock current
        current = current.copy(preview = preview)
        current
    }

    /**
     * 发布需要宿主回答的交互，同时保留 lineage、Child link 和已有 preview 等完整快照字段。
     */
    suspend fun awaitUserInteraction(
        interaction: SubAssistantUserInteraction,
        preview: String?,
    ): SubAssistantCallMetadata = mutex.withLock {
        if (current.state.isTerminal()) return@withLock current
        current = current.copy(
            phase = SubAssistantCallPhase.AWAITING_USER,
            activeToolName = null,
            preview = preview,
            userInteraction = interaction,
        )
        current
    }

    /** 回答落盘后清除宿主交互入口，再进入下一工具 step。 */
    suspend fun clearUserInteraction(): SubAssistantCallMetadata = mutex.withLock {
        if (current.state.isTerminal()) return@withLock current
        current = current.copy(
            phase = SubAssistantCallPhase.BETWEEN_STEPS,
            activeToolName = null,
            userInteraction = null,
        )
        current
    }

    /**
     * 更新运行状态（starting → running）。
     * 终态后不可回到 running。
     */
    suspend fun updateRunningState(
        childConversationId: String? = null,
        childTaskNodeId: String? = null,
    ): SubAssistantCallMetadata = mutex.withLock {
        if (current.state.isTerminal()) return@withLock current
        if (current.state.canTransitionTo(SubAssistantCallState.RUNNING)) {
            current = current.copy(
                state = SubAssistantCallState.RUNNING,
                childConversationId = childConversationId ?: current.childConversationId,
                childTaskNodeId = childTaskNodeId ?: current.childTaskNodeId,
                phase = current.phase,
                activeToolName = current.activeToolName,
            )
        }
        current
    }

    /**
     * 写入终态。
     * 幂等：已经处于相同终态时直接返回；不同终态不覆盖（先到先得）。
     */
    suspend fun updateTerminalState(
        state: SubAssistantCallState,
        reason: String? = null,
        preview: String? = null,
        hasNonTextOutput: Boolean = false,
    ): SubAssistantCallMetadata = mutex.withLock {
        if (current.state.isTerminal()) {
            // 幂等：已经终态，不覆盖更早的结果
            return@withLock current
        }
        if (current.state.canTransitionTo(state)) {
            current = current.copy(
                state = state,
                reason = reason,
                phase = null,
                activeToolName = null,
                preview = preview ?: current.preview,
                hasNonTextOutput = hasNonTextOutput,
                userInteraction = null,
            )
        }
        current
    }

    /**
     * 设置 Child link 信息。
     */
    suspend fun setChildLink(
        childConversationId: String,
        childTaskNodeId: String,
    ): SubAssistantCallMetadata = mutex.withLock {
        if (current.state.isTerminal()) return@withLock current
        current = current.copy(
            childConversationId = childConversationId,
            childTaskNodeId = childTaskNodeId,
        )
        current
    }
}
