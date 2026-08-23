package net.weero.measix.pilot.service.runtime

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.data.db.entity.TurnExecutionEntity
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.service.runtime.ConversationHeaderPatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.uuid.Uuid

private const val TAG = "ConversationRuntime"
private const val IDLE_TIMEOUT_MS = 5_000L

/**
 * 会话运行时主体（原 ConversationSession 改名+改造）。
 *
 * 保留既有职责与签名：refCount / idle 逐出 / generationJob / turn 取消 / TTS 队列 / 审批锁。
 * 新增：
 *  - [snapshot] 唯一内部事实流（UI 主订阅源）
 *  - [state] 兼容投影（由 snapshot 派生，旧消费方继续读 conversation.currentMessages）
 *  - [submit] / [submitGeneration] 所有结构性修改唯一入口（commandMutex 单写互斥）
 *  - [applyStreamingDelta] 流式高频更新（无锁、conflated、永不落库）
 *  - [isWriteInFlight] 写通道占用（Registry idle 守卫）
 *
 * B3 已完成：persistMutex/stateRevision/persistedRevision/withPersistLock 移除（被
 * commandMutex 吸收）；"内存领先于 DB"由 [pendingPersist] 失败标记承载。
 */
class ConversationRuntime(
    val id: Uuid,
    initial: Conversation,
    private val scope: CoroutineScope,
    private val onIdle: (Uuid) -> Unit,
    private val repository: ConversationRepository? = null,
) {
    // 会话状态：`state` 为对外兼容投影（activeTurn 覆盖最后 assistant 节点当前消息），
    // `_state` 为真实 reducer 输入（nodes 原貌）。流式 delta 只更新投影，不污染真实状态——
    // 否则后续 CommitCheckpoint 的 structural diff 会因投影提前写入而误判"无变化"。
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<Conversation> get() = _compatibleState
    private val _compatibleState = MutableStateFlow(initial)

    // 唯一内部事实流
    private val _snapshot = MutableStateFlow(initial.toSnapshot())

    // 原子引用计数
    private val refCount = AtomicInteger(0)

    // 处理状态（如 OCR 识别中）
    val processingStatus = MutableStateFlow<String?>(null)

    // 生成任务（内聚在 session 中）
    private val _generationJob = MutableStateFlow<Job?>(null)
    private val toolApprovalMutex = Mutex()
    private var ttsQueueSessionId: String? = null
    val generationJob: StateFlow<Job?> = _generationJob.asStateFlow()
    val isGenerating: Boolean get() = _generationJob.value?.isActive == true
    val isInUse: Boolean get() = refCount.get() > 0 || isGenerating || isDirty()

    // 写通道互斥（吸收原 persistMutex / stateRevision / persistedRevision / withPersistLock）
    private val commandMutex = Mutex()
    private val activeTurnId = AtomicReference<Uuid?>(null)
    private val cancelReasons = ConcurrentHashMap<Uuid, String>()
    private val finalizedTurns: MutableSet<Uuid> = ConcurrentHashMap.newKeySet()

    // 持久化失败跟踪（失败不回滚内存，下次命令重试整包差异）。
    // 取代原 stateRevision/persistedRevision 计数对——命令通道下 submit 返回即已落库，
    // "内存领先于 DB"仅发生在持久化失败时。
    private val pendingPersist = AtomicBoolean(false)

    // 持久化基线：上次成功落库的状态（用例 C5）。失败期间内存前进而基线停驻，
    // 重试 diff 以基线为起点——structural sharing 对内存相同实例的跳过不会丢掉
    // 未落盘变更；成功后基线追平内存，恢复正常 delta 语义。
    private var persistedState: Conversation? = null

    // ---- 计划新增：单事实源投影 ----

    /** UI 主订阅源 */
    val snapshot: StateFlow<ConversationSnapshot> = _snapshot.asStateFlow()

    /**
     * 流式高频更新：非挂起、conflated、永不落库、不加锁。
     * 只更新 activeTurn.messages 并派生 conversation 投影（旧消费方继续看到更新）。
     */
    fun applyStreamingDelta(turnId: Uuid, assistantMessageId: Uuid, messages: List<UIMessage>) {
        _snapshot.value = _snapshot.value.copyWithActiveTurn(ActiveTurnState(turnId, assistantMessageId, messages))
        // 兼容投影：把最后 assistant 节点当前消息替换为 activeTurn 内容（仅投影，不污染真实 nodes）
        _compatibleState.value = _snapshot.value.conversation
    }

    /** 所有结构性修改的唯一入口 */
    suspend fun submit(command: ConversationCommand): Conversation {
        // 流式命令：无锁更新 activeTurn + 兼容投影，永不落库
        if (command is ApplyStreamingDelta) {
            applyStreamingDeltaInternal(command.messages)
            return _state.value
        }
        writeInFlight.set(true)
        try {
            commandMutex.withLock {
                val old = _state.value
                val new = ConversationReducer.reduce(old, command)
                _snapshot.value = new.toSnapshot()
                _state.value = new
                _compatibleState.value = new
                // 持久化：reducer 之后 diff 落库；生成期命令附带 executionFacts。
                // 即使 reducer 无内存变化（new===old），生成期命令的 turn 事实仍需落库，故不短路。
                persistCommand(old, new, command)
                return new
            }
        } finally {
            writeInFlight.set(false)
        }
    }

    /** 生成期命令的 executionFacts（Turn/ToolExecution 同事务落库）。 */
    private suspend fun persistCommand(old: Conversation, new: Conversation, command: ConversationCommand) {
        val repo = repository ?: return
        // 基线 = 上次成功落库状态：失败期间内存已前进，
        // 以内存为基线的 structural diff 会跳过未落盘节点；以持久化基线重放则完整覆盖。
        val baseline = persistedState ?: old
        val mutation = buildMutation(baseline, new)
        val facts = when (command) {
            is CommitCheckpoint -> ExecutionFacts(
                turn = buildTurn(repo, command.turnId, command.turnStatus, command.turnReason, command.assistantMessageId),
                toolExecution = command.toolExecution,
            )
            is FinalizeTurn -> ExecutionFacts(
                turn = buildTurn(repo, command.turnId, command.terminalStatus, command.terminalReason, command.assistantMessageId),
                toolExecution = null,
            )
            else -> null
        }
        runCatching {
            repo.applyMutation(mutation, facts)
        }.onSuccess {
            persistedState = new
            pendingPersist.set(false)
            // 失败转成功后重新调度 idle 逐出（原 markPersisted 的副作用语义）
            if (refCount.get() <= 0 && !isGenerating && !pendingPersist.get()) {
                scheduleIdleCheck()
            }
        }.onFailure { error ->
            Log.w(TAG, "submit persist failed: ${error.message}")
            // 持久化失败不回滚内存（避免 UI 与 DB 双向不一致放大）；下次命令重试整包差异
            pendingPersist.set(true)
        }
    }

    private suspend fun buildTurn(
        repo: ConversationRepository,
        turnId: Uuid,
        status: TurnExecutionStatus,
        reason: String?,
        assistantMessageId: Uuid,
    ): TurnExecutionEntity {
        val now = System.currentTimeMillis()
        // upsert（INSERT OR REPLACE）会覆盖全部列，终态重写 RUNNING 行时保留首次创建时间
        val previous = runCatching { repo.getTurnExecution(turnId.toString()) }.getOrNull()
        return TurnExecutionEntity(
            turnId = turnId.toString(),
            conversationId = id.toString(),
            assistantMessageId = assistantMessageId.toString(),
            status = status,
            reason = reason,
            createdAt = previous?.createdAt ?: now,
            updatedAt = now,
        )
    }

    private fun buildMutation(old: Conversation, new: Conversation): ConversationMutation {
        val changedNodes = mutableListOf<MessageNode>()
        val deletedNodeIds = mutableListOf<Uuid>()
        val max = maxOf(old.messageNodes.size, new.messageNodes.size)
        for (i in 0 until max) {
            val oldNode = old.messageNodes.getOrNull(i)
            val newNode = new.messageNodes.getOrNull(i)
            when {
                newNode == null -> oldNode?.let { deletedNodeIds.add(it.id) }
                oldNode === newNode -> Unit // structural sharing：未变
                oldNode == null -> changedNodes.add(newNode)
                oldNode.id != newNode.id -> {
                    deletedNodeIds.add(oldNode.id)
                    changedNodes.add(newNode)
                }
                else -> changedNodes.add(newNode)
            }
        }
        val headerChanged = new.title != old.title ||
            new.chatSuggestions != old.chatSuggestions ||
            new.isPinned != old.isPinned ||
            new.folderId != old.folderId ||
            new.assistantId != old.assistantId ||
            new.customSystemPrompt != old.customSystemPrompt ||
            new.modeInjectionIds != old.modeInjectionIds ||
            new.workspaceCwd != old.workspaceCwd
        val headerPatch = if (headerChanged) {
            ConversationHeaderPatch(
                title = if (new.title != old.title) new.title else null,
                chatSuggestions = if (new.chatSuggestions != old.chatSuggestions) new.chatSuggestions else null,
                isPinned = if (new.isPinned != old.isPinned) new.isPinned else null,
                folderId = when {
                    new.folderId == old.folderId -> OptionalFolderId.Keep
                    new.folderId == null -> OptionalFolderId.Clear
                    else -> OptionalFolderId.SetTo(new.folderId)
                },
                assistantId = if (new.assistantId != old.assistantId) new.assistantId else null,
                customSystemPrompt = if (new.customSystemPrompt != old.customSystemPrompt) OptionalString.Set(new.customSystemPrompt) else OptionalString.Keep,
                modeInjectionIds = if (new.modeInjectionIds != old.modeInjectionIds) OptionalUuidSet.Set(new.modeInjectionIds) else OptionalUuidSet.Keep,
                workspaceCwd = if (new.workspaceCwd != old.workspaceCwd) OptionalString.Set(new.workspaceCwd) else OptionalString.Keep,
            )
        } else null
        return ConversationMutation(
            conversationId = new.id,
            headerPatch = headerPatch,
            upsertedNodes = changedNodes,
            deletedNodeIds = deletedNodeIds,
            updateAt = new.updateAt.toEpochMilli(),
        )
    }

    suspend fun submitGeneration(command: ConversationCommand): Conversation = submit(command)

    private val writeInFlight = AtomicBoolean(false)

    /** 写通道占用中（submit 进行时），Registry 据此阻止 idle 回收 */
    fun isWriteInFlight(): Boolean = writeInFlight.get()

    // ---- 保留的既有职责（签名不变） ----

    /**
     * 内存是否领先于 DB（仅持久化失败时为 true）。idle 逐出守卫与装载判定使用。
     * 取代原 revision 计数对（commandMutex 吸收持久化互斥与计数）。
     */
    fun isDirty(): Boolean = pendingPersist.get()

    fun beginTurn(turnId: Uuid) {
        activeTurnId.set(turnId)
    }

    fun currentTurnId(): Uuid? = activeTurnId.get()

    fun requestCancel(turnId: Uuid, reason: String) {
        cancelReasons[turnId] = reason
    }

    fun consumeCancelReason(turnId: Uuid): String? = cancelReasons.remove(turnId)

    fun peekCancelReason(turnId: Uuid): String? = cancelReasons[turnId]

    /**
     * 登记已提交终态的 turn。一个 turn 只允许提交一次终态。
     */
    fun markTurnFinalized(turnId: Uuid) {
        finalizedTurns.add(turnId)
    }

    fun isTurnFinalized(turnId: Uuid): Boolean = finalizedTurns.contains(turnId)

    // 空闲检查任务
    private var idleCheckJob: Job? = null

    fun acquire(): Int = refCount.incrementAndGet().also {
        cancelIdleCheck()
        Log.d(TAG, "acquire $id (refs=$it)")
    }

    fun release(): Int = refCount.decrementAndGet().also {
        Log.d(TAG, "release $id (refs=$it)")
        if (it <= 0) scheduleIdleCheck()
    }

    // 作用域 API - 短请求（REST）
    inline fun <T> withRef(block: () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    // 作用域 API - 长连接（SSE、挂起函数）
    suspend inline fun <T> withRefSuspend(block: () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    fun setJob(job: Job?, turnId: Uuid? = null) {
        _generationJob.value?.cancel()
        _generationJob.value = job
        if (job != null && turnId != null) {
            activeTurnId.set(turnId)
        }
        job?.invokeOnCompletion {
            // A cancelled previous job may finish after its replacement was installed.
            // Only the job that is still current may clear the slot or schedule eviction.
            if (_generationJob.compareAndSet(job, null)) {
                if (turnId != null) {
                    activeTurnId.compareAndSet(turnId, null)
                    cancelReasons.remove(turnId)
                }
                if (refCount.get() <= 0) scheduleIdleCheck()
            }
        }
    }

    fun getJob(): Job? = _generationJob.value

    /**
     * 返回主代理 turn 独占的 TTS 队列 ID。
     */
    @Synchronized
    fun getTtsQueueSessionId(resumeExistingTurn: Boolean): String {
        if (!resumeExistingTurn || ttsQueueSessionId == null) {
            ttsQueueSessionId = Uuid.random().toString()
        }
        return requireNotNull(ttsQueueSessionId)
    }

    @Synchronized
    fun peekTtsQueueSessionId(): String? = ttsQueueSessionId

    suspend fun <T> withToolApprovalLock(block: suspend () -> T): T =
        toolApprovalMutex.withLock { block() }

    private fun scheduleIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            if (refCount.get() <= 0 && !isGenerating && !isDirty()) {
                onIdle(id)
            }
        }
    }

    private fun cancelIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = null
    }

    fun cleanup() {
        _generationJob.value?.cancel()
        _generationJob.value = null
        activeTurnId.set(null)
        cancelReasons.clear()
        finalizedTurns.clear()
        ttsQueueSessionId = null
        idleCheckJob?.cancel()
        idleCheckJob = null
    }

    // ---- 内部 ----

    private fun applyStreamingDeltaInternal(messages: List<UIMessage>) {
        val cur = _snapshot.value
        val turn = cur.activeTurn ?: return
        _snapshot.value = ConversationSnapshot(
            conversationId = cur.conversationId,
            header = cur.header,
            nodes = cur.nodes,
            activeTurn = turn.copy(messages = messages),
        )
        _compatibleState.value = _snapshot.value.conversation
    }

    /**
     * 外部整对象替换入口（导入/迁移/legacy 写路径）。真实状态与投影同步刷新，
     * activeTurn 清空。
     */
    fun replaceState(conversation: Conversation) {
        if (conversation.id != id) return
        _state.value = conversation
        _snapshot.value = conversation.toSnapshot()
        _compatibleState.value = conversation
        // 装载路径：内存来自 DB 快照，持久化基线同步重置（基线与内存一致）
        persistedState = conversation
    }
}

private fun Conversation.toSnapshot(): ConversationSnapshot = ConversationSnapshot(
    conversationId = id,
    header = ConversationHeader(
        id = id,
        title = title,
        assistantId = assistantId,
        folderId = folderId,
        isPinned = isPinned,
        chatSuggestions = chatSuggestions,
        customSystemPrompt = customSystemPrompt,
        modeInjectionIds = modeInjectionIds,
        workspaceCwd = workspaceCwd,
        parentConversationId = parentConversationId,
        newConversation = newConversation,
        createAt = createAt.toEpochMilli(),
        updateAt = updateAt.toEpochMilli(),
    ),
    nodes = messageNodes,
    activeTurn = null,
)

private fun ConversationSnapshot.copyWithActiveTurn(turn: ActiveTurnState): ConversationSnapshot =
    ConversationSnapshot(
        conversationId = conversationId,
        header = header,
        nodes = nodes,
        activeTurn = turn,
    )
