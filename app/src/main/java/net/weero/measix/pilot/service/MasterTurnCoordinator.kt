package net.weero.measix.pilot.service

import android.app.Application
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
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
import me.rerere.ai.core.Tool
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
import net.weero.measix.pilot.data.ai.GenerationLoop
import net.weero.measix.pilot.data.ai.GenerationRequest
import net.weero.measix.pilot.data.ai.ToolExecutionEventStatus
import net.weero.measix.pilot.data.ai.replaceToolsAtOrdinals
import net.weero.measix.pilot.data.ai.resolveMemoryOwnerId
import net.weero.measix.pilot.data.db.entity.ToolExecutionStatus
import net.weero.measix.pilot.data.ai.tools.shouldUseExternalWebSearch
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallState
import net.weero.measix.pilot.data.ai.subassistant.buildSubAssistantCallResult
import net.weero.measix.pilot.data.ai.subassistant.getSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.mergeSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.parseAssistantCallExtrasFromInput
import net.weero.measix.pilot.data.ai.mcp.McpRuntimeCoordinator
import net.weero.measix.pilot.data.ai.tools.AssistantToolFactory
import net.weero.measix.pilot.data.ai.tools.buildMemoryTools
import net.weero.measix.pilot.data.ai.tools.local.TtsToolPlaybackContext
import net.weero.measix.pilot.data.ai.tts.TtsPlaybackSource
import net.weero.measix.pilot.data.ai.tools.GenerationToolSetFactory
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.data.ai.transformers.AttachmentProjectionTransformer
import net.weero.measix.pilot.data.ai.transformers.Base64ImageToLocalFileTransformer
import net.weero.measix.pilot.data.ai.transformers.DocumentAsPromptTransformer
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
import java.time.Instant
import java.util.Locale
import kotlinx.datetime.LocalDateTime
import kotlin.uuid.Uuid
import net.weero.measix.pilot.service.runtime.ConversationRuntime
import net.weero.measix.pilot.service.runtime.ConversationRuntimeRegistry
import net.weero.measix.pilot.service.runtime.ConversationTransition
import net.weero.measix.pilot.service.runtime.ConversationCommandCoordinator
import net.weero.measix.pilot.service.runtime.ConversationCommandConflictException
import net.weero.measix.pilot.service.runtime.ConversationAggregateSnapshot
import net.weero.measix.pilot.service.runtime.TurnPipelineFactory
import net.weero.measix.pilot.service.runtime.TurnEngine
import net.weero.measix.pilot.service.runtime.TurnEvent
import net.weero.measix.pilot.service.runtime.TurnHandle
import net.weero.measix.pilot.service.runtime.TurnOutcome
import net.weero.measix.pilot.service.runtime.ToolCallPhase
import net.weero.measix.pilot.service.runtime.currentTurnPresentation
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
import net.weero.measix.pilot.service.runtime.ResolveToolInteraction
import net.weero.measix.pilot.service.runtime.ToolUserDecision
import net.weero.measix.pilot.service.runtime.toApprovalState
import net.weero.measix.pilot.data.ai.tools.ToolInteractionKind
import net.weero.measix.pilot.data.ai.tools.ToolRuntimeMetadata

private const val TAG = "MasterTurnCoordinator"

/** Master generation has two non-interchangeable entry protocols. */
internal enum class MasterTurnEntry {
    /** Opens a new durable turn after structural preflight. */
    START,

    /** Continues the existing approval-paused owner without mutating the message tree. */
    CONTINUE_USER_INTERACTION,
}

/** Carries the one Settings snapshot only for a new START; continuation has no reconstruction input. */
private sealed interface MasterTurnLaunch {
    val entry: MasterTurnEntry

    data class Start(val settings: Settings) : MasterTurnLaunch {
        override val entry = MasterTurnEntry.START
    }

    data object Continue : MasterTurnLaunch {
        override val entry = MasterTurnEntry.CONTINUE_USER_INTERACTION
    }
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
    snapshot: ConversationAggregateSnapshot,
    turnId: Uuid,
    messageRange: ClosedRange<Int>?,
): MasterTurnLaunchPolicy = when (entry) {
    MasterTurnEntry.START -> {
        check(snapshot.activeTurn == null) { "a new master turn cannot start while another turn is active" }
        MasterTurnLaunchPolicy(runStructuralPreflight = true, reuseTtsQueue = false)
    }

    MasterTurnEntry.CONTINUE_USER_INTERACTION -> {
        check(messageRange == null) { "an approval continuation cannot use a message range" }
        val active = requireNotNull(snapshot.activeTurn) { "approval continuation has no active turn" }
        check(active.turnId == turnId) {
            "active turn ${active.turnId} does not match approval continuation $turnId"
        }
        MasterTurnLaunchPolicy(runStructuralPreflight = false, reuseTtsQueue = true)
    }
}

/**
 * User decisions are continuations of the active durable turn. This orchestration seam owns the
 * exact interaction command and makes it impossible for approve/deny/answer to enter the new-turn
 * structural preflight path. A decision whose type does not match the interaction the call paused
 * for is rejected fail-closed; an Answer is never permission and an Approve is never an answer.
 */
internal suspend fun applyToolUserDecision(
    locator: ToolCallLocator,
    decision: ToolUserDecision,
    awaitPreviousGeneration: suspend () -> Unit,
    currentSnapshot: () -> ConversationAggregateSnapshot,
    submit: suspend (ResolveToolInteraction) -> Unit,
    onMoreApprovalsPending: suspend () -> Unit,
    continueTurn: suspend (ActiveTurnState, MasterTurnEntry) -> Unit,
) {
    awaitPreviousGeneration()
    val before = currentSnapshot()
    val located = before.currentMessages()
        .firstOrNull { it.id == locator.messageId }
        ?.getTools()
        ?.getOrNull(locator.toolOrdinal)
        ?: throw ConversationCommandConflictException("stale tool interaction locator: $locator")
    val targetState = decision.toApprovalState()
    if (located.approvalState == targetState) return
    val pending = located.takeIf { it.approvalState == ToolApprovalState.Pending }
        ?: throw ConversationCommandConflictException("tool interaction is no longer pending: $locator")
    check(!pending.hasReplayResult) { "tool with a replay result cannot accept a user decision" }
    requireDecisionMatchesInteraction(pending, decision)

    submit(
        ResolveToolInteraction(
            messageId = locator.messageId,
            toolOrdinal = locator.toolOrdinal,
            decision = decision,
            handle = before.activeTurn.let { owner ->
                requireNotNull(owner) { "tool interaction has no active turn owner" }
                TurnHandle(
                    conversationId = before.conversationId,
                    epoch = owner.epoch,
                    turnId = owner.turnId,
                    assistantMessageId = owner.assistantMessageId,
                )
            },
        ),
    )

    val after = currentSnapshot()
    val committed = after.currentMessages()
        .firstOrNull { it.id == locator.messageId }
        ?.getTools()
        ?.getOrNull(locator.toolOrdinal)
    check(committed?.approvalState == targetState) {
        "tool interaction command was not committed"
    }
    if (after.currentMessages().lastOrNull()?.getTools()?.any { it.isPending } == true) {
        onMoreApprovalsPending()
        return
    }
    continueTurn(
        requireNotNull(after.activeTurn) { "decided tool has no owning active turn" },
        MasterTurnEntry.CONTINUE_USER_INTERACTION,
    )
}

/** Runtime metadata 存在时必须严格匹配；只有真正缺少保留键的旧 Pending 才走 legacy 恢复。 */
private fun requireDecisionMatchesInteraction(
    pending: UIMessagePart.Tool,
    decision: ToolUserDecision,
) {
    check(!ToolRuntimeMetadata.isInvalid(pending.metadata)) {
        "pending tool interaction metadata is invalid"
    }
    val kind = ToolRuntimeMetadata.interactionKindOf(pending.metadata) ?: return
    val matches = when (decision) {
        ToolUserDecision.Approve,
        is ToolUserDecision.Deny,
        -> kind == ToolInteractionKind.APPROVAL

        is ToolUserDecision.Answer -> kind == ToolInteractionKind.USER_INPUT
    }
    check(matches) {
        "decision ${decision::class.simpleName} does not match interaction $kind"
    }
}

/**
 * 主回合生成编排器。持久化命令、终态处理和边缘副作用分别委托给各自 owner。
 */

internal fun retainValidMessageNodes(nodes: List<MessageNode>): List<MessageNode> {
    var messagesNodes = nodes.map { node ->
        val current = runCatching { node.currentMessage }.getOrNull() ?: return@map node
        val tools = current.getTools()
        val hasPendingReplayResults = tools.any { !it.hasReplayResult }
        if (!hasPendingReplayResults) return@map node
        if (tools.any { !it.hasReplayResult && (it.isPending || it.approvalState.canResumeToolExecution()) }) {
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

/** Stable identity for observing a requested user-message append; it is not a durable-success result. */
data class SendMessageReceipt(
    val conversationId: Uuid,
    val turnId: Uuid,
    val userMessageId: Uuid,
)

class MasterTurnCoordinator(
    private val context: Application,
    private val appScope: AppScope,
    private val appEventBus: AppEventBus,
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val generationLoop: GenerationLoop,
    private val templateTransformer: TemplateTransformer,
    private val mcpManager: McpRuntimeCoordinator,
    private val toolSetFactory: GenerationToolSetFactory,
    private val turnRequestContextFactory: net.weero.measix.pilot.service.runtime.TurnRequestContextFactory,
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
    private val workspaceReminderTransformer = WorkspaceReminderTransformer()
    private val toolArtifactReplayTransformer = ToolArtifactReplayTransformer(toolArtifactRewriter)

    // Master/Target 共用管道装配（取代顶层 inputTransformers/outputTransformers val）
    private val turnPipelineFactory = TurnPipelineFactory(
        templateTransformer = templateTransformer,
        workspaceReminderTransformer = workspaceReminderTransformer,
        toolArtifactReplayTransformer = toolArtifactReplayTransformer,
        attachmentProjectionTransformer = AttachmentProjectionTransformer(artifactStore),
        base64ImageToLocalFileTransformer = Base64ImageToLocalFileTransformer(artifactStore),
        documentAsPromptTransformer = DocumentAsPromptTransformer(artifactStore),
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

    private fun liveSnapshot(conversationId: Uuid): ConversationAggregateSnapshot =
        runtimeRegistry.requireRuntime(conversationId).snapshot.value

    // ---- 发送消息 ----

    suspend fun sendMessage(
        conversationId: Uuid,
        content: List<UIMessagePart>,
        answer: Boolean = true,
        artifactDraftScope: ArtifactDraftScope? = null,
    ): SendMessageReceipt? {
        if (content.isEmptyInputMessage()) return null

        val runtime = requireRuntime(conversationId)
        val turnId = Uuid.random()
        val userMessageId = Uuid.random()
        val job = appScope.launch(start = CoroutineStart.LAZY) {
            try {
                recoveryGate.awaitReady()
                runtime.awaitPreviousWorker(turnId)
                turnFinalization.finalizeSupersededTurn(conversationId, runtime.previousTurnId(turnId))

                val currentSnapshot = runtime.snapshot.value
                val settings = settingsStore.effectiveSettings.first().settings
                val assistant = settings.getAssistantById(currentSnapshot.header.assistantId)
                    ?: settings.getCurrentAssistant()
                val processedContent = preprocessUserInputParts(content, assistant)
                val userMessage = UIMessage(
                    id = userMessageId,
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

                // USER preprocessing 与 START wire 共用同一 EffectiveSettingsSnapshot。
                if (answer) {
                    launchRun(conversationId, turnId = turnId, launch = MasterTurnLaunch.Start(settings))
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                chatErrorStore.add(e, conversationId, title = context.getString(R.string.error_title_send_message))
            } finally {
                if (!answer || !runtime.isAwaitingApproval(turnId)) {
                    runtime.releaseActiveRequest(turnId, coroutineContext[Job])
                }
            }
        }
        try {
            runtimeRegistry.installAndStartActiveRequest(
                conversationId = conversationId,
                turnId = turnId,
                worker = job,
                supersedeReason = TurnTerminalReasons.SUPERSEDED_BY_NEW_TURN,
            )
        } catch (e: CancellationException) {
            job.cancel()
            throw e
        } catch (e: Exception) {
            job.cancel()
            throw e
        }
        return SendMessageReceipt(
            conversationId = conversationId,
            turnId = turnId,
            userMessageId = userMessageId,
        )
    }

    // ---- 重新生成消息 ----

    fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        val runtime = requireRuntime(conversationId)
        val turnId = Uuid.random()
        val job = appScope.launch(start = CoroutineStart.LAZY) {
            try {
                recoveryGate.awaitReady()
                runtime.awaitPreviousWorker(turnId)
                turnFinalization.finalizeSupersededTurn(conversationId, runtime.previousTurnId(turnId))
                val snapshot = subAssistantLifecycle.finalizeRunsBeforeTreeMutation(runtime.snapshot.value)

                if (message.role == MessageRole.USER) {
                    // 如果是用户消息，则截止到当前消息（TruncateToNodeIndex，master 树 delta 落库）
                    val indexAt = snapshot.nodes.indexOfFirst { node ->
                        node.messages.any { it.id == message.id }
                    }
                    check(indexAt >= 0) { "Message not found: ${message.id}" }
                    commandCoordinator.executeOrThrow(conversationId, TruncateToNodeIndex(nodeIndexInclusive = indexAt))
                    subAssistantLifecycle.applyRetentionAfterTreeMutation(conversationId)
                    val startSettings = settingsStore.effectiveSettings.first().settings
                    launchRun(conversationId, turnId = turnId, launch = MasterTurnLaunch.Start(startSettings))
                } else {
                    if (regenerateAssistantMsg) {
                        val nodeIndex = snapshot.nodes.indexOfFirst { node ->
                            node.messages.any { it.id == message.id }
                        }
                        check(nodeIndex >= 0) { "Message not found: ${message.id}" }
                        // 保留目标 Assistant node 以追加新 variant；其后历史先通过唯一 truncate 协议删除。
                        commandCoordinator.executeOrThrow(
                            conversationId,
                            TruncateToNodeIndex(nodeIndexInclusive = nodeIndex),
                        )
                        subAssistantLifecycle.applyRetentionAfterTreeMutation(conversationId)
                        val startSettings = settingsStore.effectiveSettings.first().settings
                        launchRun(
                            conversationId,
                            turnId = turnId,
                            launch = MasterTurnLaunch.Start(startSettings),
                        )
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
            } finally {
                if (!runtime.isAwaitingApproval(turnId)) {
                    runtime.releaseActiveRequest(turnId, coroutineContext[Job])
                }
            }
        }
        appScope.launch {
            try {
                runtimeRegistry.installAndStartActiveRequest(
                    conversationId = conversationId,
                    turnId = turnId,
                    worker = job,
                    supersedeReason = TurnTerminalReasons.SUPERSEDED_BY_NEW_TURN,
                )
            } catch (e: CancellationException) {
                job.cancel()
                throw e
            } catch (e: Exception) {
                job.cancel()
                chatErrorStore.add(e, conversationId, title = context.getString(R.string.error_title_regenerate_message))
            }
        }
    }

    // ---- 处理工具审批与用户输入 ----

    fun submitToolDecision(
        conversationId: Uuid,
        locator: ToolCallLocator,
        decision: ToolUserDecision,
    ) {
        val runtime = requireRuntime(conversationId)
        appScope.launch {
            try {
                recoveryGate.awaitReady()
                runtime.withToolApprovalLock {
                    applyToolUserDecision(
                        locator = locator,
                        decision = decision,
                        // Pending is emitted immediately before the Flow terminates. Joining the
                        // previous job guarantees its checkpoint is durable before the decision.
                        awaitPreviousGeneration = { runtime.awaitCurrentWorker() },
                        currentSnapshot = { runtime.snapshot.value },
                        submit = { command -> commandCoordinator.executeOrThrow(conversationId, command) },
                        onMoreApprovalsPending = { _generationDoneFlow.emit(conversationId) },
                        continueTurn = { owner, entry ->
                            val turnId = owner.turnId
                            val handle = TurnHandle(
                                conversationId = conversationId,
                                epoch = owner.epoch,
                                turnId = owner.turnId,
                                assistantMessageId = owner.assistantMessageId,
                            )
                            val resumeJob = appScope.launch(start = CoroutineStart.LAZY) {
                                try {
                                    launchRun(
                                        conversationId = conversationId,
                                        turnId = turnId,
                                        launch = MasterTurnLaunch.Continue,
                                    )
                                    _generationDoneFlow.emit(conversationId)
                                } finally {
                                    if (!runtime.isAwaitingApproval(turnId)) {
                                        runtime.releaseActiveRequest(turnId, coroutineContext[Job])
                                    }
                                }
                            }
                            try {
                                runtimeRegistry.installAndStartApprovalContinuation(
                                    conversationId = conversationId,
                                    handle = handle,
                                    worker = resumeJob,
                                )
                            } catch (error: Throwable) {
                                resumeJob.cancel()
                                throw error
                            }
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
        launch: MasterTurnLaunch,
    ) {
        val entry = launch.entry
        // 用户可见地开始或继续 Master 生成后，请求平台保活；何时停止由 service 依据
        // conversationActivities 投影自决，这里只做单向请求，不读取任何运行结果。
        GenerationForegroundLifetime.ensureStarted(context)
        var turnOutcome: TurnOutcome? = null
        var inFlightAssistantId: Uuid? = null
        var senderName: String? = null
        var generationSoundEnabled = false
        var turnEngine: TurnEngine? = null
        var startedRuntime: ConversationRuntime? = null
        try {
            val runtime = requireRuntime(conversationId)
            startedRuntime = runtime
            val initialSnapshot = liveSnapshot(conversationId)
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
            var startDisclosureCandidate: String? = null
            val worker = requireNotNull(kotlinx.coroutines.currentCoroutineContext()[Job])
            val requestContext = when (entry) {
                MasterTurnEntry.START -> {
                    val settings = (launch as MasterTurnLaunch.Start).settings
                    val assistant = settings.getAssistantById(snapshot.header.assistantId)
                        ?: settings.getCurrentAssistant()
                    generationSoundEnabled = settings.displaySetting.enableMessageGenerationSoundEffect
                    val model = settings.getChatModel(assistant)
                        ?: error("No chat model is configured for assistant ${assistant.id}")
                    val providerSetting = model.findProvider(settings.providers) ?: error("Provider not found")
                    val mediaCapabilities = generationLoop.resolveRequestMediaCapabilities(settings, model)
                    val promptInputs = turnRequestContextFactory.capturePromptInputs(
                        settings = settings,
                        assistant = assistant,
                        model = model,
                        conversationSystemPrompt = snapshot.header.customSystemPrompt,
                        conversationModeInjectionIds = snapshot.header.modeInjectionIds,
                    )
                    startDisclosureCandidate = ConversationDisclosureSnapshotService.captureCandidate(
                        settings = settings,
                        assistant = assistant,
                        memoryRepository = memoryRepository,
                    )
                    val mcpCapabilities = mcpManager.prepareTurnCapabilities(assistant)
                    val unavailableMcp = mcpCapabilities.serverOutcomes.filter {
                        it.state != net.weero.measix.pilot.data.ai.mcp.McpServerCapabilityState.READY
                    }
                    if (unavailableMcp.isNotEmpty()) {
                        chatErrorStore.add(
                            IllegalStateException(
                                context.getString(
                                    R.string.error_mcp_turn_capability_unavailable,
                                    unavailableMcp.joinToString(", ") { it.serverName },
                                ),
                            ),
                            conversationId,
                            title = context.getString(R.string.error_title_tool_unavailable),
                        )
                    }
                    senderName = if (assistant.useAssistantAvatar) {
                        assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
                    } else {
                        model.displayName
                    }
                    if (!model.abilities.contains(ModelAbility.TOOL) &&
                        (shouldUseExternalWebSearch(assistant, model) || mcpCapabilities.tools.isNotEmpty())
                    ) {
                        chatErrorStore.add(
                            IllegalStateException(context.getString(R.string.tools_warning)),
                            conversationId,
                            title = context.getString(R.string.error_title_tool_unavailable),
                        )
                    }
                    val turnTtsContext = TtsToolPlaybackContext(
                        sessionId = runtime.getTtsQueueSessionId(launchPolicy.reuseTtsQueue),
                        assistantId = assistant.id,
                        assistantName = assistant.name,
                        sourceType = TtsPlaybackSource.SourceType.NORMAL,
                    )
                    val regularTools = toolSetFactory.buildTools(
                        assistant = assistant,
                        conversationId = conversationId,
                        settings = settings,
                        capabilityModel = model,
                        workspaceCwd = snapshot.header.workspaceCwd,
                        ttsPlaybackContext = turnTtsContext,
                        mcpCapabilities = mcpCapabilities,
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
                                        invalidNames.joinToString(", "),
                                    ),
                                ),
                                conversationId = conversationId,
                            )
                        },
                    )
                    val memoryOwnerId = resolveMemoryOwnerId(assistant)
                    val tools = buildList {
                        if (memoryOwnerId != null) {
                            addAll(
                                buildMemoryTools(
                                    onCreation = { content -> memoryRepository.addMemory(memoryOwnerId, content) },
                                    onUpdate = { id, content -> memoryRepository.updateContent(id, content, memoryOwnerId) },
                                    onDelete = { id -> memoryRepository.deleteMemory(id, memoryOwnerId) },
                                    isStillAllowed = {
                                        resolveMemoryOwnerId(
                                            settingsStore.effectiveSettings.value.settings.getAssistantById(assistant.id),
                                        ) == memoryOwnerId
                                    },
                                ),
                            )
                        }
                        addAll(regularTools)
                    }
                    val credentialOwner = net.weero.measix.pilot.service.runtime.captureProviderCredentialOwner(
                        settings = settings,
                        model = model,
                        selectedProvider = providerSetting,
                    )
                    val turnRequestContext = turnRequestContextFactory.create(
                        assistant = assistant,
                        model = model,
                        providerSetting = providerSetting,
                        providerTransportLease = net.weero.measix.pilot.service.runtime.ProviderTransportLease {
                            net.weero.measix.pilot.service.runtime.resolveProviderTransportOwner(
                                settingsStore.effectiveSettings.value.settings,
                                credentialOwner,
                            )
                        },
                        mediaCapabilities = mediaCapabilities,
                        promptInputs = promptInputs,
                        tools = tools,
                    )
                    runtime.bindTurnRequestContext(turnId, worker, turnRequestContext)
                    turnRequestContext
                }

                MasterTurnEntry.CONTINUE_USER_INTERACTION ->
                    runtime.requireTurnRequestContext(turnId, worker)
            }
            val assistant = requestContext.assistant
            val model = requestContext.model.model
            val displaySettings = settingsStore.effectiveSettings.value.settings
            generationSoundEnabled = displaySettings.displaySetting.enableMessageGenerationSoundEffect
            if (senderName == null) {
                val currentAssistant = displaySettings.getAssistantById(assistant.id)
                senderName = if (currentAssistant?.useAssistantAvatar == true) {
                    assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
                } else {
                    model.displayName
                }
            }
            val started = when (entry) {
                MasterTurnEntry.START -> TurnEngine.start(
                    commandCoordinator = commandCoordinator,
                    runtime = runtime,
                    turnId = turnId,
                    modelContextCandidate = requireNotNull(startDisclosureCandidate) {
                        "START disclosure candidate was not captured"
                    },
                    turnFinalization = turnFinalization,
                )

                MasterTurnEntry.CONTINUE_USER_INTERACTION -> {
                    val resumableApprovalMessage = sourceMessages.lastOrNull()?.takeIf { message ->
                        message.role == MessageRole.ASSISTANT &&
                            message.getTools().any {
                                !it.hasReplayResult && it.approvalState.canResumeToolExecution()
                            }
                    }
                    check(resumableApprovalMessage != null) {
                        "active turn does not point to a resumable approval message"
                    }
                    TurnEngine.continueActive(
                        commandCoordinator = commandCoordinator,
                        runtime = runtime,
                        expectedTurnId = turnId,
                        messages = sourceMessages,
                        turnFinalization = turnFinalization,
                    )
                }
            }
            val activeTurnEngine = started.engine
            turnEngine = activeTurnEngine
            inFlightAssistantId = started.assistantMessageId
            val modelContextProjection = when (entry) {
                MasterTurnEntry.START -> {
                    snapshot = liveSnapshot(conversationId)
                    // START 的请求输入是提交后的 selected branch（与 Child 路径同一协议）：
                    // regenerate 中被替换的旧 Assistant variant 已退出目标分支，不得把旧回答
                    // 带进请求让模型“续写”。
                    val projection = ConversationTransition.projectTurnModelContext(snapshot)
                    runtime.bindModelContextProjection(turnId, worker, projection)
                    projection
                }
                MasterTurnEntry.CONTINUE_USER_INTERACTION ->
                    // 审批 / ask-user continuation 只复用 START 冻结的 projection（§7.3），
                    // 不重新求值适用谓词。
                    runtime.requireTurnModelContextProjection(turnId, worker)
            }
            val generationMessages = if (started.resumableMessage == null) {
                snapshot.currentMessages()
            } else {
                sourceMessages
            }
            val soundTracker = sideEffects.soundTracker()
            generationLoop.run(
                GenerationRequest(
                    conversationId = conversationId,
                    requestContext = requestContext,
                    reportProcessingText = runtime.processingReporter(),
                    messages = generationMessages,
                    assistantMessageId = started.assistantMessageId,
                    providerSessionId = conversationId.toString(),
                    inputTransformers = turnPipelineFactory.masterInput(),
                    outputTransformers = turnPipelineFactory.masterOutput(),
                    onCheckpoint = activeTurnEngine::onCheckpoint,
                    onMessagesObserved = activeTurnEngine::observeMessages,
                    modelContextEntries = modelContextProjection.entries,
                    durableMessageLocators = modelContextProjection.locators,
                )
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
                                val executingToolOrdinal = lastMessage.getTools().indices.lastOrNull { ordinal ->
                                    runtime.currentTurnPresentation().toolCallPhases[
                                        ToolCallLocator(lastMessage.id, ordinal)
                                    ] == ToolCallPhase.EXECUTING
                                }
                                appEventBus.tryEmit(
                                    AppEvent.ChatGenerationUpdate(
                                        conversationId = conversationId,
                                        lastMessage = lastMessage,
                                        senderName = senderName.orEmpty(),
                                        executingToolOrdinal = executingToolOrdinal,
                                    )
                                )
                            }

                            // 前台声音反馈: 单步生成完成 + 工具待审批
                            if (isForeground.value && generationSoundEnabled) {
                                soundTracker.onStreaming(event.lastMessage)
                            }
                        }

                        is TurnEvent.Phase -> { }
                        is TurnEvent.Finished -> {
                            turnOutcome = event.outcome
                        }
                    }
                }
            }

            if (turnOutcome is TurnOutcome.Failed && isForeground.value && generationSoundEnabled) {
                sideEffects.playTurnFailedSound()
            }
            if (turnOutcome is TurnOutcome.AwaitingApproval) {
                val active = requireNotNull(runtime.snapshot.value.activeTurn) {
                    "approval checkpoint has no durable turn owner"
                }
                runtime.retainAwaitingApproval(
                    TurnHandle(conversationId, active.epoch, active.turnId, active.assistantMessageId),
                )
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

            if (isForeground.value && generationSoundEnabled) {
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
            val finalMessage = liveSnapshot(conversationId).currentMessages()
                .firstOrNull { it.id == inFlightAssistantId }
            val terminalStatus = when (outcome) {
                is TurnOutcome.Failed -> MessageTerminalStatus.FAILED
                is TurnOutcome.Incomplete -> MessageTerminalStatus.INCOMPLETE
                else -> null
            }
            if (terminalStatus != null && inFlightAssistantId != null) {
                terminalChatError(
                    context = context,
                    conversationId = conversationId,
                    messageId = inFlightAssistantId,
                    status = finalMessage?.terminalStatus ?: terminalStatus,
                    reason = finalMessage?.terminalReason ?: outcome.terminalReason,
                    detail = finalMessage?.terminalDetail ?: outcome.terminalDetail,
                )?.let(chatErrorStore::add)
            } else if (outcome is TurnOutcome.Failed) {
                chatErrorStore.add(
                    error = outcome.error,
                    conversationId = conversationId,
                    title = context.getString(R.string.error_title_generation),
                )
            }
            val pendingToolOrdinal = (outcome as? TurnOutcome.AwaitingApproval)
                ?.pending
                ?.firstOrNull()
                ?.locator
                ?.toolOrdinal
            if (finalMessage != null && pendingToolOrdinal != null) {
                appEventBus.emit(
                    AppEvent.ChatGenerationAwaitingApproval(
                        conversationId = conversationId,
                        lastMessage = finalMessage,
                        senderName = senderName,
                        pendingToolOrdinal = pendingToolOrdinal,
                    )
                )
            } else {
                appEventBus.emit(
                    AppEvent.ChatGenerationEnded(
                        conversationId = conversationId,
                        senderName = senderName,
                        contentPreview = finalMessage?.toText()?.take(50)?.trim(),
                        notifyCompletion = outcome is TurnOutcome.Completed,
                    )
                )
            }
        }
    }
}

/** Backfill plans are derived from durable nodes, never from the per-read rendering overlay. */
internal fun planDurableAttachmentRefBackfills(
    snapshot: ConversationAggregateSnapshot,
) = AttachmentRefs.planBackfills(snapshot.nodes)
