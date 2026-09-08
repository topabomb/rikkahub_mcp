package net.weero.measix.pilot.service.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.data.repository.ExecutionStateConflictException
import net.weero.measix.pilot.service.ApplicationRecoveryGate
import net.weero.measix.pilot.service.ConversationOpenRequest
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

    /** A lineage change commits all aggregates before any runtime publishes or disappears. */
    internal suspend fun commitTreeMutation(
        scope: ConfigurationScope,
        rootId: Uuid,
        replacements: Map<Uuid, ReplaceMessageTree>,
        deletedChildIds: Set<Uuid>,
    ) = withRootTree(scope, rootId) {
        check(rootId in replacements && rootId !in deletedChildIds)
        check(replacements.keys.none { it in deletedChildIds })
        val childIds = repository.getChildConversationIds(rootId).toSet()
        check((replacements.keys - rootId + deletedChildIds).all { it in childIds })
        deletedChildIds.forEach { ensureNotActive(it, includeAuxiliary = true) }
        val planned = replacements.map { (id, command) ->
            val runtime = load(id)
            check(!runtime.durable.header.newConversation) { "tree_mutation_requires_persisted_conversation" }
            val change = ConversationTransition.plan(runtime.durable, command, nowMillis()) as ConversationChange.Durable
            Triple(runtime, command, change)
        }
        val write = ConversationWrite.MutateTree(rootId, planned.map {
            (it.third.write as ConversationWrite.Mutate).mutation
        }, deletedChildIds)
        coroutineContext.ensureActive()
        withContext(NonCancellable) {
            commitDurable(write)
            planned.forEach { (runtime, command, change) -> runtime.publishCommitted(command, change.snapshot) }
            deletedChildIds.forEach { registry.evictRuntime(it) }
        }
    }

    internal suspend fun <T> withResidentRuntime(
        conversationId: Uuid,
        operation: suspend (ConversationRuntime?) -> T,
    ): T = operationLocks.withLock(conversationId) { operation(registry.findRuntime(conversationId)) }

    /** Authorize headers before loading any tree, under the same boundary used by all writes. */
    internal suspend fun <T> withRootHeaders(
        scope: ConfigurationScope,
        conversationIds: Collection<Uuid>,
        operation: suspend (List<ConversationHeader>) -> T,
    ): T = gated { operationLocks.withLocks(conversationIds) {
        val headers = conversationIds.map { id ->
            val header = registry.findRuntime(id)?.durable?.header ?: repository.getConversationHeader(id)
                ?: throw ConversationNotFoundException(id)
            check(header.scope == scope) { "conversation_scope_mismatch" }
            check(header.parentConversationId == null) { "child_conversation_requires_detail_access" }
            header
        }
        operation(headers)
    } }

    /** Complete lineage locks precede reads and mutations; a changed lineage must be retried. */
    internal suspend fun <T> withRootTree(
        scope: ConfigurationScope,
        conversationId: Uuid,
        requestWorker: kotlinx.coroutines.Job? = null,
        operation: suspend () -> T,
    ): T {
        val childIds = withRootHeaders(scope, listOf(conversationId)) { repository.getChildConversationIds(conversationId) }
        return operationLocks.withLocks(childIds + conversationId) {
            withRootHeaders(scope, listOf(conversationId)) {
                check(repository.getChildConversationIds(conversationId).toSet() == childIds.toSet()) { "conversation_lineage_changed" }
                childIds.forEach { id ->
                    val header = registry.findRuntime(id)?.durable?.header ?: repository.getConversationHeader(id)
                        ?: throw ConversationNotFoundException(id)
                    check(header.scope == scope && header.parentConversationId == conversationId) { "conversation_lineage_scope_mismatch" }
                }
                childIds.forEach { ensureNotActive(it) }
                if (requestWorker == null) ensureNotActive(conversationId)
                else check(registry.findRuntime(conversationId)?.currentWorker() === requestWorker) { "conversation_request_owner_changed" }
                operation()
            }
        }
    }

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

    internal suspend fun openForView(
        request: ConversationOpenRequest,
        draft: Conversation?,
    ): ConversationRuntimeLease = gated { operationLocks.withLock(request.id) {
        val header = registry.findRuntime(request.id)?.durable?.header ?: repository.getConversationHeader(request.id)
        if (header != null) {
            check(header.scope == request.access.scope) { "conversation_scope_mismatch" }
            check(header.parentConversationId == null) { "child_conversation_requires_detail_access" }
            if (request is ConversationOpenRequest.OpenExisting && registry.isDraft(request.id)) {
                throw ConversationNotFoundException(request.id)
            }
        } else if (request is ConversationOpenRequest.OpenExisting) {
            throw ConversationNotFoundException(request.id)
        }
        val runtime = if (header != null) registry.loadRuntime(request.id) else {
            val candidate = requireNotNull(draft) { "conversation_draft_assistant_unavailable" }
            check(candidate.id == request.id && candidate.scope == request.access.scope)
            registry.installDraft(candidate)
        }
        registry.acquireRegisteredRuntime(request.id, runtime)
    } }

    internal suspend fun createTree(
        master: ConversationAggregateSnapshot,
        children: List<ConversationAggregateSnapshot>,
        checkAccess: () -> Unit = {},
    ): ConversationRuntime = gated {
        val ids = listOf(master.conversationId) + children.map { it.conversationId }
        operationLocks.withLocks(ids) {
            checkAccess()
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
            ensureNotActive(conversationId, includeAuxiliary = true)
            val root = repository.getConversationSnapshotById(conversationId)
                ?: throw ConversationNotFoundException(conversationId)
            val children = if (root.header.parentConversationId == null) {
                repository.getChildConversationSnapshots(root.conversationId)
            } else {
                emptyList()
            }
            check(children.all { it.conversationId in lockIds }) { "conversation lineage changed outside command boundary" }
            children.forEach { ensureNotActive(it.conversationId, includeAuxiliary = true) }
            val deleted = DeletedConversationTree(root, children)
            beforeDelete(deleted)
            coroutineContext.ensureActive()
            withContext(NonCancellable) {
                repository.deleteConversation(conversationId)
                (children.map { it.conversationId } + conversationId).forEach { id -> registry.evictRuntime(id) }
                deleted
            }
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
            ensureNotActive(conversationId, includeAuxiliary = true)
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
            childIds.forEach { ensureNotActive(it, includeAuxiliary = true) }
            coroutineContext.ensureActive()
            withContext(NonCancellable) {
                repository.deleteConversation(conversationId)
                (childIds + conversationId).forEach { id -> registry.evictRuntime(id) }
            }
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

    private fun ensureNotActive(conversationId: Uuid, includeAuxiliary: Boolean = false) {
        val runtime = registry.findRuntime(conversationId) ?: return
        if (runtime.isGenerating || runtime.snapshot.value.stream != null || runtime.hasExecutionLeases ||
            (includeAuxiliary && runtime.hasAuxiliaryWork)) {
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
                    current.stream,
                    command,
                    runtime.currentGenerationTurnId(),
                )
                // epoch 必须在 plan 之前分配：plan 产生的 durable 携带同一 epoch，
                // 发布的 snapshot 与返回的 TurnHandle 才能互相匹配（否则首个 checkpoint 即被拒）。
                val started = command.copy(epoch = runtime.nextTurnEpoch())
                val change = ConversationTransition.plan(current.durable, started, nowMillis())
                commitAndPublish(runtime, started, change)
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
            validateConversationCommandOwner(runtime.id, current.stream, command, runtime.currentGenerationTurnId())
        }
        val titleCasMatched = command !is UpdateTitleIfCurrent ||
            current.durable.header.title == command.expectedTitle
        val change = ConversationTransition.plan(current.durable, command, nowMillis())
        when (change) {
            is ConversationChange.DraftOnly -> runtime.publishDraft(change.snapshot)
            is ConversationChange.Durable -> commitAndPublish(
                runtime = runtime,
                command = command,
                change = change,
                promoteDraft = change.write is ConversationWrite.MaterializeDraft,
            )
        }
        return titleCasMatched
    }

    private suspend fun commitAndPublish(
        runtime: ConversationRuntime,
        command: ConversationCommand,
        change: ConversationChange,
        promoteDraft: Boolean = false,
    ) {
        val durable = change as? ConversationChange.Durable
            ?: error("draft-only changes cannot enter the durable commit path")
        coroutineContext.ensureActive()
        withContext(NonCancellable) {
            commitDurable(durable.write)
            runtime.publishCommitted(command, durable.snapshot)
            if (promoteDraft) registry.promoteDraft(runtime.id, runtime)
        }
    }

    private suspend fun commitDurable(write: ConversationWrite) {
        when (write) {
            is ConversationWrite.MutateTree -> repository.commit(write)
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
    activeTurn: TurnStreamProjection?,
    command: ConversationCommand,
    activeTurnId: Uuid? = null,
) {
    fun requireActiveIdentity(turnId: Uuid) {
        if (activeTurnId == null || activeTurnId != turnId) {
            throw ConversationCommandConflictException(
                "active turn $activeTurnId does not own command $turnId",
            )
        }
    }
    when (command) {
        is StartTurn -> {
            requireActiveIdentity(command.turnId)
            if (activeTurn != null) {
                throw ConversationCommandConflictException(
                    "conversation $conversationId already has an active turn",
                )
            }
        }
        is TurnCheckpoint -> {
            requireActiveIdentity(command.turn.turnId)
            if (
                command.turn.conversationId != conversationId ||
                activeTurn?.matches(command.turn) != true
            ) {
                throw ConversationCommandConflictException("stale checkpoint for turn ${command.turn.turnId}")
            }
        }
        is FinalizeTurn -> if (
            command.handle.conversationId != conversationId ||
            activeTurn?.matches(command.handle) != true
        ) {
            throw ConversationCommandConflictException("stale finalization for turn ${command.handle.turnId}")
        }
        is HeaderConversationCommand -> Unit
        is ResolveToolInteraction -> {
            val handle = command.handle
            requireActiveIdentity(handle.turnId)
            if (
                handle.conversationId != conversationId ||
                activeTurn?.matches(handle) != true
            ) {
                throw ConversationCommandConflictException("stale tool approval for turn ${handle.turnId}")
            }
        }
        is RecoverInterruptedTurn -> if (activeTurn != null) {
            throw ConversationCommandConflictException("recovery cannot overwrite an active turn")
        }
        else -> if (activeTurn != null) {
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
