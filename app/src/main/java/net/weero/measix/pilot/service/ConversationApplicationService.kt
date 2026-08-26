package net.weero.measix.pilot.service

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.getAssistantById
import net.weero.measix.pilot.data.datastore.getCurrentAssistant
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.AttachmentCloner
import net.weero.measix.pilot.data.files.OwnedArtifact
import net.weero.measix.pilot.data.files.ArtifactRetentionLease
import net.weero.measix.pilot.data.files.ToolArtifactRewriter
import net.weero.measix.pilot.data.files.requireDiscarded
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.AssistantAffectScope
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.model.replaceRegexes
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.data.repository.FolderRepository
import net.weero.measix.pilot.service.runtime.ConversationCommandCoordinator
import net.weero.measix.pilot.service.runtime.ConversationCommandConflictException
import net.weero.measix.pilot.service.runtime.ConversationNotFoundException
import net.weero.measix.pilot.service.runtime.ConversationRuntimeRegistry
import net.weero.measix.pilot.service.runtime.ConversationSnapshot
import net.weero.measix.pilot.service.runtime.DeleteMessage
import net.weero.measix.pilot.service.runtime.EditMessageVariant
import net.weero.measix.pilot.service.runtime.MoveToAssistant
import net.weero.measix.pilot.service.runtime.OptionalFolderId
import net.weero.measix.pilot.service.runtime.OptionalString
import net.weero.measix.pilot.service.runtime.OptionalUuidSet
import net.weero.measix.pilot.service.runtime.SelectNodeVariant
import net.weero.measix.pilot.service.runtime.TogglePinned
import net.weero.measix.pilot.service.runtime.UpdateHeader
import net.weero.measix.pilot.service.runtime.currentTurnPresentation
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

/** Opaque page-lifetime ownership; UI cannot access Runtime capabilities through this handle. */
class ConversationViewLease internal constructor(
    private val closeAction: () -> Unit,
) : AutoCloseable {
    override fun close() = closeAction()
}

/** 用户会话命令、创建、删除与 fork 的唯一 Application owner。 */
class ConversationApplicationService(
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val folderRepository: FolderRepository,
    private val runtimeRegistry: ConversationRuntimeRegistry,
    private val commandCoordinator: ConversationCommandCoordinator,
    private val recoveryGate: ApplicationRecoveryGate,
    private val subAssistantLifecycle: SubAssistantLifecycle,
    private val sideEffects: GenerationSideEffects,
    private val artifactStore: ArtifactStore,
    private val artifactUseCase: ArtifactUseCase,
    private val turnFinalization: TurnFinalization,
    private val json: Json,
    private val toolArtifactRewriter: ToolArtifactRewriter,
    private val titleCoordinator: ConversationTitleCoordinator,
) {
    private enum class DeleteAuthority { APPLICATION, PENDING_CLEANUP }

    /** Opaque undo capability containing the complete cascade-deleted lineage. */
    class RestoreToken internal constructor(
        internal val root: Conversation,
        internal val children: List<Conversation>,
        private val artifactRetention: ArtifactRetentionLease,
    ) : AutoCloseable {
        override fun close() = artifactRetention.close()
    }

    suspend fun initialize(conversationId: Uuid): ConversationViewLease {
        recoveryGate.awaitReady()
        val settings = settingsStore.effectiveSettings.first().settings
        val assistant = settings.getCurrentAssistant()
        val conversation = Conversation.ofId(
            id = conversationId,
            assistantId = assistant.id,
            newConversation = true,
        ).updateCurrentMessages(assistant.presetMessages)
        val runtime = commandCoordinator.loadOrRegisterDraft(conversation)
        val lease = runtimeRegistry.acquireRegisteredRuntime(conversationId, runtime)
        return ConversationViewLease(lease::close)
    }

    suspend fun updateTitle(conversationId: Uuid, title: String) {
        titleCoordinator.commitManualTitle(conversationId, title) {
            commandCoordinator.executeOrThrow(conversationId, UpdateHeader(title = title))
        }
    }

    suspend fun updateCustomSystemPrompt(conversationId: Uuid, prompt: String?) {
        commandCoordinator.executeOrThrow(
            conversationId,
            UpdateHeader(customSystemPrompt = OptionalString.Set(prompt)),
        )
    }

    suspend fun updateModeInjectionIds(conversationId: Uuid, ids: Set<Uuid>) {
        commandCoordinator.executeOrThrow(
            conversationId,
            UpdateHeader(modeInjectionIds = OptionalUuidSet.Set(ids)),
        )
    }

    suspend fun updateWorkspaceCwd(conversationId: Uuid, cwd: String?) {
        commandCoordinator.executeOrThrow(
            conversationId,
            UpdateHeader(workspaceCwd = OptionalString.Set(cwd)),
        )
    }

    suspend fun generateTitle(conversationId: Uuid, force: Boolean = false) {
        recoveryGate.awaitReady()
        sideEffects.generateTitle(commandCoordinator.load(conversationId).snapshot.value, force)
    }

    suspend fun compress(
        snapshot: ConversationSnapshot,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int,
    ): Result<Unit> {
        recoveryGate.awaitReady()
        return sideEffects.compressConversation(snapshot, additionalPrompt, targetTokens, keepRecentMessages)
    }

    suspend fun moveToFolder(conversationId: Uuid, folderId: Uuid?) {
        commandCoordinator.executeOrThrow(
            conversationId,
            UpdateHeader(
                folderId = folderId?.let(OptionalFolderId::SetTo) ?: OptionalFolderId.Clear,
            ),
        )
    }

    fun hasActiveConversationTurnInFolder(folderId: Uuid): Boolean =
        runtimeRegistry.activeRuntimes().any { runtime ->
            runtime.currentTurnPresentation().isActive && runtime.snapshot.value.header.folderId == folderId
        }

    suspend fun deleteFolder(folderId: Uuid) {
        recoveryGate.awaitReady()
        folderRepository.getConversationIds(folderId).forEach { conversationId ->
            commandCoordinator.executeOrThrow(conversationId, UpdateHeader(folderId = OptionalFolderId.Clear))
        }
        folderRepository.deleteEmptyFolder(folderId)
    }

    suspend fun createFolder(assistantId: Uuid, name: String) {
        recoveryGate.awaitReady()
        folderRepository.createFolder(assistantId, name)
    }

    suspend fun renameFolder(folderId: Uuid, name: String) {
        recoveryGate.awaitReady()
        folderRepository.renameFolder(folderId, name)
    }

    suspend fun createForDiagnostics(
        id: Uuid,
        assistantId: Uuid,
        title: String,
        nodes: List<MessageNode>,
    ) {
        commandCoordinator.create(
            Conversation(id = id, assistantId = assistantId, title = title, messageNodes = nodes)
        )
    }

    suspend fun moveToAssistant(conversationId: Uuid, assistantId: Uuid) {
        commandCoordinator.executeOrThrow(
            conversationId,
            MoveToAssistant(assistantId),
        )
    }

    suspend fun delete(conversationId: Uuid) {
        recoveryGate.awaitReady()
        stopAndDelete(conversationId, DeleteAuthority.APPLICATION)
    }

    suspend fun deleteForUndo(conversationId: Uuid): RestoreToken {
        recoveryGate.awaitReady()
        turnFinalization.stopTurn(conversationId)
        var retention: ArtifactRetentionLease? = null
        try {
            val deleted = commandCoordinator.deleteCapturingTree(conversationId) { tree ->
                retention = artifactStore.retainForUndo(listOf(tree.root) + tree.children)
            }
            (deleted.children.map { it.id } + deleted.root.id).forEach(sideEffects::clearTitleTracking)
            return RestoreToken(deleted.root, deleted.children, requireNotNull(retention))
        } catch (error: Throwable) {
            retention?.close()
            throw error
        }
    }

    suspend fun restore(token: RestoreToken) {
        recoveryGate.awaitReady()
        try {
            if (token.children.isEmpty()) {
                commandCoordinator.create(token.root)
            } else {
                commandCoordinator.createTree(token.root, token.children)
            }
        } finally {
            token.close()
        }
    }

    fun discardRestoreToken(token: RestoreToken) = token.close()

    private suspend fun stopAndDelete(
        conversationId: Uuid,
        authority: DeleteAuthority,
    ) {
        turnFinalization.stopTurn(conversationId)
        deletePersistedConversation(conversationId, authority)
    }

    private suspend fun deletePersistedConversation(
        conversationId: Uuid,
        authority: DeleteAuthority,
    ) {
        val childIds = conversationRepo.getChildConversationIds(conversationId)
        when (authority) {
            DeleteAuthority.APPLICATION -> commandCoordinator.deleteOrThrow(conversationId)
            DeleteAuthority.PENDING_CLEANUP -> commandCoordinator.deleteFromPendingCleanup(conversationId)
        }
        (childIds + conversationId).forEach { id ->
            sideEffects.clearTitleTracking(id)
        }
    }

    suspend fun deleteOfAssistant(assistantId: Uuid) {
        recoveryGate.awaitReady()
        deleteOfAssistantCommitted(assistantId, DeleteAuthority.APPLICATION)
    }

    internal suspend fun deleteOfAssistantFromPendingCleanup(assistantId: Uuid) {
        deleteOfAssistantCommitted(assistantId, DeleteAuthority.PENDING_CLEANUP)
    }

    private suspend fun deleteOfAssistantCommitted(assistantId: Uuid, authority: DeleteAuthority) {
        conversationRepo.getConversationsOfAssistant(assistantId).first().forEach {
            stopAndDelete(it.id, authority)
        }
    }

    suspend fun stopGeneration(conversationId: Uuid) {
        recoveryGate.awaitReady()
        turnFinalization.stopTurn(conversationId)
    }

    suspend fun togglePin(conversationId: Uuid) {
        commandCoordinator.executeOrThrow(conversationId, TogglePinned)
    }

    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>,
        artifactDraftScope: ArtifactDraftScope? = null,
    ) {
        if (parts.isEmptyInputMessage()) return
        recoveryGate.awaitReady()
        val snapshot = liveSnapshot(conversationId)
        val settings = settingsStore.effectiveSettings.first().settings
        val assistant = settings.getAssistantById(snapshot.header.assistantId) ?: settings.getCurrentAssistant()
        val target = snapshot.nodes.firstOrNull { node -> node.messages.any { it.id == messageId } } ?: return
        val processedParts = preprocessUserInputParts(parts, assistant)
        commandCoordinator.executeOrThrow(
            conversationId,
            EditMessageVariant(
                nodeId = target.id,
                variant = UIMessage(
                    role = target.currentMessage.role,
                    parts = processedParts,
                ),
            ),
        )
        artifactDraftScope?.publishCommittedReferences(processedParts)
    }

    suspend fun forkAtMessage(conversationId: Uuid, messageId: Uuid): Uuid {
        turnFinalization.stopTurn(conversationId)
        val current = subAssistantLifecycle.finalizeRunsBeforeTreeMutation(liveSnapshot(conversationId))
        val targetIndex = current.nodes.indexOfFirst { node -> node.messages.any { it.id == messageId } }
        if (targetIndex < 0) throw NoSuchElementException("Message not found")

        val owned = mutableListOf<OwnedArtifact>()
        var committed = false
        var createAttempted = false
        var forkIdForCleanup: Uuid? = null
        var primaryFailure: Throwable? = null
        try {
            val copiedNodes = current.nodes.subList(0, targetIndex + 1).map { node ->
                node.copy(
                    id = Uuid.random(),
                    messages = node.messages.map { message ->
                        message.copy(parts = cloneParts(message.parts, owned))
                    },
                )
            }
            val forkId = Uuid.random()
            forkIdForCleanup = forkId
            val sourceChildren = conversationRepo.getChildConversations(current.conversationId).associateBy { it.id }
            val tree = forkSubAssistantTree(
                sourceMasterId = current.conversationId,
                copiedMasterNodes = copiedNodes,
                newMasterId = forkId,
                sourceChildren = sourceChildren,
                json = json,
            )
            val fork = Conversation(
                id = forkId,
                assistantId = current.header.assistantId,
                messageNodes = tree.masterNodes,
                customSystemPrompt = current.header.customSystemPrompt,
                modeInjectionIds = current.header.modeInjectionIds,
                // Fork inherits the master's organization (folderId) and Workspace context
                // (workspaceCwd) from the committed header, not from renderNodes or page state.
                folderId = current.header.folderId,
                workspaceCwd = current.header.workspaceCwd,
            )
            val children = tree.children.map { child ->
                child.copy(
                    messageNodes = child.messageNodes.map { node ->
                        node.copy(messages = node.messages.map { message ->
                            message.copy(parts = cloneParts(message.parts, owned))
                        })
                    },
                )
            }
            createAttempted = true
            commandCoordinator.createTree(fork, children)
            artifactStore.publishAllUnpublished(owned)
            committed = true
            return fork.id
        } catch (error: Throwable) {
            primaryFailure = error
            if (createAttempted && error !is ConversationCommandConflictException) {
                withContext(NonCancellable) {
                    try {
                        commandCoordinator.deleteOrThrow(requireNotNull(forkIdForCleanup))
                    } catch (cleanupFailure: Throwable) {
                        error.addSuppressed(cleanupFailure)
                    }
                }
            }
            throw error
        } finally {
            if (!committed) withContext(NonCancellable) {
                owned.forEach {
                    try {
                        artifactStore.discardUnpublished(it).requireDiscarded("fork rollback")
                    } catch (cleanupFailure: Throwable) {
                        val primary = primaryFailure
                        if (primary != null) primary.addSuppressed(cleanupFailure) else throw cleanupFailure
                    }
                }
            }
        }
    }

    suspend fun selectNode(conversationId: Uuid, nodeId: Uuid, selectIndex: Int) {
        val snapshot = liveSnapshot(conversationId)
        val node = snapshot.nodes.firstOrNull { it.id == nodeId }
            ?: throw NoSuchElementException("Message node not found")
        require(selectIndex in node.messages.indices) { "Invalid selectIndex" }
        commandCoordinator.executeOrThrow(conversationId, SelectNodeVariant(nodeId, selectIndex))
    }

    suspend fun deleteMessage(conversationId: Uuid, messageId: Uuid, failIfMissing: Boolean = true) {
        turnFinalization.stopTurn(conversationId)
        subAssistantLifecycle.finalizeRunsBeforeTreeMutation(liveSnapshot(conversationId))
        val runtime = runtimeRegistry.requireRuntime(conversationId)
        val before = runtime.snapshot.value
        commandCoordinator.executeOrThrow(conversationId, DeleteMessage(messageId))
        if (runtime.snapshot.value === before) {
            if (failIfMissing) throw NoSuchElementException("Message not found")
            return
        }
        subAssistantLifecycle.applyRetentionAfterTreeMutation(conversationId)
    }

    suspend fun deleteMessage(conversationId: Uuid, message: UIMessage) =
        deleteMessage(conversationId, message.id, failIfMissing = false)

    private suspend fun cloneParts(
        parts: List<UIMessagePart>,
        owned: MutableList<OwnedArtifact>,
    ): List<UIMessagePart> = AttachmentCloner.cloneParts(
        parts,
        artifactStore,
        owned,
        toolArtifactRewriter,
    )

    private suspend fun liveSnapshot(conversationId: Uuid): ConversationSnapshot =
        commandCoordinator.load(conversationId).snapshot.value
}

internal fun preprocessUserInputParts(
    parts: List<UIMessagePart>,
    assistant: Assistant,
): List<UIMessagePart> = parts.map { part ->
    when (part) {
        is UIMessagePart.Text -> part.copy(
            text = part.text.replaceRegexes(
                assistant = assistant,
                scope = AssistantAffectScope.USER,
                visual = false,
            ),
        )
        else -> AttachmentRefs.ensureAttachmentRef(part)
    }
}
