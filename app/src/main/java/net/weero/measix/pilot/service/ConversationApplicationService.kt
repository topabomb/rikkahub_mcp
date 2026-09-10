package net.weero.measix.pilot.service

import net.weero.measix.pilot.service.runtime.ConversationRuntime
import net.weero.measix.pilot.service.turn.TurnFinalizer
import net.weero.measix.pilot.service.subassistant.SubAssistantLifecycle
import net.weero.measix.pilot.service.subassistant.forkSubAssistantTree
import me.rerere.common.configuration.ConfigurationReference
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.data.datastore.SettingsStore
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
import net.weero.measix.pilot.data.enterprise.RealmSelection
import java.util.concurrent.atomic.AtomicInteger
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
    private val subAssistantRunGate: net.weero.measix.pilot.service.subassistant.SubAssistantRunGate,
) {
    /** One-use undo ownership. Discard cannot release retention already claimed by restore. */
    class RestoreToken internal constructor(
        internal val root: ConversationAggregateSnapshot,
        internal val children: List<ConversationAggregateSnapshot>,
        internal val selection: RealmSelection,
        private val artifactRetention: ArtifactRetentionLease,
    ) : AutoCloseable {
        private val state = AtomicInteger(0)
        internal fun claim() { check(state.compareAndSet(0, 1)) { "conversation_restore_token_consumed" } }
        internal fun finishRestore() {
            check(state.compareAndSet(1, 2)) { "conversation_restore_owner_missing" }
            artifactRetention.close()
        }
        override fun close() {
            if (state.compareAndSet(0, 2)) artifactRetention.close()
        }
    }

    private suspend fun <T> withCommandTarget(target: ConversationCommandTarget, operation: suspend () -> T): T {
        recoveryGate.awaitReady()
        return sessions.withSelectedRealmSelection(target.selection) {
            target.requireOpen()
            operation()
        }
    }

    private suspend fun <T> withRootCommand(target: ConversationCommandTarget, operation: suspend () -> T): T =
        withCommandTarget(target) {
            commandCoordinator.withRootHeaders(target.selection.access.scope, listOf(target.conversationId)) {
                target.requireOpen()
                operation()
            }
        }

    private suspend fun <T> withTreeCommand(target: ConversationCommandTarget, operation: suspend () -> T): T =
        withCommandTarget(target) {
            commandCoordinator.withRootTree(target.selection.access.scope, target.conversationId) {
                target.requireOpen()
                operation()
            }
        }

    suspend fun answerSubAssistant(
        target: ConversationCommandTarget,
        runId: String,
        interactionId: String,
        answer: String,
    ): Boolean = withRootCommand(target) {
        subAssistantRunGate.completeAnswer(target.conversationId, target.selection.access, runId, interactionId, answer)
    }

    suspend fun newDraftRequest(
        access: RealmAccess,
        assistantId: ConfigurationReference? = null,
    ): ConversationOpenRequest.NewDraft {
        recoveryGate.awaitReady()
        return sessions.withSelectedRealmAccess(access) {
            createDraftRequest(RealmSelection(access, sessions.selectionRevision.value), assistantId)
        }
    }

    suspend fun newDraftRequest(
        selection: RealmSelection,
        assistantId: ConfigurationReference? = null,
    ): ConversationOpenRequest.NewDraft {
        recoveryGate.awaitReady()
        return sessions.withSelectedRealmSelection(selection) { createDraftRequest(selection, assistantId) }
    }

    private suspend fun createDraftRequest(selection: RealmSelection, assistantId: ConfigurationReference?) =
        settingsStore.withResolvedConfiguration(selection.access.scope, sessions.state.value) { configuration ->
            sessions.requirePublishedSelection(selection)
            val selected = requireNotNull(assistantId ?: configuration.selections.assistantId) { "conversation_assistant_missing" }
            check(configuration.selection(ConfigurationCategory.ASSISTANT, selected).isAvailable) { "conversation_assistant_unavailable" }
            ConversationOpenRequest.NewDraft(Uuid.random(), selection.access, selected)
        }

    suspend fun initialize(request: ConversationOpenRequest): ConversationViewLease {
        recoveryGate.awaitReady()
        return sessions.withSelectedRealmAccess(request.access) {
            val lease = if (request is ConversationOpenRequest.NewDraft) {
                settingsStore.withResolvedConfiguration(request.access.scope, sessions.state.value) { configuration ->
                    commandCoordinator.openForView(request) {
                        val assistant = requireNotNull(configuration.assistants[request.assistantId]) { "conversation_assistant_missing" }
                        check(configuration.selection(ConfigurationCategory.ASSISTANT, assistant.id).isAvailable) { "conversation_assistant_unavailable" }
                        val owned = mutableListOf<net.weero.measix.pilot.data.files.OwnedArtifact>()
                        try {
                            val presets = artifactStore.materializeConfigurationMessages(request.access.scope, assistant.presetMessages, owned)
                            net.weero.measix.pilot.service.runtime.ConversationDraft(
                                Conversation.ofId(id = request.id, assistantId = assistant.id, newConversation = true)
                                    .copy(scope = request.access.scope).updateCurrentMessages(presets),
                                artifactStore, owned.toList(),
                            )
                        } catch (error: Throwable) {
                            withContext(NonCancellable) {
                                owned.asReversed().forEach { artifact ->
                                    try { artifactStore.discardUnpublished(artifact).requireDiscarded("preset materialization rollback") }
                                    catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                                }
                            }
                            throw error
                        }
                    }
                }
            } else commandCoordinator.openForView(request)
            ConversationViewLease(request.id, request.access, sessions.selectionRevision.value, lease::draftArtifacts, lease::close)
        }
    }

    suspend fun selectAssistantRequest(
        selection: RealmSelection,
        assistantId: ConfigurationReference,
        createNew: Boolean,
    ): ConversationOpenRequest {
        recoveryGate.awaitReady()
        return sessions.withSelectedRealmSelection(selection) {
            val access = selection.access
            settingsStore.withResolvedConfiguration(access.scope, sessions.state.value) { configuration ->
                check(configuration.selection(ConfigurationCategory.ASSISTANT, assistantId).isAvailable) {
                    "conversation_assistant_unavailable"
                }
            }
            writeSelectedAssistant(access, assistantId)
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

    suspend fun updateTitle(target: ConversationCommandTarget, title: String) = withCommandTarget(target) {
        titleCoordinator.commitManualTitle(target.conversationId, title) {
            commandCoordinator.withRootHeaders(target.selection.access.scope, listOf(target.conversationId)) {
                target.requireOpen()
                commandCoordinator.executeOrThrow(target.conversationId, UpdateHeader(title = title))
            }
        }
    }

    suspend fun updateCustomSystemPrompt(target: ConversationAssistantTarget, prompt: String?) = withAssistantCommand(target) { assistant ->
        check(target.assistantId !is ConfigurationReference.Enterprise) { "managed_assistant_system_prompt_is_fixed" }
        check(assistant.allowConversationSystemPrompt) { "conversation_system_prompt_disabled" }
        commandCoordinator.executeOrThrow(target.conversation.conversationId, UpdateHeader(customSystemPrompt = OptionalString.Set(prompt)))
    }

    suspend fun updateModeInjectionIds(target: ConversationAssistantTarget, ids: Set<ConfigurationReference>) = withAssistantCommand(target) { assistant ->
        check(assistant.allowConversationPromptInjection) { "conversation_prompt_injection_disabled" }
        commandCoordinator.executeOrThrow(target.conversation.conversationId, UpdateHeader(modeInjectionIds = OptionalConfigurationReferenceSet.Set(ids)))
    }

    suspend fun updateWorkspaceCwd(target: ConversationAssistantTarget, expectedWorkspaceId: Uuid?, cwd: String?) = withAssistantCommand(target) { assistant ->
        check(assistant.workspaceId == expectedWorkspaceId) { "conversation_workspace_changed" }
        commandCoordinator.executeOrThrow(target.conversation.conversationId, UpdateHeader(workspaceCwd = OptionalString.Set(cwd)))
    }

    private suspend fun <T> withAssistantCommand(target: ConversationAssistantTarget, action: suspend (Assistant) -> T): T =
        withCommandTarget(target.conversation) {
            settingsStore.withResolvedConfiguration(target.conversation.selection.access.scope, sessions.state.value) { configuration ->
                check(configuration.selection(ConfigurationCategory.ASSISTANT, target.assistantId).isAvailable) { "conversation_assistant_unavailable" }
                val assistant = requireNotNull(configuration.assistants[target.assistantId])
                commandCoordinator.withRootHeaders(target.conversation.selection.access.scope, listOf(target.conversation.conversationId)) { headers ->
                    target.conversation.requireOpen()
                    check(headers.single().assistantId == target.assistantId) { "conversation_assistant_changed" }
                    action(assistant)
                }
            }
        }

    suspend fun generateTitle(target: ConversationCommandTarget, force: Boolean = false) {
        withRootCommand(target) {
            sideEffects.launchTitle(commandCoordinator.load(target.conversationId), target.selection.access, force)
        }
    }

    /** Manual summary cancellation waits for its original worker before another operation can begin. */
    suspend fun compress(
        target: ConversationCommandTarget,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int,
    ): Result<Unit> {
        var owned: Deferred<Result<Unit>>? = null
        var ownerRuntime: ConversationRuntime? = null
        var failure: Throwable? = null
        try {
            withTreeCommand(target) {
                val runtime = commandCoordinator.load(target.conversationId)
                ownerRuntime = runtime
                owned = sideEffects.launchCompression(runtime, target.selection.access, additionalPrompt, targetTokens, keepRecentMessages) { nodes ->
                    subAssistantLifecycle.commitSummary(runtime.durable, nodes)
                }
            }
            return requireNotNull(owned).await()
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try { withContext(NonCancellable) {
                owned?.let { worker ->
                    worker.cancelAndJoin()
                    requireNotNull(ownerRuntime).releaseAuxiliaryModels(listOf(worker))
                }
            } } catch (error: Throwable) {
                if (failure == null) throw error
                if (failure !== error) failure.addSuppressed(error)
            }
        }
    }

    suspend fun moveToFolder(access: ConversationFolderAccess, conversation: ConversationSummary, folderId: Uuid?) =
        withFolderAccess(access) {
            check(conversation.selection == access.selection) { "conversation_selection_mismatch" }
            val conversationId = conversation.id
            folderId?.let { requireFolder(access, it) }
            commandCoordinator.withRootHeaders(access.selection.access.scope, listOf(conversationId)) { headers ->
                check(headers.single().assistantId == access.assistantId) { "conversation_assistant_mismatch" }
                commandCoordinator.executeOrThrow(conversationId, UpdateHeader(
                    folderId = folderId?.let(OptionalFolderId::SetTo) ?: OptionalFolderId.Clear,
                ))
            }
        }

    suspend fun deleteFolder(access: ConversationFolderAccess, folderId: Uuid) = withFolderAccess(access) {
        requireFolder(access, folderId)
        val ids = folderRepository.getConversationIds(folderId)
        commandCoordinator.withRootHeaders(access.selection.access.scope, ids) { headers ->
            check(folderRepository.getConversationIds(folderId).toSet() == ids.toSet()) { "folder_membership_changed" }
            check(headers.all { it.assistantId == access.assistantId && it.folderId == folderId }) { "folder_membership_mismatch" }
            if (ids.any { runtimeRegistry.findRuntime(it)?.currentTurnPresentation()?.isActive == true }) {
                throw ConversationFolderBusyException()
            }
            // A failed detach leaves the folder present. Retrying only detaches its remaining members.
            ids.forEach { commandCoordinator.executeOrThrow(it, UpdateHeader(folderId = OptionalFolderId.Clear)) }
            folderRepository.deleteEmptyFolder(folderId)
        }
    }

    suspend fun createFolder(access: ConversationFolderAccess, name: String) = withFolderAccess(access) {
        val trimmed = name.trim().also { require(it.isNotEmpty()) { "folder_name_required" } }
        settingsStore.withResolvedConfiguration(access.selection.access.scope, sessions.state.value) { configuration ->
            check(configuration.selection(ConfigurationCategory.ASSISTANT, access.assistantId).isAvailable) {
                "conversation_assistant_unavailable"
            }
        }
        folderRepository.createFolder(access.selection.access.scope, access.assistantId, trimmed)
        Unit
    }

    suspend fun renameFolder(access: ConversationFolderAccess, folderId: Uuid, name: String) = withFolderAccess(access) {
        requireFolder(access, folderId)
        val trimmed = name.trim().also { require(it.isNotEmpty()) { "folder_name_required" } }
        folderRepository.renameFolder(folderId, trimmed)
    }

    private suspend fun requireFolder(access: ConversationFolderAccess, id: Uuid) {
        val folder = requireNotNull(folderRepository.getFolder(id)) { "folder_not_found" }
        check(folder.scope == access.selection.access.scope && folder.assistantId == access.assistantId) { "folder_scope_mismatch" }
    }

    private suspend fun <T> withFolderAccess(access: ConversationFolderAccess, operation: suspend () -> T): T {
        recoveryGate.awaitReady()
        return sessions.withSelectedRealmSelection(access.selection, operation)
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

    suspend fun moveToAssistant(
        assistantTarget: ConversationAssistantTarget,
        assistantId: ConfigurationReference,
        selectForNewChats: Boolean,
    ) {
        val target = assistantTarget.conversation
        withCommandTarget(target) {
        settingsStore.withResolvedConfiguration(target.selection.access.scope, sessions.state.value) { configuration ->
            check(configuration.selection(ConfigurationCategory.ASSISTANT, assistantId).isAvailable) { "conversation_assistant_unavailable" }
            commandCoordinator.withRootHeaders(target.selection.access.scope, listOf(target.conversationId)) { headers ->
                target.requireOpen()
                check(headers.single().assistantId == assistantTarget.assistantId) { "conversation_assistant_changed" }
                commandCoordinator.executeOrThrow(target.conversationId, MoveToAssistant(assistantId))
            }
        }
        if (selectForNewChats) writeSelectedAssistant(target.selection.access, assistantId)
        }
    }

    suspend fun delete(target: ConversationCommandTarget) {
        stopGeneration(target)
        withTreeCommand(target) {
            val childIds = conversationRepo.getChildConversationIds(target.conversationId)
            commandCoordinator.deleteOrThrow(target.conversationId)
            (childIds + target.conversationId).forEach(sideEffects::clearTitleTracking)
        }
    }

    suspend fun deleteForUndo(target: ConversationCommandTarget): RestoreToken {
        stopGeneration(target)
        var retention: ArtifactRetentionLease? = null
        try {
            return withTreeCommand(target) {
                val deleted = commandCoordinator.deleteCapturingTree(target.conversationId) { tree ->
                    retention = artifactStore.retainNodesForUndo(tree.root.header.scope, (listOf(tree.root) + tree.children).map { it.nodes })
                }
                (deleted.children.map { it.conversationId } + deleted.root.conversationId).forEach(sideEffects::clearTitleTracking)
                RestoreToken(deleted.root, deleted.children, target.selection, requireNotNull(retention))
            }
        } catch (error: Throwable) {
            retention?.close()
            throw error
        }
    }

    suspend fun restore(token: RestoreToken) {
        token.claim()
        try {
            recoveryGate.awaitReady()
            sessions.withSelectedRealmSelection(token.selection) {
                check(token.root.header.scope == token.selection.access.scope && token.root.header.parentConversationId == null)
                check(token.children.all { it.header.scope == token.root.header.scope && it.header.parentConversationId == token.root.conversationId })
                if (token.children.isEmpty()) commandCoordinator.createSnapshot(token.root)
                else commandCoordinator.createTree(token.root, token.children)
            }
        } finally { token.finishRestore() }
    }

    fun discardRestoreToken(token: RestoreToken) = token.close()

    /** Deletes the set the user reviewed; conversations created later are not silently included. */
    suspend fun deleteConversations(targets: List<ConversationCommandTarget>) {
        val first = targets.firstOrNull() ?: return
        withCommandTarget(first) {
            check(targets.all { it.selection == first.selection }) { "conversation_selection_mismatch" }
            targets.forEach { it.requireOpen() }
            commandCoordinator.withRootHeaders(first.selection.access.scope, targets.map { it.conversationId }) {}
        }
        targets.distinctBy { it.conversationId }.forEach { delete(it) }
    }

    internal suspend fun cancelGenerationsForAssistant(assistantId: ConfigurationReference, reason: String) {
        val requests = mutableListOf<TurnFinalizer.StopRequest>()
        runtimeRegistry.activeRuntimes()
            .sortedBy { it.durable.header.parentConversationId == null }.forEach { runtime ->
                commandCoordinator.withResidentRuntime(runtime.id) { current ->
                    if (current === runtime) {
                        turnFinalizer.captureStop(current.id, reason, assistantId)?.let(requests::add)
                    }
                }
            }
        requests.forEach { turnFinalizer.awaitStoppedWorkers(it) }
        stopCapturedRequests { it.addAll(requests) }
    }

    internal suspend fun deleteOfAssistantFromPendingCleanup(assistantId: ConfigurationReference) {
        conversationRepo.getConversationsOfAssistant(ConfigurationScope.Personal, assistantId).first().forEach { conversation ->
            turnFinalizer.stopTurn(conversation.id)
            val childIds = conversationRepo.getChildConversationIds(conversation.id)
            commandCoordinator.deleteFromPendingCleanup(conversation.id)
            (childIds + conversation.id).forEach(sideEffects::clearTitleTracking)
        }
    }

    suspend fun stopGeneration(target: ConversationCommandTarget) = stopCapturedRequests { owned ->
        withRootCommand(target) { turnFinalizer.captureStop(target.conversationId)?.let(owned::add) }
    }

    /** CLOSING prevents any new Session from owning this scope until every captured worker is finalized. */
    internal suspend fun stopEnterpriseWork(token: net.weero.measix.pilot.data.enterprise.EnterpriseExitToken) {
        stopCapturedRequests { owned ->
            sessions.withClosingSession(token) {
                val runtimes = runtimeRegistry.activeRuntimes().filter { it.durable.header.scope == token.access.scope }
                    .sortedBy { it.durable.header.parentConversationId == null }
                runtimes.forEach { runtime ->
                    commandCoordinator.withResidentRuntime(runtime.id) { current ->
                        if (current != null) {
                            check(current.durable.header.scope == token.access.scope) { "enterprise_exit_runtime_changed" }
                            turnFinalizer.captureStop(current.id)?.let(owned::add)
                        }
                    }
                }
            }
        }
        requireEnterpriseStopped(token)
    }

    /** Also used after startup TurnRecovery, without waiting for the gate that recovery itself owns. */
    internal suspend fun requireEnterpriseStopped(token: net.weero.measix.pilot.data.enterprise.EnterpriseExitToken) {
        sessions.withClosingSession(token) {
            check(runtimeRegistry.activeRuntimes().filter { it.durable.header.scope == token.access.scope }.none {
                it.hasAuxiliaryWork || it.currentWorker()?.isCompleted == false || it.snapshot.value.stream != null
            }) { "enterprise_workers_pending" }
            check(conversationRepo.countUnfinishedTurns(token.access.scope) == 0) { "enterprise_turns_pending" }
        }
    }

    /** CLOSING owns this maintenance request, including cold recovery before the application gate opens. */
    internal suspend fun clearEnterpriseData(token: net.weero.measix.pilot.data.enterprise.EnterpriseExitToken) {
        require(token.reason == net.weero.measix.pilot.data.enterprise.EnterpriseExitReason.CLEAR_EXAMPLE_DATA)
        requireEnterpriseStopped(token)
        val scope = token.access.scope
        conversationRepo.getRootIds(scope).forEach { id ->
            val childIds = conversationRepo.getChildConversationIds(id)
            commandCoordinator.deleteFromPendingCleanup(id)
            (childIds + id).forEach(sideEffects::clearTitleTracking)
        }
        runtimeRegistry.activeRuntimes().filter { it.durable.header.scope == scope }.forEach { runtime ->
            check(runtimeRegistry.isDraft(runtime.id)) { "enterprise_conversation_cleanup_incomplete" }
            runtimeRegistry.evictRuntime(runtime.id)
        }
        folderRepository.getIdsInScope(scope).forEach { folderRepository.deleteEmptyFolder(it) }
    }

    /** OS foreground timeout owns stopping the exact working requests captured at that event. */
    internal suspend fun stopForForegroundTimeout() {
        recoveryGate.awaitReady()
        stopCapturedRequests { owned ->
            val ids = runtimeRegistry.activeRuntimes().map { it.durable.conversationId }
            ids.forEach { id ->
                commandCoordinator.withResidentRuntime(id) { runtime ->
                    if (runtime != null && runtime.durable.header.parentConversationId == null && runtime.currentTurnPresentation().isWorking) {
                        turnFinalizer.captureStop(id)?.let(owned::add)
                    }
                }
            }
        }
    }

    private suspend fun stopCapturedRequests(capture: suspend (MutableList<TurnFinalizer.StopRequest>) -> Unit) {
        val owned = mutableListOf<TurnFinalizer.StopRequest>()
        var primary: Throwable? = null
        try { capture(owned) }
        catch (error: Throwable) { primary = error; throw error }
        finally {
            withContext(NonCancellable) {
                var failure: Throwable? = primary
                owned.forEach { request ->
                    try { turnFinalizer.finishStop(request) }
                    catch (error: Throwable) {
                        if (failure == null) failure = error else failure!!.addSuppressed(error)
                    }
                }
                if (primary == null) failure?.let { throw it }
            }
        }
    }

    suspend fun togglePin(target: ConversationCommandTarget) = withRootCommand(target) {
        commandCoordinator.executeOrThrow(target.conversationId, TogglePinned)
    }

    suspend fun editMessage(
        target: ConversationCommandTarget,
        messageId: Uuid,
        parts: List<UIMessagePart>,
        artifactDraftScope: ArtifactDraftScope? = null,
    ) {
        if (parts.isEmptyInputMessage()) return
        artifactDraftScope?.requireTarget(target)
        withCommandTarget(target) {
            settingsStore.withResolvedConfiguration(target.selection.access.scope, sessions.state.value) { configuration ->
                commandCoordinator.withRootHeaders(target.selection.access.scope, listOf(target.conversationId)) {
                    target.requireOpen()
                    val snapshot = liveSnapshot(target.conversationId)
                    val assistant = requireNotNull(configuration.assistants[snapshot.header.assistantId]) { "conversation_assistant_missing" }
                    val node = snapshot.nodes.firstOrNull { node -> node.messages.any { it.id == messageId } }
                        ?: throw NoSuchElementException("Message not found")
                    val processedParts = preprocessUserInputParts(parts, assistant)
                    commandCoordinator.executeOrThrow(target.conversationId, EditMessageVariant(
                        nodeId = node.id, variant = UIMessage(role = node.currentMessage.role, parts = processedParts),
                    ))
                    artifactDraftScope?.publishCommittedReferences(processedParts)
                }
            }
        }
    }

    suspend fun forkAtMessage(target: ConversationCommandTarget, messageId: Uuid): Uuid {
        val conversationId = target.conversationId
        stopGeneration(target)
        val (current, sourceChildren) = withTreeCommand(target) {
            subAssistantLifecycle.requireClosedRunsBeforeTreeMutation(liveSnapshot(conversationId)) to
                conversationRepo.getChildConversationSnapshots(conversationId).associateBy { it.conversationId }
        }
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
                        message.copy(parts = withCommandTarget(target) { cloneParts(message.parts, owned, copiedArtifacts) })
                    },
                )
            }
            val forkId = Uuid.random()
            forkIdForCleanup = forkId
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
                            message.copy(parts = withCommandTarget(target) { cloneParts(message.parts, owned, copiedArtifacts) })
                        })
                    },
                )
            }
            withCommandTarget(target) {
                createAttempted = true
                commandCoordinator.createTree(fork, children, target::requireOpen)
                artifactStore.publishAllUnpublished(owned)
                committed = true
            }
            return fork.conversationId
        } catch (error: Throwable) {
            primaryFailure = error
            if (!committed && createAttempted && error !is ConversationCommandConflictException) {
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

    /** Favorite mutations share the conversation lock with node and tree deletion. */
    internal suspend fun <T> withFavoriteNode(
        target: ConversationCommandTarget,
        nodeId: Uuid,
        operation: suspend (net.weero.measix.pilot.data.model.NodeFavoriteTarget) -> T,
    ): T = withRootCommand(target) {
        val snapshot = liveSnapshot(target.conversationId)
        check(!snapshot.header.newConversation) { "favorite_requires_persisted_conversation" }
        val node = snapshot.nodes.singleOrNull { it.id == nodeId } ?: error("favorite_node_missing")
        target.requireOpen()
        sessions.requirePublishedSelection(target.selection)
        operation(net.weero.measix.pilot.data.model.NodeFavoriteTarget(
            scope = snapshot.header.scope,
            conversationId = snapshot.conversationId,
            conversationTitle = snapshot.header.title,
            nodeId = node.id,
            node = node,
        ))
    }

    suspend fun selectNode(target: ConversationCommandTarget, nodeId: Uuid, selectIndex: Int) = withRootCommand(target) {
        val snapshot = liveSnapshot(target.conversationId)
        val node = snapshot.nodes.firstOrNull { it.id == nodeId } ?: throw NoSuchElementException("Message node not found")
        require(selectIndex in node.messages.indices) { "Invalid selectIndex" }
        commandCoordinator.executeOrThrow(target.conversationId, SelectNodeVariant(nodeId, selectIndex))
    }

    suspend fun deleteMessage(target: ConversationCommandTarget, message: UIMessage) {
        stopGeneration(target)
        withTreeCommand(target) {
            val snapshot = liveSnapshot(target.conversationId)
            if (snapshot.nodes.none { node -> node.messages.any { it.id == message.id } }) return@withTreeCommand
            subAssistantLifecycle.requireClosedRunsBeforeTreeMutation(snapshot)
            commandCoordinator.executeOrThrow(target.conversationId, DeleteMessage(message.id))
            subAssistantLifecycle.applyRetentionAfterTreeMutation(target.conversationId)
        }
    }

    private suspend fun writeSelectedAssistant(access: RealmAccess, assistantId: ConfigurationReference) {
        when (access) {
            RealmAccess.Personal -> settingsStore.updateLocal { it.copy(assistantId = assistantId) }
            is RealmAccess.Enterprise -> settingsStore.updateResourceSelections(
                access.scope, sessions.state.value as net.weero.measix.pilot.data.enterprise.EnterpriseState.Available,
                requireOwner = { sessions.requirePublishedRealmAccess(access) },
            ) { it.copy(assistantId = assistantId) }
        }
    }

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

class ConversationFolderBusyException internal constructor() : IllegalStateException("folder_has_active_conversation")

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
