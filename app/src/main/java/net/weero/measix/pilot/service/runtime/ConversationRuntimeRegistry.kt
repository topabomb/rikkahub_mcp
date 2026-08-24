package net.weero.measix.pilot.service.runtime

import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
                repository.getConversationById(conversationId)
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
                        check(current.runtime.snapshot.value.header.id == conversation.id)
                        current.runtime
                    }
                    is ConversationRuntimeState.Draft -> error(
                        "durable runtime cannot replace a registered draft: ${conversation.id}",
                    )
                    else -> installReadyRuntime(entry, conversation)
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
                    else -> installRuntime(entry, conversation, draft = true)
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

    fun getGenerationJobFlow(conversationId: Uuid): Flow<Job?> =
        observeRuntimeState(conversationId).flatMapLatest { state ->
            state.runtimeOrNull()?.generationJob ?: flowOf(null)
        }

    fun getProcessingStatusFlow(conversationId: Uuid): Flow<String?> =
        observeRuntimeState(conversationId).flatMapLatest { state ->
            state.runtimeOrNull()?.processingStatus ?: flowOf(null)
        }

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> = _runtimesVersion.flatMapLatest {
        val current = activeRuntimes()
        if (current.isEmpty()) {
            flowOf(emptyMap())
        } else {
            combine(current.map { runtime ->
                runtime.generationJob.map { job -> runtime.id to job }
            }) { pairs -> pairs.filter { it.second != null }.toMap() }
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
            .filter { it.snapshot.value.header.assistantId == assistantId }
            .mapNotNull { it.generationJob.value }
            .distinct()
        jobs.forEach { it.cancel(reason) }
        jobs.joinAll()
    }

    private fun installReadyRuntime(entry: Entry, conversation: Conversation): ConversationRuntime =
        installRuntime(entry, conversation, draft = false)

    private fun installRuntime(
        entry: Entry,
        conversation: Conversation,
        draft: Boolean,
    ): ConversationRuntime {
        val runtime = ConversationRuntime(
            id = conversation.id,
            initial = conversation.toSnapshot(),
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
        Log.i(TAG, "runtime installed: ${conversation.id} (draft=$draft, active=${activeRuntimes().size})")
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
