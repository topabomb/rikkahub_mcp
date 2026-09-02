package net.weero.measix.pilot.service.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.data.repository.ExecutionStateConflictException
import net.weero.measix.pilot.service.ApplicationRecoveryGate
import kotlin.coroutines.coroutineContext
import kotlin.uuid.Uuid

/**
 * Single application entry for durable conversation commands. Resident and non-resident writes
 * share one [ConversationTransition], one repository commit and the same failure semantics.
 */
class ConversationCommandCoordinator(
    private val registry: ConversationRuntimeRegistry,
    private val repository: ConversationRepository,
    private val recoveryGate: ApplicationRecoveryGate,
    private val operationLocks: ConversationOperationLocks,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun load(conversationId: Uuid): ConversationRuntime =
        operationLocks.withLock(conversationId) { registry.loadRuntime(conversationId) }

    suspend fun create(conversation: Conversation): ConversationRuntime =
        gated { operationLocks.withLocks(conversation.lockIds()) {
            val runtime = withContext(NonCancellable) {
                if (registry.findRuntime(conversation.id) != null || repository.existsConversationById(conversation.id)) {
                    throw ConversationCommandConflictException("conversation already exists: ${conversation.id}")
                }
                repository.insertConversation(conversation)
                registry.registerRuntime(conversation)
            }
            runtime
        } }

    internal suspend fun createSnapshot(snapshot: ConversationAggregateSnapshot): ConversationRuntime =
        gated { operationLocks.withLock(snapshot.conversationId) {
            val runtime = withContext(NonCancellable) {
                if (
                    registry.findRuntime(snapshot.conversationId) != null ||
                    repository.existsConversationById(snapshot.conversationId)
                ) {
                    throw ConversationCommandConflictException(
                        "conversation already exists: ${snapshot.conversationId}",
                    )
                }
                repository.insertConversationSnapshot(snapshot)
                registry.registerSnapshot(snapshot)
            }
            runtime
        } }

    suspend fun loadOrRegisterDraft(conversation: Conversation): ConversationRuntime =
        gated { operationLocks.withLocks(conversation.lockIds()) {
            if (repository.existsConversationById(conversation.id)) {
                registry.loadRuntime(conversation.id)
            } else {
                registry.installDraft(conversation)
            }
        } }

    internal suspend fun createTree(
        master: ConversationAggregateSnapshot,
        children: List<ConversationAggregateSnapshot>,
    ): ConversationRuntime = gated {
        val ids = listOf(master.conversationId) + children.map { it.conversationId }
        operationLocks.withLocks(ids) {
            val runtime = withContext(NonCancellable) {
                if (
                    ids.distinct().size != children.size + 1 ||
                    ids.any { registry.findRuntime(it) != null || repository.existsConversationById(it) }
                ) {
                    throw ConversationCommandConflictException("fork conversation id already exists or is duplicated")
                }
                repository.insertConversationTree(master, children)
                registry.registerSnapshot(master)
            }
            runtime
        }
    }

    suspend fun delete(conversationId: Uuid): ConversationDeletionResult = try {
        gated {
            deleteLocked(conversationId)
            ConversationDeletionResult.Success
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (missing: ConversationNotFoundException) {
        ConversationDeletionResult.AlreadyDeleted
    } catch (failure: Exception) {
        ConversationDeletionResult.Failure(failure)
    }

    suspend fun deleteOrThrow(conversationId: Uuid) {
        when (val result = delete(conversationId)) {
            ConversationDeletionResult.Success,
            ConversationDeletionResult.AlreadyDeleted,
            -> Unit
            is ConversationDeletionResult.Failure -> throw result.error
        }
    }

    internal suspend fun deleteCapturingTree(
        conversationId: Uuid,
        beforeDelete: suspend (DeletedConversationTree) -> Unit = {},
    ): DeletedConversationTree = gated {
        val lockIds = deletionLockIds(conversationId)
        operationLocks.withLocks(lockIds) {
            ensureNotActive(conversationId)
            val root = repository.getConversationSnapshotById(conversationId)
                ?: throw ConversationNotFoundException(conversationId)
            val children = if (root.header.parentConversationId == null) {
                repository.getChildConversationSnapshots(root.conversationId)
            } else {
                emptyList()
            }
            check(children.all { it.conversationId in lockIds }) { "conversation lineage changed outside command boundary" }
            children.forEach { ensureNotActive(it.conversationId) }
            val deleted = DeletedConversationTree(root, children)
            beforeDelete(deleted)
            repository.deleteConversation(conversationId)
            (children.map { it.conversationId } + conversationId).forEach { id -> registry.evictRuntime(id) }
            deleted
        }
    }

    internal suspend fun deleteFromPendingCleanup(conversationId: Uuid) {
        deleteLocked(conversationId)
    }

    suspend fun execute(
        conversationId: Uuid,
        command: ConversationCommand,
    ): ConversationCommandResult = try {
        gated { executeLocked(conversationId, command) }
        ConversationCommandResult.Success
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (conflict: ConversationCommandConflictException) {
        ConversationCommandResult.Conflict(conflict)
    } catch (conflict: ConversationNotFoundException) {
        ConversationCommandResult.Conflict(conflict)
    } catch (conflict: ExecutionStateConflictException) {
        ConversationCommandResult.Conflict(conflict)
    } catch (failure: Exception) {
        ConversationCommandResult.Failure(failure)
    }

    suspend fun executeOrThrow(
        conversationId: Uuid,
        command: ConversationCommand,
    ) = execute(conversationId, command).getOrThrow()

    /** Executes the typed title CAS without loading a non-resident message tree to inspect it. */
    suspend fun updateTitleIfCurrent(
        conversationId: Uuid,
        expectedTitle: String,
        title: String,
    ): Boolean = gated {
        executeLocked(conversationId, UpdateTitleIfCurrent(expectedTitle, title))
    }

    internal suspend fun executeRecovery(
        conversationId: Uuid,
        command: ConversationCommand,
    ) {
        executeLocked(conversationId, command)
    }

    private suspend fun executeLocked(
        conversationId: Uuid,
        command: ConversationCommand,
    ): Boolean = operationLocks.withLock(conversationId) {
        val resident = registry.findRuntime(conversationId)
        if (resident != null) {
            resident.acquireLease().use {
                applyResidentCommand(resident, command, draft = registry.isDraft(conversationId))
            }
        } else if (command is HeaderConversationCommand) {
            val header = repository.getConversationHeader(conversationId)
                ?: throw ConversationNotFoundException(conversationId)
            val change = ConversationTransition.planHeader(header, command, nowMillis())
            commitDurable(change.write)
            command !is UpdateTitleIfCurrent || header.title == command.expectedTitle
        } else {
            val runtime = registry.loadRuntime(conversationId)
            runtime.acquireLease().use {
                applyResidentCommand(runtime, command, draft = false)
            }
        }
    }

    private suspend fun deleteLocked(conversationId: Uuid) {
        val lockIds = deletionLockIds(conversationId)
        operationLocks.withLocks(lockIds) {
            ensureNotActive(conversationId)
            val header = repository.getConversationHeader(conversationId)
            if (header == null) {
                registry.evictRuntime(conversationId)
                throw ConversationNotFoundException(conversationId)
            }
            val childIds = if (header.parentConversationId == null) {
                repository.getChildConversationIds(conversationId)
            } else {
                emptyList()
            }
            check(childIds.all { it in lockIds }) { "conversation lineage changed outside command boundary" }
            childIds.forEach(::ensureNotActive)
            repository.deleteConversation(conversationId)
            (childIds + conversationId).forEach { id -> registry.evictRuntime(id) }
        }
    }

    /**
     * Parent creation also acquires the parent stripe, so after this snapshot is released and the
     * complete lock set is acquired no new child can cross the deletion boundary. The lineage is
     * queried again while locked before mutation.
     */
    private suspend fun deletionLockIds(conversationId: Uuid): Set<Uuid> =
        operationLocks.withLock(conversationId) {
            val header = repository.getConversationHeader(conversationId)
                ?: return@withLock setOf(conversationId)
            buildSet {
                add(conversationId)
                if (header.parentConversationId == null) {
                    addAll(repository.getChildConversationIds(conversationId))
                }
            }
        }

    private fun ensureNotActive(conversationId: Uuid) {
        val runtime = registry.findRuntime(conversationId) ?: return
        if (runtime.isGenerating || runtime.snapshot.value.activeTurn != null) {
            throw ConversationCommandConflictException("cannot delete active conversation: $conversationId")
        }
    }

    /**
     * `START` 的唯一 durable 入口：锁内由 [ConversationTransition] 重算目标 selected
     * prefix，与命令携带的 expected token 逐项相同才继续；随后在同一 Room 事务提交
     * Assistant slot、turn_execution 与可选 model-context entry。任何一步失败都不发布
     * Runtime snapshot。epoch 由本入口唯一分配。
     */
    internal suspend fun startTurn(conversationId: Uuid, command: StartTurn): TurnHandle = gated {
        operationLocks.withLock(conversationId) {
            check(!registry.isDraft(conversationId)) {
                "a turn cannot start before the first user message materializes the draft"
            }
            val runtime = registry.loadRuntime(conversationId)
            runtime.acquireLease().use {
                val current = runtime.snapshot.value
                // Identity check does not consume epoch; a rejected START must not advance it.
                validateConversationCommandOwner(
                    runtime.id,
                    current,
                    command,
                    runtime.currentGenerationTurnId(),
                )
                // epoch 必须在 plan 之前分配：plan 产生的 durable activeTurn 携带同一 epoch，
                // 发布的 snapshot 与返回的 TurnHandle 才能互相匹配（否则首个 checkpoint 即被拒）。
                val started = command.copy(epoch = runtime.nextTurnEpoch())
                val change = ConversationTransition.plan(current, started, nowMillis())
                commitAndPublish(runtime, current, started, change)
                TurnHandle(runtime.id, started.epoch, started.turnId, started.assistantMessageId)
            }
        }
    }

    private suspend fun applyResidentCommand(
        runtime: ConversationRuntime,
        command: ConversationCommand,
        draft: Boolean,
    ): Boolean {
        require(command !is StartTurn) { "use startTurn so the caller receives the TurnHandle" }
        val current = runtime.snapshot.value
        if (!draft) {
            validateConversationCommandOwner(runtime.id, current, command, runtime.currentGenerationTurnId())
        }
        val titleCasMatched = command !is UpdateTitleIfCurrent ||
            current.header.title == command.expectedTitle
        val change = ConversationTransition.plan(current, command, nowMillis())
        when (change) {
            is ConversationChange.DraftOnly -> runtime.publishDraft(change.snapshot)
            is ConversationChange.Durable -> commitAndPublish(
                runtime = runtime,
                old = current,
                command = command,
                change = change,
                promoteDraft = change.write is ConversationWrite.MaterializeDraft,
            )
        }
        return titleCasMatched
    }

    private suspend fun commitAndPublish(
        runtime: ConversationRuntime,
        old: ConversationAggregateSnapshot,
        command: ConversationCommand,
        change: ConversationChange,
        promoteDraft: Boolean = false,
    ) {
        val durable = change as? ConversationChange.Durable
            ?: error("draft-only changes cannot enter the durable commit path")
        coroutineContext.ensureActive()
        withContext(NonCancellable) {
            commitDurable(durable.write)
            runtime.publishCommitted(old, command, durable.snapshot)
            if (promoteDraft) registry.promoteDraft(runtime.id, runtime)
        }
    }

    private suspend fun commitDurable(write: ConversationWrite) {
        when (write) {
            is ConversationWrite.MaterializeDraft -> repository.commit(write)
            is ConversationWrite.Mutate -> {
                if (write.mutation.hasChanges() || write.executionFacts != null) {
                    repository.commit(write)
                }
            }
        }
    }

    private suspend fun <T> gated(block: suspend () -> T): T {
        recoveryGate.awaitReady()
        return block()
    }

}

internal fun validateConversationCommandOwner(
    conversationId: Uuid,
    snapshot: ConversationAggregateSnapshot,
    command: ConversationCommand,
    activeRequestTurnId: Uuid? = null,
) {
    fun requireActiveIdentity(turnId: Uuid) {
        if (activeRequestTurnId == null || activeRequestTurnId != turnId) {
            throw ConversationCommandConflictException(
                "active request $activeRequestTurnId does not own command $turnId",
            )
        }
    }
    when (command) {
        is StartTurn -> {
            requireActiveIdentity(command.turnId)
            if (snapshot.activeTurn != null) {
                throw ConversationCommandConflictException(
                    "conversation $conversationId already has an active turn",
                )
            }
        }
        is CommitCheckpoint -> {
            requireActiveIdentity(command.handle.turnId)
            if (
                command.handle.conversationId != conversationId ||
                snapshot.activeTurn?.matches(command.handle) != true
            ) {
                throw ConversationCommandConflictException("stale checkpoint for turn ${command.handle.turnId}")
            }
        }
        is FinalizeTurn -> if (
            command.handle.conversationId != conversationId ||
            snapshot.activeTurn?.matches(command.handle) != true
        ) {
            throw ConversationCommandConflictException("stale finalization for turn ${command.handle.turnId}")
        }
        is HeaderConversationCommand -> Unit
        is ResolveToolInteraction -> {
            val handle = command.handle
            requireActiveIdentity(handle.turnId)
            if (
                handle.conversationId != conversationId ||
                snapshot.activeTurn?.matches(handle) != true
            ) {
                throw ConversationCommandConflictException("stale tool approval for turn ${handle.turnId}")
            }
        }
        is RecoverInterruptedTurn -> if (snapshot.activeTurn != null) {
            throw ConversationCommandConflictException("recovery cannot overwrite an active turn")
        }
        else -> if (snapshot.activeTurn != null) {
            throw ConversationCommandConflictException(
                "tree command ${command::class.simpleName} requires the active turn to finish first",
            )
        }
    }
}

private fun Conversation.lockIds(): List<Uuid> = buildList {
    add(id)
    parentConversationId?.let(::add)
}

sealed interface ConversationCommandResult {
    data object Success : ConversationCommandResult
    data class Conflict(val error: Throwable) : ConversationCommandResult
    data class Failure(val error: Throwable) : ConversationCommandResult

    fun getOrThrow(): Unit = when (this) {
        Success -> Unit
        is Conflict -> throw error
        is Failure -> throw error
    }
}

class ConversationCommandConflictException(message: String) : IllegalStateException(message)

internal data class DeletedConversationTree(
    val root: ConversationAggregateSnapshot,
    val children: List<ConversationAggregateSnapshot>,
)

sealed interface ConversationDeletionResult {
    data object Success : ConversationDeletionResult
    data object AlreadyDeleted : ConversationDeletionResult
    data class Failure(val error: Throwable) : ConversationDeletionResult
}
