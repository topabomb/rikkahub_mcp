package net.weero.measix.pilot.service

import android.app.Application
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
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
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.ProviderTerminalStatus
import me.rerere.common.android.Logging
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.event.AppEvent
import net.weero.measix.pilot.data.event.AppEventBus
import net.weero.measix.pilot.data.ai.FinishedReason
import net.weero.measix.pilot.data.ai.GenerationHandler
import net.weero.measix.pilot.data.ai.ToolExecutionEventStatus
import net.weero.measix.pilot.data.ai.replaceToolsAtOrdinals
import net.weero.measix.pilot.data.db.entity.ToolExecutionStatus
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
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
import net.weero.measix.pilot.data.files.AttachmentCloner
import net.weero.measix.pilot.data.files.FilesManager
import net.weero.measix.pilot.data.files.ToolArtifactRewriter
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.AssistantAffectScope
import net.weero.measix.pilot.data.model.replaceRegexes
import net.weero.measix.pilot.data.model.toMessageNode
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.data.repository.FolderRepository
import net.weero.measix.pilot.data.repository.MemoryRepository
import net.weero.measix.pilot.data.repository.WorkspaceRepository
import net.weero.measix.pilot.utils.applyPlaceholders
import net.weero.measix.pilot.utils.SoundEffectPlayer
import java.time.Instant
import java.util.Locale
import kotlinx.datetime.LocalDateTime
import kotlin.uuid.Uuid
import net.weero.measix.pilot.service.runtime.ConversationCommand
import net.weero.measix.pilot.service.runtime.ConversationRuntime
import net.weero.measix.pilot.service.runtime.ConversationRuntimeRegistry
import net.weero.measix.pilot.service.runtime.ConversationSnapshot
import net.weero.measix.pilot.service.runtime.TurnPipelineFactory
import net.weero.measix.pilot.service.runtime.TurnEngine
import net.weero.measix.pilot.service.runtime.TurnEvent
import net.weero.measix.pilot.service.runtime.toConversation
import net.weero.measix.pilot.service.runtime.AppendUserMessage
import net.weero.measix.pilot.service.runtime.BeginTurn
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

private const val TAG = "ChatService"

data class ChatError(
    val id: Uuid = Uuid.random(),
    val title: String? = null,
    val error: Throwable,
    val conversationId: Uuid? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val solution: ChatErrorSolution? = null,
)

enum class ChatErrorSolution {
    CheckTitleModelSettings,
}

/**
 * 主回合被用户停止或被新回合替换后的 ToolCall 收口在 TurnRecovery
 * （恢复语义唯一所有者）。音效注意力键 / 完成副作用判定在 GenerationSideEffects。
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

class ChatService(
    private val context: Application,
    private val appScope: AppScope,
    private val appEventBus: AppEventBus,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val generationHandler: GenerationHandler,
    private val templateTransformer: TemplateTransformer,
    private val providerManager: ProviderManager,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val toolSetFactory: GenerationToolSetFactory,
    private val workspaceRepository: WorkspaceRepository,
    private val folderRepository: FolderRepository,
    private val soundEffectPlayer: SoundEffectPlayer,
    private val assistantToolFactory: AssistantToolFactory,
    private val delegationCoordinator: DelegationCoordinator,
    private val turnRecovery: TurnRecovery,
    private val sessionRegistry: ConversationRuntimeRegistry,
    private val recoveryGate: AssistantDataRecoveryGate = AssistantDataRecoveryGate.completed(),
    private val json: Json,
    private val toolArtifactRewriter: ToolArtifactRewriter? = null,
) {
    // workspace 系统提示注入 (依赖 workspaceRepository, 故在类内构造)
    private val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)
    private val toolArtifactReplayTransformer = toolArtifactRewriter?.let(::ToolArtifactReplayTransformer)

    // Master/Target 共用管道装配（取代顶层 inputTransformers/outputTransformers val）
    private val turnPipelineFactory = TurnPipelineFactory(
        templateTransformer = templateTransformer,
        workspaceReminderTransformer = workspaceReminderTransformer,
        toolArtifactReplayTransformer = toolArtifactReplayTransformer,
    )

    // 错误状态
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    private val turnRecoveryReady = CompletableDeferred<Unit>()

    /** 生成副作用域（音效反馈 + 标题/建议/压缩衍生生成），UI 经此访问衍生生成。 */
    val sideEffects = GenerationSideEffects(
        context = context,
        appScope = appScope,
        settingsStore = settingsStore,
        providerManager = providerManager,
        conversationRepo = conversationRepo,
        sessionRegistry = sessionRegistry,
        soundEffectPlayer = soundEffectPlayer,
        json = json,
        reportError = { chatError -> _errors.update { it + chatError } },
    )

    fun addError(
        error: Throwable,
        conversationId: Uuid? = null,
        title: String? = null,
        solution: ChatErrorSolution? = null,
    ) {
        if (error is CancellationException) return
        _errors.update {
            it + ChatError(title = title, error = error, conversationId = conversationId, solution = solution)
        }
    }

    fun dismissError(id: Uuid) {
        _errors.update { list -> list.filter { it.id != id } }
    }

    fun clearAllErrors() {
        _errors.value = emptyList()
    }

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
        appScope.launch {
            runCatching {
                recoveryGate.awaitReady()
                recoverInterruptedTurns()
            }.onFailure { error ->
                Logging.log(TAG, "turn recovery failed: ${error.message}")
            }
            turnRecoveryReady.complete(Unit)
        }
    }

    fun cleanup() = runCatching {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        sessionRegistry.cleanup()
    }

    private suspend fun recoverInterruptedTurns() {
        turnRecovery.recoverInterruptedTurns()
    }

    // ---- Session 管理 ----

    private fun getOrCreateSession(conversationId: Uuid): ConversationRuntime {
        return sessionRegistry.getOrCreateSession(conversationId)
    }

    // ---- 引用管理 ----

    fun addConversationReference(conversationId: Uuid) {
        sessionRegistry.addConversationReference(conversationId)
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessionRegistry.removeConversationReference(conversationId)
    }

    private fun launchWithConversationReference(
        conversationId: Uuid,
        block: suspend () -> Unit
    ): Job = appScope.launch {
        addConversationReference(conversationId)
        try {
            block()
        } finally {
            removeConversationReference(conversationId)
        }
    }

    // ---- 对话状态访问 ----

    /**
     * 命令语义读取（turn 边界低频点）：内存快照 → Conversation 形状。
     * 流式高频路径禁止使用（订阅 [getConversationSnapshot]）。
     */
    private fun liveConversation(conversationId: Uuid): Conversation {
        return sessionRegistry.getOrCreateSession(conversationId).snapshot.value.toConversation()
    }

    /** 唯一内部事实流（UI 主订阅源）。 */
    fun getConversationSnapshot(conversationId: Uuid): StateFlow<ConversationSnapshot> {
        return sessionRegistry.getOrCreateSession(conversationId).snapshot
    }

    /** 所有结构性修改的唯一入口（UI/Application 提交命令）。 */
    suspend fun submitConversationCommand(conversationId: Uuid, command: ConversationCommand): Conversation {
        return sessionRegistry.getOrCreateSession(conversationId).submit(command).toConversation()
    }

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> {
        return sessionRegistry.getGenerationJobStateFlow(conversationId)
    }

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> {
        return sessionRegistry.getProcessingStatusFlow(conversationId)
    }

    fun getTtsQueueSessionId(conversationId: Uuid): String? =
        sessionRegistry.getSession(conversationId)?.peekTtsQueueSessionId()

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> {
        return sessionRegistry.getConversationJobs()
    }

    // ---- 初始化对话 ----

    suspend fun initializeConversation(conversationId: Uuid) {
        recoveryGate.awaitReady()
        turnRecoveryReady.await()
        val session = getOrCreateSession(conversationId)
        val conversation = conversationRepo.getConversationById(conversationId)
        if (conversation != null) {
            val live = session.snapshot.value.toConversation()
            val keepLiveTree = session.isGenerating ||
                session.isDirty() ||
                (live.messageNodes.isNotEmpty() && !live.updateAt.isBefore(conversation.updateAt))
            if (keepLiveTree) {
                // 装载合并：以内存树为准，仅回填 DB 侧 header 权威值（装载路径，无结构写）
                val merged = live.copy(
                    title = live.title.ifBlank { conversation.title },
                    isPinned = conversation.isPinned,
                    folderId = conversation.folderId,
                )
                session.loadSnapshot(merged)
            } else {
                // DB 为权威：整树装载（装载路径）
                session.loadSnapshot(conversation)
            }
            settingsStore.updateAssistant(conversation.assistantId)
        } else {
            // 新建对话, 并添加预设消息（装载路径：新会话初始化，树落库由首次生成命令承担）
            val currentSettings = settingsStore.settingsFlowRaw.first()
            val assistant = currentSettings.getCurrentAssistant()
            val newConversation = Conversation.ofId(
                id = conversationId,
                assistantId = assistant.id,
                newConversation = true
            ).updateCurrentMessages(assistant.presetMessages)
            session.loadSnapshot(newConversation)
        }
    }

    // ---- 发送消息 ----

    fun sendMessage(conversationId: Uuid, content: List<UIMessagePart>, answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return

        val session = getOrCreateSession(conversationId)
        val previousJob = session.getJob()
        val previousTurnId = session.currentTurnId()
        if (previousJob != null && previousTurnId != null) {
            session.requestCancel(previousTurnId, TurnTerminalReasons.SUPERSEDED_BY_NEW_TURN)
            previousJob.cancel()
        }

        val turnId = Uuid.random()
        session.beginTurn(turnId)
        val job = appScope.launch {
            try {
                recoveryGate.awaitReady()
                turnRecoveryReady.await()
                runCatching { previousJob?.join() }
                turnRecovery.finishInterruptedPendingTools(conversationId, previousTurnId)

                val currentConversation = session.snapshot.value.toConversation()
                val settings = settingsStore.settingsFlow.first()
                val assistant = settings.getAssistantById(currentConversation.assistantId)
                    ?: settings.getCurrentAssistant()
                val processedContent = preprocessUserInputParts(content, assistant)

                // append 用户消息走命令协议（AppendUserMessage → reducer → delta 落库；
                // reducer 同时清理 newConversation 运行态标记）
                if (!conversationRepo.existsConversationById(conversationId)) {
                    conversationRepo.insertConversation(session.snapshot.value.toConversation())
                }
                session.submit(
                    AppendUserMessage(
                        UIMessage(
                            role = MessageRole.USER,
                            parts = processedContent,
                        )
                    )
                )

                // 开始补全
                if (answer) {
                    launchRun(conversationId, turnId = turnId)
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            }
        }
        session.setJob(job, turnId)
    }

    private fun preprocessUserInputParts(parts: List<UIMessagePart>, assistant: Assistant): List<UIMessagePart> {
        return parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    part.copy(
                        text = part.text.replaceRegexes(
                            assistant = assistant,
                            scope = AssistantAffectScope.USER,
                            visual = false
                        )
                    )
                }

                else -> AttachmentRefs.ensureAttachmentRef(part)
            }
        }
    }

    // ---- 重新生成消息 ----

    fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        val session = getOrCreateSession(conversationId)
        val previousJob = session.getJob()
        val previousTurnId = session.currentTurnId()
        if (previousJob != null && previousTurnId != null) {
            session.requestCancel(previousTurnId, TurnTerminalReasons.SUPERSEDED_BY_NEW_TURN)
            previousJob.cancel()
        }

        val turnId = Uuid.random()
        session.beginTurn(turnId)
        val job = appScope.launch {
            try {
                recoveryGate.awaitReady()
                turnRecoveryReady.await()
                runCatching { previousJob?.join() }
                turnRecovery.finishInterruptedPendingTools(conversationId, previousTurnId)
                val conversation = turnRecovery.finalizeStaleRunsBeforeMutation(session.snapshot.value.toConversation())

                if (message.role == MessageRole.USER) {
                    // 如果是用户消息，则截止到当前消息（TruncateToNodeIndex，master 树 delta 落库）
                    val node = conversation.getMessageNodeByMessage(message)
                    val indexAt = conversation.messageNodes.indexOf(node)
                    session.submit(TruncateToNodeIndex(nodeIndexInclusive = indexAt))
                    applyChildRetentionAfterTreeMutation(conversationId)
                    launchRun(conversationId, turnId = turnId)
                } else {
                    if (regenerateAssistantMsg) {
                        val node = conversation.getMessageNodeByMessage(message)
                        val nodeIndex = conversation.messageNodes.indexOf(node)
                        launchRun(conversationId, turnId = turnId, messageRange = 0..<nodeIndex)
                    } else {
                        // recoverMasterForMutation 可能已收口树（stale runs），同步落库
                        session.submit(ReplaceMessageTree(conversation.messageNodes))
                    }
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_regenerate_message))
            }
        }

        session.setJob(job, turnId)
    }

    // ---- 处理工具调用审批 ----

    fun handleToolApproval(
        conversationId: Uuid,
        locator: ToolCallLocator,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
    ) {
        val session = getOrCreateSession(conversationId)
        appScope.launch {
            try {
                recoveryGate.awaitReady()
                turnRecoveryReady.await()
                session.withToolApprovalLock {
                    // Pending is emitted just before the generation Flow ends. Wait for its
                    // final checkpoint/save instead of cancelling it. Rapid decisions for
                    // several cards are serialized so one answer cannot cancel another.
                    session.getJob()?.join()

                    // 工具审批走 UpdateToolApproval 命令（reducer 纯变换 + delta 落库）
                    val newApprovalState = when {
                        answer != null -> ToolApprovalState.Answered(answer)
                        approved -> ToolApprovalState.Approved
                        else -> ToolApprovalState.Denied(reason)
                    }
                    val before = session.snapshot.value
                    session.submit(
                        UpdateToolApproval(
                            messageId = locator.messageId,
                            toolOrdinal = locator.toolOrdinal,
                            approvalState = newApprovalState,
                        )
                    )
                    if (session.snapshot.value === before) return@withToolApprovalLock
                    val updatedConversation = session.snapshot.value.toConversation()

                    val hasPendingTools = updatedConversation.currentMessages.lastOrNull()
                        ?.getTools()?.any { it.isPending } == true
                    if (hasPendingTools) {
                        _generationDoneFlow.emit(conversationId)
                    } else {
                        val turnId = Uuid.random()
                        session.beginTurn(turnId)
                        val resumeJob = appScope.launch {
                            launchRun(
                                conversationId = conversationId,
                                turnId = turnId,
                                resumeExistingTtsTurn = true,
                            )
                            _generationDoneFlow.emit(conversationId)
                        }
                        session.setJob(resumeJob, turnId)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_tool_approval))
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
        resumeExistingTtsTurn: Boolean = false,
    ) {
        var finishedReason: FinishedReason? = null
        var inFlightAssistantId: Uuid? = null
        var senderName: String? = null
        var generationSoundEnabled = false
        try {
            val session = getOrCreateSession(conversationId)
            val settings = settingsStore.settingsFlow.first()
            val initialConversation = liveConversation(conversationId)
            val assistant = settings.getAssistantById(initialConversation.assistantId)
                ?: settings.getCurrentAssistant()
            generationSoundEnabled = settings.displaySetting.enableMessageGenerationSoundEffect

            // reset suggestions
            session.submit(UpdateHeader(suggestions = emptyList()))

            checkInvalidMessages(conversationId)
            val loadedConversation = liveConversation(conversationId)
            val backfilled = AttachmentRefs.backfillConversation(loadedConversation)
            if (backfilled != loadedConversation) {
                // 附件引用回填为内存投影修正（随首个 checkpoint 落库，无独立持久化需求）
                session.loadSnapshot(backfilled)
            }

            var conversation = liveConversation(conversationId)
            val sourceMessages = if (messageRange != null) {
                conversation.currentMessages.subList(messageRange.start, messageRange.endInclusive + 1)
            } else {
                conversation.currentMessages
            }
            inFlightAssistantId = null
            // turn 骨架唯一实现：resumable 槽探测 + BeginTurn + RUNNING turn 事实（TurnEngine.start），
            // 终态 FinalizeTurn 由 bind 提交；此处仅注入取消/失败时的 Child 消息并入。
            val started = TurnEngine.start(
                runtime = session,
                turnId = turnId,
                messages = sourceMessages,
                resumeFilter = { message ->
                    message.role == MessageRole.ASSISTANT &&
                        message.getTools().any { !it.isExecuted && it.approvalState.canResumeToolExecution() }
                },
                prepareFinalize = { status, messages ->
                    if (status == TurnExecutionStatus.CANCELLED || status == TurnExecutionStatus.FAILED) {
                        turnRecovery.closeOpenTools(
                            conversation = liveConversation(conversationId),
                            messageId = inFlightAssistantId,
                            cancelledByUser = status == TurnExecutionStatus.CANCELLED,
                        ).currentMessages
                    } else {
                        val current = liveConversation(conversationId).currentMessages
                        current.ifEmpty { messages }
                    }
                },
            )
            val turnEngine = started.engine
            inFlightAssistantId = started.assistantMessageId
            val assistantSlot = started.resumableMessage ?: UIMessage(
                id = started.assistantMessageId,
                role = MessageRole.ASSISTANT,
                parts = emptyList(),
            )
            val generationMessages = if (started.resumableMessage == null) sourceMessages + assistantSlot else sourceMessages
            conversation = liveConversation(conversationId)

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
                    addError(
                        IllegalStateException(context.getString(R.string.tools_warning)),
                        conversationId,
                        title = context.getString(R.string.error_title_tool_unavailable)
                    )
                }
            }

            conversation = liveConversation(conversationId)

            // start generating
            // loop 声音反馈（生成副作用域：per-turn 去重器）
            val soundTracker = sideEffects.soundTracker()
            // 每轮 Master Generation 创建一个 turn-level TtsToolPlaybackContext，
            // 在整轮 turn 内被 Master 和所有 Target 共享。播放器以 sessionId 独占队列：
            // 新 turn 替换旧队列；同一 turn 是否追加由顺序播放开关决定。
            val turnTtsContext = TtsToolPlaybackContext(
                sessionId = session.getTtsQueueSessionId(resumeExistingTtsTurn),
                assistantId = assistant.id,
                assistantName = assistant.name,
                sourceType = TtsPlaybackSource.SourceType.NORMAL,
            )
            generationHandler.generateText(
                settings = settings,
                model = model,
                processingStatus = session.processingStatus,
                messages = generationMessages,
                assistantMessageId = assistantSlot.id,
                assistant = assistant,
                conversationSystemPrompt = conversation.customSystemPrompt,
                conversationModeInjectionIds = conversation.modeInjectionIds,
                workspaceCwd = conversation.workspaceCwd,
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
                    resolvedModel = model,
                    workspaceCwd = conversation.workspaceCwd,
                    ttsPlaybackContext = turnTtsContext,
                    additionalToolsBeforeMcp = assistantToolFactory.buildTools(
                        callerAssistant = assistant,
                        masterConversationId = conversationId,
                        ttsPlaybackContext = turnTtsContext,
                    ),
                    onInvalidMcpServerNames = { invalidNames ->
                        addError(
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
                onCheckpoint = turnEngine::onCheckpoint,
            ).let { source ->
                // 提交协议唯一实现——chunk→applyStreamingDelta（永不落库）、
                // checkpoint→CommitCheckpoint、Finished/异常/取消→FinalizeTurn
                turnEngine.bind(source).collect { event ->
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
                            // bind 将流内异常/取消转为 Finished(null, error)；此处重抛，
                            // 由外层 catch 统一分类终态（CancellationException → CANCELLED，其余 → FAILED）
                            if (event.error != null) throw event.error
                            finishedReason = event.reason
                        }
                    }
                }
            }

            applyMasterTurnSideEffects(
                conversationId = conversationId,
                turnId = turnId,
                outcome = when (finishedReason) {
                    FinishedReason.COMPLETED -> MasterTurnOutcome.SUCCESS
                    FinishedReason.AWAITING_APPROVAL -> MasterTurnOutcome.AWAITING_APPROVAL
                    FinishedReason.STEP_LIMIT_REACHED,
                    FinishedReason.INTERACTION_LIMIT_REACHED,
                    -> MasterTurnOutcome.INCOMPLETE
                    null -> MasterTurnOutcome.INCOMPLETE
                },
                inFlightAssistantId = inFlightAssistantId,
                senderName = senderName.orEmpty(),
            )
            if (!shouldLaunchCompletionSideEffects(finishedReason)) {
                return
            }
            val hasPendingApproval = liveConversation(conversationId)
                .currentMessages.lastOrNull()?.getTools()?.any { it.isPending } == true
            if (hasPendingApproval) {
                return
            }

            if (isForeground.value && settings.displaySetting.enableMessageGenerationSoundEffect) {
                sideEffects.playTurnCompleteSound()
            }

            launchWithConversationReference(conversationId) {
                sideEffects.generateTitle(liveConversation(conversationId))
            }
            launchWithConversationReference(conversationId) {
                sideEffects.generateSuggestion(conversationId, liveConversation(conversationId))
            }
        } catch (e: CancellationException) {
            applyMasterTurnSideEffects(
                conversationId = conversationId,
                turnId = turnId,
                outcome = MasterTurnOutcome.CANCELLED,
                inFlightAssistantId = inFlightAssistantId,
                senderName = senderName.orEmpty(),
            )
            throw e
        } catch (e: Exception) {
            Logging.log(TAG, "launchRun failed: ${e.message}")
            Logging.log(TAG, e.stackTraceToString().lines().take(6).joinToString("\n"))
            if (isForeground.value && generationSoundEnabled) {
                sideEffects.playTurnFailedSound()
            }
            applyMasterTurnSideEffects(
                conversationId = conversationId,
                turnId = turnId,
                outcome = if (
                    e is HttpException && e.terminalStatus == ProviderTerminalStatus.INCOMPLETE
                ) {
                    MasterTurnOutcome.INCOMPLETE
                } else {
                    MasterTurnOutcome.FAILED
                },
                error = e,
                inFlightAssistantId = inFlightAssistantId,
                senderName = senderName.orEmpty(),
            )
        }
    }

    // ---- 检查无效消息 ----

    private suspend fun checkInvalidMessages(conversationId: Uuid) {
        val conversation = liveConversation(conversationId)
        val messagesNodes = retainValidMessageNodes(conversation.messageNodes)
        if (messagesNodes == conversation.messageNodes) return
        // 无效消息清理 = 树替换（命令通道 delta 落库）
        getOrCreateSession(conversationId).submit(ReplaceMessageTree(messagesNodes))
    }

    /**
     * Master 终态副作用（通知、dangling 工具行、错误上报）。
     * FinalizeTurn 已由 TurnEngine.bind 提交；此处不再落库。
     */
    private suspend fun applyMasterTurnSideEffects(
        conversationId: Uuid,
        turnId: Uuid,
        outcome: MasterTurnOutcome,
        error: Throwable? = null,
        inFlightAssistantId: Uuid? = null,
        senderName: String,
    ) {
        withContext(NonCancellable) {
            val reason = when (outcome) {
                MasterTurnOutcome.SUCCESS,
                MasterTurnOutcome.AWAITING_APPROVAL,
                -> null
                MasterTurnOutcome.CANCELLED -> {
                    getOrCreateSession(conversationId).consumeCancelReason(turnId)
                        ?: TurnTerminalReasons.USER_STOP
                }
                MasterTurnOutcome.FAILED -> if (error is HttpException) {
                    TurnTerminalReasons.PROVIDER_FAILED
                } else {
                    TurnTerminalReasons.RUNTIME_ERROR
                }
                MasterTurnOutcome.INCOMPLETE -> TurnTerminalReasons.PROVIDER_INCOMPLETE
            }
            turnRecovery.finalizeDanglingToolExecutions(turnId, outcome, reason)
            if (outcome == MasterTurnOutcome.FAILED && error != null) {
                addError(error, conversationId, title = context.getString(R.string.error_title_generation))
            }
            val finalMessage = liveConversation(conversationId).currentMessages
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

    /**
     * 取消 / 中断 / 崩溃收口原语统一在 [TurnRecovery]（恢复语义唯一所有者）：
     * closeOpenTools / finishInterruptedPendingTools / finalizeDanglingToolExecutions。
     */

    // ---- 对话状态更新 ----

    /**
     * @Transient 投影修正入口（isFavorite 等不落库字段；收藏事实由 FavoriteRepository 持有，
     * Runtime 下次装载时回填）。结构性修改必须经 [submitConversationCommand]（命令通道）。
     */
    fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        val session = getOrCreateSession(conversationId)
        session.loadSnapshot(update(session.snapshot.value.toConversation()))
    }

    /**
     * Header 更新分流：活跃 session 走 UpdateHeader 命令（内存 + 窄列原子提交）；
     * 非活跃会话无内存态，走 [fallback] 窄列写。全库不存在整对象回写路径（单一写者不变式）。
     */
    private suspend fun submitHeaderUpdate(
        conversationId: Uuid,
        fallback: suspend () -> Unit,
        build: () -> UpdateHeader,
    ) {
        val session = sessionRegistry.getSession(conversationId)
        if (session != null) {
            session.submit(build())
        } else {
            fallback()
        }
    }

    suspend fun updateConversationTitle(conversationId: Uuid, title: String) {
        recoveryGate.awaitReady()
        submitHeaderUpdate(
            conversationId,
            fallback = { conversationRepo.updateConversationTitle(conversationId, title) },
            build = { UpdateHeader(title = title) },
        )
    }

    /**
     * 移动会话到文件夹（folderId 为 null 表示移出到未归类）。
     *
     * 活跃 session 走 UpdateHeader 命令（内存 + folder_id 窄列原子提交，消除整对象回写
     * 覆盖缺陷）；非活跃会话无内存态，直接窄列写。
     */
    suspend fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) {
        recoveryGate.awaitReady()
        sessionRegistry.getSession(conversationId)?.let { session ->
            session.submit(
                UpdateHeader(
                    folderId = if (folderId == null) {
                        OptionalFolderId.Clear
                    } else {
                        OptionalFolderId.SetTo(folderId)
                    },
                )
            )
        } ?: conversationRepo.updateConversationFolderId(conversationId, folderId)
    }

    /**
     * 文件夹内是否存在正在生成回复的会话。
     * 仅活跃 session 可能在生成；内存态 folderId 为权威（移动会先同步内存态）。
     */
    fun hasGeneratingConversationInFolder(folderId: Uuid): Boolean {
        return sessionRegistry.getSessionsSnapshot()
            .any { it.isGenerating && it.snapshot.value.header.folderId == folderId }
    }

    /**
     * 删除文件夹（folder_id 归属会被清空，会话本身保留）。
     *
     * 活跃 session 走 UpdateHeader(folderId=Clear) 命令（内存 + 窄列原子清空，
     * 消除"整对象保存写回已删除 folder_id"的悬空缺陷）；非活跃会话由
     * folderRepository.deleteFolder 批量清空 DB 归属。
     */
    suspend fun deleteFolder(folderId: Uuid) {
        recoveryGate.awaitReady()
        sessionRegistry.getSessionsSnapshot()
            .filter { it.snapshot.value.header.folderId == folderId }
            .forEach { it.submit(UpdateHeader(folderId = OptionalFolderId.Clear)) }
        folderRepository.deleteFolder(folderId)
    }

    /** Serializes user-initiated repository writes behind startup recovery. */
    suspend fun insertConversation(conversation: Conversation) {
        recoveryGate.awaitReady()
        conversationRepo.insertConversation(conversation)
        sessionRegistry.getSession(conversation.id)?.let {
            sessionRegistry.loadConversation(conversation.id, conversation)
        }
    }

    /**
     * 非活跃会话的助手迁移（窄列写：assistantId + folder 清空，对齐 withAssistant 语义；
     * 消除整对象回写路径——不变式 2）。活跃会话走 UpdateHeader 命令。
     */
    suspend fun updatePersistedConversation(conversation: Conversation) {
        recoveryGate.awaitReady()
        conversationRepo.updateConversationAssistantId(conversation.id, conversation.assistantId)
        conversationRepo.updateConversationFolderId(conversation.id, null)
        sessionRegistry.getSession(conversation.id)?.let {
            sessionRegistry.loadConversation(conversation.id, conversation)
        }
    }

    suspend fun deleteConversation(conversation: Conversation) {
        recoveryGate.awaitReady()
        stopGeneration(conversation.id)
        val childIds = conversationRepo.getChildConversations(conversation.id).map { it.id }
        conversationRepo.deleteConversation(conversation)
        childIds.forEach { childId ->
            sideEffects.clearTitleTracking(childId)
            sessionRegistry.evictSession(childId)
        }
        sideEffects.clearTitleTracking(conversation.id)
        sessionRegistry.evictSession(conversation.id)
    }

    suspend fun deleteConversationsOfAssistant(assistantId: Uuid) {
        recoveryGate.awaitReady()
        conversationRepo.getConversationsOfAssistant(assistantId).first()
            .forEach { deleteConversation(it) }
    }

    suspend fun togglePinStatus(conversationId: Uuid) {
        recoveryGate.awaitReady()
        submitHeaderUpdate(
            conversationId,
            fallback = { conversationRepo.togglePinStatus(conversationId) },
            build = {
                val session = sessionRegistry.getSession(conversationId)!!
                UpdateHeader(isPinned = !session.snapshot.value.header.isPinned)
            },
        )
    }

    // ---- 消息操作 ----

    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>
    ) {
        if (parts.isEmptyInputMessage()) return
        recoveryGate.awaitReady()

        val currentConversation = liveConversation(conversationId)
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(currentConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val processedParts = preprocessUserInputParts(parts, assistant)
        val targetNode = currentConversation.messageNodes.firstOrNull { node ->
            node.messages.any { it.id == messageId }
        } ?: return

        // 编辑 = 在目标节点追加新变体并选中（EditMessageVariant，reducer 唯一路径）
        getOrCreateSession(conversationId).submit(
            EditMessageVariant(
                nodeId = targetNode.id,
                variant = UIMessage(
                    role = targetNode.currentMessage.role,
                    parts = processedParts,
                ),
            )
        )
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid
    ): Conversation {
        stopGeneration(conversationId)
        val currentConversation = turnRecovery.finalizeStaleRunsBeforeMutation(
            liveConversation(conversationId)
        )
        val targetNodeIndex = currentConversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            throw NoSuchElementException("Message not found")
        }

        val copiedNodes = currentConversation.messageNodes
            .subList(0, targetNodeIndex + 1)
            .map { node ->
                node.copy(
                    id = Uuid.random(),
                    messages = node.messages.map { message ->
                        message.copy(parts = message.parts.copyWithForkedFilesRecursively())
                    }
                )
            }

        val forkId = Uuid.random()
        val sourceChildren = conversationRepo.getChildConversations(currentConversation.id)
            .associateBy { it.id }
        val forkedTree = forkSubAssistantTree(
            sourceMaster = currentConversation,
            copiedMasterNodes = copiedNodes,
            newMasterId = forkId,
            sourceChildren = sourceChildren,
            json = json,
        )
        val forkConversation = Conversation(
            id = forkId,
            assistantId = currentConversation.assistantId,
            messageNodes = forkedTree.masterNodes,
            customSystemPrompt = currentConversation.customSystemPrompt,
            modeInjectionIds = currentConversation.modeInjectionIds,
        )
        val forkedChildren = forkedTree.children.map { child ->
            child.copy(
                messageNodes = child.messageNodes.map { node ->
                    node.copy(
                        messages = node.messages.map { message ->
                            message.copy(parts = message.parts.copyWithForkedFilesRecursively())
                        }
                    )
                }
            )
        }
        conversationRepo.insertConversationTree(forkConversation, forkedChildren)
        // fork 直建新会话（明确不经 Runtime 命令通道）；装载进 runtime 供后续交互
        getOrCreateSession(forkConversation.id).loadSnapshot(forkConversation)
        return forkConversation
    }

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int
    ) {
        recoveryGate.awaitReady()
        val currentConversation = liveConversation(conversationId)
        val targetNode = currentConversation.messageNodes.firstOrNull { it.id == nodeId }
            ?: throw NoSuchElementException("Message node not found")

        if (selectIndex !in targetNode.messages.indices) {
            throw IllegalArgumentException("Invalid selectIndex")
        }

        // 分支切换 = SelectNodeVariant（reducer 幂等，相同 selectIndex 不产生变更）
        getOrCreateSession(conversationId).submit(SelectNodeVariant(nodeId, selectIndex))
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) {
        stopGeneration(conversationId)
        turnRecovery.finalizeStaleRunsBeforeMutation(
            liveConversation(conversationId)
        )
        // 删除消息变体 = DeleteMessage 命令（master 树 delta 落库 + FTS 增量 + 引用投影）
        val session = getOrCreateSession(conversationId)
        val before = session.snapshot.value
        session.submit(DeleteMessage(messageId))
        if (session.snapshot.value === before) {
            if (failIfMissing) {
                throw NoSuchElementException("Message not found")
            }
            return
        }
        // Child retention：master 树收缩后按 lineage 收口子助手（App 层编排，子助手域职责）
        applyChildRetentionAfterTreeMutation(conversationId)
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        message: UIMessage,
    ) {
        deleteMessage(conversationId, message.id, failIfMissing = false)
    }

    /**
     * master 树 mutation（删除/截断）后的 Child retention 收口（子助手域职责，
     * 编排在 DelegationCoordinator）。
     */
    private suspend fun applyChildRetentionAfterTreeMutation(conversationId: Uuid) {
        turnRecovery.applyChildRetentionAfterTreeMutation(conversationId)
    }

    private suspend fun List<UIMessagePart>.copyWithForkedFilesRecursively(): List<UIMessagePart> =
        AttachmentCloner.cloneParts(this, filesManager, toolArtifactRewriter)



    // 停止当前会话生成任务（不清理会话缓存）
    suspend fun stopGeneration(conversationId: Uuid) {
        recoveryGate.awaitReady()
        turnRecoveryReady.await()
        val session = sessionRegistry.getSession(conversationId) ?: return
        val job = session.getJob() ?: return
        // job 已自然结束时无需停止；提前返回避免 requestCancel 写入后无人消费，
        // 在「job 完成与 invokeOnCompletion 清理」的竞态窗口里残留取消原因。
        if (job.isCompleted) return
        val turnId = session.currentTurnId() ?: return
        session.requestCancel(turnId, TurnTerminalReasons.USER_STOP)
        job.cancel()
        runCatching { job.join() }
    }
}

