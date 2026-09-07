package net.weero.measix.pilot.service
import net.weero.measix.pilot.service.turn.TurnFinalizer
import net.weero.measix.pilot.service.subassistant.SubAssistantLifecycle
import net.weero.measix.pilot.service.turn.TurnContextFactory

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
import me.rerere.ai.ui.ToolInteractionState
import me.rerere.ai.ui.TurnTerminalReasons
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.findUserTurnStart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.common.android.Logging
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.event.AppEvent
import net.weero.measix.pilot.data.event.AppEventBus
import net.weero.measix.pilot.service.turn.TurnRunner
import net.weero.measix.pilot.service.turn.TurnRunInputs
import net.weero.measix.pilot.service.turn.resolveMemoryOwnerId
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
import net.weero.measix.pilot.data.ai.tools.TurnToolSetFactory
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.findModelById
import net.weero.measix.pilot.data.datastore.findProvider
import net.weero.measix.pilot.data.datastore.getAssistantById
import net.weero.measix.pilot.data.datastore.getChatModel
import net.weero.measix.pilot.data.datastore.getCurrentAssistant
import net.weero.measix.pilot.data.datastore.getCurrentChatModel
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
import net.weero.measix.pilot.service.runtime.turnLivePhaseOf
import net.weero.measix.pilot.service.runtime.ConversationRuntimeRegistry
import net.weero.measix.pilot.service.runtime.TurnTransition
import net.weero.measix.pilot.service.runtime.ConversationCommandCoordinator
import net.weero.measix.pilot.service.runtime.ConversationCommandConflictException
import net.weero.measix.pilot.service.runtime.ConversationAggregateSnapshot
import net.weero.measix.pilot.service.turn.TurnPipelineFactory
import net.weero.measix.pilot.service.turn.TurnCommitter
import net.weero.measix.pilot.service.runtime.TurnHandle
import net.weero.measix.pilot.service.runtime.TurnKind
import net.weero.measix.pilot.service.turn.TurnOutcome
import net.weero.measix.pilot.service.turn.TurnRunResult
import net.weero.measix.pilot.service.turn.TurnPause
import net.weero.measix.pilot.service.runtime.ToolLivePhase
import net.weero.measix.pilot.service.runtime.currentTurnPresentation
import net.weero.measix.pilot.service.runtime.AppendUserMessage
import net.weero.measix.pilot.service.runtime.ConversationRuntimeSnapshot
import net.weero.measix.pilot.service.runtime.TurnStreamProjection
import net.weero.measix.pilot.service.runtime.BackfillAttachmentRefs
import net.weero.measix.pilot.service.subassistant.SubAssistantRunCoordinator
import net.weero.measix.pilot.service.runtime.DeleteMessage
import net.weero.measix.pilot.service.runtime.EditMessageVariant
import net.weero.measix.pilot.service.runtime.FinalizeTurn
import net.weero.measix.pilot.service.runtime.OptionalFolderId
import net.weero.measix.pilot.service.runtime.ReplaceMessageTree
import net.weero.measix.pilot.service.runtime.SelectNodeVariant
import net.weero.measix.pilot.service.runtime.TruncateToNodeIndex
import net.weero.measix.pilot.service.runtime.UpdateHeader
import net.weero.measix.pilot.service.runtime.ResolveToolInteraction
import net.weero.measix.pilot.service.runtime.ToolInteractionDecision
import net.weero.measix.pilot.service.runtime.toInteractionState

private const val TAG = "ConversationTurnService"

/** 用户会话 Turn 有两个不可互换的入口协议。 */
internal enum class TurnEntry {
    /** Opens a new durable turn after structural preflight. */
    START,

    /** Continues the existing user-paused owner without mutating the message tree. */
    CONTINUE_USER_INTERACTION,
}

/** Carries the one Settings snapshot only for a new START; continuation has no reconstruction input. */
private sealed interface TurnLaunch {
    val entry: TurnEntry

    data class Start(val settings: Settings) : TurnLaunch {
        override val entry = TurnEntry.START
    }

    data object Continue : TurnLaunch {
        override val entry = TurnEntry.CONTINUE_USER_INTERACTION
    }
}

internal data class TurnLaunchPolicy(
    val runStructuralPreflight: Boolean,
    val reuseTtsQueue: Boolean,
)

/**
 * Validates the entry against its owning snapshot before any command is submitted.
 * User-interaction continuation must retain the current turn owner and cannot become a second start path.
 */
internal fun turnLaunchPolicy(
    entry: TurnEntry,
    activeTurn: TurnStreamProjection?,
    turnId: Uuid,
    messageRange: ClosedRange<Int>?,
): TurnLaunchPolicy = when (entry) {
    TurnEntry.START -> {
        check(activeTurn == null) { "a new conversation turn cannot start while another turn is active" }
        TurnLaunchPolicy(runStructuralPreflight = true, reuseTtsQueue = false)
    }

    TurnEntry.CONTINUE_USER_INTERACTION -> {
        check(messageRange == null) { "a user-interaction continuation cannot use a message range" }
        val active = requireNotNull(activeTurn) { "user-interaction continuation has no active turn" }
        check(active.turnId == turnId) {
            "active turn ${active.turnId} does not match user-interaction continuation $turnId"
        }
        TurnLaunchPolicy(runStructuralPreflight = false, reuseTtsQueue = true)
    }
}

/**
 * User decisions are continuations of the active durable turn. This orchestration seam owns the
 * exact interaction command and makes it impossible for approve/deny/answer to enter the new-turn
 * structural preflight path. A decision whose type does not match the interaction the call paused
 * for is rejected fail-closed; an Answer is never permission and an Approve is never an answer.
 */
internal suspend fun applyToolInteractionDecision(
    locator: ToolCallLocator,
    decision: ToolInteractionDecision,
    awaitPreviousGeneration: suspend () -> Unit,
    currentSnapshot: () -> ConversationRuntimeSnapshot,
    submit: suspend (ResolveToolInteraction) -> Unit,
    onMoreApprovalsPending: suspend () -> Unit,
    continueTurn: suspend (TurnStreamProjection, TurnEntry) -> Unit,
) {
    awaitPreviousGeneration()
    val before = currentSnapshot()
    val located = before.durable.currentMessages()
        .firstOrNull { it.id == locator.assistantMessageId }
        ?.getTools()
        ?.firstOrNull { it.localCallId == locator.localCallId }
        ?: throw ConversationCommandConflictException("stale tool interaction locator: $locator")
    val targetState = decision.toInteractionState()
    if (located.interactionState == targetState) return
    val pending = located.takeIf { it.isPending }
        ?: throw ConversationCommandConflictException("tool interaction is no longer pending: $locator")
    check(!pending.hasReplayResult) { "tool with a replay result cannot accept a user decision" }
    requireDecisionMatchesInteraction(pending, decision)

    submit(
        ResolveToolInteraction(
            messageId = locator.assistantMessageId,
            stepId = locator.stepId,
            localCallId = locator.localCallId,
            decision = decision,
            handle = before.stream.let { owner ->
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
    val assistant = after.durable.currentMessages().firstOrNull { it.id == locator.assistantMessageId }
        ?: throw ConversationCommandConflictException("stale tool interaction locator: $locator")
    val committed = assistant.getTools().firstOrNull { it.localCallId == locator.localCallId }
    check(committed?.interactionState == targetState) {
        "tool interaction command was not committed"
    }
    if (assistant.getTools().any { it.isPending }) {
        onMoreApprovalsPending()
        return
    }
    continueTurn(
        requireNotNull(after.stream) { "decided tool has no owning active turn" },
        TurnEntry.CONTINUE_USER_INTERACTION,
    )
}

/** 决策类型必须与调用挂起时的 typed 交互一致；Approve/Deny 只对审批，Answer 只对用户输入。 */
private fun requireDecisionMatchesInteraction(
    pending: UIMessagePart.Tool,
    decision: ToolInteractionDecision,
) {
    val matches = when (decision) {
        ToolInteractionDecision.Approve,
        is ToolInteractionDecision.Deny,
        -> pending.interactionState is ToolInteractionState.AwaitingApproval

        is ToolInteractionDecision.Answer -> pending.interactionState is ToolInteractionState.AwaitingInput
    }
    check(matches) {
        "decision ${decision::class.simpleName} does not match interaction ${pending.interactionState}"
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
        if (tools.any { !it.hasReplayResult && (it.isPending || it.canResumeResultAssembly) }) {
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

class ConversationTurnService(
    private val context: Application,
    private val appScope: AppScope,
    private val appEventBus: AppEventBus,
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val turnRunner: TurnRunner,
    private val turnPipelineFactory: TurnPipelineFactory,
    private val mcpManager: McpRuntimeCoordinator,
    private val toolSetFactory: TurnToolSetFactory,
    private val turnContextFactory: net.weero.measix.pilot.service.turn.TurnContextFactory,
    private val assistantToolFactory: AssistantToolFactory,
    private val subAssistantRunCoordinator: SubAssistantRunCoordinator,
    private val turnFinalizer: TurnFinalizer,
    private val subAssistantLifecycle: SubAssistantLifecycle,
    private val runtimeRegistry: ConversationRuntimeRegistry,
    private val commandCoordinator: ConversationCommandCoordinator,
    private val recoveryGate: ApplicationRecoveryGate,
    private val chatErrorStore: ChatErrorStore,
    private val sideEffects: GenerationSideEffects,
    private val artifactUseCase: ArtifactUseCase,
    private val titleCoordinator: ConversationTitleCoordinator,
) {

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
        runtimeRegistry.requireRuntime(conversationId).durable

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
                turnFinalizer.finalizeSupersededTurn(conversationId, runtime.previousTurnId(turnId))

                val currentSnapshot = runtime.snapshot.value.durable
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
                    val committedHeader = runtime.durable.header
                    titleCoordinator.synchronize(
                        conversationId = conversationId,
                        title = committedHeader.title,
                        localFallbackTitle = localTitle,
                    )
                }
                artifactDraftScope?.publishCommittedReferences(processedContent)

                // USER preprocessing 与 START wire 共用同一 EffectiveSettingsSnapshot。
                if (answer) {
                    launchRun(conversationId, turnId = turnId, launch = TurnLaunch.Start(settings))
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                chatErrorStore.add(e, conversationId, title = context.getString(R.string.error_title_send_message))
            } finally {
                if (!answer || !runtime.isAwaitingUser(turnId)) {
                    runtime.releaseTurnWorker(turnId, coroutineContext[Job])
                }
            }
        }
        try {
            runtimeRegistry.installAndStartTurnWorker(
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

    /**
     * 编辑已有 USER 并重新发送：先截断到该 USER node、再提交新 USER variant，然后按
     * 结构变换后的目标分支走新的 `START`。纯编辑不启动模型请求时
     * 走 [ConversationApplicationService.editMessage]，不得调用本方法。
     */
    suspend fun editAndResend(
        conversationId: Uuid,
        messageId: Uuid,
        content: List<UIMessagePart>,
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
                turnFinalizer.finalizeSupersededTurn(conversationId, runtime.previousTurnId(turnId))

                val snapshot = subAssistantLifecycle.finalizeRunsBeforeTreeMutation(runtime.durable)
                val nodeIndex = snapshot.nodes.indexOfFirst { node ->
                    node.messages.any { it.id == messageId }
                }
                check(nodeIndex >= 0) { "Message not found: $messageId" }
                val target = snapshot.nodes[nodeIndex]
                val edited = target.messages.first { it.id == messageId }
                check(edited.role == MessageRole.USER) {
                    "edit-and-resend requires a USER message: $messageId"
                }

                val settings = settingsStore.effectiveSettings.first().settings
                val assistant = settings.getAssistantById(snapshot.header.assistantId)
                    ?: settings.getCurrentAssistant()
                val processedContent = preprocessUserInputParts(content, assistant)

                commandCoordinator.executeOrThrow(
                    conversationId,
                    TruncateToNodeIndex(nodeIndexInclusive = nodeIndex),
                )
                subAssistantLifecycle.applyRetentionAfterTreeMutation(conversationId)
                commandCoordinator.executeOrThrow(
                    conversationId,
                    EditMessageVariant(
                        nodeId = target.id,
                        variant = UIMessage(
                            id = userMessageId,
                            role = MessageRole.USER,
                            parts = processedContent,
                        ),
                    ),
                )
                artifactDraftScope?.publishCommittedReferences(processedContent)
                launchRun(conversationId, turnId = turnId, launch = TurnLaunch.Start(settings))
                _generationDoneFlow.emit(conversationId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                chatErrorStore.add(e, conversationId, title = context.getString(R.string.error_title_send_message))
            } finally {
                if (!runtime.isAwaitingUser(turnId)) {
                    runtime.releaseTurnWorker(turnId, coroutineContext[Job])
                }
            }
        }
        try {
            runtimeRegistry.installAndStartTurnWorker(
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
                turnFinalizer.finalizeSupersededTurn(conversationId, runtime.previousTurnId(turnId))
                val snapshot = subAssistantLifecycle.finalizeRunsBeforeTreeMutation(runtime.durable)

                if (message.role == MessageRole.USER) {
                    // 如果是用户消息，则截止到当前消息（TruncateToNodeIndex，会话树 delta 落库）
                    val indexAt = snapshot.nodes.indexOfFirst { node ->
                        node.messages.any { it.id == message.id }
                    }
                    check(indexAt >= 0) { "Message not found: ${message.id}" }
                    commandCoordinator.executeOrThrow(conversationId, TruncateToNodeIndex(nodeIndexInclusive = indexAt))
                    subAssistantLifecycle.applyRetentionAfterTreeMutation(conversationId)
                    val startSettings = settingsStore.effectiveSettings.first().settings
                    launchRun(conversationId, turnId = turnId, launch = TurnLaunch.Start(startSettings))
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
                            launch = TurnLaunch.Start(startSettings),
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
                if (!runtime.isAwaitingUser(turnId)) {
                    runtime.releaseTurnWorker(turnId, coroutineContext[Job])
                }
            }
        }
        appScope.launch {
            try {
                runtimeRegistry.installAndStartTurnWorker(
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
        decision: ToolInteractionDecision,
    ) {
        val runtime = requireRuntime(conversationId)
        appScope.launch {
            try {
                recoveryGate.awaitReady()
                runtime.withToolApprovalLock {
                    applyToolInteractionDecision(
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
                                        launch = TurnLaunch.Continue,
                                    )
                                    _generationDoneFlow.emit(conversationId)
                                } finally {
                                    if (!runtime.isAwaitingUser(turnId)) {
                                        runtime.releaseTurnWorker(turnId, coroutineContext[Job])
                                    }
                                }
                            }
                            try {
                                runtimeRegistry.installAndStartUserInteractionContinuation(
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
        return subAssistantRunCoordinator.answerUserInteraction(runId, interactionId, answer)
    }

    // ---- 处理消息补全 ----

    private suspend fun launchRun(
        conversationId: Uuid,
        turnId: Uuid,
        messageRange: ClosedRange<Int>? = null,
        launch: TurnLaunch,
    ) {
        val entry = launch.entry
        // 用户可见地开始或继续本会话生成后，请求平台保活；何时停止由 service 依据
        // conversationActivities 投影自决，这里只做单向请求，不读取任何运行结果。
        GenerationForegroundLifetime.ensureStarted(context)
        var inFlightAssistantId: Uuid? = null
        var senderName: String? = null
        var generationSoundEnabled = false
        var turnCommitter: TurnCommitter? = null
        var startedRuntime: ConversationRuntime? = null
        try {
            val runtime = requireRuntime(conversationId)
            startedRuntime = runtime
            val launchPolicy = turnLaunchPolicy(entry, runtime.snapshot.value.stream, turnId, messageRange)

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
            // START 先做一次性 prepareLaunch（唯一允许 IO、可失败，此时尚无 Turn）。
            val launchPlan = when (entry) {
                TurnEntry.START -> {
                    val settings = (launch as TurnLaunch.Start).settings
                    val assistant = settings.getAssistantById(snapshot.header.assistantId)
                        ?: settings.getCurrentAssistant()
                    val model = settings.getChatModel(assistant)
                        ?: error("No chat model is configured for assistant ${assistant.id}")
                    val providerSetting = model.findProvider(settings.providers) ?: error("Provider not found")
                    val mediaCapabilities = turnRunner.resolveRequestMediaCapabilities(settings, model)
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
                    turnContextFactory.prepareLaunch(
                        settings = settings,
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
                        conversationSystemPrompt = snapshot.header.customSystemPrompt,
                        conversationModeInjectionIds = snapshot.header.modeInjectionIds,
                        tools = tools,
                    )
                }

                TurnEntry.CONTINUE_USER_INTERACTION -> null
            }
            val started = when (entry) {
                TurnEntry.START -> TurnCommitter.start(
                    commandCoordinator = commandCoordinator,
                    runtime = runtime,
                    turnId = turnId,
                    modelContextCandidate = requireNotNull(startDisclosureCandidate) {
                        "START disclosure candidate was not captured"
                    },
                    turnFinalizer = turnFinalizer,
                )

                TurnEntry.CONTINUE_USER_INTERACTION -> {
                    val resumableApprovalMessage = sourceMessages.lastOrNull()?.takeIf { message ->
                        message.role == MessageRole.ASSISTANT &&
                            message.getTools().any {
                                !it.hasReplayResult && it.canResumeResultAssembly
                            }
                    }
                    check(resumableApprovalMessage != null) {
                        "active turn does not point to a resumable approval message"
                    }
                    TurnCommitter.continueActive(
                        commandCoordinator = commandCoordinator,
                        runtime = runtime,
                        expectedTurnId = turnId,
                        messages = sourceMessages,
                        turnFinalizer = turnFinalizer,
                    )
                }
            }
            // 先认领终态 owner：materialize 若失败，本 catch 收口；即便收口本身抛错，
            // 外层 launchRun catch 仍能以同一 committer 兜底，绝不留 RUNNING-without-context。
            turnCommitter = started.turnCommitter
            // StartTurn 事务已建立 Turn；materialize 只做纯绑定并交给 durable 槽，禁止 IO / 重读 Settings。
            // 纯绑定抛错即编程错误：以专用 reason 收口已启动的 Turn，绝不留下无 TurnContext 的 RUNNING。
            val turnContext = when (entry) {
                TurnEntry.START -> try {
                    val context = turnContextFactory.materialize(requireNotNull(launchPlan))
                    runtime.bindTurnContext(turnId, worker, context)
                    context
                } catch (error: Exception) {
                    withContext(NonCancellable) {
                        started.turnCommitter.finalizeOwnerFailure(
                            TurnOutcome.Failed(error, TurnTerminalReasons.TURN_CONTEXT_MATERIALIZE),
                        )
                    }
                    throw error
                }

                TurnEntry.CONTINUE_USER_INTERACTION ->
                    runtime.requireTurnContext(turnId, worker)
            }
            val displaySettings = settingsStore.effectiveSettings.value.settings
            generationSoundEnabled = displaySettings.displaySetting.enableMessageGenerationSoundEffect
            if (senderName == null) {
                val currentAssistant = displaySettings.getAssistantById(turnContext.assistant.id)
                senderName = if (currentAssistant?.useAssistantAvatar == true) {
                    turnContext.assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
                } else {
                    turnContext.model.model.displayName
                }
            }
            val activeTurnCommitter = started.turnCommitter
            inFlightAssistantId = started.assistantMessageId
            val modelContextProjection = when (entry) {
                TurnEntry.START -> {
                    snapshot = liveSnapshot(conversationId)
                    // START 的请求输入是提交后的 selected branch（与 Child 路径同一协议）：
                    // regenerate 中被替换的旧 Assistant variant 已退出目标分支，不得把旧回答
                    // 带进请求让模型“续写”。
                    val projection = TurnTransition.projectTurnModelContext(snapshot)
                    runtime.bindModelContextProjection(turnId, worker, projection)
                    projection
                }
                TurnEntry.CONTINUE_USER_INTERACTION ->
                    // 审批 / ask-user continuation 只复用 START 冻结的 projection，
                    // 不重新求值适用谓词。
                    runtime.requireTurnModelContextProjection(turnId, worker)
            }
            val generationMessages = if (started.resumableMessage == null) {
                snapshot.currentMessages()
            } else {
                sourceMessages
            }
            val soundTracker = sideEffects.soundTracker()
            val phaseReporter = runtime.livePhaseReporter()
            val turnResult = turnRunner.run(
                TurnRunInputs(
                    turnContext = turnContext,
                    handle = started.handle,
                    reportProcessingText = runtime.processingReporter(),
                    // loop 的 typed 阶段推进本 Turn 的进程内 live phase（本会话不再忽略 onPhase）。
                    onPhase = { phase, _ -> turnLivePhaseOf(phase)?.let(phaseReporter) },
                    messages = generationMessages,
                    assistantMessageId = started.assistantMessageId,
                    providerSessionId = conversationId.toString(),
                    inputTransformers = turnPipelineFactory.input(TurnKind.USER),
                    outputTransformers = turnPipelineFactory.output(),
                    onCheckpoint = activeTurnCommitter::onCheckpoint,
                    onAssistantObserved = activeTurnCommitter::observeAssistant,
                    modelContextEntries = modelContextProjection.entries,
                    durableMessageLocators = modelContextProjection.locators,
                    // 提交协议唯一实现——流式 delta 只动投影（永不落库），随后做 turn-owned 呈现。
                    onStreamDelta = { lastMessage ->
                        activeTurnCommitter.publishStream(lastMessage)
                        inFlightAssistantId = lastMessage.id

                        // 通知等边缘副作用由 ChatNotificationManager 消费；
                        // tryEmit 不挂起，事件丢失只影响单次通知更新，不能反压生成链
                        val tools = lastMessage.getTools()
                        val executingToolLocalCallId = tools.lastOrNull { tool ->
                            runtime.currentTurnPresentation().toolLivePhases[
                                ToolCallLocator(lastMessage.id, tool.stepId, tool.localCallId)
                            ] == ToolLivePhase.EXECUTING
                        }?.localCallId
                        appEventBus.tryEmit(
                            AppEvent.ChatGenerationUpdate(
                                conversationId = conversationId,
                                lastMessage = lastMessage,
                                senderName = senderName.orEmpty(),
                                executingToolLocalCallId = executingToolLocalCallId,
                            )
                        )

                        // 前台声音反馈: 单步生成完成 + 工具待审批
                        if (isForeground.value && generationSoundEnabled) {
                            soundTracker.onStreaming(lastMessage)
                        }
                    },
                    onResult = activeTurnCommitter::commitRunResult,
                    cancelReason = { runtime.peekCancelReason(started.handle.turnId) },
                )
            )

            if (turnResult is TurnOutcome.Failed && isForeground.value && generationSoundEnabled) {
                sideEffects.playTurnFailedSound()
            }
            if (turnResult is TurnPause) {
                val active = requireNotNull(runtime.snapshot.value.stream) {
                    "pause checkpoint has no durable turn owner"
                }
                runtime.retainAwaitingUser(
                    TurnHandle(conversationId, active.epoch, active.turnId, active.assistantMessageId),
                )
            }
            applyTurnSideEffects(
                conversationId = conversationId,
                result = turnResult,
                inFlightAssistantId = inFlightAssistantId,
                senderName = senderName.orEmpty(),
            )
            if (!shouldLaunchCompletionSideEffects(turnResult)) {
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
                    turnCommitter?.finalizeOwnerFailure(outcome = outcome)
                }
            } catch (finalizationError: Exception) {
                e.addSuppressed(finalizationError)
            }
            applyTurnSideEffects(
                conversationId = conversationId,
                result = outcome,
                inFlightAssistantId = inFlightAssistantId,
                senderName = senderName.orEmpty(),
            )
            throw e
        } catch (e: Exception) {
            var reportedError = e
            var outcome = TurnOutcome.fromFailure(e)
            try {
                withContext(NonCancellable) {
                    turnCommitter?.finalizeOwnerFailure(outcome = outcome)
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
            applyTurnSideEffects(
                conversationId = conversationId,
                result = outcome,
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

    /** 本会话终态副作用（通知与错误上报）；执行事实已由 FinalizeTurn 原子收口。 */
    private suspend fun applyTurnSideEffects(
        conversationId: Uuid,
        result: TurnRunResult,
        inFlightAssistantId: Uuid? = null,
        senderName: String,
    ) {
        withContext(NonCancellable) {
            val finalMessage = liveSnapshot(conversationId).currentMessages()
                .firstOrNull { it.id == inFlightAssistantId }
            val terminalStatus = when (result) {
                is TurnOutcome.Failed -> MessageTerminalStatus.FAILED
                is TurnOutcome.Incomplete -> MessageTerminalStatus.INCOMPLETE
                else -> null
            }
            if (terminalStatus != null && inFlightAssistantId != null) {
                val outcome = result as TurnOutcome
                terminalChatError(
                    context = context,
                    conversationId = conversationId,
                    messageId = inFlightAssistantId,
                    status = finalMessage?.terminalStatus ?: terminalStatus,
                    reason = finalMessage?.terminalReason ?: outcome.terminalReason,
                    detail = finalMessage?.terminalDetail ?: outcome.terminalDetail,
                )?.let(chatErrorStore::add)
            } else if (result is TurnOutcome.Failed) {
                chatErrorStore.add(
                    error = result.error,
                    conversationId = conversationId,
                    title = context.getString(R.string.error_title_generation),
                )
            }
            val pendingToolLocalCallId = (result as? TurnPause)
                ?.pendingInteractions
                ?.firstOrNull()
                ?.locator
                ?.localCallId
            if (finalMessage != null && pendingToolLocalCallId != null) {
                appEventBus.emit(
                    AppEvent.ChatGenerationAwaitingUser(
                        conversationId = conversationId,
                        lastMessage = finalMessage,
                        senderName = senderName,
                        pendingToolLocalCallId = pendingToolLocalCallId,
                    )
                )
            } else {
                appEventBus.emit(
                    AppEvent.ChatGenerationEnded(
                        conversationId = conversationId,
                        senderName = senderName,
                        contentPreview = finalMessage?.toText()?.take(50)?.trim(),
                        notifyCompletion = result is TurnOutcome.Completed,
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
