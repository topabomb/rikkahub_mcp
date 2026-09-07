package net.weero.measix.pilot.service.runtime
import net.weero.measix.pilot.service.turn.TurnContext

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.data.ai.request.TurnModelContextProjection
import net.weero.measix.pilot.data.model.Conversation
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.uuid.Uuid

private const val TAG = "ConversationRuntime"
private const val IDLE_TIMEOUT_MS = 5_000L

internal data class InstalledTurnWorker(
    val turnId: Uuid,
    val previousTurnId: Uuid?,
    val previousWorker: Job?,
)

internal data class CapturedTurnWorker(
    val turnId: Uuid,
    val worker: Job?,
)

internal data class ActiveTurnPresentationFacts(
    val turnId: Uuid?,
    val phase: TurnLivePhase?,
    val handle: TurnHandle?,
    val processingText: String?,
)

/**
 * Resident conversation projection. Durable commands are planned and committed by
 * [ConversationCommandCoordinator]; this runtime only publishes snapshots, streaming
 * deltas and one private active turn.
 */
class ConversationRuntime internal constructor(
    val id: Uuid,
    initial: ConversationAggregateSnapshot,
    private val scope: CoroutineScope,
    private val onIdle: (Uuid) -> Unit,
    private val idleTimeoutMs: Long = IDLE_TIMEOUT_MS,
) {
    // 唯一事实流：durable 聚合 + 唯一高频流式投影
    private val _snapshot = MutableStateFlow(
        ConversationRuntimeSnapshot(durable = initial, stream = null),
    )

    // 原子引用计数
    private val refCount = AtomicInteger(0)

    /**
     * Process-local owner of one Conversation request. Durable CAS still uses [TurnHandle];
     * this machine never becomes a second write protocol.
     */
    private class ActiveTurnSession(
        val turnId: Uuid,
        val worker: Job,
        val previousWorker: Job? = null,
        val previousTurnId: Uuid? = null,
        handle: TurnHandle? = null,
        phase: TurnLivePhase = TurnLivePhase.PREPARING,
        turnContext: TurnContext? = null,
        modelContextProjection: TurnModelContextProjection? = null,
    ) {
        private val _phase = AtomicReference(phase)
        private val _handle = AtomicReference(handle)
        private val _turnContext = AtomicReference(turnContext)
        private val _modelContextProjection = AtomicReference(modelContextProjection)
        private val _cancelReason = AtomicReference<String?>(null)
        private val _processingText = AtomicReference<String?>(null)

        val handle: TurnHandle? get() = _handle.get()

        /**
         * 本 Turn 冻结的 `TurnContext`。它在 `StartTurn` 事务之后由 materialize 绑定，
         * 绑定前不存在合法读取者，故对外为必需（未绑定即读取是编程错误）。
         */
        val turnContext: TurnContext
            get() = requireNotNull(_turnContext.get()) { "turn context is missing for turn $turnId" }
        val modelContextProjection: TurnModelContextProjection? get() = _modelContextProjection.get()
        val processingText: String? get() = _processingText.get()
        val workerIdentity: Int get() = System.identityHashCode(worker)

        fun presentationPhase(): TurnLivePhase = _phase.get()

        fun bindTurnContext(context: TurnContext) {
            check(_turnContext.compareAndSet(null, context)) {
                "turn context is already bound for turn $turnId"
            }
        }

        fun bindModelContextProjection(projection: TurnModelContextProjection) {
            check(_modelContextProjection.compareAndSet(null, projection)) {
                "model context projection is already bound for turn $turnId"
            }
        }

        fun peekCancelReason(): String? = _cancelReason.get()

        fun consumeCancelReason(): String? = _cancelReason.getAndSet(null)

        fun requestCancel(reason: String) {
            _cancelReason.compareAndSet(null, reason)
            val current = _phase.get()
            _phase.compareAndSet(current, TurnLivePhase.STOPPING)
            worker.cancel()
        }

        fun markRunning(handle: TurnHandle) {
            check(handle.turnId == turnId) { "running handle ${handle.turnId} does not match turn $turnId" }
            _handle.set(handle)
            // Turn 已运行但尚未产出任何流式内容：初始子阶段是 PREPARING，由 loop 的 typed 阶段事件细化。
            _phase.set(TurnLivePhase.PREPARING)
        }

        fun markAwaitingUser(handle: TurnHandle) {
            check(handle.turnId == turnId) { "handle ${handle.turnId} does not match turn $turnId" }
            _handle.set(handle)
            _phase.set(TurnLivePhase.AWAITING_USER)
        }

        /**
         * loop 驱动的生成子阶段（PREPARING/MODEL_WAITING/MODEL_STREAMING/TOOL_PREPARING/TOOL_EXECUTING）。
         * AWAITING_USER 与 STOPPING 由 markAwaitingUser/requestCancel 拥有，loop 阶段不得覆盖；
         * 续跑时 markRunning 先把 AWAITING_USER 复位为 PREPARING，loop 才重新推进。
         */
        fun updateLivePhase(phase: TurnLivePhase) {
            val current = _phase.get()
            if (current == TurnLivePhase.STOPPING || current == TurnLivePhase.AWAITING_USER) return
            _phase.set(phase)
        }

        fun reportProcessingText(text: String?) {
            _processingText.set(text)
        }

        suspend fun awaitPreviousWorker() {
            previousWorker?.join()
        }
    }

    private val _activeTurn = MutableStateFlow<ActiveTurnSession?>(null)
    private val _activeTurnRevision = MutableStateFlow(0L)
    /**
     * Last request identity that was released or superseded. This is a read-only
     * lifecycle marker for receipt consumers; it is not a second turn state.
     */
    private val _lastTerminatedRequestTurnId = AtomicReference<Uuid?>(null)
    /**
     * Requests stay keyed by turnId until their worker completes so a superseded
     * owner can still read its own cancel reason. Removing on replace would make
     * TurnCommitter fall back to `user_stop`. A completed turnId is a no-op target.
     */
    private val ownedRequests = ConcurrentHashMap<Uuid, ActiveTurnSession>()
    internal val activeTurnRevision: StateFlow<Long> = _activeTurnRevision.asStateFlow()

    internal fun lastTerminatedRequestTurnId(): Uuid? = _lastTerminatedRequestTurnId.get()

    private val toolApprovalMutex = Mutex()
    private var ttsQueueSessionId: String? = null
    internal val isGenerating: Boolean get() = _activeTurn.value?.worker?.isActive == true
    val isInUse: Boolean
        get() = refCount.get() > 0 || _activeTurn.value != null || snapshot.value.stream != null

    private val turnEpoch = AtomicLong(0)

    // ---- snapshot 事实源 ----

    /** internal 事实流；UI 订阅的是 ConversationPresentationSnapshot（见 ConversationQueryService）。 */
    internal val snapshot: StateFlow<ConversationRuntimeSnapshot> = _snapshot.asStateFlow()

    /**
     * durable 聚合的即时读取入口：命令 planning、终态收口与 request 组装只读已提交树。
     * 它是 [snapshot] 单一事实流的 durable 视图，不是第二状态源；流式草稿经 [snapshot] 读取。
     */
    internal val durable: ConversationAggregateSnapshot get() = _snapshot.value.durable

    /**
     * 流式高频更新：非挂起、conflated、永不落库、不加锁。只换流式投影，durable 树保持共享引用。
     */
    fun applyStreamingDelta(handle: TurnHandle, assistantMessage: UIMessage): StreamingDeltaResult {
        if (handle.conversationId != id) return StreamingDeltaResult.STALE_TURN
        var applied = false
        _snapshot.update { current ->
            val stream = current.stream
            if (stream != null && stream.matches(handle)) {
                applied = true
                current.copy(stream = stream.withStreamingAssistant(assistantMessage))
            } else {
                applied = false
                current
            }
        }
        return if (applied) StreamingDeltaResult.APPLIED else StreamingDeltaResult.STALE_TURN
    }

    internal fun nextTurnEpoch(): Long = turnEpoch.incrementAndGet()

    internal fun publishDraft(snapshot: ConversationAggregateSnapshot) {
        check(snapshot.header.newConversation) { "publishDraft requires a Draft snapshot" }
        check(_snapshot.value.durable.header.newConversation) { "a Ready runtime cannot accept a Draft publish" }
        _snapshot.update { it.copy(durable = snapshot) }
    }

    /**
     * durable 提交只换 [ConversationRuntimeSnapshot.durable]；流式投影按命令语义独立演进：
     * StartTurn 建立空草稿、Finalize/Recover 清空、checkpoint/resolve 只在归属同一 Turn 的
     * 最新草稿上重放（[afterCheckpoint]/[afterResolve]），从而提交事务前到达的在途 delta 不被回退。
     * durable 与 stream 是两个正交字段，各自独立更新。
     */
    internal fun publishCommitted(
        command: ConversationCommand,
        committed: ConversationAggregateSnapshot,
    ) {
        _snapshot.update { latest ->
            val nextStream: TurnStreamProjection? = when (command) {
                is StartTurn -> TurnStreamProjection(
                    epoch = command.epoch,
                    turnId = command.turnId,
                    assistantMessageId = command.assistantMessageId,
                    assistantMessage = null,
                )

                is FinalizeTurn,
                is RecoverInterruptedTurn,
                -> null

                is TurnCheckpoint ->
                    latest.stream?.takeIf { it.matches(command.turn) }?.afterCheckpoint(command)
                        ?: latest.stream

                is ResolveToolInteraction ->
                    latest.stream?.takeIf { it.matches(command.handle) }?.afterResolve(command)
                        ?: latest.stream

                else -> latest.stream
            }
            val next = latest.copy(durable = committed, stream = nextStream)
            if (next == latest) latest else next
        }
    }

    // ---- Active turn ownership ----

    internal fun currentGenerationTurnId(): Uuid? = _activeTurn.value?.turnId

    internal fun previousTurnId(turnId: Uuid): Uuid? {
        val current = requireNotNull(_activeTurn.value) { "worker lost its active turn $turnId" }
        check(current.turnId == turnId) { "worker lost its active turn $turnId" }
        return current.previousTurnId
    }

    internal suspend fun awaitPreviousWorker(turnId: Uuid) {
        val current = requireNotNull(_activeTurn.value) { "worker lost its active turn $turnId" }
        check(current.turnId == turnId) { "worker lost its active turn $turnId" }
        current.awaitPreviousWorker()
    }

    internal fun isAwaitingUser(turnId: Uuid): Boolean {
        val current = _activeTurn.value ?: return false
        return current.turnId == turnId &&
            current.presentationPhase() == TurnLivePhase.AWAITING_USER
    }

    internal suspend fun awaitCurrentWorker() {
        _activeTurn.value?.worker?.join()
    }

    internal fun cancelActiveGeneration(reason: String): Job? {
        val current = _activeTurn.value ?: return null
        current.requestCancel(reason)
        return current.worker
    }

    internal fun currentWorker(): Job? = _activeTurn.value?.worker

    /** Binds the immutable request context once to the exact worker that owns this Turn. */
    internal fun bindTurnContext(turnId: Uuid, worker: Job, context: TurnContext) {
        val current = _activeTurn.value
        check(current != null && current.turnId == turnId && current.worker === worker) {
            "turn context owner does not match turn $turnId"
        }
        current.bindTurnContext(context)
    }

    /** Continuations and retries must reuse the context owned by their exact active worker. */
    internal fun requireTurnContext(turnId: Uuid, worker: Job): TurnContext {
        val current = _activeTurn.value
        check(current != null && current.turnId == turnId && current.worker === worker) {
            "turn context owner does not match turn $turnId"
        }
        return current.turnContext
    }

    /**
     * Binds the START-frozen model-context projection once, right after the StartTurn
     * transaction commits. Continuations reuse it instead of re-evaluating the
     * applicability predicate.
     */
    internal fun bindModelContextProjection(
        turnId: Uuid,
        worker: Job,
        projection: TurnModelContextProjection,
    ) {
        val current = _activeTurn.value
        check(current != null && current.turnId == turnId && current.worker === worker) {
            "model context projection owner does not match turn $turnId"
        }
        current.bindModelContextProjection(projection)
    }

    internal fun requireTurnModelContextProjection(turnId: Uuid, worker: Job): TurnModelContextProjection {
        val current = _activeTurn.value
        check(current != null && current.turnId == turnId && current.worker === worker) {
            "model context projection owner does not match turn $turnId"
        }
        return requireNotNull(current.modelContextProjection) {
            "model context projection is missing for turn $turnId"
        }
    }

    internal fun activeTurnPresentationFacts(): ActiveTurnPresentationFacts? {
        val current = _activeTurn.value ?: return null
        return ActiveTurnPresentationFacts(
            turnId = current.turnId,
            phase = current.presentationPhase(),
            handle = current.handle,
            processingText = current.processingText,
        )
    }

    internal fun requestCancel(turnId: Uuid, reason: String) {
        ownedRequests[turnId]?.requestCancel(reason)
    }

    internal fun consumeCancelReason(turnId: Uuid): String? =
        ownedRequests[turnId]?.consumeCancelReason()

    internal fun peekCancelReason(turnId: Uuid): String? =
        ownedRequests[turnId]?.peekCancelReason()

    /**
     * Captures one active-turn identity and requests its stop.
     * Job and turnId come from the same object so a concurrent START cannot mix them.
     */
    internal fun captureAndRequestStop(reason: String): CapturedTurnWorker? {
        val current = _activeTurn.value
        if (current != null) {
            current.requestCancel(reason)
            return CapturedTurnWorker(current.turnId, current.worker)
        }
        val durableTurnId = snapshot.value.stream?.turnId ?: return null
        return CapturedTurnWorker(durableTurnId, null)
    }

    internal fun installTurnWorker(
        turnId: Uuid,
        worker: Job,
        handle: TurnHandle? = null,
        phase: TurnLivePhase = TurnLivePhase.PREPARING,
        supersedeReason: String? = null,
        turnContext: TurnContext? = null,
        modelContextProjection: TurnModelContextProjection? = null,
    ): InstalledTurnWorker {
        cancelIdleCheck()
        val previous = _activeTurn.value
        if (previous != null && previous.turnId != turnId) {
            previous.requestCancel(supersedeReason ?: "superseded_by_new_turn")
            _lastTerminatedRequestTurnId.set(previous.turnId)
        }
        val installed = ActiveTurnSession(
            turnId = turnId,
            worker = worker,
            previousWorker = previous?.worker,
            previousTurnId = previous?.turnId,
            handle = handle,
            phase = phase,
            turnContext = turnContext,
            modelContextProjection = modelContextProjection,
        )
        ownedRequests[turnId] = installed
        publishActive(installed)
        worker.invokeOnCompletion {
            completeActiveWorker(installed)
        }
        return InstalledTurnWorker(turnId, previous?.turnId, previous?.worker)
    }

    internal fun markRunning(handle: TurnHandle) {
        val current = requireNotNull(_activeTurn.value) { "no active turn to mark running" }
        check(current.turnId == handle.turnId) {
            "running handle ${handle.turnId} does not match active turn ${current.turnId}"
        }
        current.markRunning(handle)
        publishActive(current)
    }

    internal fun retainAwaitingUser(handle: TurnHandle) {
        val current = _activeTurn.value ?: return
        if (current.turnId != handle.turnId) return
        current.markAwaitingUser(handle)
        publishActive(current)
    }

    /**
     * Replaces the completed user-paused worker with the continuation worker.
     * A mismatched owner, phase or handle is rejected without cancelling the current request.
     */
    internal fun continueAwaitingUser(handle: TurnHandle, worker: Job): InstalledTurnWorker {
        val current = _activeTurn.value
        val stream = snapshot.value.stream
        if (
            current == null ||
            current.turnId != handle.turnId ||
            current.presentationPhase() != TurnLivePhase.AWAITING_USER ||
            current.handle != handle ||
            stream == null ||
            !stream.matches(handle)
        ) {
            throw ConversationCommandConflictException(
                "continuation ${handle.turnId} is not the current awaiting owner",
            )
        }
        val context = current.turnContext
        val projection = requireNotNull(current.modelContextProjection) {
            "continuation ${handle.turnId} has no model context projection"
        }
        return installTurnWorker(
            turnId = handle.turnId,
            worker = worker,
            handle = handle,
            phase = TurnLivePhase.PREPARING,
            turnContext = context,
            modelContextProjection = projection,
        )
    }

    internal fun processingReporter(): (String?) -> Unit {
        val current = _activeTurn.value ?: return {}
        val turnId = current.turnId
        val workerIdentity = current.workerIdentity
        return { text -> reportProcessingText(turnId, workerIdentity, text) }
    }

    internal fun reportProcessingText(turnId: Uuid, workerIdentity: Int, text: String?) {
        val current = _activeTurn.value ?: return
        if (current.turnId != turnId || current.workerIdentity != workerIdentity) return
        current.reportProcessingText(text)
        publishActive(current)
    }

    /** Captures the current owner identity so loop phase events only advance this exact Turn's live phase. */
    internal fun livePhaseReporter(): (TurnLivePhase) -> Unit {
        val current = _activeTurn.value ?: return {}
        val turnId = current.turnId
        val workerIdentity = current.workerIdentity
        return { phase -> updateLivePhase(turnId, workerIdentity, phase) }
    }

    internal fun updateLivePhase(turnId: Uuid, workerIdentity: Int, phase: TurnLivePhase) {
        val current = _activeTurn.value ?: return
        if (current.turnId != turnId || current.workerIdentity != workerIdentity) return
        current.updateLivePhase(phase)
        publishActive(current)
    }

    internal fun releaseTurnWorker(
        turnId: Uuid,
        worker: Job? = null,
        retainAwaitingOwner: Boolean = true,
    ) {
        val current = _activeTurn.value ?: return
        if (current.turnId != turnId) return
        if (worker != null && current.worker !== worker) return
        if (
            retainAwaitingOwner &&
            current.presentationPhase() == TurnLivePhase.AWAITING_USER &&
            worker == null &&
            snapshot.value.stream?.turnId == turnId
        ) return
        if (_activeTurn.compareAndSet(current, null)) {
            ownedRequests.remove(turnId, current)
            _lastTerminatedRequestTurnId.set(turnId)
            _activeTurnRevision.value++
            if (!isInUse) scheduleIdleCheck()
        }
    }

    private fun publishActive(request: ActiveTurnSession?) {
        _activeTurn.value = request
        _activeTurnRevision.value++
    }

    private fun completeActiveWorker(request: ActiveTurnSession) {
        val current = _activeTurn.value
        if (current === request) {
            when (current.presentationPhase()) {
                TurnLivePhase.AWAITING_USER,
                TurnLivePhase.STOPPING,
                -> publishActive(current)
                else -> releaseTurnWorker(current.turnId, current.worker)
            }
            return
        }
        ownedRequests.remove(request.turnId, request)
    }

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
        _activeTurn.value?.worker?.cancel()
        _activeTurn.value = null
        ownedRequests.clear()
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

internal fun TurnStreamProjection.matches(handle: TurnHandle): Boolean =
    epoch == handle.epoch &&
        turnId == handle.turnId &&
        assistantMessageId == handle.assistantMessageId

internal fun Conversation.toSnapshot(
    modelContextEntries: List<net.weero.measix.pilot.data.model.ConversationModelContextEntry> = emptyList(),
): ConversationAggregateSnapshot = ConversationAggregateSnapshot(
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
    // durable context 只进入 internal aggregate，不进入 public Conversation。
    modelContextEntries = modelContextEntries,
)
