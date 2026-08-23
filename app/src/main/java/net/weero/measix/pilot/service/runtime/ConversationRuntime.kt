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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.uuid.Uuid

private const val TAG = "ConversationRuntime"
private const val IDLE_TIMEOUT_MS = 5_000L

/**
 * 会话运行时主体：单写命令通道、流式投影与 delta 落库。
 *
 * 保留既有职责与签名：refCount / idle 逐出 / generationJob / turn 取消 / TTS 队列 / 审批锁。
 *  - [snapshot] 唯一事实流（UI 与内部读取的唯一订阅源）
 *  - [submit] 所有结构性修改唯一入口（commandMutex 单写互斥）
 *  - [applyStreamingDelta] 流式高频更新（无锁、conflated、永不落库；只动 activeTurn）
 *  - [isWriteInFlight] 写通道占用（Registry idle 守卫）
 *
 * "内存领先于 DB"由 [pendingPersist] 失败标记承载。
 */
class ConversationRuntime(
    val id: Uuid,
    initial: ConversationSnapshot,
    private val scope: CoroutineScope,
    private val onIdle: (Uuid) -> Unit,
    private val repository: ConversationRepository? = null,
) {
    // 唯一事实流
    private val _snapshot = MutableStateFlow(initial)

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
    private val pendingPersist = AtomicBoolean(false)

    // 持久化基线：上次成功落库的状态。失败期间内存前进而基线停驻，
    // 重试 diff 以基线为起点——structural sharing 对内存相同实例的跳过不会丢掉
    // 未落盘变更；成功后基线追平内存，恢复正常 delta 语义。
    private var persistedState: ConversationSnapshot? = null

    // ---- snapshot 事实源 ----

    /** UI 与内部读取的唯一订阅源 */
    val snapshot: StateFlow<ConversationSnapshot> = _snapshot.asStateFlow()

    /**
     * 流式高频更新：非挂起、conflated、永不落库、不加锁。只更新 activeTurn.messages。
     */
    fun applyStreamingDelta(turnId: Uuid, assistantMessageId: Uuid, messages: List<UIMessage>) {
        _snapshot.value = _snapshot.value.copy(
            activeTurn = ActiveTurnState(turnId, assistantMessageId, messages),
        )
    }

    /** 所有结构性修改的唯一入口 */
    suspend fun submit(command: ConversationCommand): ConversationSnapshot {
        // 流式命令：无锁更新 activeTurn，永不落库
        if (command is ApplyStreamingDelta) {
            applyStreamingDeltaInternal(command.messages)
            return _snapshot.value
        }
        writeInFlight.set(true)
        try {
            commandMutex.withLock {
                val old = _snapshot.value
                val new = ConversationReducer.reduce(old, command)
                // 结构性命令收口流式态：activeTurn 仅在两次命令之间的流式期间有效
                // （终态标记由 reducer 写入 nodes，残留 activeTurn 会遮蔽终态投影）。
                val settled = if (new.activeTurn != null) new.copy(activeTurn = null) else new
                _snapshot.value = settled
                // 持久化：reducer 之后 diff 落库；生成期命令附带 executionFacts。
                // 即使 reducer 无内存变化（new===old），生成期命令的 turn 事实仍需落库，故不短路。
                persistCommand(old, settled, command)
                return settled
            }
        } finally {
            writeInFlight.set(false)
        }
    }

    /** 生成期命令的 executionFacts（Turn/ToolExecution 同事务落库）。 */
    private suspend fun persistCommand(old: ConversationSnapshot, new: ConversationSnapshot, command: ConversationCommand) {
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
            // 失败转成功后重新调度 idle 逐出
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

    private fun buildMutation(old: ConversationSnapshot, new: ConversationSnapshot): ConversationMutation {
        val changedNodes = mutableListOf<MessageNode>()
        val changedIndices = mutableListOf<Int>()
        val deletedNodeIds = mutableListOf<Uuid>()
        val max = maxOf(old.nodes.size, new.nodes.size)
        for (i in 0 until max) {
            val oldNode = old.nodes.getOrNull(i)
            val newNode = new.nodes.getOrNull(i)
            when {
                newNode == null -> oldNode?.let { deletedNodeIds.add(it.id) }
                oldNode === newNode -> Unit // structural sharing：未变
                oldNode == null -> {
                    changedNodes.add(newNode)
                    changedIndices.add(i)
                }
                oldNode.id != newNode.id -> {
                    deletedNodeIds.add(oldNode.id)
                    changedNodes.add(newNode)
                    changedIndices.add(i)
                }
                else -> {
                    changedNodes.add(newNode)
                    changedIndices.add(i)
                }
            }
        }
        val oldHeader = old.header
        val newHeader = new.header
        val headerChanged = newHeader.title != oldHeader.title ||
            newHeader.chatSuggestions != oldHeader.chatSuggestions ||
            newHeader.isPinned != oldHeader.isPinned ||
            newHeader.folderId != oldHeader.folderId ||
            newHeader.assistantId != oldHeader.assistantId ||
            newHeader.customSystemPrompt != oldHeader.customSystemPrompt ||
            newHeader.modeInjectionIds != oldHeader.modeInjectionIds ||
            newHeader.workspaceCwd != oldHeader.workspaceCwd
        val headerPatch = if (headerChanged) {
            ConversationHeaderPatch(
                title = if (newHeader.title != oldHeader.title) newHeader.title else null,
                chatSuggestions = if (newHeader.chatSuggestions != oldHeader.chatSuggestions) newHeader.chatSuggestions else null,
                isPinned = if (newHeader.isPinned != oldHeader.isPinned) newHeader.isPinned else null,
                folderId = when {
                    newHeader.folderId == oldHeader.folderId -> OptionalFolderId.Keep
                    newHeader.folderId == null -> OptionalFolderId.Clear
                    else -> OptionalFolderId.SetTo(newHeader.folderId)
                },
                assistantId = if (newHeader.assistantId != oldHeader.assistantId) newHeader.assistantId else null,
                customSystemPrompt = if (newHeader.customSystemPrompt != oldHeader.customSystemPrompt) OptionalString.Set(newHeader.customSystemPrompt) else OptionalString.Keep,
                modeInjectionIds = if (newHeader.modeInjectionIds != oldHeader.modeInjectionIds) OptionalUuidSet.Set(newHeader.modeInjectionIds) else OptionalUuidSet.Keep,
                workspaceCwd = if (newHeader.workspaceCwd != oldHeader.workspaceCwd) OptionalString.Set(newHeader.workspaceCwd) else OptionalString.Keep,
            )
        } else null
        return ConversationMutation(
            conversationId = new.conversationId,
            headerPatch = headerPatch,
            upsertedNodes = changedNodes,
            deletedNodeIds = deletedNodeIds,
            updateAt = newHeader.updateAt,
            // Runtime 内存 header 为权威：title 随 delta 携带，applyMutation 禁止回查 DB
            titleForIndex = newHeader.title,
            upsertedNodeIndices = changedIndices,
        )
    }

    private val writeInFlight = AtomicBoolean(false)

    /** 写通道占用中（submit 进行时），Registry 据此阻止 idle 回收 */
    fun isWriteInFlight(): Boolean = writeInFlight.get()

    // ---- 保留的既有职责（签名不变） ----

    /**
     * 内存是否领先于 DB（仅持久化失败时为 true）。idle 逐出守卫与装载判定使用。
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
        _snapshot.value = cur.copy(activeTurn = turn.copy(messages = messages))
    }

    /**
     * 整对象装载入口（从 DB 加载 / 导入）。activeTurn 清空，持久化基线同步重置
     * （基线与内存一致）。
     */
    fun loadSnapshot(conversation: Conversation) {
        if (conversation.id != id) return
        _snapshot.value = conversation.toSnapshot()
        // 装载路径：内存来自 DB 快照，持久化基线同步重置（基线与内存一致）
        persistedState = _snapshot.value
    }
}

internal fun Conversation.toSnapshot(): ConversationSnapshot = ConversationSnapshot(
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
