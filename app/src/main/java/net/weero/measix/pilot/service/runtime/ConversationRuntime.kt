package net.weero.measix.pilot.service.runtime

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.data.model.Conversation
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import kotlin.uuid.Uuid

private const val TAG = "ConversationRuntime"
private const val IDLE_TIMEOUT_MS = 5_000L

/**
 * 会话运行时主体：单写命令通道、流式投影与持久化提交边界。
 *
 *  - [snapshot] 唯一事实流（UI 与内部读取的唯一订阅源）
 *  - [submit] Ready 会话的结构命令入口（commandMutex 单写互斥，持久化成功后发布）
 *  - [updateDraftHeader] Draft 唯一的非持久化编辑入口
 *  - [applyStreamingDelta] 流式高频更新（无锁、conflated、永不落库；只动 activeTurn）
 *  - [isCommandWriteInFlight] 命令通道占用（Registry idle 守卫）
 *
 */
class ConversationRuntime(
    val id: Uuid,
    initial: ConversationSnapshot,
    private val scope: CoroutineScope,
    private val onIdle: (Uuid) -> Unit,
    private val idleTimeoutMs: Long = IDLE_TIMEOUT_MS,
) {
    // 唯一事实流
    private val _snapshot = MutableStateFlow(initial)

    // 原子引用计数
    private val refCount = AtomicInteger(0)

    // 处理状态（如 OCR 识别中）
    val processingStatus = MutableStateFlow<String?>(null)

    // 生成任务由本 ConversationRuntime 持有。
    private val _generationJob = MutableStateFlow<Job?>(null)
    private val toolApprovalMutex = Mutex()
    private var ttsQueueSessionId: String? = null
    val generationJob: StateFlow<Job?> = _generationJob.asStateFlow()
    val isGenerating: Boolean get() = _generationJob.value?.isActive == true
    val isInUse: Boolean
        get() = refCount.get() > 0 || isGenerating || snapshot.value.activeTurn != null || isCommandWriteInFlight()

    // All durable conversation commands are serialized through this mutex.
    private val commandMutex = Mutex()
    private val activeTurnId = AtomicReference<Uuid?>(null)
    private val cancelReasons = ConcurrentHashMap<Uuid, String>()
    private val turnEpoch = AtomicLong(0)

    // ---- snapshot 事实源 ----

    /** UI 与内部读取的唯一订阅源 */
    val snapshot: StateFlow<ConversationSnapshot> = _snapshot.asStateFlow()

    /**
     * 流式高频更新：非挂起、conflated、永不落库、不加锁。只更新 activeTurn.messages。
     */
    fun applyStreamingDelta(handle: TurnHandle, messages: List<UIMessage>): StreamingDeltaResult {
        if (handle.conversationId != id) return StreamingDeltaResult.STALE_TURN
        var applied = false
        _snapshot.update { current ->
            val active = current.activeTurn
            if (active != null && active.matches(handle)) {
                applied = true
                current.copy(activeTurn = active.withStreamingMessages(messages))
            } else {
                applied = false
                current
            }
        }
        return if (applied) StreamingDeltaResult.APPLIED else StreamingDeltaResult.STALE_TURN
    }

    internal suspend fun startTurn(
        turnId: Uuid,
        assistantMessageId: Uuid,
        resume: Boolean,
        persist: suspend (ConversationSnapshot, ConversationSnapshot, ConversationCommand) -> ConversationSnapshot,
    ): TurnHandle = withCommandWrite {
        val old = _snapshot.value
        if (old.activeTurn != null) {
            throw ConversationCommandConflictException("conversation $id already has an active turn")
        }
        val handle = TurnHandle(id, turnEpoch.incrementAndGet(), turnId, assistantMessageId)
        val command = StartTurn(turnId, assistantMessageId, resume)
        val reduced = ConversationReducer.reduce(old, command).copy(
            activeTurn = ActiveTurnState(handle.epoch, turnId, assistantMessageId, emptyList()),
        )
        val committed = persist(old, reduced, command)
        _snapshot.value = committed
        handle
    }

    /** Ready 会话所有结构性修改的唯一 Runtime 入口。 */
    internal suspend fun submit(
        command: ConversationCommand,
        persist: suspend (ConversationSnapshot, ConversationSnapshot, ConversationCommand) -> ConversationSnapshot,
    ): ConversationSnapshot {
        require(command !is StartTurn) { "use startTurn so the caller receives the TurnHandle" }
        return withCommandWrite {
            val old = _snapshot.value
            validateCommandOwner(old, command)
            val reduced = ConversationReducer.reduce(old, command)
            val durable = when (command) {
                is UpdateHeader,
                is UpdateTitleIfCurrent,
                is MoveToAssistant,
                TogglePinned,
                is CommitCheckpoint,
                is UpdateToolApproval,
                -> reduced.copy(activeTurn = old.activeTurn)
                is FinalizeTurn,
                is RecoverInterruptedTurn,
                -> reduced.copy(activeTurn = null)
                else -> reduced.copy(activeTurn = null)
            }
            val committed = persist(old, durable, command)
            _snapshot.update { latest ->
                when (command) {
                    is UpdateHeader,
                    is UpdateTitleIfCurrent,
                    is MoveToAssistant,
                    TogglePinned,
                    is CommitCheckpoint,
                    is UpdateToolApproval,
                    -> {
                        val latestActive = latest.activeTurn
                            ?.takeIf { active -> old.activeTurn?.sameOwner(active) == true }
                        // Approval decisions are durable first, but the resident projection must
                        // carry the same narrow change. Reapplying the pure reducer to the latest
                        // same-owner stream snapshot preserves deltas that arrived during IO.
                        val publishedActive = if (
                            (command is UpdateToolApproval ||
                                command is CommitCheckpoint) && latestActive != null
                        ) {
                            ConversationReducer.reduce(
                                committed.copy(activeTurn = latestActive),
                                command,
                            ).activeTurn
                        } else {
                            latestActive
                        }
                        val next = committed.copy(activeTurn = publishedActive)
                        if (next == latest) latest else next
                    }
                    else -> committed
                }
            }
            _snapshot.value
        }
    }

    private suspend fun <T> withCommandWrite(block: suspend () -> T): T {
        cancelIdleCheck()
        commandWrites.incrementAndGet()
        return try {
            // Mutex acquisition remains cancellable. Once this coroutine owns the single-writer
            // boundary, reduce -> durable commit -> resident publish is one indivisible unit.
            commandMutex.withLock {
                // Cancellation cannot interrupt an owned commit, but it may still be delivered
                // when control returns to the caller. Callers that own provisional resources must
                // therefore publish by durable roots or compensate with exact idempotent identity.
                withContext(NonCancellable) { block() }
            }
        } finally {
            check(commandWrites.decrementAndGet() >= 0) { "command writer count underflow: $id" }
            if (!isInUse) scheduleIdleCheck()
        }
    }

    /** Draft 尚无 durable aggregate；只允许编辑其内存 header，且绝不晋升 Ready。 */
    internal suspend fun updateDraftHeader(command: ConversationCommand): ConversationSnapshot = withCommandWrite {
        require(command is UpdateHeader || command is MoveToAssistant) { "command is not a draft header edit" }
        val old = _snapshot.value
        check(old.activeTurn == null) { "a draft cannot own an active turn" }
        ConversationReducer.reduce(old, command).also { updated -> _snapshot.value = updated }
    }

    private fun validateCommandOwner(snapshot: ConversationSnapshot, command: ConversationCommand) {
        when (command) {
            is CommitCheckpoint -> if (
                command.handle.conversationId != id || snapshot.activeTurn?.matches(command.handle) != true
            ) throw ConversationCommandConflictException("stale checkpoint for turn ${command.handle.turnId}")
            is FinalizeTurn -> if (
                command.handle.conversationId != id || snapshot.activeTurn?.matches(command.handle) != true
            ) throw ConversationCommandConflictException("stale finalization for turn ${command.handle.turnId}")
            is UpdateHeader -> Unit
            is UpdateTitleIfCurrent -> Unit
            is MoveToAssistant -> Unit
            TogglePinned -> Unit
            is UpdateToolApproval -> Unit
            is RecoverInterruptedTurn,
            -> if (snapshot.activeTurn != null) {
                throw ConversationCommandConflictException("recovery cannot overwrite an active turn")
            }
            else -> if (snapshot.activeTurn != null) {
                throw ConversationCommandConflictException(
                    "tree command ${command::class.simpleName} requires the active turn to finish first",
                )
            }
        }
    }

    private val commandWrites = AtomicInteger(0)

    /** 写通道占用中（submit 进行时），Registry 据此阻止 idle 回收 */
    fun isCommandWriteInFlight(): Boolean = commandWrites.get() > 0

    // ---- Generation job ownership and cancellation ----

    fun trackGenerationTurn(turnId: Uuid) {
        activeTurnId.set(turnId)
    }

    fun currentGenerationTurnId(): Uuid? = activeTurnId.get()

    fun requestCancel(turnId: Uuid, reason: String) {
        cancelReasons[turnId] = reason
    }

    fun consumeCancelReason(turnId: Uuid): String? = cancelReasons.remove(turnId)

    fun peekCancelReason(turnId: Uuid): String? = cancelReasons[turnId]

    // 空闲检查任务
    private var idleCheckJob: Job? = null

    private fun acquire(): Int = refCount.incrementAndGet().also {
        cancelIdleCheck()
        Log.d(TAG, "acquire $id (refs=$it)")
    }

    private fun release(): Int = refCount.decrementAndGet().also {
        check(it >= 0) {
            refCount.incrementAndGet()
            "unpaired conversation runtime release: $id"
        }
        Log.d(TAG, "release $id (refs=$it)")
        if (it == 0 && !isInUse) scheduleIdleCheck()
    }

    internal fun acquireLease(): ConversationRuntimeLease {
        acquire()
        return ConversationRuntimeLease(this)
    }

    internal fun releaseLease() {
        release()
    }

    // 作用域 API - 短请求（REST）
    fun <T> withRef(block: () -> T): T {
        val lease = acquireLease()
        try {
            return block()
        } finally {
            lease.close()
        }
    }

    fun setJob(job: Job?, turnId: Uuid? = null) {
        if (job != null) cancelIdleCheck()
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
                if (!isInUse) scheduleIdleCheck()
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
            delay(idleTimeoutMs)
            if (!isInUse) {
                onIdle(id)
            }
        }
    }

    /** Registry 安装后立即武装空闲逐出；短生命周期后台命令无需手写 acquire/release。 */
    internal fun armIdleEviction() {
        if (!isInUse) scheduleIdleCheck()
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
        ttsQueueSessionId = null
        idleCheckJob?.cancel()
        idleCheckJob = null
    }

}

/** Exactly-once observation lease; a caller can only release the reference it acquired. */
internal class ConversationRuntimeLease internal constructor(
    private val runtime: ConversationRuntime,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) runtime.releaseLease()
    }
}

private fun ActiveTurnState.matches(handle: TurnHandle): Boolean =
    epoch == handle.epoch &&
        turnId == handle.turnId &&
        assistantMessageId == handle.assistantMessageId

private fun ActiveTurnState.sameOwner(other: ActiveTurnState): Boolean =
    epoch == other.epoch && turnId == other.turnId && assistantMessageId == other.assistantMessageId

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
