package net.weero.measix.pilot.service.runtime

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.repository.ConversationRepository
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "ConversationRuntimeRegistry"

sealed interface ConversationRuntimeState {
    data object Loading : ConversationRuntimeState
    data class Draft(val runtime: ConversationRuntime) : ConversationRuntimeState
    data class Ready(val runtime: ConversationRuntime) : ConversationRuntimeState
    data object Missing : ConversationRuntimeState
    data class Failed(val error: Throwable) : ConversationRuntimeState
}

class ConversationNotFoundException(id: Uuid) :
    IllegalStateException("conversation does not exist: $id")

/**
 * Owns resident conversation runtimes. Durable conversations publish [ConversationRuntimeState.Ready];
 * a new-chat screen publishes an explicit non-durable [ConversationRuntimeState.Draft] and is
 * promoted only with its first user-message transaction. Loading/Missing/Failed never fabricate a
 * usable conversation snapshot.
 */
class ConversationRuntimeRegistry(
    private val appScope: AppScope,
    private val repository: ConversationRepository,
    private val operationLocks: ConversationOperationLocks,
    private val idleTimeoutMs: Long = 5_000L,
) {
    private data class Entry(
        val state: MutableStateFlow<ConversationRuntimeState> = MutableStateFlow(ConversationRuntimeState.Loading),
        val loadMutex: Mutex = Mutex(),
    )

    private val entries = ConcurrentHashMap<Uuid, Entry>()
    private val _runtimesVersion = MutableStateFlow(0L)

    fun observeRuntimeState(conversationId: Uuid): StateFlow<ConversationRuntimeState> {
        val entry = entries.computeIfAbsent(conversationId) { Entry() }
        if (entry.state.value == ConversationRuntimeState.Loading) {
            appScope.launch {
                try {
                    loadRuntime(conversationId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: ConversationNotFoundException) {
                    // loadRuntime has already published Missing.
                } catch (error: Exception) {
                    entry.state.value = ConversationRuntimeState.Failed(error)
                }
            }
        }
        return entry.state.asStateFlow()
    }

    suspend fun loadRuntime(conversationId: Uuid): ConversationRuntime {
        return operationLocks.withLock(conversationId) {
            loadRuntimeLocked(conversationId)
        }
    }

    private suspend fun loadRuntimeLocked(conversationId: Uuid): ConversationRuntime {
        val entry = entries.computeIfAbsent(conversationId) { Entry() }
        entry.state.value.runtimeOrNull()?.let { return it }
        return entry.loadMutex.withLock {
            entry.state.value.runtimeOrNull()?.let { return@withLock it }
            entry.state.value = ConversationRuntimeState.Loading
            val conversation = try {
                repository.getConversationSnapshotById(conversationId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                entry.state.value = ConversationRuntimeState.Failed(error)
                throw error
            }
            if (conversation == null) {
                entry.state.value = ConversationRuntimeState.Missing
                throw ConversationNotFoundException(conversationId)
            }
            installReadyRuntime(entry, conversation)
        }
    }

    /** Installs a conversation only after its create/import transaction has committed. */
    suspend fun registerRuntime(conversation: Conversation): ConversationRuntime =
        operationLocks.withLock(conversation.id) {
            val entry = entries.computeIfAbsent(conversation.id) { Entry() }
            entry.loadMutex.withLock {
                when (val current = entry.state.value) {
                    is ConversationRuntimeState.Ready -> {
                        check(current.runtime.snapshot.value.durable.header.id == conversation.id)
                        current.runtime
                    }
                    is ConversationRuntimeState.Draft -> error(
                        "durable runtime cannot replace a registered draft: ${conversation.id}",
                    )
                    else -> installReadyRuntime(entry, conversation)
                }
            }
        }

    /** Installs an internal aggregate only after its create transaction has committed. */
    internal suspend fun registerSnapshot(snapshot: ConversationAggregateSnapshot): ConversationRuntime =
        operationLocks.withLock(snapshot.conversationId) {
            val entry = entries.computeIfAbsent(snapshot.conversationId) { Entry() }
            entry.loadMutex.withLock {
                when (val current = entry.state.value) {
                    is ConversationRuntimeState.Ready -> current.runtime
                    is ConversationRuntimeState.Draft -> error(
                        "durable runtime cannot replace a registered draft: ${snapshot.conversationId}",
                    )
                    else -> installReadyRuntime(entry, snapshot)
                }
            }
        }

    /** Registers a non-durable new-chat draft. It is evicted without persistence when unused. */
    internal suspend fun installDraft(conversation: Conversation): ConversationRuntime =
        operationLocks.withLock(conversation.id) {
            val entry = entries.computeIfAbsent(conversation.id) { Entry() }
            entry.loadMutex.withLock {
                when (val current = entry.state.value) {
                    is ConversationRuntimeState.Draft -> current.runtime
                    is ConversationRuntimeState.Ready -> error(
                        "draft cannot replace a durable runtime: ${conversation.id}",
                    )
                    else -> installRuntime(entry, conversation.toSnapshot(), draft = true)
                }
            }
        }

    suspend fun promoteDraft(conversationId: Uuid, runtime: ConversationRuntime) =
        operationLocks.withLock(conversationId) {
            val entry = entries[conversationId]
                ?: error("draft runtime entry is missing: $conversationId")
            val current = entry.state.value
            check(current is ConversationRuntimeState.Draft && current.runtime === runtime) {
                "runtime is not the registered draft: $conversationId"
            }
            entry.state.value = ConversationRuntimeState.Ready(runtime)
            _runtimesVersion.value++
        }

    fun isDraft(conversationId: Uuid): Boolean =
        entries[conversationId]?.state?.value is ConversationRuntimeState.Draft

    fun findRuntime(conversationId: Uuid): ConversationRuntime? =
        entries[conversationId]?.state?.value.runtimeOrNull()

    fun requireRuntime(conversationId: Uuid): ConversationRuntime =
        findRuntime(conversationId) ?: error("conversation runtime is not Ready: $conversationId")

    internal suspend fun acquireRuntime(conversationId: Uuid): ConversationRuntimeLease =
        loadRuntime(conversationId).acquireLease()

    internal suspend fun acquireRegisteredRuntime(
        conversationId: Uuid,
        expected: ConversationRuntime,
    ): ConversationRuntimeLease = operationLocks.withLock(conversationId) {
        check(findRuntime(conversationId) === expected) { "registered runtime changed before acquire: $conversationId" }
        expected.acquireLease()
    }

    internal suspend fun installAndStartTurnWorker(
        conversationId: Uuid,
        turnId: Uuid,
        worker: Job,
        handle: TurnHandle? = null,
        phase: TurnLivePhase = TurnLivePhase.PREPARING,
        supersedeReason: String? = null,
    ): InstalledTurnWorker = operationLocks.withLock(conversationId) {
        val runtime = findRuntime(conversationId)
            ?: error("conversation runtime is not resident: $conversationId")
        val installed = runtime.installTurnWorker(
            turnId = turnId,
            worker = worker,
            handle = handle,
            phase = phase,
            supersedeReason = supersedeReason,
        )
        worker.start()
        installed
    }

    /**
     * Continues a user-paused turn under the conversation operation lock.
     * A newer START that already replaced the owner is rejected without cancelling it.
     */
    internal suspend fun installAndStartUserInteractionContinuation(
        conversationId: Uuid,
        handle: TurnHandle,
        worker: Job,
    ): InstalledTurnWorker = operationLocks.withLock(conversationId) {
        check(handle.conversationId == conversationId) {
            "user-interaction continuation ${handle.turnId} belongs to ${handle.conversationId}"
        }
        val runtime = findRuntime(conversationId)
            ?: error("conversation runtime is not resident: $conversationId")
        val installed = runtime.continueAwaitingUser(handle, worker)
        worker.start()
        installed
    }

    fun getTurnPresentationFlow(conversationId: Uuid): Flow<ConversationPresentation> =
        observeRuntimeState(conversationId).flatMapLatest { state ->
            state.runtimeOrNull()?.let { runtime ->
                combine(runtime.activeTurnRevision, runtime.snapshot) { _, snapshot ->
                    resolveConversationPresentation(
                        runtime.activeTurnPresentationFacts(),
                        snapshot,
                        runtime.lastTerminatedRequestTurnId(),
                    )
                }
            } ?: flowOf(ConversationPresentation.IDLE)
        }

    /**
     * Snapshot and turn presentation joined at the Runtime owner. Consumers that need to
     * correlate a receipt with its durable target must not combine two independent UI flows.
     */
    internal fun getConversationUiFlow(
        conversationId: Uuid,
    ): Flow<Pair<ConversationRuntimeSnapshot, ConversationPresentation>> =
        observeRuntimeState(conversationId).flatMapLatest { state ->
            state.runtimeOrNull()?.let { runtime ->
                combine(runtime.snapshot, runtime.activeTurnRevision) { snapshot, _ ->
                    snapshot to resolveConversationPresentation(
                        runtime.activeTurnPresentationFacts(),
                        snapshot,
                        runtime.lastTerminatedRequestTurnId(),
                    )
                }
            } ?: emptyFlow()
        }

    fun getConversationTurnPresentations(): Flow<Map<Uuid, ConversationPresentation>> =
        _runtimesVersion.flatMapLatest {
            val current = activeRuntimes()
            if (current.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(current.map { runtime ->
                    combine(runtime.activeTurnRevision, runtime.snapshot) { _, snapshot ->
                        runtime.id to resolveConversationPresentation(
                            runtime.activeTurnPresentationFacts(),
                            snapshot,
                            runtime.lastTerminatedRequestTurnId(),
                        )
                    }
                }) { pairs ->
                    pairs.filter { (_, presentation) -> presentation != ConversationPresentation.IDLE }.toMap()
                }
            }
        }

    fun activeRuntimes(): List<ConversationRuntime> = entries.values.mapNotNull { entry ->
        entry.state.value.runtimeOrNull()
    }

    suspend fun evictRuntime(conversationId: Uuid) = operationLocks.withLock(conversationId) {
        val entry = entries[conversationId] ?: return@withLock
        val runtime = entry.state.value.runtimeOrNull()
        entry.state.value = ConversationRuntimeState.Missing
        runtime?.cleanup()
        _runtimesVersion.value++
    }

    suspend fun cancelGenerationsForAssistant(assistantId: Uuid, reason: String) {
        val jobs = activeRuntimes()
            .filter { it.snapshot.value.durable.header.assistantId == assistantId }
            .mapNotNull { runtime ->
                runtime.cancelActiveGeneration(reason)
            }
            .distinct()
        jobs.joinAll()
    }

    private fun installReadyRuntime(
        entry: Entry,
        snapshot: ConversationAggregateSnapshot,
    ): ConversationRuntime = installRuntime(entry, snapshot, draft = false)

    private fun installReadyRuntime(entry: Entry, conversation: Conversation): ConversationRuntime =
        installRuntime(entry, conversation.toSnapshot(), draft = false)

    private fun installRuntime(
        entry: Entry,
        snapshot: ConversationAggregateSnapshot,
        draft: Boolean,
    ): ConversationRuntime {
        val runtime = ConversationRuntime(
            id = snapshot.conversationId,
            initial = snapshot,
            scope = appScope,
            onIdle = ::removeIdleRuntime,
            idleTimeoutMs = idleTimeoutMs,
        )
        entry.state.value = if (draft) {
            ConversationRuntimeState.Draft(runtime)
        } else {
            ConversationRuntimeState.Ready(runtime)
        }
        _runtimesVersion.value++
        runtime.armIdleEviction()
        Log.i(TAG, "runtime installed: ${snapshot.conversationId} (draft=$draft, active=${activeRuntimes().size})")
        return runtime
    }

    private fun removeIdleRuntime(conversationId: Uuid) {
        appScope.launch {
            operationLocks.withLock(conversationId) {
                val entry = entries[conversationId] ?: return@withLock
                val runtime = entry.state.value.runtimeOrNull() ?: return@withLock
                if (runtime.isInUse) return@withLock
                if (entries.remove(conversationId, entry)) {
                    runtime.cleanup()
                    _runtimesVersion.value++
                    Log.i(TAG, "runtime evicted: $conversationId")
                }
            }
        }
    }
}

private fun ConversationRuntimeState?.runtimeOrNull(): ConversationRuntime? = when (this) {
    is ConversationRuntimeState.Draft -> runtime
    is ConversationRuntimeState.Ready -> runtime
    else -> null
}
