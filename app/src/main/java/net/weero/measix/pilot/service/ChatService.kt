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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
import net.weero.measix.pilot.data.ai.CheckpointKind
import net.weero.measix.pilot.data.ai.FinishedReason
import net.weero.measix.pilot.data.ai.GenerationChunk
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
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.findModelById
import net.weero.measix.pilot.data.datastore.findProvider
import net.weero.measix.pilot.data.datastore.getAssistantById
import net.weero.measix.pilot.data.datastore.getChatModel
import net.weero.measix.pilot.data.datastore.getCurrentAssistant
import net.weero.measix.pilot.data.datastore.getCurrentChatModel
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
import net.weero.measix.pilot.service.runtime.ConversationMutation
import net.weero.measix.pilot.service.runtime.ConversationCommand
import net.weero.measix.pilot.service.runtime.ConversationRuntime
import net.weero.measix.pilot.service.runtime.ExecutionFacts
import net.weero.measix.pilot.service.runtime.ConversationRuntimeRegistry
import net.weero.measix.pilot.service.runtime.ConversationSnapshot
import net.weero.measix.pilot.service.runtime.TurnPipelineFactory
import net.weero.measix.pilot.service.runtime.TurnEngine
import net.weero.measix.pilot.service.runtime.TurnEvent
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
import net.weero.measix.pilot.service.runtime.collectSubAssistantCallOutputs

private const val TAG = "ChatService"

internal fun backgroundTextGenerationParams(
    model: Model,
    reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
): TextGenerationParams = TextGenerationParams(
    model = model,
    reasoningLevel = reasoningLevel,
    customHeaders = model.customHeaders,
    customBody = model.customBodies,
)

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
 * 主回合被用户停止或被新回合替换后，为尚未返回的 ToolCall 补齐协议结果。
 * assistant_call 还必须同步收口 Running Card，避免持久化后继续显示运行中。
 */
internal fun finishInterruptedToolAfterGenerationStop(
    tool: UIMessagePart.Tool,
    json: Json,
    childMessages: List<UIMessage> = emptyList(),
): UIMessagePart.Tool {
    if (tool.toolName == "assistant_call") {
        val metadata = tool.getSubAssistantCallMetadata(json)
        if (metadata != null && !metadata.state.isTerminal()) {
            val stoppedMetadata = metadata.copy(
                state = SubAssistantCallState.STOPPED,
                phase = null,
                activeToolName = null,
                reason = "user_cancelled",
                userInteraction = null,
            )
            val taskId = metadata.childTaskNodeId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            val outputs = collectSubAssistantCallOutputs(
                messages = childMessages,
                childTaskNodeId = taskId,
                extras = parseAssistantCallExtrasFromInput(tool.input),
            )
            return tool.mergeSubAssistantCallMetadata(json, stoppedMetadata).copy(
                output = listOf(
                    UIMessagePart.Text(
                        buildSubAssistantCallResult(
                            json = json,
                            status = "stopped",
                            assistantName = metadata.targetNameSnapshot,
                            content = "",
                            reason = "user_cancelled",
                            toolCalls = outputs.toolCalls,
                            ttsTexts = outputs.ttsTexts,
                            ttsStats = outputs.ttsStats,
                        )
                    )
                )
            )
        }
    }
    return tool.copy(
        output = listOf(
            UIMessagePart.Text(
                """{"status":"interrupted","error":"Tool execution was interrupted before completion."}"""
            )
        )
    )
}

/**
 * 需要用户立刻处理的注意力键：普通工具 Pending，以及子助手桥接的 ask_user。
 * 用于前台审批音效去重，避免同一交互重复播放。
 */
internal fun collectUserAttentionKeys(
    messages: List<UIMessage>,
    json: Json,
): Set<String> {
    val keys = linkedSetOf<String>()
    messages.forEach { message ->
        message.getTools().forEachIndexed { ordinal, tool ->
            if (tool.isPending) {
                keys += "tool:${message.id}:$ordinal"
            }
            if (tool.toolName == "assistant_call") {
                val metadata = tool.getSubAssistantCallMetadata(json)
                val interaction = metadata?.userInteraction
                if (
                    metadata != null &&
                    !metadata.state.isTerminal() &&
                    interaction?.toolName == "ask_user"
                ) {
                    val interactionId = interaction.interactionId.takeIf { it.isNotBlank() }
                    if (interactionId != null) {
                        keys += "ask:$interactionId"
                    }
                }
            }
        }
    }
    return keys
}

internal fun shouldLaunchCompletionSideEffects(reason: FinishedReason?): Boolean {
    return reason == FinishedReason.COMPLETED
}

internal enum class MasterTurnOutcome {
    SUCCESS,
    AWAITING_APPROVAL,
    CANCELLED,
    FAILED,
    INCOMPLETE,
}

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

    private val autoTitleGeneration = AutoTitleGenerationTracker()
    private val turnRecoveryReady = CompletableDeferred<Unit>()

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
        // 预加载 loop 声音反馈资源
        soundEffectPlayer.preload(
            R.raw.loop_complete,
            R.raw.loop_failed,
            R.raw.loop_step,
            R.raw.loop_approval
        )
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
        val recoverable = conversationRepo.getRecoverableTurnExecutionsByConversation()
        recoverable.forEach { (conversationId, executions) ->
            executions.forEach executionLoop@ { execution ->
                val assistantMessageId = execution.assistantMessageId
                    ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                    ?: return@executionLoop
                var conversation = conversationRepo.getConversationById(conversationId) ?: return@executionLoop
                // Child 会话的 turn 由 DelegationCoordinator（SubAssistantRecovery
                // 载体）全权收口，Master 全库扫描跳过，避免双路径收口
                if (conversation.parentConversationId != null) return@executionLoop
                val located = conversation.locateAssistant(assistantMessageId) ?: return@executionLoop
                val startedTools = conversationRepo.getToolExecutions(execution.turnId)
                    .filter { it.status == ToolExecutionStatus.STARTED }
                if (startedTools.isNotEmpty()) {
                    val (nodeIndex, message) = located
                    val messageTools = message.getTools()
                    val replacements = startedTools.mapNotNull { toolExecution ->
                        val tool = messageTools.getOrNull(toolExecution.toolOrdinal) ?: return@mapNotNull null
                        toolExecution.toolOrdinal to tool.copy(
                            output = listOf(
                                UIMessagePart.Text(
                                    """{"status":"unknown","error":"The app stopped after tool execution started; the side-effect outcome is unknown and will not be retried automatically."}"""
                                )
                            ),
                        )
                    }.toMap()
                    val recoveredMessage = message.replaceToolsAtOrdinals(replacements)
                    conversation = conversation.copy(
                        messageNodes = conversation.messageNodes.mapIndexed { index, node ->
                            if (index == nodeIndex) {
                                node.copy(
                                    messages = node.messages.map { candidate ->
                                        if (candidate.id == assistantMessageId) recoveredMessage else candidate
                                    }
                                )
                            } else {
                                node
                            }
                        }
                    )
                }
                conversation = conversation.markAssistantTerminal(
                    messageId = assistantMessageId,
                    status = MessageTerminalStatus.INTERRUPTED,
                    reason = TurnTerminalReasons.PROCESS_RESTARTED,
                )
                val now = System.currentTimeMillis()
                // D2：恢复收口走 FinalizeTurn 命令（消息分发 + markAssistantTerminal 幂等 +
                // turn INTERRUPTED 事实 delta 落库）；startedTools 的 UNKNOWN 语义由 App 层
                // 构造、命令提交后单独落库
                val session = getOrCreateSession(conversationId)
                session.replaceState(conversation)
                session.submitGeneration(
                    FinalizeTurn(
                        turnId = runCatching { Uuid.parse(execution.turnId) }.getOrNull()
                            ?: return@executionLoop,
                        assistantMessageId = assistantMessageId,
                        messages = conversation.currentMessages,
                        terminalStatus = TurnExecutionStatus.INTERRUPTED,
                        terminalReason = TurnTerminalReasons.PROCESS_RESTARTED,
                        closeInterruptedTools = false,
                    )
                )
                startedTools.forEach { tool ->
                    conversationRepo.upsertToolExecution(
                        tool.copy(
                            status = ToolExecutionStatus.UNKNOWN,
                            reason = TurnTerminalReasons.PROCESS_RESTARTED,
                            updatedAt = now,
                        )
                    )
                }
            }
        }
        // Even if an execution cannot be projected back into a message (for example an
        // imported/corrupted snapshot has lost the assistant id), it must not remain RUNNING
        // forever. The transactional sweep only touches records that were not finalized above.
        conversationRepo.recoverInterruptedExecutions(updatedAt = System.currentTimeMillis())
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

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> {
        return sessionRegistry.getConversationFlow(conversationId)
    }

    /** 唯一内部事实流（UI 主订阅源）。 */
    fun getConversationSnapshot(conversationId: Uuid): StateFlow<ConversationSnapshot> {
        return sessionRegistry.getOrCreateSession(conversationId).snapshot
    }

    /** 所有结构性修改的唯一入口（UI/Application 提交命令）。 */
    suspend fun submitConversationCommand(conversationId: Uuid, command: ConversationCommand): Conversation {
        return sessionRegistry.getOrCreateSession(conversationId).submit(command)
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
            val live = session.state.value
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
                session.replaceState(merged)
            } else {
                // DB 为权威：整树装载（装载路径）
                session.replaceState(conversation)
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
            session.replaceState(newConversation)
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
                finishInterruptedPendingTools(conversationId, previousTurnId)

                val currentConversation = session.state.value
                val settings = settingsStore.settingsFlow.first()
                val assistant = settings.getAssistantById(currentConversation.assistantId)
                    ?: settings.getCurrentAssistant()
                val processedContent = preprocessUserInputParts(content, assistant)

                // D2：append 用户消息走命令协议（AppendUserMessage → reducer → delta 落库；
                // reducer 同时清理 newConversation 运行态标记）
                if (!conversationRepo.existsConversationById(conversationId)) {
                    conversationRepo.insertConversation(session.state.value)
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
                    handleMessageComplete(conversationId, turnId = turnId)
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
                finishInterruptedPendingTools(conversationId, previousTurnId)
                val conversation = delegationCoordinator.recoverMasterForMutation(session.state.value)

                if (message.role == MessageRole.USER) {
                    // 如果是用户消息，则截止到当前消息（TruncateToNodeIndex，master 树 delta 落库）
                    val node = conversation.getMessageNodeByMessage(message)
                    val indexAt = conversation.messageNodes.indexOf(node)
                    session.submit(TruncateToNodeIndex(nodeIndexInclusive = indexAt))
                    applyChildRetentionAfterTreeMutation(conversationId)
                    handleMessageComplete(conversationId, turnId = turnId)
                } else {
                    if (regenerateAssistantMsg) {
                        val node = conversation.getMessageNodeByMessage(message)
                        val nodeIndex = conversation.messageNodes.indexOf(node)
                        handleMessageComplete(conversationId, turnId = turnId, messageRange = 0..<nodeIndex)
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

                    // D2：工具审批走 UpdateToolApproval 命令（reducer 纯变换 + delta 落库）
                    val newApprovalState = when {
                        answer != null -> ToolApprovalState.Answered(answer)
                        approved -> ToolApprovalState.Approved
                        else -> ToolApprovalState.Denied(reason)
                    }
                    val before = session.state.value
                    session.submit(
                        UpdateToolApproval(
                            messageId = locator.messageId,
                            toolOrdinal = locator.toolOrdinal,
                            approvalState = newApprovalState,
                        )
                    )
                    val updatedConversation = session.state.value
                    if (updatedConversation === before) return@withToolApprovalLock

                    val hasPendingTools = updatedConversation.currentMessages.lastOrNull()
                        ?.getTools()?.any { it.isPending } == true
                    if (hasPendingTools) {
                        _generationDoneFlow.emit(conversationId)
                    } else {
                        val turnId = Uuid.random()
                        session.beginTurn(turnId)
                        val resumeJob = appScope.launch {
                            handleMessageComplete(
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

    private suspend fun handleMessageComplete(
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
            val initialConversation = getConversationFlow(conversationId).value
            val assistant = settings.getAssistantById(initialConversation.assistantId)
                ?: settings.getCurrentAssistant()
            generationSoundEnabled = settings.displaySetting.enableMessageGenerationSoundEffect

            // reset suggestions
            session.submit(UpdateHeader(suggestions = emptyList()))

            checkInvalidMessages(conversationId)
            val loadedConversation = getConversationFlow(conversationId).value
            val backfilled = AttachmentRefs.backfillConversation(loadedConversation)
            if (backfilled != loadedConversation) {
                // 附件引用回填为内存投影修正（随首个 checkpoint 落库，无独立持久化需求）
                session.replaceState(backfilled)
            }

            var conversation = getConversationFlow(conversationId).value
            val sourceMessages = if (messageRange != null) {
                conversation.currentMessages.subList(messageRange.start, messageRange.endInclusive + 1)
            } else {
                conversation.currentMessages
            }
            val resumableAssistant = sourceMessages.lastOrNull()
                ?.takeIf { message ->
                    message.role == MessageRole.ASSISTANT &&
                        message.getTools().any { !it.isExecuted && it.approvalState.canResumeToolExecution() }
                }
            val assistantSlot = resumableAssistant ?: UIMessage(
                id = Uuid.random(),
                role = MessageRole.ASSISTANT,
                parts = emptyList(),
            )
            inFlightAssistantId = assistantSlot.id
            val generationMessages = if (resumableAssistant == null) sourceMessages + assistantSlot else sourceMessages
            // D2：turn 开始走命令协议——BeginTurn 打开 assistant 槽（reducer 分发 + delta 落库），
            // 随后空消息 CommitCheckpoint 落 RUNNING turn 事实（崩溃恢复扫描依据）
            val turnEngine = TurnEngine(session, turnId, assistantSlot.id)
            session.submitGeneration(
                BeginTurn(
                    turnId = turnId,
                    assistantMessageId = assistantSlot.id,
                    fromNodeId = null,
                    resume = resumableAssistant != null,
                    onStart = true,
                )
            )
            session.submitGeneration(
                CommitCheckpoint(
                    turnId = turnId,
                    assistantMessageId = assistantSlot.id,
                    messages = emptyList(),
                    turnStatus = TurnExecutionStatus.RUNNING,
                    turnReason = null,
                    toolExecution = null,
                )
            )
            conversation = getConversationFlow(conversationId).value

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

            conversation = getConversationFlow(conversationId).value

            // start generating
            // loop 声音反馈状态跟踪
            var previousFinishedAt: LocalDateTime? = null
            val previousAttentionKeys = mutableSetOf<String>()
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
                // D2：提交协议唯一实现——chunk→applyStreamingDelta（永不落库）、
                // checkpoint→CommitCheckpoint（onCheckpoint 回调内，delta + facts 同事务落库）
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
                                val lastMsg = event.lastMessage
                                if (lastMsg != null && lastMsg.finishedAt != null && lastMsg.finishedAt != previousFinishedAt) {
                                    soundEffectPlayer.play(R.raw.loop_step)
                                }
                                previousFinishedAt = lastMsg?.finishedAt

                                val attentionKeys = collectUserAttentionKeys(listOfNotNull(lastMsg), json)
                                if (attentionKeys.any { previousAttentionKeys.add(it) }) {
                                    soundEffectPlayer.play(R.raw.loop_approval)
                                }
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

            finalizeMasterTurn(
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
                finishedReason = finishedReason,
                inFlightAssistantId = inFlightAssistantId,
                senderName = senderName.orEmpty(),
            )
            if (!shouldLaunchCompletionSideEffects(finishedReason)) {
                return
            }
            val hasPendingApproval = getConversationFlow(conversationId).value
                .currentMessages.lastOrNull()?.getTools()?.any { it.isPending } == true
            if (hasPendingApproval) {
                return
            }

            if (isForeground.value && settings.displaySetting.enableMessageGenerationSoundEffect) {
                soundEffectPlayer.play(R.raw.loop_complete)
            }

            launchWithConversationReference(conversationId) {
                generateTitle(getConversationFlow(conversationId).value)
            }
            launchWithConversationReference(conversationId) {
                generateSuggestion(conversationId, getConversationFlow(conversationId).value)
            }
        } catch (e: CancellationException) {
            finalizeMasterTurn(
                conversationId = conversationId,
                turnId = turnId,
                outcome = MasterTurnOutcome.CANCELLED,
                finishedReason = finishedReason,
                inFlightAssistantId = inFlightAssistantId,
                senderName = senderName.orEmpty(),
            )
            throw e
        } catch (e: Exception) {
            Logging.log(TAG, "handleMessageComplete failed: ${e.message}")
            Logging.log(TAG, e.stackTraceToString().lines().take(6).joinToString("\n"))
            if (isForeground.value && generationSoundEnabled) {
                soundEffectPlayer.play(R.raw.loop_failed)
            }
            finalizeMasterTurn(
                conversationId = conversationId,
                turnId = turnId,
                outcome = if (
                    e is HttpException && e.terminalStatus == ProviderTerminalStatus.INCOMPLETE
                ) {
                    MasterTurnOutcome.INCOMPLETE
                } else {
                    MasterTurnOutcome.FAILED
                },
                finishedReason = finishedReason,
                error = e,
                inFlightAssistantId = inFlightAssistantId,
                senderName = senderName.orEmpty(),
            )
        }
    }

    // ---- 检查无效消息 ----

    private suspend fun checkInvalidMessages(conversationId: Uuid) {
        val conversation = getConversationFlow(conversationId).value
        val messagesNodes = retainValidMessageNodes(conversation.messageNodes)
        if (messagesNodes == conversation.messageNodes) return
        // 无效消息清理 = 树替换（命令通道 delta 落库）
        getOrCreateSession(conversationId).submit(ReplaceMessageTree(messagesNodes))
    }

    private suspend fun finalizeMasterTurn(
        conversationId: Uuid,
        turnId: Uuid,
        outcome: MasterTurnOutcome,
        finishedReason: FinishedReason?,
        error: Throwable? = null,
        inFlightAssistantId: Uuid? = null,
        senderName: String,
    ) {
        withContext(NonCancellable) {
            // 幂等守卫：一个 turn 只允许提交一次终态。SUCCESS 提交后 job 可能仍在收尾
            // （例如 generationDoneFlow 的 emit 等待慢订阅者时被取消），CancellationException
            // 路径会再次进入这里；已提交的终态不允许被覆盖为 CANCELLED。
            if (getOrCreateSession(conversationId).isTurnFinalized(turnId)) {
                Logging.log(TAG, "turn $turnId already finalized; skip duplicate ${outcome.name} finalization")
                return@withContext
            }
            val reason = when (outcome) {
                MasterTurnOutcome.SUCCESS -> null
                MasterTurnOutcome.AWAITING_APPROVAL -> null
                MasterTurnOutcome.CANCELLED -> {
                    getOrCreateSession(conversationId).consumeCancelReason(turnId)
                        ?: TurnTerminalReasons.USER_STOP
                }
                MasterTurnOutcome.FAILED -> if (error is HttpException) {
                    TurnTerminalReasons.PROVIDER_FAILED
                } else {
                    TurnTerminalReasons.RUNTIME_ERROR
                }
                MasterTurnOutcome.INCOMPLETE -> when (finishedReason) {
                    FinishedReason.STEP_LIMIT_REACHED -> TurnTerminalReasons.TOOL_LOOP_LIMIT
                    FinishedReason.INTERACTION_LIMIT_REACHED -> TurnTerminalReasons.INTERACTION_LIMIT
                    else -> TurnTerminalReasons.PROVIDER_INCOMPLETE
                }
            }
            var lastCommitError: Throwable? = null
            for (attempt in 0 until 3) {
                try {
                    withTimeout(30_000L) {
                        val session = getOrCreateSession(conversationId)
                        // ① 终态收口的 IO 前置（子助手消息并入、未闭合工具收口）在 Application 层完成
                        var latest = getConversationFlow(conversationId).value
                        if (outcome == MasterTurnOutcome.CANCELLED || outcome == MasterTurnOutcome.FAILED) {
                            latest = closeOpenTools(
                                conversation = latest,
                                messageId = inFlightAssistantId,
                                cancelledByUser = outcome == MasterTurnOutcome.CANCELLED,
                            )
                        }
                        // ② FinalizeTurn 命令：reducer 纯收口（消息分发 + finishReasoning +
                        // markAssistantTerminal）+ delta 落库 + turn 事实，提交协议唯一实现
                        session.submitGeneration(
                            FinalizeTurn(
                                turnId = turnId,
                                assistantMessageId = inFlightAssistantId ?: Uuid.random(),
                                messages = latest.currentMessages,
                                terminalStatus = when (outcome) {
                                    MasterTurnOutcome.SUCCESS -> TurnExecutionStatus.COMPLETED
                                    MasterTurnOutcome.AWAITING_APPROVAL -> TurnExecutionStatus.AWAITING_APPROVAL
                                    MasterTurnOutcome.CANCELLED -> TurnExecutionStatus.CANCELLED
                                    MasterTurnOutcome.FAILED -> TurnExecutionStatus.FAILED
                                    MasterTurnOutcome.INCOMPLETE -> TurnExecutionStatus.INCOMPLETE
                                },
                                terminalReason = reason,
                                closeInterruptedTools = false, // IO 前置已在 ① 完成
                            )
                        )
                        // ③ dangling tool executions 收口（STARTED → CANCELLED/UNKNOWN）
                        finalizeDanglingToolExecutions(turnId, outcome, reason)
                    }
                    lastCommitError = null
                    break
                } catch (commitError: Throwable) {
                    lastCommitError = commitError
                    Logging.log(TAG, "turn finalization attempt ${attempt + 1} failed: ${commitError.message}")
                    if (attempt < 2) delay(150L * (attempt + 1))
                }
            }
            lastCommitError?.let { commitError ->
                addError(
                    commitError,
                    conversationId,
                    title = context.getString(R.string.error_title_generation),
                )
            }
            if (outcome == MasterTurnOutcome.FAILED && error != null) {
                addError(error, conversationId, title = context.getString(R.string.error_title_generation))
            }
            val finalMessage = getConversationFlow(conversationId).value.currentMessages
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
     * 终态后收口 dangling tool executions：turn 内仍处 STARTED 的工具行，
     * CANCELLED → CANCELLED（用户停止），FAILED/INCOMPLETE → UNKNOWN（无法确认结果）。
     */
    private suspend fun finalizeDanglingToolExecutions(
        turnId: Uuid,
        outcome: MasterTurnOutcome,
        reason: String?,
    ) {
        val now = System.currentTimeMillis()
        val dangling = conversationRepo.getToolExecutions(turnId.toString())
            .filter { it.status == ToolExecutionStatus.STARTED }
        if (dangling.isEmpty()) return
        dangling.forEach { tool ->
            conversationRepo.upsertToolExecution(
                tool.copy(
                    status = if (outcome == MasterTurnOutcome.CANCELLED) {
                        ToolExecutionStatus.CANCELLED
                    } else {
                        ToolExecutionStatus.UNKNOWN
                    },
                    reason = reason,
                    updatedAt = now,
                )
            )
        }
    }

    private suspend fun closeOpenTools(
        conversation: Conversation,
        messageId: Uuid?,
        cancelledByUser: Boolean = true,
    ): Conversation {
        val located = conversation.locateAssistant(messageId) ?: return conversation
        val (nodeIndex, targetMessage) = located
        var updatedMessage = targetMessage.finishPendingTools { tool ->
            if (cancelledByUser) cancelToolByUser(tool) else interruptPendingTool(tool)
        }
        val childMessagesByConversation = loadChildMessagesForInterruptedCalls(updatedMessage)
        updatedMessage = updatedMessage.finishInterruptedTools { tool ->
            val childId = tool.getSubAssistantCallMetadata(json)?.childConversationId
                ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            finishInterruptedToolAfterGenerationStop(
                tool = tool,
                json = json,
                childMessages = childId?.let { childMessagesByConversation[it] }.orEmpty(),
            )
        }
        if (updatedMessage == targetMessage) return conversation
        return conversation.copy(
            messageNodes = conversation.messageNodes.mapIndexed { index, node ->
                if (index != nodeIndex) {
                    node
                } else {
                    node.copy(
                        messages = node.messages.map { message ->
                            if (message.id == targetMessage.id) updatedMessage else message
                        },
                    )
                }
            },
        )
    }

    private fun cancelToolByUser(tool: UIMessagePart.Tool): UIMessagePart.Tool {
        return tool.copy(
            output = listOf(
                UIMessagePart.Text(
                    """{"status":"cancelled","error":"Generation cancelled by user before tool execution completed."}"""
                )
            ),
            approvalState = ToolApprovalState.Denied("Generation cancelled by user")
        )
    }

    private fun interruptPendingTool(tool: UIMessagePart.Tool): UIMessagePart.Tool = tool.copy(
        output = listOf(
            UIMessagePart.Text(
                """{"status":"interrupted","error":"Tool execution was interrupted before completion."}"""
            )
        ),
    )

    /**
     * 上一回合残留的开放工具收口：并入 FinalizeTurn(closeInterruptedTools=true)，
     * 与 Master 崩溃恢复同一命令路径。[previousTurnId] 用于终态事实归属（可空：无活跃 turn 时）。
     */
    private suspend fun finishInterruptedPendingTools(conversationId: Uuid, previousTurnId: Uuid?) {
        val session = getOrCreateSession(conversationId)
        val currentConversation = session.state.value
        val assistantId = currentConversation.currentMessages.lastOrNull()
            ?.takeIf { it.role == MessageRole.ASSISTANT }
            ?.id
            ?: return
        session.submit(
            FinalizeTurn(
                turnId = previousTurnId ?: Uuid.random(),
                assistantMessageId = assistantId,
                messages = null,
                terminalStatus = TurnExecutionStatus.INTERRUPTED,
                terminalReason = null,
                closeInterruptedTools = true,
            )
        )
    }

    private suspend fun loadChildMessagesForInterruptedCalls(
        message: UIMessage,
    ): Map<Uuid, List<UIMessage>> {
        val childIds = message.getTools().mapNotNull { tool ->
            if (tool.toolName != "assistant_call") return@mapNotNull null
            tool.getSubAssistantCallMetadata(json)?.childConversationId
                ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        }.toSet()
        return childIds.associateWith { childId ->
            sessionRegistry.getSession(childId)?.state?.value?.currentMessages
                ?: conversationRepo.getConversationById(childId)?.currentMessages
                ?: emptyList()
        }
    }

    // ---- 生成标题 ----

    suspend fun generateTitle(
        conversation: Conversation,
        force: Boolean = false,
    ) {
        val conversationId = conversation.id
        val decision = autoTitleGeneration.begin(
            conversationId = conversationId,
            force = force,
            titleBlank = conversation.title.isBlank(),
        )
        if (decision != AutoTitleGenerationDecision.Proceed) return

        try {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.titleModelId, fallback = settings.fastModelId)
                ?: settings.getCurrentChatModel()
                ?: return
            val provider = model.findProvider(settings.providers) ?: return

            if (!force) {
                autoTitleGeneration.recordAttempt(conversationId)
            }

            runCatching {
                val providerHandler = providerManager.getProviderByType(provider)
                val result = providerHandler.generateText(
                    providerSetting = provider,
                    messages = listOf(
                        UIMessage.user(
                            prompt = settings.titlePrompt.applyPlaceholders(
                                "locale" to Locale.getDefault().displayName,
                                "content" to conversation.currentMessages
                                    .takeLast(4).joinToString("\n\n") { it.summaryAsText(maxLength = 500) })
                        ),
                    ),
                    params = backgroundTextGenerationParams(model),
                )

                val generatedTitle = result.choices.getOrNull(0)?.message?.toText().orEmpty()
                val latestTitle = sessionRegistry.getSession(conversationId)?.state?.value?.title
                    ?: conversation.title
                val titleToWrite = resolveGeneratedTitleWrite(
                    force = force,
                    latestTitle = latestTitle,
                    generatedTitle = generatedTitle,
                ) ?: return@runCatching
                submitHeaderUpdate(
                    conversationId,
                    fallback = { conversationRepo.updateConversationTitle(conversationId, titleToWrite) },
                    build = { UpdateHeader(title = titleToWrite) },
                )
            }.onFailure {
                it.printStackTrace()
                addError(
                    error = it,
                    conversationId = conversationId,
                    title = context.getString(R.string.error_title_generate_title),
                    solution = ChatErrorSolution.CheckTitleModelSettings,
                )
            }
        } finally {
            val retry = autoTitleGeneration.end(conversationId)
            if (retry != null) {
                launchWithConversationReference(conversationId) {
                    val latest = conversationRepo.getConversationById(conversationId) ?: return@launchWithConversationReference
                    generateTitle(latest, force = retry.force)
                }
            }
        }
    }

    // ---- 生成建议 ----

    suspend fun generateSuggestion(conversationId: Uuid, conversation: Conversation) {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            if (!settings.enableSuggestion) return
            val model = settings.findModelById(settings.suggestionModelId, fallback = settings.fastModelId)
                ?: settings.getCurrentChatModel()
                ?: return
            val provider = model.findProvider(settings.providers) ?: return

            submitHeaderUpdate(
                conversationId,
                fallback = {
                    if (conversationRepo.existsConversationById(conversationId)) {
                        conversationRepo.updateConversationSuggestions(conversationId, emptyList())
                    }
                },
                build = { UpdateHeader(suggestions = emptyList()) },
            )

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        settings.suggestionPrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(8).joinToString("\n\n") { it.summaryAsText(maxLength = 500) }),
                    )
                ),
                params = backgroundTextGenerationParams(model),
            )
            val suggestions =
                result.choices[0].message?.toText()?.split("\n")?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?.take(10)
                    ?: emptyList()

            submitHeaderUpdate(
                conversationId,
                fallback = {
                    if (conversationRepo.existsConversationById(conversationId)) {
                        conversationRepo.updateConversationSuggestions(conversationId, suggestions)
                    }
                },
                build = { UpdateHeader(suggestions = suggestions) },
            )
        }.onFailure {
            it.printStackTrace()
        }
    }

    // ---- 压缩对话历史 ----

    suspend fun compressConversation(
        conversationId: Uuid,
        conversation: Conversation,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int = 32
    ): Result<Unit> = runCatching {
        val settings = settingsStore.settingsFlow.first()
        val model = settings.findModelById(settings.compressModelId)
            ?: settings.getCurrentChatModel()
            ?: throw IllegalStateException("No model available for compression")
        val provider = model.findProvider(settings.providers)
            ?: throw IllegalStateException("Provider not found")

        val providerHandler = providerManager.getProviderByType(provider)

        val maxMessagesPerChunk = 256
        val allMessages = conversation.currentMessages

        // Split messages into those to compress and those to keep
        val messagesToCompress: List<UIMessage>
        val messagesToKeep: List<UIMessage>

        if (keepRecentMessages > 0 && allMessages.size > keepRecentMessages) {
            val keepStartIndex = allMessages.findUserTurnStart(allMessages.size - keepRecentMessages)
            messagesToCompress = allMessages.take(keepStartIndex)
            messagesToKeep = allMessages.drop(keepStartIndex)
            if (messagesToCompress.isEmpty()) {
                throw IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages))
            }
        } else if (keepRecentMessages > 0) {
            // Not enough messages to compress while keeping recent ones
            throw IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages))
        } else {
            messagesToCompress = allMessages
            messagesToKeep = emptyList()
        }

        fun splitMessages(messages: List<UIMessage>): List<List<UIMessage>> {
            if (messages.size <= maxMessagesPerChunk) return listOf(messages)
            val rawMid = messages.size / 2
            val mid = messages.findUserTurnStart(rawMid).takeIf { it > 0 } ?: rawMid
            val left = splitMessages(messages.subList(0, mid))
            val right = splitMessages(messages.subList(mid, messages.size))
            return left + right
        }

        suspend fun compressMessages(messages: List<UIMessage>): String {
            val contentToCompress = messages.joinToString("\n\n") { it.summaryAsText(maxLength = 2000) }
            val prompt = settings.compressPrompt.applyPlaceholders(
                "content" to contentToCompress,
                "target_tokens" to targetTokens.toString(),
                "additional_context" to if (additionalPrompt.isNotBlank()) {
                    "Additional instructions from user: $additionalPrompt"
                } else "",
                "locale" to Locale.getDefault().displayName
            )

            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                params = backgroundTextGenerationParams(model),
            )

            return result.choices[0].message?.toText()?.trim()
                ?: throw IllegalStateException("Failed to generate compressed summary")
        }

        val compressedSummaries = coroutineScope {
            splitMessages(messagesToCompress)
                .map { chunk -> async { compressMessages(chunk) } }
                .awaitAll()
        }

        // Replace older history with summary messages while preserving complete recent turns.
        val newMessageNodes = buildList {
            compressedSummaries.forEach { summary ->
                add(UIMessage.user(summary).toMessageNode())
            }
            addAll(messagesToKeep.map { it.toMessageNode() })
        }

        // 压缩 = ReplaceMessageTree（树替换 delta：旧行删除 + 新行 upsert + FTS 增量
        // + 引用投影替换）+ 清空建议；原 deleteUnreferencedChatFiles 全量扫描路径由 GC 取代
        val session = getOrCreateSession(conversationId)
        session.submit(ReplaceMessageTree(newMessageNodes))
        session.submit(UpdateHeader(suggestions = emptyList()))
        conversationRepo.collectUnreferencedArtifacts()
    }

    // ---- 对话状态更新 ----

    /**
     * @Transient 投影修正入口（isFavorite 等不落库字段；收藏事实由 FavoriteRepository 持有，
     * Runtime 下次装载时回填）。结构性修改必须经 [submitConversationCommand]（命令通道）。
     */
    fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        val session = getOrCreateSession(conversationId)
        session.replaceState(update(session.state.value))
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
            .any { it.isGenerating && it.state.value.folderId == folderId }
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
            .filter { it.state.value.folderId == folderId }
            .forEach { it.submit(UpdateHeader(folderId = OptionalFolderId.Clear)) }
        folderRepository.deleteFolder(folderId)
    }

    /** Serializes user-initiated repository writes behind startup recovery. */
    suspend fun insertConversation(conversation: Conversation) {
        recoveryGate.awaitReady()
        conversationRepo.insertConversation(conversation)
        sessionRegistry.getSession(conversation.id)?.let {
            sessionRegistry.updateConversationState(conversation.id, conversation)
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
            sessionRegistry.updateConversationState(conversation.id, conversation)
        }
    }

    suspend fun deleteConversation(conversation: Conversation) {
        recoveryGate.awaitReady()
        stopGeneration(conversation.id)
        val childIds = conversationRepo.getChildConversations(conversation.id).map { it.id }
        conversationRepo.deleteConversation(conversation)
        childIds.forEach { childId ->
            autoTitleGeneration.clear(childId)
            sessionRegistry.evictSession(childId)
        }
        autoTitleGeneration.clear(conversation.id)
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
                UpdateHeader(isPinned = !session.state.value.isPinned)
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

        val currentConversation = getConversationFlow(conversationId).value
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
        val currentConversation = delegationCoordinator.recoverMasterForMutation(
            getConversationFlow(conversationId).value
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
        getOrCreateSession(forkConversation.id).replaceState(forkConversation)
        return forkConversation
    }

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int
    ) {
        recoveryGate.awaitReady()
        val currentConversation = getConversationFlow(conversationId).value
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
        delegationCoordinator.recoverMasterForMutation(
            getConversationFlow(conversationId).value
        )
        // 删除消息变体 = DeleteMessage 命令（master 树 delta 落库 + FTS 增量 + 引用投影）
        val session = getOrCreateSession(conversationId)
        val before = session.state.value
        session.submit(DeleteMessage(messageId))
        if (session.state.value === before) {
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
     * master 树 mutation（删除/截断）后的 Child retention 收口（子助手域编排保留 App 层，
     * master 树本身已由命令通道 delta 落库）。retained children 走 children 收缩
     * 事务（引用替换 + GC），deleted children 删除 + evict。
     */
    private suspend fun applyChildRetentionAfterTreeMutation(conversationId: Uuid) {
        val master = getConversationFlow(conversationId).value
        val children = conversationRepo.getChildConversations(master.id).associateBy { it.id }
        val plan = planSubAssistantRetention(master, children, json)
        conversationRepo.updateChildRetention(
            retainedChildren = plan.retainedChildren,
            deletedChildren = plan.deletedChildren,
        )
        plan.retainedChildren.forEach { child ->
            sessionRegistry.getSession(child.id)?.let {
                sessionRegistry.updateConversationState(child.id, child)
            }
        }
        plan.deletedChildren.forEach { child -> sessionRegistry.evictSession(child.id) }
    }

    private suspend fun List<UIMessagePart>.copyWithForkedFilesRecursively(): List<UIMessagePart> =
        buildList {
            for (part in this@copyWithForkedFilesRecursively) {
                add(part.copyForkedPart())
            }
        }

    private suspend fun UIMessagePart.copyForkedPart(): UIMessagePart {
        suspend fun copyLocalFileIfNeeded(url: String): String {
            if (!url.startsWith("file:")) return url
            val copied = filesManager.createChatFilesByContents(listOf(url.toUri())).firstOrNull()
            return copied?.toString() ?: url
        }

        return when (this) {
            is UIMessagePart.Image -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Document -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Video -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Audio -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Tool -> {
                val rewriter = toolArtifactRewriter
                val sourceRef = metadata?.let { rewriter?.decodeArtifactRef(it) }
                if (rewriter != null && sourceRef != null) {
                    val (newOutput, newMetadata) = rewriter.rewriteToolOutput(output, metadata)
                    copy(output = newOutput, metadata = newMetadata)
                } else {
                    copy(output = output.copyWithForkedFilesRecursively())
                }
            }
            else -> this
        }
    }



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

private fun Conversation.sanitizeForPersistence(): Conversation {
    if (messageNodes.none { node -> node.messages.any { it.hasBase64Part() } }) return this
    return copy(
        messageNodes = messageNodes.map { node ->
            node.copy(messages = node.messages.map { it.withoutUnpersistableBase64() })
        },
    )
}

private fun Conversation.locateAssistant(messageId: Uuid?): Pair<Int, UIMessage>? {
    if (messageId == null) return null
    messageNodes.forEachIndexed { index, node ->
        val message = node.messages.firstOrNull { it.id == messageId && it.role == MessageRole.ASSISTANT }
        if (message != null) return index to message
    }
    return null
}

private fun Conversation.markAssistantTerminal(
    messageId: Uuid?,
    status: MessageTerminalStatus,
    reason: String?,
): Conversation {
    val located = locateAssistant(messageId) ?: return this
    val (nodeIndex, targetMessage) = located
    if (targetMessage.role != MessageRole.ASSISTANT) return this
    val marked = targetMessage.copy(terminalStatus = status, terminalReason = reason)
    return copy(
        messageNodes = messageNodes.mapIndexed { index, node ->
            if (index != nodeIndex) {
                node
            } else {
                node.copy(
                    messages = node.messages.map { message ->
                        if (message.id == targetMessage.id) marked else message
                    },
                )
            }
        },
    )
}
