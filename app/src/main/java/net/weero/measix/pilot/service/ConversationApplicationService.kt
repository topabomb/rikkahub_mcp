package net.weero.measix.pilot.service

import net.weero.measix.pilot.service.turn.TurnFinalizer
import net.weero.measix.pilot.service.subassistant.SubAssistantLifecycle
import net.weero.measix.pilot.service.subassistant.forkSubAssistantTree
import me.rerere.common.configuration.ConfigurationReference
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
import net.weero.measix.pilot.data.model.ConversationModelContextApplicability
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.model.replaceRegexes
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.data.repository.FolderRepository
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.configuration.ConfigurationCategory
import net.weero.measix.pilot.service.runtime.ConversationCommandCoordinator
import net.weero.measix.pilot.service.runtime.ConversationCommandConflictException
import net.weero.measix.pilot.service.runtime.ConversationRuntimeRegistry
import net.weero.measix.pilot.service.runtime.ConversationAggregateSnapshot
import net.weero.measix.pilot.service.runtime.DeleteMessage
import net.weero.measix.pilot.service.runtime.EditMessageVariant
import net.weero.measix.pilot.service.runtime.MoveToAssistant
import net.weero.measix.pilot.service.runtime.OptionalFolderId
import net.weero.measix.pilot.service.runtime.OptionalString
import net.weero.measix.pilot.service.runtime.OptionalConfigurationReferenceSet
import net.weero.measix.pilot.service.runtime.SelectNodeVariant
import net.weero.measix.pilot.service.runtime.TogglePinned
import net.weero.measix.pilot.service.runtime.UpdateHeader
import net.weero.measix.pilot.service.runtime.currentTurnPresentation
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

/** 用户会话命令、创建、删除与 fork 的唯一 Application owner。 */
class ConversationApplicationService internal constructor(
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
    private val turnFinalizer: TurnFinalizer,
    private val json: Json,
    private val toolArtifactRewriter: ToolArtifactRewriter,
    private val titleCoordinator: ConversationTitleCoordinator,
    private val sessions: EnterpriseSessionController,
) {
    private enum class DeleteAuthority { APPLICATION, PENDING_CLEANUP }

    /** Opaque undo capability containing the complete cascade-deleted lineage. */
    class RestoreToken internal constructor(
        internal val root: ConversationAggregateSnapshot,
        internal val children: List<ConversationAggregateSnapshot>,
        private val artifactRetention: ArtifactRetentionLease,
    ) : AutoCloseable {
        override fun close() = artifactRetention.close()
    }

    suspend fun newDraftRequest(
        access: RealmAccess,
        assistantId: ConfigurationReference? = null,
    ): ConversationOpenRequest.NewDraft {
        recoveryGate.awaitReady()
        return sessions.withSelectedRealmAccess(access) {
            settingsStore.withResolvedConfiguration(access.scope, sessions.state.value) { configuration ->
                val selected = requireNotNull(assistantId ?: configuration.selections.assistantId) { "conversation_assistant_missing" }
                check(configuration.selection(ConfigurationCategory.ASSISTANT, selected).isAvailable) { "conversation_assistant_unavailable" }
                ConversationOpenRequest.NewDraft(Uuid.random(), access, selected)
            }
        }
    }

    suspend fun initialize(request: ConversationOpenRequest): ConversationViewLease {
        recoveryGate.awaitReady()
        return sessions.withSelectedRealmAccess(request.access) {
            val draft = (request as? ConversationOpenRequest.NewDraft)?.let { creation ->
                settingsStore.withResolvedConfiguration(creation.access.scope, sessions.state.value) { configuration ->
                    configuration.assistants[creation.assistantId]
                        ?.takeIf { configuration.selection(ConfigurationCategory.ASSISTANT, it.id).isAvailable }
                        ?.let { assistant ->
                            Conversation.ofId(id = creation.id, assistantId = assistant.id, newConversation = true)
                                .copy(scope = creation.access.scope)
                                .updateCurrentMessages(assistant.presetMessages)
                        }
                }
            }
            val lease = commandCoordinator.openForView(request, draft)
            ConversationViewLease(request.id, request.access, sessions.selectionRevision.value, lease::close)
        }
    }

    suspend fun selectAssistantRequest(
        access: RealmAccess,
        assistantId: ConfigurationReference,
        createNew: Boolean,
    ): ConversationOpenRequest {
        recoveryGate.awaitReady()
        return sessions.withSelectedRealmAccess(access) {
            settingsStore.withResolvedConfiguration(access.scope, sessions.state.value) { configuration ->
                check(configuration.selection(ConfigurationCategory.ASSISTANT, assistantId).isAvailable) {
                    "conversation_assistant_unavailable"
                }
            }
            when (access) {
                RealmAccess.Personal -> settingsStore.updateLocal { it.copy(assistantId = assistantId) }
                is RealmAccess.Enterprise -> settingsStore.updateResourceSelections(
                    access.scope,
                    sessions.state.value as net.weero.measix.pilot.data.enterprise.EnterpriseState.Available,
                ) { it.copy(assistantId = assistantId) }
            }
            val recent = if (createNew) null else conversationRepo
                .getRecentConversationRecords(access.scope, assistantId, 1).firstOrNull()
            if (recent != null) ConversationOpenRequest.OpenExisting(recent.id, access)
            else settingsStore.withResolvedConfiguration(access.scope, sessions.state.value) { configuration ->
                check(configuration.selection(ConfigurationCategory.ASSISTANT, assistantId).isAvailable) {
                    "conversation_assistant_unavailable"
                }
                ConversationOpenRequest.NewDraft(Uuid.random(), access, assistantId)
            }
        }
    }

    suspend fun initialRequest(createNew: Boolean): ConversationOpenRequest {
        recoveryGate.awaitReady()
        val access = sessions.captureSelectedRealmAccess()
        val existing = sessions.withSelectedRealmAccess(access) { settingsStore.lastConversation(access.scope) }
        return if (!createNew && existing != null) ConversationOpenRequest.OpenExisting(existing, access)
            else newDraftRequest(access)
    }

    suspend fun rememberConversation(lease: ConversationViewLease) {
        sessions.withSelectedRealmAccess(lease.access) {
            lease.requireOpen()
            check(lease.selectionRevision == sessions.selectionRevision.value) { "conversation_view_revoked" }
            val header = conversationRepo.getConversationHeader(lease.conversationId) ?: return@withSelectedRealmAccess
            check(header.scope == lease.access.scope && header.parentConversationId == null) { "conversation_scope_mismatch" }
            settingsStore.rememberConversation(header.scope, lease.conversationId)
        }
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

    suspend fun updateModeInjectionIds(conversationId: Uuid, ids: Set<ConfigurationReference>) {
        commandCoordinator.executeOrThrow(
            conversationId,
            UpdateHeader(modeInjectionIds = OptionalConfigurationReferenceSet.Set(ids)),
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
        sideEffects.generateTitle(commandCoordinator.load(conversationId).durable, force)
    }

    /**
     * 显式压缩入口。UI 只交 conversationId：internal aggregate（含 model context）由本 service 自己
     * 解析，不让 durable 事实穿过 presentation 边界。
     */
    suspend fun compress(
        conversationId: Uuid,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int,
    ): Result<Unit> {
        recoveryGate.awaitReady()
        val snapshot = commandCoordinator.load(conversationId).durable
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
            runtime.currentTurnPresentation().isActive && runtime.durable.header.folderId == folderId
        }

    suspend fun deleteFolder(folderId: Uuid) {
        recoveryGate.awaitReady()
        folderRepository.getConversationIds(folderId).forEach { conversationId ->
            commandCoordinator.executeOrThrow(conversationId, UpdateHeader(folderId = OptionalFolderId.Clear))
        }
        folderRepository.deleteEmptyFolder(folderId)
    }

    suspend fun createFolder(assistantId: ConfigurationReference, name: String) {
        recoveryGate.awaitReady()
        folderRepository.createFolder(assistantId, name)
    }

    suspend fun renameFolder(folderId: Uuid, name: String) {
        recoveryGate.awaitReady()
        folderRepository.renameFolder(folderId, name)
    }

    suspend fun createForDiagnostics(
        id: Uuid,
        assistantId: ConfigurationReference,
        title: String,
        nodes: List<MessageNode>,
    ) {
        commandCoordinator.create(
            Conversation(id = id, assistantId = assistantId, title = title, messageNodes = nodes)
        )
    }

    suspend fun moveToAssistant(conversationId: Uuid, assistantId: ConfigurationReference) {
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
        turnFinalizer.stopTurn(conversationId)
        var retention: ArtifactRetentionLease? = null
        try {
            val deleted = commandCoordinator.deleteCapturingTree(conversationId) { tree ->
                retention = artifactStore.retainNodesForUndo(
                    (listOf(tree.root) + tree.children).map { it.nodes },
                )
            }
            (deleted.children.map { it.conversationId } + deleted.root.conversationId)
                .forEach(sideEffects::clearTitleTracking)
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
                commandCoordinator.createSnapshot(token.root)
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
        turnFinalizer.stopTurn(conversationId)
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

    suspend fun deleteOfAssistant(assistantId: ConfigurationReference) {
        recoveryGate.awaitReady()
        deleteOfAssistantCommitted(sessions.captureSelectedRealmAccess().scope, assistantId, DeleteAuthority.APPLICATION)
    }

    internal suspend fun deleteOfAssistantFromPendingCleanup(assistantId: ConfigurationReference) {
        deleteOfAssistantCommitted(ConfigurationScope.Personal, assistantId, DeleteAuthority.PENDING_CLEANUP)
    }

    private suspend fun deleteOfAssistantCommitted(scope: ConfigurationScope, assistantId: ConfigurationReference, authority: DeleteAuthority) {
        conversationRepo.getConversationsOfAssistant(scope, assistantId).first().forEach {
            stopAndDelete(it.id, authority)
        }
    }

    suspend fun stopGeneration(conversationId: Uuid) {
        recoveryGate.awaitReady()
        turnFinalizer.stopTurn(conversationId)
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
        turnFinalizer.stopTurn(conversationId)
        val current = subAssistantLifecycle.requireClosedRunsBeforeTreeMutation(liveSnapshot(conversationId))
        val targetIndex = current.nodes.indexOfFirst { node -> node.messages.any { it.id == messageId } }
        if (targetIndex < 0) throw NoSuchElementException("Message not found")

        val owned = mutableListOf<OwnedArtifact>()
        var committed = false
        var createAttempted = false
        var forkIdForCleanup: Uuid? = null
        var primaryFailure: Throwable? = null
        val copiedArtifacts = linkedMapOf<String, OwnedArtifact>()
        try {
            val copiedNodeIdMap = mutableMapOf<Uuid, Uuid>()
            val copiedNodes = current.nodes.subList(0, targetIndex + 1).map { node ->
                val newNodeId = Uuid.random()
                copiedNodeIdMap[node.id] = newNodeId
                node.copy(
                    id = newNodeId,
                    messages = node.messages.map { message ->
                        message.copy(parts = cloneParts(message.parts, owned, copiedArtifacts))
                    },
                )
            }
            val forkId = Uuid.random()
            forkIdForCleanup = forkId
            val sourceChildren = conversationRepo.getChildConversationSnapshots(current.conversationId)
                .associateBy { it.conversationId }
            val tree = forkSubAssistantTree(
                sourceMasterId = current.conversationId,
                copiedMasterNodes = copiedNodes,
                newMasterId = forkId,
                sourceChildren = sourceChildren,
                json = json,
            )
            val fork = current.copy(
                conversationId = forkId,
                header = current.header.copy(
                    id = forkId,
                    title = "",
                    isPinned = false,
                    chatSuggestions = emptyList(),
                    parentConversationId = null,
                    newConversation = false,
                ),
                nodes = tree.masterNodes,
                // Master fork 保留 message id、重建 node id：context entries 经唯一映射。
                modelContextEntries = ConversationModelContextApplicability.remapForClone(
                    entries = current.modelContextEntries,
                    nodeIdMap = copiedNodeIdMap,
                    messageIdMap = emptyMap(),
                    clonedNodes = tree.masterNodes,
                ),
            )
            val children = tree.children.map { child ->
                child.copy(
                    nodes = child.nodes.map { node ->
                        node.copy(messages = node.messages.map { message ->
                            message.copy(parts = cloneParts(message.parts, owned, copiedArtifacts))
                        })
                    },
                )
            }
            createAttempted = true
            commandCoordinator.createTree(fork, children)
            artifactStore.publishAllUnpublished(owned)
            committed = true
            return fork.conversationId
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
        turnFinalizer.stopTurn(conversationId)
        subAssistantLifecycle.requireClosedRunsBeforeTreeMutation(liveSnapshot(conversationId))
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
        copiedArtifacts: MutableMap<String, OwnedArtifact>,
    ): List<UIMessagePart> = AttachmentCloner.cloneParts(
        parts,
        artifactStore,
        owned,
        toolArtifactRewriter,
        copiedArtifacts,
    )

    private suspend fun liveSnapshot(conversationId: Uuid): ConversationAggregateSnapshot =
        commandCoordinator.load(conversationId).durable
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
