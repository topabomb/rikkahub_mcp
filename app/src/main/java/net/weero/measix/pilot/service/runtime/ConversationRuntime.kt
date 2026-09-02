package net.weero.measix.pilot.service.runtime

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
import net.weero.measix.pilot.data.ai.TurnModelContextProjection
import net.weero.measix.pilot.data.model.Conversation
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.uuid.Uuid

private const val TAG = "ConversationRuntime"
private const val IDLE_TIMEOUT_MS = 5_000L

internal data class InstalledActiveRequest(
    val turnId: Uuid,
    val previousTurnId: Uuid?,
    val previousWorker: Job?,
)

internal data class CapturedActiveRequest(
    val turnId: Uuid,
    val worker: Job?,
)

internal data class ActiveRequestPresentationFacts(
    val turnId: Uuid?,
    val phase: ConversationTurnPhase?,
    val handle: TurnHandle?,
    val processingText: String?,
)

/**
 * Resident conversation projection. Durable commands are planned and committed by
 * [ConversationCommandCoordinator]; this runtime only publishes snapshots, streaming
 * deltas and one private active request.
 */
class ConversationRuntime internal constructor(
    val id: Uuid,
    initial: ConversationAggregateSnapshot,
    private val scope: CoroutineScope,
    private val onIdle: (Uuid) -> Unit,
    private val idleTimeoutMs: Long = IDLE_TIMEOUT_MS,
) {
    // 唯一事实流
    private val _snapshot = MutableStateFlow(initial)

    // 原子引用计数
    private val refCount = AtomicInteger(0)

    /**
     * Process-local owner of one Conversation request. Durable CAS still uses [TurnHandle];
     * this machine never becomes a second write protocol.
     */
    private class ActiveTurnRuntime(
        val turnId: Uuid,
        val worker: Job,
        val previousWorker: Job? = null,
        val previousTurnId: Uuid? = null,
        handle: TurnHandle? = null,
        phase: ConversationTurnPhase = ConversationTurnPhase.PREPARING,
        requestContext: TurnRequestContext? = null,
        modelContextProjection: TurnModelContextProjection? = null,
    ) {
        private val _phase = AtomicReference(phase)
        private val _handle = AtomicReference(handle)
        private val _requestContext = AtomicReference(requestContext)
        private val _modelContextProjection = AtomicReference(modelContextProjection)
        private val _cancelReason = AtomicReference<String?>(null)
        private val _processingText = AtomicReference<String?>(null)

        val handle: TurnHandle? get() = _handle.get()
        val requestContext: TurnRequestContext? get() = _requestContext.get()
        val modelContextProjection: TurnModelContextProjection? get() = _modelContextProjection.get()
        val processingText: String? get() = _processingText.get()
        val workerIdentity: Int get() = System.identityHashCode(worker)

        fun presentationPhase(): ConversationTurnPhase = _phase.get()

        fun bindRequestContext(context: TurnRequestContext) {
            check(_requestContext.compareAndSet(null, context)) {
                "request context is already bound for turn $turnId"
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
            _phase.compareAndSet(current, ConversationTurnPhase.STOPPING)
            worker.cancel()
        }

        fun markRunning(handle: TurnHandle) {
            check(handle.turnId == turnId) { "running handle ${handle.turnId} does not match request $turnId" }
            _handle.set(handle)
            _phase.set(ConversationTurnPhase.GENERATING)
        }

        fun markAwaitingApproval(handle: TurnHandle) {
            check(handle.turnId == turnId) { "approval handle ${handle.turnId} does not match request $turnId" }
            _handle.set(handle)
            _phase.set(ConversationTurnPhase.AWAITING_USER)
        }

        fun reportProcessingText(text: String?) {
            _processingText.set(text)
        }

        suspend fun awaitPreviousWorker() {
            previousWorker?.join()
        }
    }

    private val _activeRequest = MutableStateFlow<ActiveTurnRuntime?>(null)
    private val _activeRequestRevision = MutableStateFlow(0L)
    /**
     * Last request identity that was released or superseded. This is a read-only
     * lifecycle marker for receipt consumers; it is not a second turn state.
     */
    private val _lastTerminatedRequestTurnId = AtomicReference<Uuid?>(null)
    /**
     * Requests stay keyed by turnId until their worker completes so a superseded
     * owner can still read its own cancel reason. Removing on replace would make
     * TurnEngine fall back to `user_stop`. A completed turnId is a no-op target.
     */
    private val ownedRequests = ConcurrentHashMap<Uuid, ActiveTurnRuntime>()
    internal val activeRequestRevision: StateFlow<Long> = _activeRequestRevision.asStateFlow()

    internal fun lastTerminatedRequestTurnId(): Uuid? = _lastTerminatedRequestTurnId.get()

    private val toolApprovalMutex = Mutex()
    private var ttsQueueSessionId: String? = null
    internal val isGenerating: Boolean get() = _activeRequest.value?.worker?.isActive == true
    val isInUse: Boolean
        get() = refCount.get() > 0 || _activeRequest.value != null || snapshot.value.activeTurn != null

    private val turnEpoch = AtomicLong(0)

    // ---- snapshot 事实源 ----

    /** internal 事实流；UI 订阅的是 ConversationPresentationSnapshot（见 ConversationQueryService）。 */
    internal val snapshot: StateFlow<ConversationAggregateSnapshot> = _snapshot.asStateFlow()

    /**
     * 流式高频更新：非挂起、conflated、永不落库、不加锁。只更新 activeTurn 展示态。
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

    internal fun nextTurnEpoch(): Long = turnEpoch.incrementAndGet()

    internal fun publishDraft(snapshot: ConversationAggregateSnapshot) {
        check(snapshot.header.newConversation) { "publishDraft requires a Draft snapshot" }
        check(_snapshot.value.header.newConversation) { "a Ready runtime cannot accept a Draft publish" }
        _snapshot.value = snapshot
    }

    internal fun publishCommitted(
        old: ConversationAggregateSnapshot,
        command: ConversationCommand,
        committed: ConversationAggregateSnapshot,
    ) {
        _snapshot.update { latest ->
            when (command) {
                is HeaderConversationCommand,
                is CommitCheckpoint,
                is ResolveToolInteraction,
                -> {
                    val latestActive = latest.activeTurn
                        ?.takeIf { active -> old.activeTurn?.sameOwner(active) == true }
                    val publishedActive = if (
                        (command is ResolveToolInteraction || command is CommitCheckpoint) &&
                        latestActive != null
                    ) {
                        ConversationTransition.apply(
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
    }

    // ---- Active request ownership ----

    internal fun currentGenerationTurnId(): Uuid? = _activeRequest.value?.turnId

    internal fun previousTurnId(turnId: Uuid): Uuid? {
        val current = requireNotNull(_activeRequest.value) { "worker lost its active request $turnId" }
        check(current.turnId == turnId) { "worker lost its active request $turnId" }
        return current.previousTurnId
    }

    internal suspend fun awaitPreviousWorker(turnId: Uuid) {
        val current = requireNotNull(_activeRequest.value) { "worker lost its active request $turnId" }
        check(current.turnId == turnId) { "worker lost its active request $turnId" }
        current.awaitPreviousWorker()
    }

    internal fun isAwaitingApproval(turnId: Uuid): Boolean {
        val current = _activeRequest.value ?: return false
        return current.turnId == turnId &&
            current.presentationPhase() == ConversationTurnPhase.AWAITING_USER
    }

    internal suspend fun awaitCurrentWorker() {
        _activeRequest.value?.worker?.join()
    }

    internal fun cancelActiveGeneration(reason: String): Job? {
        val current = _activeRequest.value ?: return null
        current.requestCancel(reason)
        return current.worker
    }

    internal fun currentWorker(): Job? = _activeRequest.value?.worker

    /** Binds the immutable request context once to the exact worker that owns this Turn. */
    internal fun bindTurnRequestContext(turnId: Uuid, worker: Job, context: TurnRequestContext) {
        val current = _activeRequest.value
        check(current != null && current.turnId == turnId && current.worker === worker) {
            "request context owner does not match turn $turnId"
        }
        current.bindRequestContext(context)
    }

    /** Continuations and retries must reuse the context owned by their exact active worker. */
    internal fun requireTurnRequestContext(turnId: Uuid, worker: Job): TurnRequestContext {
        val current = _activeRequest.value
        check(current != null && current.turnId == turnId && current.worker === worker) {
            "request context owner does not match turn $turnId"
        }
        return requireNotNull(current.requestContext) { "request context is missing for turn $turnId" }
    }

    /**
     * Binds the START-frozen model-context projection once, right after the StartTurn
     * transaction commits. Continuations reuse it instead of re-evaluating the
     * applicability predicate (权威方案 §7.3).
     */
    internal fun bindModelContextProjection(
        turnId: Uuid,
        worker: Job,
        projection: TurnModelContextProjection,
    ) {
        val current = _activeRequest.value
        check(current != null && current.turnId == turnId && current.worker === worker) {
            "model context projection owner does not match turn $turnId"
        }
        current.bindModelContextProjection(projection)
    }

    internal fun requireTurnModelContextProjection(turnId: Uuid, worker: Job): TurnModelContextProjection {
        val current = _activeRequest.value
        check(current != null && current.turnId == turnId && current.worker === worker) {
            "model context projection owner does not match turn $turnId"
        }
        return requireNotNull(current.modelContextProjection) {
            "model context projection is missing for turn $turnId"
        }
    }

    internal fun activeRequestPresentationFacts(): ActiveRequestPresentationFacts? {
        val current = _activeRequest.value ?: return null
        return ActiveRequestPresentationFacts(
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
     * Captures one active-request identity and requests its stop.
     * Job and turnId come from the same object so a concurrent START cannot mix them.
     */
    internal fun captureAndRequestStop(reason: String): CapturedActiveRequest? {
        val current = _activeRequest.value
        if (current != null) {
            current.requestCancel(reason)
            return CapturedActiveRequest(current.turnId, current.worker)
        }
        val durableTurnId = snapshot.value.activeTurn?.turnId ?: return null
        return CapturedActiveRequest(durableTurnId, null)
    }

    internal fun installActiveRequest(
        turnId: Uuid,
        worker: Job,
        handle: TurnHandle? = null,
        phase: ConversationTurnPhase = ConversationTurnPhase.PREPARING,
        supersedeReason: String? = null,
        requestContext: TurnRequestContext? = null,
        modelContextProjection: TurnModelContextProjection? = null,
    ): InstalledActiveRequest {
        cancelIdleCheck()
        val previous = _activeRequest.value
        if (previous != null && previous.turnId != turnId) {
            previous.requestCancel(supersedeReason ?: "superseded_by_new_turn")
            _lastTerminatedRequestTurnId.set(previous.turnId)
        }
        val installed = ActiveTurnRuntime(
            turnId = turnId,
            worker = worker,
            previousWorker = previous?.worker,
            previousTurnId = previous?.turnId,
            handle = handle,
            phase = phase,
            requestContext = requestContext,
            modelContextProjection = modelContextProjection,
        )
        ownedRequests[turnId] = installed
        publishActive(installed)
        worker.invokeOnCompletion {
            completeActiveWorker(installed)
        }
        return InstalledActiveRequest(turnId, previous?.turnId, previous?.worker)
    }

    internal fun markRunning(handle: TurnHandle) {
        val current = requireNotNull(_activeRequest.value) { "no active request to mark running" }
        check(current.turnId == handle.turnId) {
            "running handle ${handle.turnId} does not match active request ${current.turnId}"
        }
        current.markRunning(handle)
        publishActive(current)
    }

    internal fun retainAwaitingApproval(handle: TurnHandle) {
        val current = _activeRequest.value ?: return
        if (current.turnId != handle.turnId) return
        current.markAwaitingApproval(handle)
        publishActive(current)
    }

    /**
     * Replaces the completed approval-paused worker with the continuation worker.
     * A mismatched owner, phase or handle is rejected without cancelling the current request.
     */
    internal fun continueAwaitingApproval(handle: TurnHandle, worker: Job): InstalledActiveRequest {
        val current = _activeRequest.value
        val durable = snapshot.value.activeTurn
        if (
            current == null ||
            current.turnId != handle.turnId ||
            current.presentationPhase() != ConversationTurnPhase.AWAITING_USER ||
            current.handle != handle ||
            durable == null ||
            !durable.matches(handle)
        ) {
            throw ConversationCommandConflictException(
                "approval continuation ${handle.turnId} is not the current awaiting owner",
            )
        }
        val context = requireNotNull(current.requestContext) {
            "approval continuation ${handle.turnId} has no request context"
        }
        val projection = requireNotNull(current.modelContextProjection) {
            "approval continuation ${handle.turnId} has no model context projection"
        }
        return installActiveRequest(
            turnId = handle.turnId,
            worker = worker,
            handle = handle,
            phase = ConversationTurnPhase.PREPARING,
            requestContext = context,
            modelContextProjection = projection,
        )
    }

    internal fun processingReporter(): (String?) -> Unit {
        val current = _activeRequest.value ?: return {}
        val turnId = current.turnId
        val workerIdentity = current.workerIdentity
        return { text -> reportProcessingText(turnId, workerIdentity, text) }
    }

    internal fun reportProcessingText(turnId: Uuid, workerIdentity: Int, text: String?) {
        val current = _activeRequest.value ?: return
        if (current.turnId != turnId || current.workerIdentity != workerIdentity) return
        current.reportProcessingText(text)
        publishActive(current)
    }

    internal fun releaseActiveRequest(
        turnId: Uuid,
        worker: Job? = null,
        retainAwaitingOwner: Boolean = true,
    ) {
        val current = _activeRequest.value ?: return
        if (current.turnId != turnId) return
        if (worker != null && current.worker !== worker) return
        if (
            retainAwaitingOwner &&
            current.presentationPhase() == ConversationTurnPhase.AWAITING_USER &&
            worker == null &&
            snapshot.value.activeTurn?.turnId == turnId
        ) return
        if (_activeRequest.compareAndSet(current, null)) {
            ownedRequests.remove(turnId, current)
            _lastTerminatedRequestTurnId.set(turnId)
            _activeRequestRevision.value++
            if (!isInUse) scheduleIdleCheck()
        }
    }

    private fun publishActive(request: ActiveTurnRuntime?) {
        _activeRequest.value = request
        _activeRequestRevision.value++
    }

    private fun completeActiveWorker(request: ActiveTurnRuntime) {
        val current = _activeRequest.value
        if (current === request) {
            when (current.presentationPhase()) {
                ConversationTurnPhase.AWAITING_USER,
                ConversationTurnPhase.STOPPING,
                -> publishActive(current)
                else -> releaseActiveRequest(current.turnId, current.worker)
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
        _activeRequest.value?.worker?.cancel()
        _activeRequest.value = null
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

internal fun ActiveTurnState.matches(handle: TurnHandle): Boolean =
    epoch == handle.epoch &&
        turnId == handle.turnId &&
        assistantMessageId == handle.assistantMessageId

private fun ActiveTurnState.sameOwner(other: ActiveTurnState): Boolean =
    epoch == other.epoch && turnId == other.turnId && assistantMessageId == other.assistantMessageId

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
    activeTurn = null,
    // durable context 只进入 internal aggregate，不进入 public Conversation。
    modelContextEntries = modelContextEntries,
)
