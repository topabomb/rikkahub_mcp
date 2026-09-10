package net.weero.measix.pilot.service

import net.weero.measix.pilot.service.turn.TurnFinalizer
import net.weero.measix.pilot.service.subassistant.SubAssistantLifecycle
import net.weero.measix.pilot.service.turn.TurnContextFactory
import android.app.Application
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.service.runtime.InstalledTurnWorker
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
import net.weero.measix.pilot.data.datastore.getAssistantById
import net.weero.measix.pilot.data.datastore.getChatModel
import net.weero.measix.pilot.data.datastore.getCurrentChatModel
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.AssistantAffectScope
import net.weero.measix.pilot.data.model.replaceRegexes
import net.weero.measix.pilot.data.model.toMessageNode
import net.weero.measix.pilot.service.MemoryService
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

/** START captures configuration through the model owner; continuation reuses its original context. */
private sealed interface TurnLaunch {
    val entry: TurnEntry
    val realmAccess: RealmAccess

    data class Start(override val realmAccess: RealmAccess) : TurnLaunch {
        override val entry = TurnEntry.START
    }

    data class Continue(override val realmAccess: RealmAccess) : TurnLaunch {
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

class ConversationTurnService internal constructor(
    private val context: Application,
    private val appScope: AppScope,
    private val appEventBus: AppEventBus,
    private val settingsStore: SettingsStore,
    private val modelExecutions: ModelExecutionService,
    private val speech: SpeechApplicationService,
    private val memoryService: MemoryService,
    private val sessions: EnterpriseSessionController,
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
    private val _completedSpeech = MutableSharedFlow<ConversationSpeechCompletion>()
    internal val completedSpeech: SharedFlow<ConversationSpeechCompletion> = _completedSpeech.asSharedFlow()

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

    // ---- 对话状态访问 ----

    private fun liveSnapshot(conversationId: Uuid): ConversationAggregateSnapshot =
        runtimeRegistry.requireRuntime(conversationId).durable

    // ---- 发送消息 ----

    private suspend fun <T> withUiTarget(
        target: ConversationCommandTarget,
        operation: suspend (ConversationRuntime) -> T,
    ): T {
        recoveryGate.awaitReady()
        return sessions.withSelectedRealmSelection(target.selection) {
            commandCoordinator.withRootHeaders(target.selection.access.scope, listOf(target.conversationId)) {
                target.requireOpen()
                operation(requireRuntime(target.conversationId))
            }
        }
    }

    private suspend fun <T> withRequest(
        access: RealmAccess,
        runtime: ConversationRuntime,
        turnId: Uuid,
        tree: Boolean = false,
        operation: suspend () -> T,
    ): T {
        val worker = requireNotNull(currentCoroutineContext()[Job])
        suspend fun execute(): T {
            worker.ensureActive()
            check(runtimeRegistry.findRuntime(runtime.id) === runtime && runtime.currentWorker() === worker &&
                runtime.currentGenerationTurnId() == turnId) { "conversation_request_owner_changed" }
            return operation()
        }
        return sessions.withRealmAccess(access) {
            if (tree) commandCoordinator.withRootTree(access.scope, runtime.id, requestWorker = worker) { execute() }
            else commandCoordinator.withRootHeaders(access.scope, listOf(runtime.id)) { execute() }
        }
    }

    /** Installation accepts a request; the page owns only the wait, never the accepted worker. */
    private suspend fun startRequest(
        target: ConversationCommandTarget,
        content: List<UIMessagePart>,
        artifactDraftScope: ArtifactDraftScope?,
        errorTitle: Int,
        operation: suspend (ConversationRuntime, Uuid, ArtifactSubmission?) -> Unit,
    ): Uuid {
        recoveryGate.awaitReady()
        val submission = artifactDraftScope?.claimSubmission(target, content)
        val turnId = Uuid.random()
        var accepted = false
        val ready = CompletableDeferred<Pair<ConversationRuntime, InstalledTurnWorker>?>()
        val job = appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val requestWorker = requireNotNull(coroutineContext[Job])
            var previousCleanupAttempted = false
            suspend fun closePrevious(runtime: ConversationRuntime, installed: InstalledTurnWorker) = withContext(NonCancellable) {
                previousCleanupAttempted = true
                installed.previousWorker?.join()
                turnFinalizer.finalizeSupersededTurn(runtime.id, installed.previousTurnId)
                installed.previousTurnId?.let { runtime.releaseTurnWorker(it, installed.previousWorker) }
                check(runtime.snapshot.value.stream == null) { "previous_turn_still_pending" }
            }
            try {
                val (runtime, installed) = ready.await() ?: return@launch
                closePrevious(runtime, installed)
                currentCoroutineContext().ensureActive()
                operation(runtime, turnId, submission)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                chatErrorStore.add(error, target.conversationId, title = context.getString(errorTitle))
            } finally {
                withContext(NonCancellable) {
                    ready.await()?.let { (runtime, installed) ->
                        try {
                            if (!previousCleanupAttempted) closePrevious(runtime, installed)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            chatErrorStore.add(error, runtime.id, title = context.getString(errorTitle))
                        } finally {
                            submission?.close()
                            runtime.releaseTurnWorker(turnId, requestWorker)
                        }
                    }
                }
            }
        }
        try {
            withUiTarget(target) { runtime ->
                currentCoroutineContext().ensureActive()
                check(!job.isCancelled) { "conversation_request_scope_closed" }
                withContext(NonCancellable) {
                    val installed = runtimeRegistry.installAndStartTurnWorker(
                        runtime.id, turnId, job, supersedeReason = TurnTerminalReasons.SUPERSEDED_BY_NEW_TURN,
                    )
                    accepted = true
                    ready.complete(runtime to installed)
                }
            }
            return turnId
        } finally {
            if (!accepted) {
                ready.complete(null)
                job.cancel()
                if (submission != null) requireNotNull(artifactDraftScope).returnUnaccepted(submission)
            }
        }
    }

    suspend fun sendMessage(
        target: ConversationCommandTarget,
        content: List<UIMessagePart>,
        answer: Boolean = true,
        artifactDraftScope: ArtifactDraftScope? = null,
    ): SendMessageReceipt? {
        if (content.isEmptyInputMessage()) return null
        val userMessageId = Uuid.random()
        val access = target.selection.access
        val turnId = startRequest(target, content, artifactDraftScope, R.string.error_title_send_message) { runtime, turnId, submission ->
            val configuration = modelExecutions.read(access).configuration
            val processed = withRequest(access, runtime, turnId, tree = true) {
                val snapshot = runtime.durable
                val assistant = configuration.assistants[snapshot.header.assistantId] ?: error("conversation_assistant_unavailable")
                val parts = preprocessUserInputParts(content, assistant)
                val message = UIMessage(id = userMessageId, role = MessageRole.USER, parts = parts)
                val localTitle = deriveLocalConversationTitle(message)
                commandCoordinator.executeOrThrow(runtime.id, AppendUserMessage(message, initialTitle = localTitle))
                if (snapshot.header.title.isBlank()) {
                    titleCoordinator.synchronize(runtime.id, runtime.durable.header.title, localTitle)
                }
                parts
            }
            // Publication follows the message transaction, without holding Session/conversation locks.
            submission?.publishCommittedReferences(processed)
            if (answer) launchRun(runtime.id, turnId, launch = TurnLaunch.Start(access))
        }
        return SendMessageReceipt(target.conversationId, turnId, userMessageId)
    }

    suspend fun editAndResend(
        target: ConversationCommandTarget,
        messageId: Uuid,
        content: List<UIMessagePart>,
        artifactDraftScope: ArtifactDraftScope? = null,
    ): SendMessageReceipt? {
        if (content.isEmptyInputMessage()) return null
        val userMessageId = Uuid.random()
        val access = target.selection.access
        val turnId = startRequest(target, content, artifactDraftScope, R.string.error_title_send_message) { runtime, turnId, submission ->
            val configuration = modelExecutions.read(access).configuration
            val processed = withRequest(access, runtime, turnId, tree = true) {
                val snapshot = subAssistantLifecycle.requireClosedRunsBeforeTreeMutation(runtime.durable)
                val nodeIndex = snapshot.nodes.indexOfFirst { node -> node.messages.any { it.id == messageId } }
                check(nodeIndex >= 0) { "Message not found: $messageId" }
                val node = snapshot.nodes[nodeIndex]
                check(node.messages.first { it.id == messageId }.role == MessageRole.USER) { "edit-and-resend requires a USER message" }
                val assistant = configuration.assistants[snapshot.header.assistantId] ?: error("conversation_assistant_unavailable")
                val parts = preprocessUserInputParts(content, assistant)
                commandCoordinator.executeOrThrow(runtime.id, TruncateToNodeIndex(nodeIndexInclusive = nodeIndex))
                subAssistantLifecycle.applyRetentionAfterTreeMutation(runtime.id)
                commandCoordinator.executeOrThrow(runtime.id, EditMessageVariant(node.id,
                    UIMessage(id = userMessageId, role = MessageRole.USER, parts = parts)))
                parts
            }
            submission?.publishCommittedReferences(processed)
            launchRun(runtime.id, turnId, launch = TurnLaunch.Start(access))
        }
        return SendMessageReceipt(target.conversationId, turnId, userMessageId)
    }

    suspend fun regenerateAtMessage(
        target: ConversationCommandTarget,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true,
    ) {
        val access = target.selection.access
        startRequest(target, emptyList(), null, R.string.error_title_regenerate_message) { runtime, turnId, _ ->
            withRequest(access, runtime, turnId, tree = true) {
                val snapshot = subAssistantLifecycle.requireClosedRunsBeforeTreeMutation(runtime.durable)
                val nodeIndex = snapshot.nodes.indexOfFirst { node -> node.messages.any { it.id == message.id } }
                check(nodeIndex >= 0) { "Message not found: ${message.id}" }
                val original = snapshot.nodes[nodeIndex].messages.first { it.id == message.id }
                check(original.role == message.role) { "regeneration_message_changed" }
                if (original.role == MessageRole.USER || regenerateAssistantMsg) {
                    commandCoordinator.executeOrThrow(runtime.id, TruncateToNodeIndex(nodeIndexInclusive = nodeIndex))
                    subAssistantLifecycle.applyRetentionAfterTreeMutation(runtime.id)
                } else commandCoordinator.executeOrThrow(runtime.id, ReplaceMessageTree(snapshot.nodes))
            }
            if (message.role == MessageRole.USER || regenerateAssistantMsg) {
                launchRun(runtime.id, turnId, launch = TurnLaunch.Start(access))
            }
        }
    }

    suspend fun submitToolDecision(
        target: ConversationCommandTarget,
        locator: ToolCallLocator,
        decision: ToolInteractionDecision,
    ) {
        val runtime = withUiTarget(target) { it }
        runtime.withToolApprovalLock {
            // Pending is published just before worker completion. Never join under Session/root locks.
            runtime.awaitCurrentWorker()
            withUiTarget(target) { current ->
                check(current === runtime) { "conversation_request_owner_changed" }
                val owner = requireNotNull(runtime.snapshot.value.stream) { "tool interaction has no active turn owner" }
                val previousWorker = requireNotNull(runtime.currentWorker()) { "tool interaction has no worker" }
                val original = runtime.requireTurnContext(owner.turnId, previousWorker)
                check(original.realmAccess == target.selection.access) { "tool_interaction_session_changed" }
                check(runtime.isAwaitingUser(owner.turnId)) { "tool interaction is not awaiting user" }
                currentCoroutineContext().ensureActive()
                // The decision commit and continuation installation are one accepted operation.
                withContext(NonCancellable) {
                    applyToolInteractionDecision(
                        locator, decision, awaitPreviousGeneration = {}, currentSnapshot = { runtime.snapshot.value },
                        submit = { commandCoordinator.executeOrThrow(runtime.id, it) },
                        onMoreApprovalsPending = {},
                        continueTurn = { owner, _ ->
                            val handle = TurnHandle(runtime.id, owner.epoch, owner.turnId, owner.assistantMessageId)
                            val resumeJob = appScope.launch(start = CoroutineStart.LAZY) {
                                try {
                                    launchRun(runtime.id, owner.turnId, launch = TurnLaunch.Continue(original.realmAccess))
                                } finally { runtime.releaseTurnWorker(owner.turnId, coroutineContext[Job]) }
                            }
                            try { runtimeRegistry.installAndStartUserInteractionContinuation(runtime.id, handle, resumeJob) }
                            catch (error: Throwable) { resumeJob.cancel(); throw error }
                        },
                    )
                }
            }
        }
    }

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
        var inFlightAssistantMessageId: Uuid? = null
        var senderName: String? = null
        var generationSoundEnabled = false
        var turnCommitter: TurnCommitter? = null
        var startedRuntime: ConversationRuntime? = null
        try {
            val runtime = requireRuntime(conversationId)
            startedRuntime = runtime
            val launchPolicy = turnLaunchPolicy(entry, runtime.snapshot.value.stream, turnId, messageRange)

            if (launchPolicy.runStructuralPreflight) withRequest(launch.realmAccess, runtime, turnId, tree = true) {
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
            inFlightAssistantMessageId = null
            var startDisclosureCandidate: String? = null
            val worker = requireNotNull(kotlinx.coroutines.currentCoroutineContext()[Job])
            // START 先做一次性 prepareLaunch（唯一允许 IO、可失败，此时尚无 Turn）。
            val launchPlan = when (entry) {
                TurnEntry.START -> {
                    val realmAccess = launch.realmAccess
                    val captured = modelExecutions.captureTurn(realmAccess, runtime, turnId,
                        requireNotNull(currentCoroutineContext()[Job]), snapshot.header.assistantId)
                    val settings = captured.userSettings
                    val assistant = captured.assistant
                    val model = captured.model.model
                    val mediaCapabilities = captured.mediaCapabilities
                    val memoryAccess = memoryService.captureExecution(realmAccess, assistant)
                    startDisclosureCandidate = ConversationDisclosureSnapshotService.captureCandidate(
                        settings = settings,
                        assistant = assistant,
                        memories = memoryAccess?.let { memoryService.read(it) }.orEmpty(),
                    )
                    val mcpCapabilities = mcpManager.prepareTurnCapabilities(realmAccess, captured, runtime, turnId, worker) {
                        turnFinalizer.stopInteraction(runtime, turnId, "managed_snapshot_required")
                    }
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
                        capture = speech.captureTurn(captured, realmAccess) {
                            turnFinalizer.stopInteraction(runtime, turnId, "managed_snapshot_required")
                        },
                        assistantId = assistant.id,
                        assistantName = assistant.name,
                        sourceType = TtsPlaybackSource.SourceType.NORMAL,
                    )
                    runtime.bindTtsPlaybackContext(turnTtsContext)
                    val regularTools = toolSetFactory.buildTools(
                        realmAccess = realmAccess,
                        assistant = assistant,
                        conversationId = conversationId,
                        settings = settings,
                        capabilityModel = model,
                        inspectionModel = captured.inspectionModel,
                        imageModel = captured.imageModel,
                        workspaceCwd = snapshot.header.workspaceCwd,
                        ttsPlaybackContext = turnTtsContext,
                        mcpCapabilities = mcpCapabilities,
                        additionalToolsBeforeMcp = assistantToolFactory.buildTools(
                            callerAssistant = assistant,
                            masterConversationId = conversationId,
                            realmAccess = realmAccess,
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
                    val tools = buildList {
                        if (memoryAccess != null) {
                            addAll(
                                buildMemoryTools(
                                    onCreation = { content -> memoryService.add(memoryAccess, content) },
                                    onUpdate = { id, content -> memoryService.update(memoryAccess, id, content) },
                                    onDelete = { id -> memoryService.delete(memoryAccess, id) },
                                    isStillAllowed = { memoryService.isAllowed(memoryAccess) },
                                ),
                            )
                        }
                        addAll(regularTools)
                    }
                    turnContextFactory.prepareLaunch(
                        realmAccess = realmAccess,
                        settings = settings,
                        assistant = assistant,
                        model = captured.model,
                        mediaCapabilities = mediaCapabilities,
                        conversationSystemPrompt = snapshot.header.customSystemPrompt,
                        conversationModeInjectionIds = snapshot.header.modeInjectionIds,
                        tools = tools,
                    )
                }

                TurnEntry.CONTINUE_USER_INTERACTION -> null
            }
            val started = withRequest(launch.realmAccess, runtime, turnId) {
                withContext(NonCancellable) {
                    when (entry) {
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
                    }.also { turnCommitter = it.turnCommitter }
                }
            }
            // 先认领终态 owner：materialize 若失败，本 catch 收口；即便收口本身抛错，
            // 外层 launchRun catch 仍能以同一 committer 兜底，绝不留 RUNNING-without-context。
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
            val displaySettings = settingsStore.userSettings.value
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
            inFlightAssistantMessageId = started.assistantMessageId
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
            val speechContext = runtime.peekTtsPlaybackContext()?.takeIf { it.capture.interactionId == "int_$turnId" }
            val turnResult = turnRunner.run(
                TurnRunInputs(
                    turnContext = turnContext,
                    handle = started.handle,
                    reportProcessingText = runtime.processingReporter(),
                    // loop 的 typed 阶段推进本 Turn 的进程内 live phase（本会话不再忽略 onPhase）。
                    onPhase = { phase, _ -> phaseReporter(turnLivePhaseOf(phase)) },
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
                        inFlightAssistantMessageId = lastMessage.id

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

            if (turnResult is TurnOutcome.Completed && speechContext != null) {
                runtime.durable.currentMessages().firstOrNull { it.id == started.assistantMessageId }?.let { message ->
                    _completedSpeech.emit(ConversationSpeechCompletion(conversationId, turnId, message, speechContext))
                }
            }
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
                inFlightAssistantMessageId = inFlightAssistantMessageId,
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

            try {
                withRequest(launch.realmAccess, runtime, turnId) {
                    sideEffects.launchTitle(runtime, launch.realmAccess)
                    sideEffects.launchSuggestion(runtime, launch.realmAccess)
                }
            } catch (error: net.weero.measix.pilot.data.enterprise.EnterpriseConfigurationException) {
                if (error.reason != "enterprise_data_access_unavailable") throw error
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
                inFlightAssistantMessageId = inFlightAssistantMessageId,
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
                inFlightAssistantMessageId = inFlightAssistantMessageId,
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
        inFlightAssistantMessageId: Uuid? = null,
        senderName: String,
    ) {
        withContext(NonCancellable) {
            val finalMessage = liveSnapshot(conversationId).currentMessages()
                .firstOrNull { it.id == inFlightAssistantMessageId }
            val terminalStatus = when (result) {
                is TurnOutcome.Failed -> MessageTerminalStatus.FAILED
                is TurnOutcome.Incomplete -> MessageTerminalStatus.INCOMPLETE
                else -> null
            }
            if (terminalStatus != null && inFlightAssistantMessageId != null) {
                val outcome = result as TurnOutcome
                terminalChatError(
                    context = context,
                    conversationId = conversationId,
                    messageId = inFlightAssistantMessageId,
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

/** A completed reply carries its original speech authority; consumers never look up a newer turn's context. */
internal data class ConversationSpeechCompletion(
    val conversationId: Uuid,
    val turnId: Uuid,
    val message: UIMessage,
    val context: TtsToolPlaybackContext,
)
