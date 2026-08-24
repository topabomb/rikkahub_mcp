package net.weero.measix.pilot.service.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import net.weero.measix.pilot.data.db.entity.TurnExecutionEntity
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.data.repository.ExecutionStateConflictException
import net.weero.measix.pilot.service.ApplicationRecoveryGate
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * Single application entry for durable conversation commands. Resident and non-resident writes
 * share the reducer, mutation builder, transaction and failure semantics; callers never choose a
 * storage route.
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

    suspend fun loadOrRegisterDraft(conversation: Conversation): ConversationRuntime =
        gated { operationLocks.withLocks(conversation.lockIds()) {
            if (repository.existsConversationById(conversation.id)) {
                registry.loadRuntime(conversation.id)
            } else {
                registry.installDraft(conversation)
            }
        } }

    suspend fun createTree(
        master: Conversation,
        children: List<Conversation>,
    ): ConversationRuntime = gated { operationLocks.withLocks(listOf(master.id) + children.map { it.id }) {
        val runtime = withContext(NonCancellable) {
            val ids = buildSet {
                add(master.id)
                children.forEach { add(it.id) }
            }
            if (
                ids.size != children.size + 1 ||
                ids.any { registry.findRuntime(it) != null || repository.existsConversationById(it) }
            ) {
                throw ConversationCommandConflictException("fork conversation id already exists or is duplicated")
            }
            repository.insertConversationTree(master, children)
            registry.registerRuntime(master)
        }
        runtime
    } }

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
            val root = repository.getConversationById(conversationId)
                ?: throw ConversationNotFoundException(conversationId)
            val children = if (root.parentConversationId == null) {
                repository.getChildConversations(root.id)
            } else {
                emptyList()
            }
            check(children.all { it.id in lockIds }) { "conversation lineage changed outside command boundary" }
            children.forEach { ensureNotActive(it.id) }
            val deleted = DeletedConversationTree(root, children)
            beforeDelete(deleted)
            repository.deleteConversation(conversationId)
            (children.map { it.id } + conversationId).forEach { id -> registry.evictRuntime(id) }
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

    internal suspend fun executeRecovery(
        conversationId: Uuid,
        command: ConversationCommand,
    ) = executeLocked(conversationId, command)

    private suspend fun executeLocked(
        conversationId: Uuid,
        command: ConversationCommand,
    ) = operationLocks.withLock(conversationId) {
        val resident = registry.findRuntime(conversationId)
        if (resident != null) {
            if (registry.isDraft(conversationId)) {
                when (command) {
                    is UpdateHeader,
                    is MoveToAssistant,
                    -> resident.updateDraftHeader(command)
                    is AppendUserMessage -> withContext(NonCancellable) {
                        resident.submit(
                            command = command,
                            persist = ::materializeDraft,
                        )
                        registry.promoteDraft(conversationId, resident)
                    }
                    else -> throw ConversationCommandConflictException(
                        "draft $conversationId only accepts header edits or its first user message",
                    )
                }
            } else {
                resident.submit(command, ::persistCommand)
            }
        } else if (command is UpdateHeader || command is MoveToAssistant || command === TogglePinned) {
            val header = repository.getConversationHeader(conversationId)
                ?: throw ConversationNotFoundException(conversationId)
            val updated = when (command) {
                is UpdateHeader -> ConversationReducer.reduceHeader(header, command)
                is MoveToAssistant -> ConversationReducer.reduceHeader(header, command)
                TogglePinned -> ConversationReducer.reduceHeader(header, TogglePinned)
            }
            val mutation = ConversationMutationBuilder.buildHeader(header, updated)
            if (mutation.hasChanges()) repository.applyMutation(mutation)
        } else {
            registry.loadRuntime(conversationId).submit(command, ::persistCommand)
        }
        Unit
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

    suspend fun startTurn(
        conversationId: Uuid,
        turnId: Uuid,
        assistantMessageId: Uuid,
        resume: Boolean,
    ): TurnHandle = gated { operationLocks.withLock(conversationId) {
        check(!registry.isDraft(conversationId)) {
            "a turn cannot start before the first user message materializes the draft"
        }
        registry.loadRuntime(conversationId).startTurn(
            turnId,
            assistantMessageId,
            resume,
            ::persistCommand,
        )
    } }

    private suspend fun persistCommand(
        old: ConversationSnapshot,
        new: ConversationSnapshot,
        command: ConversationCommand,
    ): ConversationSnapshot {
        val committed = committedSnapshot(old, new, command)
        val mutation = ConversationMutationBuilder.build(old, committed, command)
        val facts = when (command) {
            is StartTurn -> ExecutionFacts(
                turn = buildTurn(
                    old.conversationId,
                    command.turnId,
                    TurnExecutionStatus.RUNNING,
                    null,
                    command.assistantMessageId,
                ),
                toolExecution = null,
                turnOperation = TurnExecutionOperation.START,
            )
            is CommitCheckpoint -> ExecutionFacts(
                turn = buildTurn(
                    old.conversationId,
                    command.handle.turnId,
                    command.turnStatus,
                    command.turnReason,
                    command.handle.assistantMessageId,
                ),
                toolExecution = command.toolExecution,
            )
            is FinalizeTurn -> ExecutionFacts(
                turn = buildTurn(
                    old.conversationId,
                    command.handle.turnId,
                    command.terminalStatus,
                    command.terminalReason,
                    command.handle.assistantMessageId,
                ),
                toolExecution = null,
            )
            is RecoverInterruptedTurn -> ExecutionFacts(
                turn = buildTurn(
                    old.conversationId,
                    command.turnId,
                    TurnExecutionStatus.INTERRUPTED,
                    command.terminalReason,
                    command.assistantMessageId,
                ),
                toolExecution = null,
                turnOperation = TurnExecutionOperation.RECOVER,
            )
            else -> null
        }
        if (mutation.hasChanges() || facts != null) repository.applyMutation(mutation, facts)
        return committed
    }

    private suspend fun materializeDraft(
        old: ConversationSnapshot,
        new: ConversationSnapshot,
        command: ConversationCommand,
    ): ConversationSnapshot {
        require(command is AppendUserMessage) { "only the first user message can materialize a draft" }
        require(old.conversationId == new.conversationId) { "draft materialization changed aggregate identity" }
        val committed = committedSnapshot(old, new, command)
        repository.insertConversation(committed.materializeConversation())
        return committed
    }

    private fun committedSnapshot(
        old: ConversationSnapshot,
        snapshot: ConversationSnapshot,
        command: ConversationCommand,
    ): ConversationSnapshot = if (snapshot != old && command.updatesConversationActivity()) {
        snapshot.copy(header = snapshot.header.copy(updateAt = nowMillis()))
    } else {
        snapshot
    }

    private fun buildTurn(
        conversationId: Uuid,
        turnId: Uuid,
        status: TurnExecutionStatus,
        reason: String?,
        assistantMessageId: Uuid,
    ): TurnExecutionEntity {
        val now = nowMillis()
        return TurnExecutionEntity(
            turnId = turnId.toString(),
            conversationId = conversationId.toString(),
            assistantMessageId = assistantMessageId.toString(),
            status = status,
            reason = reason,
            createdAt = now,
            updatedAt = now,
        )
    }

    private suspend fun <T> gated(block: suspend () -> T): T {
        recoveryGate.awaitReady()
        return block()
    }

}

private fun ConversationMutation.hasChanges(): Boolean =
    headerPatch != null || upsertedNodes.isNotEmpty() || deletedNodeIds.isNotEmpty()

private fun ConversationSnapshot.materializeConversation(): Conversation = Conversation(
    id = conversationId,
    assistantId = header.assistantId,
    title = header.title,
    messageNodes = nodes,
    chatSuggestions = header.chatSuggestions,
    isPinned = header.isPinned,
    createAt = Instant.ofEpochMilli(header.createAt),
    updateAt = Instant.ofEpochMilli(header.updateAt),
    customSystemPrompt = header.customSystemPrompt,
    modeInjectionIds = header.modeInjectionIds,
    workspaceCwd = header.workspaceCwd,
    folderId = header.folderId,
    parentConversationId = header.parentConversationId,
    newConversation = header.newConversation,
)

private fun Conversation.lockIds(): List<Uuid> = buildList {
    add(id)
    parentConversationId?.let(::add)
}

private fun ConversationCommand.updatesConversationActivity(): Boolean = when (this) {
    is UpdateHeader,
    is MoveToAssistant,
    TogglePinned,
    -> false
    else -> true
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
    val root: Conversation,
    val children: List<Conversation>,
)

sealed interface ConversationDeletionResult {
    data object Success : ConversationDeletionResult
    data object AlreadyDeleted : ConversationDeletionResult
    data class Failure(val error: Throwable) : ConversationDeletionResult
}
