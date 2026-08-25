package net.weero.measix.pilot.service

import android.app.Application
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.MessageTerminalStatus
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.TurnTerminalReasons
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.findUserTurnStart
import me.rerere.ai.ui.canResumeToolExecution
import me.rerere.ai.ui.finishInterruptedTools
import me.rerere.ai.ui.finishPendingTools
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.common.android.Logging
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.event.AppEvent
import net.weero.measix.pilot.data.event.AppEventBus
import net.weero.measix.pilot.data.ai.GenerationHandler
import net.weero.measix.pilot.data.ai.ToolExecutionEventStatus
import net.weero.measix.pilot.data.ai.replaceToolsAtOrdinals
import net.weero.measix.pilot.data.db.entity.ToolExecutionStatus
import net.weero.measix.pilot.data.ai.tools.shouldUseExternalWebSearch
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallState
import net.weero.measix.pilot.data.ai.subassistant.buildSubAssistantCallResult
import net.weero.measix.pilot.data.ai.subassistant.getSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.mergeSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.parseAssistantCallExtrasFromInput
import net.weero.measix.pilot.data.ai.mcp.McpManager
import net.weero.measix.pilot.data.ai.tools.AssistantToolFactory
import net.weero.measix.pilot.data.ai.tools.local.TtsToolPlaybackContext
import net.weero.measix.pilot.data.ai.tts.TtsPlaybackSource
import net.weero.measix.pilot.data.ai.tools.GenerationToolSetFactory
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.data.ai.transformers.AttachmentProjectionTransformer
import net.weero.measix.pilot.data.ai.transformers.Base64ImageToLocalFileTransformer
import net.weero.measix.pilot.data.ai.transformers.TemplateTransformer
import net.weero.measix.pilot.data.ai.transformers.ToolArtifactReplayTransformer
import net.weero.measix.pilot.data.ai.transformers.WorkspaceReminderTransformer
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.findModelById
import net.weero.measix.pilot.data.datastore.findProvider
import net.weero.measix.pilot.data.datastore.getAssistantById
import net.weero.measix.pilot.data.datastore.getChatModel
import net.weero.measix.pilot.data.datastore.getCurrentAssistant
import net.weero.measix.pilot.data.datastore.getCurrentChatModel
import net.weero.measix.pilot.data.files.ToolArtifactRewriter
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.AssistantAffectScope
import net.weero.measix.pilot.data.model.replaceRegexes
import net.weero.measix.pilot.data.model.toMessageNode
import net.weero.measix.pilot.data.repository.MemoryRepository
import net.weero.measix.pilot.data.repository.WorkspaceRepository
import net.weero.measix.pilot.utils.applyPlaceholders
import java.time.Instant
import java.util.Locale
import kotlinx.datetime.LocalDateTime
import kotlin.uuid.Uuid
import net.weero.measix.pilot.service.runtime.ConversationRuntime
import net.weero.measix.pilot.service.runtime.ConversationRuntimeRegistry
import net.weero.measix.pilot.service.runtime.ConversationCommandCoordinator
import net.weero.measix.pilot.service.runtime.ConversationSnapshot
import net.weero.measix.pilot.service.runtime.TurnPipelineFactory
import net.weero.measix.pilot.service.runtime.TurnEngine
import net.weero.measix.pilot.service.runtime.TurnEvent
import net.weero.measix.pilot.service.runtime.TurnHandle
import net.weero.measix.pilot.service.runtime.TurnOutcome
import net.weero.measix.pilot.service.runtime.AppendUserMessage
import net.weero.measix.pilot.service.runtime.ActiveTurnState
import net.weero.measix.pilot.service.runtime.BackfillAttachmentRefs
import net.weero.measix.pilot.service.runtime.CommitCheckpoint
import net.weero.measix.pilot.service.runtime.DelegationCoordinator
import net.weero.measix.pilot.service.runtime.DeleteMessage
import net.weero.measix.pilot.service.runtime.EditMessageVariant
import net.weero.measix.pilot.service.runtime.FinalizeTurn
import net.weero.measix.pilot.service.runtime.OptionalFolderId
import net.weero.measix.pilot.service.runtime.ReplaceMessageTree
import net.weero.measix.pilot.service.runtime.SelectNodeVariant
import net.weero.measix.pilot.service.runtime.TruncateToNodeIndex
import net.weero.measix.pilot.service.runtime.UpdateHeader
import net.weero.measix.pilot.service.runtime.UpdateToolApproval

private const val TAG = "MasterTurnCoordinator"

/** Master generation has two non-interchangeable entry protocols. */
internal enum class MasterTurnEntry {
    /** Opens a new durable turn after structural preflight. */
    START,

    /** Continues the existing approval-paused owner without mutating the message tree. */
    CONTINUE_APPROVAL,
}

internal data class MasterTurnLaunchPolicy(
    val runStructuralPreflight: Boolean,
    val reuseTtsQueue: Boolean,
)

/**
 * Validates the entry against its owning snapshot before any command is submitted.
 * Approval continuation must retain the current turn owner and cannot become a second start path.
 */
internal fun masterTurnLaunchPolicy(
    entry: MasterTurnEntry,
    snapshot: ConversationSnapshot,
    turnId: Uuid,
    messageRange: ClosedRange<Int>?,
): MasterTurnLaunchPolicy = when (entry) {
    MasterTurnEntry.START -> {
        check(snapshot.activeTurn == null) { "a new master turn cannot start while another turn is active" }
        MasterTurnLaunchPolicy(runStructuralPreflight = true, reuseTtsQueue = false)
    }

    MasterTurnEntry.CONTINUE_APPROVAL -> {
        check(messageRange == null) { "an approval continuation cannot use a message range" }
        val active = requireNotNull(snapshot.activeTurn) { "approval continuation has no active turn" }
        check(active.turnId == turnId) {
            "active turn ${active.turnId} does not match approval continuation $turnId"
        }
        MasterTurnLaunchPolicy(runStructuralPreflight = false, reuseTtsQueue = true)
    }
}

/**
 * Approval decisions are continuations of the active durable turn. This orchestration seam owns
 * the exact approval command and makes it impossible for approve/deny to enter the new-turn
 * structural preflight path.
 */
internal suspend fun applyToolApprovalDecision(
    locator: ToolCallLocator,
    approvalState: ToolApprovalState,
    awaitPreviousGeneration: suspend () -> Unit,
    currentSnapshot: () -> ConversationSnapshot,
    submit: suspend (UpdateToolApproval) -> Unit,
    onMoreApprovalsPending: suspend () -> Unit,
    continueTurn: suspend (ActiveTurnState, MasterTurnEntry) -> Unit,
) {
    awaitPreviousGeneration()
    val before = currentSnapshot()
    val pending = before.currentMessages()
        .firstOrNull { it.id == locator.messageId }
        ?.getTools()
        ?.getOrNull(locator.toolOrdinal)
        ?.takeIf { it.approvalState == ToolApprovalState.Pending }
        ?: return
    check(!pending.isExecuted) { "executed tool cannot accept an approval decision" }

    submit(
        UpdateToolApproval(
            messageId = locator.messageId,
            toolOrdinal = locator.toolOrdinal,
            approvalState = approvalState,
        ),
    )

    val after = currentSnapshot()
    val committed = after.currentMessages()
        .firstOrNull { it.id == locator.messageId }
        ?.getTools()
        ?.getOrNull(locator.toolOrdinal)
    check(committed?.approvalState == approvalState) { "tool approval command was not committed" }
    if (after.currentMessages().lastOrNull()?.getTools()?.any { it.isPending } == true) {
        onMoreApprovalsPending()
        return
    }
    continueTurn(
        requireNotNull(after.activeTurn) { "decided tool has no owning active turn" },
        MasterTurnEntry.CONTINUE_APPROVAL,
    )
}

/**
 * 主回合生成编排器。持久化命令、终态处理和边缘副作用分别委托给各自 owner。
 */

internal fun retainValidMessageNodes(nodes: List<MessageNode>): List<MessageNode> {
    var messagesNodes = nodes.map { node ->
        val current = runCatching { node.currentMessage }.getOrNull() ?: return@map node
        val tools = current.getTools()
        val hasUnexecutedTools = tools.any { !it.isExecuted }
        if (!hasUnexecutedTools) return@map node
        if (tools.any { !it.isExecuted && (it.isPending || it.approvalState.canResumeToolExecution()) }) {
            return@map node
        }
        if (current.terminalStatus != null) return@map node
        node.copy(
            messages = node.messages.filter { it.id != current.id },
            selectIndex = node.selectIndex - 1,
        )
    }
    messagesNodes = messagesNodes.map { node ->
        if (node.messages.isNotEmpty() && node.selectIndex !in node.messages.indices) {
            node.copy(selectIndex = 0)
        } else {
            node
        }
    }
    return messagesNodes.filter { it.messages.isNotEmpty() }
}
/** Captures the previous turn and guarantees its durable terminal write precedes the next tree mutation. */
internal class SupersededTurnBarrier(
    private val previousJob: Job?,
    val previousTurnId: Uuid?,
) {
    suspend fun awaitDurableFinalization(finalize: suspend (Uuid?) -> Unit) {
        previousJob?.join()
        finalize(previousTurnId)
    }
}

internal fun beginSupersedingTurn(
    previousJob: Job?,
    previousTurnId: Uuid?,
    requestCancellation: (Uuid) -> Unit,
): SupersededTurnBarrier {
    if (previousJob != null && previousTurnId != null) {
        requestCancellation(previousTurnId)
        previousJob.cancel()
    }
    return SupersededTurnBarrier(previousJob, previousTurnId)
}

class MasterTurnCoordinator(
    private val context: Application,
    private val appScope: AppScope,
    private val appEventBus: AppEventBus,
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val generationHandler: GenerationHandler,
    private val templateTransformer: TemplateTransformer,
    private val mcpManager: McpManager,
    private val toolSetFactory: GenerationToolSetFactory,
    private val workspaceRepository: WorkspaceRepository,
    private val assistantToolFactory: AssistantToolFactory,
    private val delegationCoordinator: DelegationCoordinator,
    private val turnFinalization: TurnFinalization,
    private val subAssistantLifecycle: SubAssistantLifecycle,
    private val runtimeRegistry: ConversationRuntimeRegistry,
    private val commandCoordinator: ConversationCommandCoordinator,
    private val recoveryGate: ApplicationRecoveryGate,
    private val chatErrorStore: ChatErrorStore,
    private val sideEffects: GenerationSideEffects,
    private val artifactStore: ArtifactStore,
    private val artifactUseCase: ArtifactUseCase,
    private val toolArtifactRewriter: ToolArtifactRewriter,
    private val titleCoordinator: ConversationTitleCoordinator,
) {
    // workspace 系统提示注入 (依赖 workspaceRepository, 故在类内构造)
    private val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)
    private val toolArtifactReplayTransformer = ToolArtifactReplayTransformer(toolArtifactRewriter)

    // Master/Target 共用管道装配（取代顶层 inputTransformers/outputTransformers val）
    private val turnPipelineFactory = TurnPipelineFactory(
        templateTransformer = templateTransformer,
        workspaceReminderTransformer = workspaceReminderTransformer,
        toolArtifactReplayTransformer = toolArtifactReplayTransformer,
        attachmentProjectionTransformer = AttachmentProjectionTransformer(artifactStore),
        base64ImageToLocalFileTransformer = Base64ImageToLocalFileTransformer(artifactStore),
    )

    // 生成完成流
    private val _generationDoneFlow = MutableSharedFlow<Uuid>()
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()

    // 前台状态管理
    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> _isForeground.value = true
            Lifecycle.Event.ON_STOP -> _isForeground.value = false
            else -> {}
        }
    }

    init {
        // 添加生命周期观察者
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
        // 预加载 loop 声音反馈资源（生成副作用域）
        sideEffects.preloadSoundEffects()
    }

    // ---- Runtime 管理 ----

    private fun requireRuntime(conversationId: Uuid): ConversationRuntime {
        return runtimeRegistry.requireRuntime(conversationId)
    }

    private fun launchWithRuntimeLease(
        conversationId: Uuid,
        block: suspend () -> Unit
    ): Job = appScope.launch {
        val lease = runtimeRegistry.acquireRuntime(conversationId)
        try {
            block()
        } finally {
            lease.close()
        }
    }

    // ---- 对话状态访问 ----

    private fun liveSnapshot(conversationId: Uuid): ConversationSnapshot =
        runtimeRegistry.requireRuntime(conversationId).snapshot.value

    // ---- 发送消息 ----

    fun sendMessage(
        conversationId: Uuid,
        content: List<UIMessagePart>,
        answer: Boolean = true,
        artifactDraftScope: ArtifactDraftScope? = null,
    ) {
        if (content.isEmptyInputMessage()) return

        val runtime = requireRuntime(conversationId)
        val previousJob = runtime.getJob()
        val previousTurnId = runtime.currentGenerationTurnId() ?: runtime.snapshot.value.activeTurn?.turnId
        val superseded = beginSupersedingTurn(previousJob, previousTurnId) { turnId ->
            runtime.requestCancel(turnId, TurnTerminalReasons.SUPERSEDED_BY_NEW_TURN)
        }

        val turnId = Uuid.random()
        runtime.trackGenerationTurn(turnId)
        val job = appScope.launch {
            try {
                recoveryGate.awaitReady()
                superseded.awaitDurableFinalization { turnId ->
                    turnFinalization.finalizeSupersededTurn(conversationId, turnId)
                }

                val currentSnapshot = runtime.snapshot.value
                val settings = settingsStore.settingsFlow.first()
                val assistant = settings.getAssistantById(currentSnapshot.header.assistantId)
                    ?: settings.getCurrentAssistant()
                val processedContent = preprocessUserInputParts(content, assistant)
                val userMessage = UIMessage(
                    role = MessageRole.USER,
                    parts = processedContent,
                )
                val localTitle = deriveLocalConversationTitle(userMessage)

                // append 用户消息走命令协议（AppendUserMessage → reducer → delta 落库；
                // reducer 同时清理 newConversation 运行态标记）
                commandCoordinator.executeOrThrow(
                    conversationId,
                    AppendUserMessage(
                        message = userMessage,
                        initialTitle = localTitle,
                    ),
                )
                if (currentSnapshot.header.title.isBlank()) {
                    val committedHeader = runtime.snapshot.value.header
                    titleCoordinator.synchronize(
                        conversationId = conversationId,
                        title = committedHeader.title,
                        localFallbackTitle = localTitle,
                    )
                }
                artifactDraftScope?.publishCommittedReferences(processedContent)

                // 开始补全
                if (answer) {
                    launchRun(conversationId, turnId = turnId)
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                chatErrorStore.add(e, conversationId, title = context.getString(R.string.error_title_send_message))
            }
        }
        runtime.setJob(job, turnId)
    }

    // ---- 重新生成消息 ----

    fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        val runtime = requireRuntime(conversationId)
        val previousJob = runtime.getJob()
        val previousTurnId = runtime.currentGenerationTurnId() ?: runtime.snapshot.value.activeTurn?.turnId
        val superseded = beginSupersedingTurn(previousJob, previousTurnId) { turnId ->
            runtime.requestCancel(turnId, TurnTerminalReasons.SUPERSEDED_BY_NEW_TURN)
        }

        val turnId = Uuid.random()
        runtime.trackGenerationTurn(turnId)
        val job = appScope.launch {
            try {
                recoveryGate.awaitReady()
                superseded.awaitDurableFinalization { turnId ->
                    turnFinalization.finalizeSupersededTurn(conversationId, turnId)
                }
                val snapshot = subAssistantLifecycle.finalizeRunsBeforeTreeMutation(runtime.snapshot.value)

                if (message.role == MessageRole.USER) {
                    // 如果是用户消息，则截止到当前消息（TruncateToNodeIndex，master 树 delta 落库）
                    val indexAt = snapshot.nodes.indexOfFirst { node ->
                        node.messages.any { it.id == message.id }
                    }
                    check(indexAt >= 0) { "Message not found: ${message.id}" }
                    commandCoordinator.executeOrThrow(conversationId, TruncateToNodeIndex(nodeIndexInclusive = indexAt))
                    subAssistantLifecycle.applyRetentionAfterTreeMutation(conversationId)
                    launchRun(conversationId, turnId = turnId)
                } else {
                    if (regenerateAssistantMsg) {
                        val nodeIndex = snapshot.nodes.indexOfFirst { node ->
                            node.messages.any { it.id == message.id }
                        }
                        check(nodeIndex >= 0) { "Message not found: ${message.id}" }
                        launchRun(conversationId, turnId = turnId, messageRange = 0..<nodeIndex)
                    } else {
                        // 变更前的 stale run 已被收口，将结果树同步落库。
                        commandCoordinator.executeOrThrow(conversationId, ReplaceMessageTree(snapshot.nodes))
                    }
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                chatErrorStore.add(e, conversationId, title = context.getString(R.string.error_title_regenerate_message))
            }
        }

        runtime.setJob(job, turnId)
    }

    // ---- 处理工具调用审批 ----

    fun handleToolApproval(
        conversationId: Uuid,
        locator: ToolCallLocator,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
    ) {
        val runtime = requireRuntime(conversationId)
        appScope.launch {
            try {
                recoveryGate.awaitReady()
                runtime.withToolApprovalLock {
                    val newApprovalState = when {
                        answer != null -> ToolApprovalState.Answered(answer)
                        approved -> ToolApprovalState.Approved
                        else -> ToolApprovalState.Denied(reason)
                    }
                    applyToolApprovalDecision(
                        locator = locator,
                        approvalState = newApprovalState,
                        // Pending is emitted immediately before the Flow terminates. Joining the
                        // previous job guarantees its checkpoint is durable before the decision.
                        awaitPreviousGeneration = { runtime.getJob()?.join() },
                        currentSnapshot = { runtime.snapshot.value },
                        submit = { command -> commandCoordinator.executeOrThrow(conversationId, command) },
                        onMoreApprovalsPending = { _generationDoneFlow.emit(conversationId) },
                        continueTurn = { owner, entry ->
                            val turnId = owner.turnId
                            runtime.trackGenerationTurn(turnId)
                            val resumeJob = appScope.launch {
                                launchRun(
                                    conversationId = conversationId,
                                    turnId = turnId,
                                    entry = entry,
                                )
                                _generationDoneFlow.emit(conversationId)
                            }
                            runtime.setJob(resumeJob, turnId)
                        },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                chatErrorStore.add(e, conversationId, title = context.getString(R.string.error_title_tool_approval))
            }
        }
    }

    fun handleSubAssistantAnswer(runId: String, interactionId: String, answer: String): Boolean {
        return delegationCoordinator.answerUserInteraction(runId, interactionId, answer)
    }

    // ---- 处理消息补全 ----

    private suspend fun launchRun(
        conversationId: Uuid,
        turnId: Uuid,
        messageRange: ClosedRange<Int>? = null,
        entry: MasterTurnEntry = MasterTurnEntry.START,
    ) {
        var turnOutcome: TurnOutcome? = null
        var inFlightAssistantId: Uuid? = null
        var senderName: String? = null
        var generationSoundEnabled = false
        var turnEngine: TurnEngine? = null
        var startedRuntime: ConversationRuntime? = null
        try {
            val runtime = requireRuntime(conversationId)
            startedRuntime = runtime
            val settings = settingsStore.settingsFlow.first()
            val initialSnapshot = liveSnapshot(conversationId)
            val assistant = settings.getAssistantById(initialSnapshot.header.assistantId)
                ?: settings.getCurrentAssistant()
            generationSoundEnabled = settings.displaySetting.enableMessageGenerationSoundEffect
            val launchPolicy = masterTurnLaunchPolicy(entry, initialSnapshot, turnId, messageRange)

            if (launchPolicy.runStructuralPreflight) {
                // Structural maintenance belongs exclusively to START. Approval and denial both
                // continue the existing turn and must never submit tree commands while it is active.
                commandCoordinator.executeOrThrow(conversationId, UpdateHeader(suggestions = emptyList()))
                checkInvalidMessages(conversationId)
                val attachmentRefBackfills = planDurableAttachmentRefBackfills(liveSnapshot(conversationId))
                if (attachmentRefBackfills.isNotEmpty()) {
                    commandCoordinator.executeOrThrow(
                        conversationId,
                        BackfillAttachmentRefs(attachmentRefBackfills),
                    )
                }
            }

            var snapshot = liveSnapshot(conversationId)
            val sourceMessages = if (messageRange != null) {
                snapshot.currentMessages().subList(messageRange.start, messageRange.endInclusive + 1)
            } else {
                snapshot.currentMessages()
            }
            inFlightAssistantId = null
            val resumeFilter: (UIMessage) -> Boolean = { message ->
                message.role == MessageRole.ASSISTANT &&
                    message.getTools().any { !it.isExecuted && it.approvalState.canResumeToolExecution() }
            }
            val prepareFinalize: suspend (TurnOutcome, List<UIMessage>) -> List<UIMessage> =
                { outcome, messages ->
                    if (outcome is TurnOutcome.Cancelled || outcome is TurnOutcome.Failed) {
                        val latestSnapshot = liveSnapshot(conversationId)
                        val active = requireNotNull(latestSnapshot.activeTurn) {
                            "active master turn has no runtime owner"
                        }
                        val assistantMessageId = requireNotNull(inFlightAssistantId) {
                            "active master turn has no assistant message owner"
                        }
                        turnFinalization.prepareOwnedTurnMessagesForFailure(
                            snapshot = latestSnapshot,
                            handle = TurnHandle(
                                conversationId = conversationId,
                                epoch = active.epoch,
                                turnId = turnId,
                                assistantMessageId = assistantMessageId,
                            ),
                            latestMessages = messages,
                            reason = requireNotNull(outcome.terminalReason),
                            cancelledByUser = outcome is TurnOutcome.Cancelled,
                        )
                    } else {
                        val current = liveSnapshot(conversationId).currentMessages()
                        current.ifEmpty { messages }
                    }
                }
            val started = when (entry) {
                MasterTurnEntry.START -> TurnEngine.start(
                    commandCoordinator = commandCoordinator,
                    runtime = runtime,
                    turnId = turnId,
                    messages = sourceMessages,
                    resumeFilter = resumeFilter,
                    prepareFinalize = prepareFinalize,
                )

                MasterTurnEntry.CONTINUE_APPROVAL -> {
                    check(sourceMessages.lastOrNull()?.let(resumeFilter) == true) {
                        "active turn does not point to a resumable approval message"
                    }
                    TurnEngine.continueActive(
                        commandCoordinator = commandCoordinator,
                        runtime = runtime,
                        expectedTurnId = turnId,
                        messages = sourceMessages,
                        prepareFinalize = prepareFinalize,
                    )
                }
            }
            val activeTurnEngine = started.engine
            turnEngine = activeTurnEngine
            inFlightAssistantId = started.assistantMessageId
            val assistantSlot = started.resumableMessage ?: UIMessage(
                id = started.assistantMessageId,
                role = MessageRole.ASSISTANT,
                parts = emptyList(),
            )
            val generationMessages = if (started.resumableMessage == null) sourceMessages + assistantSlot else sourceMessages
            snapshot = liveSnapshot(conversationId)

            val model = settings.getChatModel(assistant)
                ?: error("No chat model is configured for assistant ${assistant.id}")
            senderName = if (assistant.useAssistantAvatar) {
                assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
            } else {
                model.displayName
            }

            // memory tool
            if (!model.abilities.contains(ModelAbility.TOOL)) {
                if (shouldUseExternalWebSearch(assistant, model) || mcpManager.getAllAvailableTools(assistant).isNotEmpty()) {
                    chatErrorStore.add(
                        IllegalStateException(context.getString(R.string.tools_warning)),
                        conversationId,
                        title = context.getString(R.string.error_title_tool_unavailable)
                    )
                }
            }

            snapshot = liveSnapshot(conversationId)

            // start generating
            // loop 声音反馈（生成副作用域：per-turn 去重器）
            val soundTracker = sideEffects.soundTracker()
            // 每轮 Master Generation 创建一个 turn-level TtsToolPlaybackContext，
            // 在整轮 turn 内被 Master 和所有 Target 共享。播放器以 sessionId 独占队列：
            // 新 turn 替换旧队列；同一 turn 是否追加由顺序播放开关决定。
            val turnTtsContext = TtsToolPlaybackContext(
                sessionId = runtime.getTtsQueueSessionId(launchPolicy.reuseTtsQueue),
                assistantId = assistant.id,
                assistantName = assistant.name,
                sourceType = TtsPlaybackSource.SourceType.NORMAL,
            )
            generationHandler.generateText(
                settings = settings,
                model = model,
                processingStatus = runtime.processingStatus,
                messages = generationMessages,
                assistantMessageId = assistantSlot.id,
                assistant = assistant,
                conversationSystemPrompt = snapshot.header.customSystemPrompt,
                conversationModeInjectionIds = snapshot.header.modeInjectionIds,
                workspaceCwd = snapshot.header.workspaceCwd,
                memories = if (assistant.useGlobalMemory) {
                    memoryRepository.getGlobalMemories()
                } else {
                    memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
                },
                inputTransformers = turnPipelineFactory.masterInput(),
                outputTransformers = turnPipelineFactory.masterOutput(),
                tools = toolSetFactory.buildTools(
                    assistant = assistant,
                    settings = settings,
                    capabilityModel = model,
                    workspaceCwd = snapshot.header.workspaceCwd,
                    ttsPlaybackContext = turnTtsContext,
                    additionalToolsBeforeMcp = assistantToolFactory.buildTools(
                        callerAssistant = assistant,
                        masterConversationId = conversationId,
                        ttsPlaybackContext = turnTtsContext,
                    ),
                    onInvalidMcpServerNames = { invalidNames ->
                        chatErrorStore.add(
                            error = IllegalStateException(
                                context.getString(
                                    R.string.error_mcp_invalid_server_name,
                                    invalidNames.joinToString(", ")
                                )
                            ),
                            conversationId = conversationId,
                        )
                    },
                ),
                onCheckpoint = activeTurnEngine::onCheckpoint,
            ).let { source ->
                // 提交协议唯一实现——chunk→applyStreamingDelta（永不落库）、
                // checkpoint→CommitCheckpoint、Finished/异常/取消→FinalizeTurn
                activeTurnEngine.bind(source).collect { event ->
                    when (event) {
                        is TurnEvent.Streaming -> {
                            event.lastMessage?.takeIf { it.role == MessageRole.ASSISTANT }?.id
                                ?.let { inFlightAssistantId = it }

                            // 通知等边缘副作用由 ChatNotificationManager 消费；
                            // tryEmit 不挂起，事件丢失只影响单次通知更新，不能反压生成链
                            event.lastMessage?.let { lastMessage ->
                                appEventBus.tryEmit(
                                    AppEvent.ChatGenerationUpdate(conversationId, lastMessage, senderName.orEmpty())
                                )
                            }

                            // 前台声音反馈: 单步生成完成 + 工具待审批
                            if (isForeground.value && settings.displaySetting.enableMessageGenerationSoundEffect) {
                                soundTracker.onStreaming(event.lastMessage)
                            }
                        }

                        is TurnEvent.Phase -> { }
                        is TurnEvent.Checkpoint -> { }
                        is TurnEvent.Finished -> {
                            turnOutcome = event.outcome
                        }
                    }
                }
            }

            if (turnOutcome is TurnOutcome.Failed && isForeground.value && generationSoundEnabled) {
                sideEffects.playTurnFailedSound()
            }
            applyMasterTurnSideEffects(
                conversationId = conversationId,
                outcome = turnOutcome ?: TurnOutcome.Incomplete(TurnTerminalReasons.PROVIDER_INCOMPLETE),
                inFlightAssistantId = inFlightAssistantId,
                senderName = senderName.orEmpty(),
            )
            if (!shouldLaunchCompletionSideEffects(turnOutcome)) {
                return
            }
            val hasPendingApproval = liveSnapshot(conversationId)
                .currentMessages().lastOrNull()?.getTools()?.any { it.isPending } == true
            if (hasPendingApproval) {
                return
            }

            if (isForeground.value && settings.displaySetting.enableMessageGenerationSoundEffect) {
                sideEffects.playTurnCompleteSound()
            }

            launchWithRuntimeLease(conversationId) {
                sideEffects.generateTitle(liveSnapshot(conversationId))
            }
            launchWithRuntimeLease(conversationId) {
                sideEffects.generateSuggestion(liveSnapshot(conversationId))
            }
        } catch (e: CancellationException) {
            val outcome = TurnOutcome.Cancelled(
                startedRuntime?.consumeCancelReason(turnId) ?: TurnTerminalReasons.USER_STOP
            )
            try {
                withContext(NonCancellable) {
                    turnEngine?.finalizeOwnerFailure(
                        outcome = outcome,
                        messages = startedRuntime?.snapshot?.value?.currentMessages().orEmpty(),
                        closeInterruptedTools = false,
                    )
                }
            } catch (finalizationError: Exception) {
                e.addSuppressed(finalizationError)
            }
            applyMasterTurnSideEffects(
                conversationId = conversationId,
                outcome = outcome,
                inFlightAssistantId = inFlightAssistantId,
                senderName = senderName.orEmpty(),
            )
            throw e
        } catch (e: Exception) {
            var reportedError = e
            var outcome = TurnOutcome.fromFailure(e)
            try {
                withContext(NonCancellable) {
                    turnEngine?.finalizeOwnerFailure(
                        outcome = outcome,
                        messages = startedRuntime?.snapshot?.value?.currentMessages().orEmpty(),
                        closeInterruptedTools = false,
                    )
                }
            } catch (finalizationError: Exception) {
                finalizationError.addSuppressed(e)
                reportedError = finalizationError
                outcome = TurnOutcome.fromFailure(finalizationError)
            }
            Logging.log(TAG, "launchRun failed: ${reportedError.message}")
            Logging.log(TAG, reportedError.stackTraceToString().lines().take(6).joinToString("\n"))
            if (isForeground.value && generationSoundEnabled) {
                sideEffects.playTurnFailedSound()
            }
            applyMasterTurnSideEffects(
                conversationId = conversationId,
                outcome = outcome,
                inFlightAssistantId = inFlightAssistantId,
                senderName = senderName.orEmpty(),
            )
        }
    }

    // ---- 检查无效消息 ----

    private suspend fun checkInvalidMessages(conversationId: Uuid) {
        val snapshot = liveSnapshot(conversationId)
        val messagesNodes = retainValidMessageNodes(snapshot.nodes)
        if (messagesNodes == snapshot.nodes) return
        // 无效消息清理 = 树替换（命令通道 delta 落库）
        commandCoordinator.executeOrThrow(conversationId, ReplaceMessageTree(messagesNodes))
    }

    /** Master 终态副作用（通知与错误上报）；执行事实已由 FinalizeTurn 原子收口。 */
    private suspend fun applyMasterTurnSideEffects(
        conversationId: Uuid,
        outcome: TurnOutcome,
        inFlightAssistantId: Uuid? = null,
        senderName: String,
    ) {
        withContext(NonCancellable) {
            if (outcome is TurnOutcome.Failed) {
                chatErrorStore.add(
                    outcome.error,
                    conversationId,
                    title = context.getString(R.string.error_title_generation),
                )
            }
            val finalMessage = liveSnapshot(conversationId).currentMessages()
                .firstOrNull { it.id == inFlightAssistantId }
            appEventBus.tryEmit(
                AppEvent.ChatGenerationEnded(
                    conversationId = conversationId,
                    senderName = senderName,
                    contentPreview = finalMessage?.toText()?.take(50)?.trim(),
                )
            )
        }
    }
}

/** Backfill plans are derived from durable nodes, never from the per-read rendering overlay. */
internal fun planDurableAttachmentRefBackfills(
    snapshot: ConversationSnapshot,
) = AttachmentRefs.planBackfills(snapshot.nodes)
