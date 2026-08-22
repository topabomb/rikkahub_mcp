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
import net.weero.measix.pilot.data.ai.GenerationCheckpoint
import net.weero.measix.pilot.data.ai.ToolExecutionEventStatus
import net.weero.measix.pilot.data.ai.replaceToolsAtOrdinals
import net.weero.measix.pilot.data.db.entity.ToolExecutionEntity
import net.weero.measix.pilot.data.db.entity.ToolExecutionStatus
import net.weero.measix.pilot.data.db.entity.TurnExecutionEntity
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
import net.weero.measix.pilot.data.ai.transformers.Base64ImageToLocalFileTransformer
import net.weero.measix.pilot.data.ai.transformers.DocumentAsPromptTransformer
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.data.ai.transformers.AttachmentProjectionTransformer
import net.weero.measix.pilot.data.ai.transformers.PlaceholderTransformer
import net.weero.measix.pilot.data.ai.transformers.PromptInjectionTransformer
import net.weero.measix.pilot.data.ai.transformers.RegexOutputTransformer
import net.weero.measix.pilot.data.ai.transformers.TemplateTransformer
import net.weero.measix.pilot.data.ai.transformers.ThinkTagTransformer
import net.weero.measix.pilot.data.ai.transformers.TimeReminderTransformer
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

/** 只更新当前分支最后一条 Assistant message 中 locator 指向的待审批 ToolCall。 */
internal fun updateCurrentToolApproval(
    conversation: Conversation,
    locator: ToolCallLocator,
    approvalState: ToolApprovalState,
): Conversation? {
    val currentMessage = conversation.currentMessages.lastOrNull() ?: return null
    if (currentMessage.id != locator.messageId) return null
    var ordinal = 0
    var matched = false
    val updatedParts = currentMessage.parts.map { part ->
        if (part !is UIMessagePart.Tool) return@map part
        val currentOrdinal = ordinal++
        if (currentOrdinal != locator.toolOrdinal) return@map part
        if (part.isExecuted || part.approvalState !is ToolApprovalState.Pending) return null
        matched = true
        part.copy(approvalState = approvalState)
    }
    if (!matched) return null
    val updatedMessage = currentMessage.copy(parts = updatedParts)
    return conversation.copy(
        messageNodes = conversation.messageNodes.map { node ->
            if (node.currentMessage.id == currentMessage.id) {
                node.copy(
                    messages = node.messages.mapIndexed { index, message ->
                        if (index == node.selectIndex) updatedMessage else message
                    }
                )
            } else {
                node
            }
        }
    )
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

private val inputTransformers by lazy {
    listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
    )
}

private val outputTransformers by lazy {
    listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
    )
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
    private val subAssistantCoordinator: SubAssistantCoordinator,
    private val sessionRegistry: ConversationSessionRegistry,
    private val recoveryGate: AssistantDataRecoveryGate = AssistantDataRecoveryGate.completed(),
    private val json: Json,
    private val toolArtifactRewriter: ToolArtifactRewriter? = null,
) {
    // workspace 系统提示注入 (依赖 workspaceRepository, 故在类内构造)
    private val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)
    private val toolArtifactReplayTransformer = toolArtifactRewriter?.let(::ToolArtifactReplayTransformer)

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
                conversationRepo.finalizeTurn(
                    conversation = conversation,
                    turnExecution = execution.copy(
                        status = TurnExecutionStatus.INTERRUPTED,
                        reason = TurnTerminalReasons.PROCESS_RESTARTED,
                        updatedAt = now,
                    ),
                    toolExecutions = startedTools.map { tool ->
                        tool.copy(
                            status = ToolExecutionStatus.UNKNOWN,
                            reason = TurnTerminalReasons.PROCESS_RESTARTED,
                            updatedAt = now,
                        )
                    },
                )
            }
        }
        // Even if an execution cannot be projected back into a message (for example an
        // imported/corrupted snapshot has lost the assistant id), it must not remain RUNNING
        // forever. The transactional sweep only touches records that were not finalized above.
        conversationRepo.recoverInterruptedExecutions(updatedAt = System.currentTimeMillis())
    }

    // ---- Session 管理 ----

    private fun getOrCreateSession(conversationId: Uuid): ConversationSession {
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
                mergeSessionConversation(conversationId) { current ->
                    current.copy(
                        title = current.title.ifBlank { conversation.title },
                        isPinned = conversation.isPinned,
                        folderId = conversation.folderId,
                    )
                }
            } else {
                updateConversation(conversationId, conversation, markDirty = false)
            }
            settingsStore.updateAssistant(conversation.assistantId)
        } else {
            // 新建对话, 并添加预设消息
            val currentSettings = settingsStore.settingsFlowRaw.first()
            val assistant = currentSettings.getCurrentAssistant()
            val newConversation = Conversation.ofId(
                id = conversationId,
                assistantId = assistant.id,
                newConversation = true
            ).updateCurrentMessages(assistant.presetMessages)
            updateConversation(conversationId, newConversation, markDirty = false)
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
                finishInterruptedPendingTools(conversationId)

                val currentConversation = session.state.value
                val settings = settingsStore.settingsFlow.first()
                val assistant = settings.getAssistantById(currentConversation.assistantId)
                    ?: settings.getCurrentAssistant()
                val processedContent = preprocessUserInputParts(content, assistant)

                // 添加消息到列表
                val newConversation = currentConversation.copy(
                    messageNodes = currentConversation.messageNodes + UIMessage(
                        role = MessageRole.USER,
                        parts = processedContent,
                    ).toMessageNode(),
                )
                saveConversation(conversationId, newConversation)

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
                finishInterruptedPendingTools(conversationId)
                val conversation = subAssistantCoordinator.recoverMasterForMutation(session.state.value)

                if (message.role == MessageRole.USER) {
                    // 如果是用户消息，则截止到当前消息
                    val node = conversation.getMessageNodeByMessage(message)
                    val indexAt = conversation.messageNodes.indexOf(node)
                    val newConversation = conversation.copy(
                        messageNodes = conversation.messageNodes.subList(0, indexAt + 1)
                    )
                    saveMasterTreeMutation(conversationId, newConversation)
                    handleMessageComplete(conversationId, turnId = turnId)
                } else {
                    if (regenerateAssistantMsg) {
                        val node = conversation.getMessageNodeByMessage(message)
                        val nodeIndex = conversation.messageNodes.indexOf(node)
                        handleMessageComplete(conversationId, turnId = turnId, messageRange = 0..<nodeIndex)
                    } else {
                        saveConversation(conversationId, conversation)
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

                    val newApprovalState = when {
                        answer != null -> ToolApprovalState.Answered(answer)
                        approved -> ToolApprovalState.Approved
                        else -> ToolApprovalState.Denied(reason)
                    }
                    val updatedConversation = updateCurrentToolApproval(
                        conversation = session.state.value,
                        locator = locator,
                        approvalState = newApprovalState,
                    ) ?: return@withToolApprovalLock
                    saveConversation(conversationId, updatedConversation)

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
        return subAssistantCoordinator.answerUserInteraction(runId, interactionId, answer)
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
            val settings = settingsStore.settingsFlow.first()
            val initialConversation = getConversationFlow(conversationId).value
            val assistant = settings.getAssistantById(initialConversation.assistantId)
                ?: settings.getCurrentAssistant()
            generationSoundEnabled = settings.displaySetting.enableMessageGenerationSoundEffect

            // reset suggestions
            updateConversationState(conversationId) { it.copy(chatSuggestions = emptyList()) }

            checkInvalidMessages(conversationId)
            val loadedConversation = getConversationFlow(conversationId).value
            val backfilled = AttachmentRefs.backfillConversation(loadedConversation)
            if (backfilled != loadedConversation) {
                updateConversation(conversationId, backfilled)
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
            if (resumableAssistant == null) {
                conversation = conversation.updateCurrentMessages(generationMessages)
                updateConversation(conversationId, conversation)
            }
            persistTurnCheckpoint(
                conversationId = conversationId,
                turnId = turnId,
                assistantMessageId = assistantSlot.id,
                status = TurnExecutionStatus.RUNNING,
            )

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
            val session = getOrCreateSession(conversationId)
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
                inputTransformers = buildList {
                    addAll(inputTransformers)
                    add(templateTransformer)
                    add(workspaceReminderTransformer)
                    toolArtifactReplayTransformer?.let(::add)
                    add(AttachmentProjectionTransformer)
                },
                outputTransformers = outputTransformers,
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
                onCheckpoint = { checkpoint ->
                    val latest = getConversationFlow(conversationId).value
                        .updateCurrentMessages(checkpoint.messages)
                    updateConversation(conversationId, latest)
                    persistTurnCheckpoint(
                        conversationId = conversationId,
                        turnId = turnId,
                        assistantMessageId = assistantSlot.id,
                        status = TurnExecutionStatus.RUNNING,
                        checkpoint = checkpoint,
                    )
                },
            ).collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        val updatedConversation = getConversationFlow(conversationId).value
                            .updateCurrentMessages(chunk.messages)
                        updateConversation(conversationId, updatedConversation)
                        chunk.messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.id
                            ?.let { inFlightAssistantId = it }

                        // 通知等边缘副作用由 ChatNotificationManager 消费；
                        // tryEmit 不挂起，事件丢失只影响单次通知更新，不能反压生成链
                        chunk.messages.lastOrNull()?.let { lastMessage ->
                            appEventBus.tryEmit(
                                AppEvent.ChatGenerationUpdate(conversationId, lastMessage, senderName.orEmpty())
                            )
                        }

                        // 前台声音反馈: 单步生成完成 + 工具待审批
                        if (isForeground.value && settings.displaySetting.enableMessageGenerationSoundEffect) {
                            val lastMsg = chunk.messages.lastOrNull()
                            if (lastMsg != null && lastMsg.finishedAt != null && lastMsg.finishedAt != previousFinishedAt) {
                                soundEffectPlayer.play(R.raw.loop_step)
                            }
                            previousFinishedAt = lastMsg?.finishedAt

                            val attentionKeys = collectUserAttentionKeys(chunk.messages, json)
                            if (attentionKeys.any { previousAttentionKeys.add(it) }) {
                                soundEffectPlayer.play(R.raw.loop_approval)
                            }
                        }
                    }

                    is GenerationChunk.Phase -> { }
                    is GenerationChunk.Checkpoint -> { }
                    is GenerationChunk.Finished -> {
                        finishedReason = chunk.reason
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

    private fun checkInvalidMessages(conversationId: Uuid) {
        val conversation = getConversationFlow(conversationId).value
        val messagesNodes = retainValidMessageNodes(conversation.messageNodes)
        if (messagesNodes == conversation.messageNodes) return
        updateConversation(conversationId, conversation.copy(messageNodes = messagesNodes))
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
                        val latestConversation = getConversationFlow(conversationId).value
                        var conversation = latestConversation.copy(
                            messageNodes = latestConversation.messageNodes.map { node ->
                                node.copy(messages = node.messages.map { it.finishReasoning() })
                            },
                            updateAt = Instant.now(),
                        )
                        if (outcome == MasterTurnOutcome.CANCELLED || outcome == MasterTurnOutcome.FAILED) {
                            conversation = closeOpenTools(
                                conversation = conversation,
                                messageId = inFlightAssistantId,
                                cancelledByUser = outcome == MasterTurnOutcome.CANCELLED,
                            )
                        }
                        val terminal = when (outcome) {
                            MasterTurnOutcome.SUCCESS,
                            MasterTurnOutcome.AWAITING_APPROVAL,
                            -> null
                            MasterTurnOutcome.CANCELLED -> MessageTerminalStatus.CANCELLED
                            MasterTurnOutcome.FAILED -> MessageTerminalStatus.FAILED
                            MasterTurnOutcome.INCOMPLETE -> MessageTerminalStatus.INCOMPLETE
                        }
                        if (terminal != null && inFlightAssistantId != null) {
                            conversation = conversation.markAssistantTerminal(
                                messageId = inFlightAssistantId,
                                status = terminal,
                                reason = reason,
                            )
                        }
                        conversation = materializeMediaForPersistence(conversation)
                        updateConversation(conversationId, conversation)

                        val session = getOrCreateSession(conversationId)
                        session.withPersistLock {
                            val revision = session.currentRevision()
                            val now = System.currentTimeMillis()
                            val previous = conversationRepo.getTurnExecution(turnId.toString())
                            val turn = TurnExecutionEntity(
                                turnId = turnId.toString(),
                                conversationId = conversationId.toString(),
                                assistantMessageId = inFlightAssistantId?.toString(),
                                status = when (outcome) {
                                    MasterTurnOutcome.SUCCESS -> TurnExecutionStatus.COMPLETED
                                    MasterTurnOutcome.AWAITING_APPROVAL -> TurnExecutionStatus.AWAITING_APPROVAL
                                    MasterTurnOutcome.CANCELLED -> TurnExecutionStatus.CANCELLED
                                    MasterTurnOutcome.FAILED -> TurnExecutionStatus.FAILED
                                    MasterTurnOutcome.INCOMPLETE -> TurnExecutionStatus.INCOMPLETE
                                },
                                reason = reason,
                                createdAt = previous?.createdAt ?: now,
                                updatedAt = now,
                            )
                            val terminalTools = conversationRepo.getToolExecutions(turnId.toString()).map { tool ->
                                if (tool.status == ToolExecutionStatus.STARTED) {
                                    tool.copy(
                                        status = if (outcome == MasterTurnOutcome.CANCELLED) {
                                            ToolExecutionStatus.CANCELLED
                                        } else {
                                            ToolExecutionStatus.UNKNOWN
                                        },
                                        reason = reason,
                                        updatedAt = now,
                                    )
                                } else {
                                    tool
                                }
                            }
                            conversationRepo.finalizeTurn(conversation, turn, terminalTools)
                            session.markTurnFinalized(turnId)
                            session.markPersisted(revision)
                        }
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

    private suspend fun materializeMediaForPersistence(conversation: Conversation): Conversation {
        if (conversation.messageNodes.none { node -> node.messages.any { it.hasBase64Part() } }) {
            return conversation
        }
        return conversation.copy(
            messageNodes = conversation.messageNodes.map { node ->
                node.copy(
                    messages = node.messages.map { message ->
                        if (!message.hasBase64Part()) {
                            message
                        } else {
                            filesManager.convertBase64ImagePartToLocalFile(message)
                        }
                    },
                )
            },
        )
    }

    private suspend fun persistLoadedConversation(
        conversationId: Uuid,
        reindexFts: Boolean,
    ) {
        recoveryGate.awaitReady()
        val session = getOrCreateSession(conversationId)
        session.withPersistLock {
            var revision = session.currentRevision()
            var snapshot = session.state.value
            val persistable = snapshot.sanitizeForPersistence()
            if (persistable != snapshot) {
                snapshot = persistable
                session.state.value = persistable
                revision = session.bumpStateRevision()
            }
            if (revision < session.lastPersistedRevision()) return@withPersistLock
            val exists = conversationRepo.existsConversationById(snapshot.id)
            if (!exists && snapshot.title.isBlank() && snapshot.messageNodes.isEmpty()) {
                session.markPersisted(revision)
                return@withPersistLock
            }
            if (!exists) {
                conversationRepo.insertConversation(snapshot)
            } else if (reindexFts) {
                conversationRepo.updateConversation(snapshot)
            } else {
                conversationRepo.checkpointConversation(snapshot)
            }
            session.markPersisted(revision)
        }
    }

    private suspend fun persistTurnCheckpoint(
        conversationId: Uuid,
        turnId: Uuid,
        assistantMessageId: Uuid,
        status: TurnExecutionStatus,
        reason: String? = null,
        checkpoint: GenerationCheckpoint? = null,
    ) {
        recoveryGate.awaitReady()
        val session = getOrCreateSession(conversationId)
        session.withPersistLock {
            var revision = session.currentRevision()
            var snapshot = session.state.value
            val persistable = snapshot.sanitizeForPersistence()
            if (persistable != snapshot) {
                snapshot = persistable
                session.state.value = persistable
                revision = session.bumpStateRevision()
            }
            val now = System.currentTimeMillis()
            val existingTurn = conversationRepo.getTurnExecution(turnId.toString())
            val turn = TurnExecutionEntity(
                turnId = turnId.toString(),
                conversationId = conversationId.toString(),
                assistantMessageId = assistantMessageId.toString(),
                status = status,
                reason = reason,
                createdAt = existingTurn?.createdAt ?: now,
                updatedAt = now,
            )
            val event = checkpoint?.toolExecution
            val toolExecution = event?.let {
                val existingTool = conversationRepo.getToolExecution(it.executionId)
                ToolExecutionEntity(
                    executionId = it.executionId,
                    turnId = turnId.toString(),
                    toolOrdinal = it.toolOrdinal,
                    status = when (it.status) {
                        ToolExecutionEventStatus.STARTED -> ToolExecutionStatus.STARTED
                        ToolExecutionEventStatus.COMPLETED -> ToolExecutionStatus.COMPLETED
                        ToolExecutionEventStatus.FAILED -> ToolExecutionStatus.FAILED
                    },
                    reason = null,
                    createdAt = existingTool?.createdAt ?: now,
                    updatedAt = now,
                )
            }
            conversationRepo.checkpointTurn(snapshot, turn, toolExecution)
            session.markPersisted(revision)
        }
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
     * 标记执行中断的工具（超时、异常等导致 output 为空但 approvalState 非 Pending）。
     * 与 [cancelToolByUser] 区分: 这不是用户主动取消，而是执行过程中被中断。
     * 保留原 approvalState（Auto/Approved），不标记为 Denied。
     */
    private suspend fun finishInterruptedPendingTools(conversationId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val assistantId = currentConversation.currentMessages.lastOrNull()
            ?.takeIf { it.role == MessageRole.ASSISTANT }
            ?.id
            ?: return
        val updatedConversation = closeOpenTools(currentConversation, messageId = assistantId)
        if (updatedConversation == currentConversation) {
            return
        }
        saveConversation(conversationId, updatedConversation)
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
            val model = settings.findModelById(settings.titleModelId, fallback = settings.fastModelId) ?: return
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
                mergeSessionConversation(conversationId) { it.copy(title = titleToWrite) }
                conversationRepo.updateConversationTitle(conversationId, titleToWrite)
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
            val model = settings.findModelById(settings.suggestionModelId, fallback = settings.fastModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return

            mergeSessionConversation(conversationId) { it.copy(chatSuggestions = emptyList()) }

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

            mergeSessionConversation(conversationId) { it.copy(chatSuggestions = suggestions) }
            if (conversationRepo.existsConversationById(conversationId)) {
                conversationRepo.updateConversationSuggestions(conversationId, suggestions)
            }
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
        val newConversation = conversation.copy(
            messageNodes = newMessageNodes,
            chatSuggestions = emptyList(),
        )
        val previousFiles = conversation.files.toSet()

        saveConversation(conversationId, newConversation)
        conversationRepo.deleteUnreferencedChatFiles(
            previousFiles = previousFiles,
            retainedConversations = listOf(newConversation),
            excludedConversationIds = setOf(conversationId),
        )
    }

    // ---- 对话状态更新 ----

    private fun updateConversation(
        conversationId: Uuid,
        conversation: Conversation,
        markDirty: Boolean = true,
    ) {
        if (conversation.id != conversationId) return
        val session = getOrCreateSession(conversationId)
        session.state.value = conversation
        if (markDirty) {
            session.bumpStateRevision()
        }
    }

    fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        val session = getOrCreateSession(conversationId)
        session.state.update { current ->
            val next = update(current)
            if (next.id != conversationId) current else next
        }
    }

    private fun mergeSessionConversation(
        conversationId: Uuid,
        update: (Conversation) -> Conversation,
    ) {
        val session = sessionRegistry.getSession(conversationId) ?: return
        session.state.update { current ->
            val next = update(current)
            if (next.id != conversationId) current else next
        }
    }

    suspend fun updateConversationTitle(conversationId: Uuid, title: String) {
        recoveryGate.awaitReady()
        mergeSessionConversation(conversationId) { it.copy(title = title) }
        conversationRepo.updateConversationTitle(conversationId, title)
    }

    /**
     * 移动会话到文件夹（folderId 为 null 表示移出到未归类）。
     *
     * 若该会话当前有活跃 session（正在查看或后台生成），先同步内存态再落库：
     * 否则仅改数据库 folder_id，而内存里那份 Conversation 仍是旧 folderId，
     * 后续任意 saveConversation(id, state.value) 会用整对象把 folder_id 覆盖回旧值，导致移动丢失。
     * 先改内存可确保这段窗口内的整对象保存也带上新 folderId。
     */
    suspend fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) {
        recoveryGate.awaitReady()
        if (sessionRegistry.getSession(conversationId) != null) {
            updateConversationState(conversationId) { it.copy(folderId = folderId) }
        }
        conversationRepo.updateConversationFolderId(conversationId, folderId)
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
     * 先把内存中归属该文件夹的活跃 session folderId 置空，再删库：
     * 否则 clearFolder 只改了数据库，而活跃 session 内存态仍指向该文件夹，
     * 后续整对象保存会写回一个已被删除的 folder_id，导致会话在列表中悬空。
     */
    suspend fun deleteFolder(folderId: Uuid) {
        recoveryGate.awaitReady()
        sessionRegistry.getSessionsSnapshot()
            .filter { it.state.value.folderId == folderId }
            .forEach { updateConversationState(it.id) { c -> c.copy(folderId = null) } }
        folderRepository.deleteFolder(folderId)
    }

    suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) {
        recoveryGate.awaitReady()
        updateConversation(conversationId, conversation.copy())
        persistLoadedConversation(conversationId, reindexFts = true)
    }

    /** Serializes user-initiated repository writes behind startup recovery. */
    suspend fun insertConversation(conversation: Conversation) {
        recoveryGate.awaitReady()
        conversationRepo.insertConversation(conversation)
        sessionRegistry.getSession(conversation.id)?.let {
            sessionRegistry.updateConversationState(conversation.id, conversation)
        }
    }

    suspend fun updatePersistedConversation(conversation: Conversation) {
        recoveryGate.awaitReady()
        conversationRepo.updateConversation(conversation)
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
        conversationRepo.togglePinStatus(conversationId)
        val pinned = conversationRepo.getConversationEntityById(conversationId)?.isPinned ?: return
        mergeSessionConversation(conversationId) { it.copy(isPinned = pinned) }
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
        var edited = false

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (!node.messages.any { it.id == messageId }) {
                return@map node
            }
            edited = true

            node.copy(
                messages = node.messages + UIMessage(
                    role = node.currentMessage.role,
                    parts = processedParts,
                ),
                selectIndex = node.messages.size
            )
        }

        if (!edited) return

        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid
    ): Conversation {
        stopGeneration(conversationId)
        val currentConversation = subAssistantCoordinator.recoverMasterForMutation(
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
        updateConversation(forkConversation.id, forkConversation)
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

        if (targetNode.selectIndex == selectIndex) {
            return
        }

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.id == nodeId) {
                node.copy(selectIndex = selectIndex)
            } else {
                node
            }
        }

        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) {
        stopGeneration(conversationId)
        val currentConversation = subAssistantCoordinator.recoverMasterForMutation(
            getConversationFlow(conversationId).value
        )
        val updatedConversation = buildConversationAfterMessageDelete(currentConversation, messageId)

        if (updatedConversation == null) {
            if (failIfMissing) {
                throw NoSuchElementException("Message not found")
            }
            return
        }

        saveMasterTreeMutation(conversationId, updatedConversation)
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        message: UIMessage,
    ) {
        deleteMessage(conversationId, message.id, failIfMissing = false)
    }

    private fun buildConversationAfterMessageDelete(
        conversation: Conversation,
        messageId: Uuid,
    ): Conversation? {
        val targetNodeIndex = conversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            return null
        }

        val updatedNodes = conversation.messageNodes.mapIndexedNotNull { index, node ->
            if (index != targetNodeIndex) {
                return@mapIndexedNotNull node
            }

            val nextMessages = node.messages.filterNot { it.id == messageId }
            if (nextMessages.isEmpty()) {
                return@mapIndexedNotNull null
            }

            val nextSelectIndex = node.selectIndex.coerceAtMost(nextMessages.lastIndex)
            node.copy(
                messages = nextMessages,
                selectIndex = nextSelectIndex,
            )
        }

        return conversation.copy(messageNodes = updatedNodes)
    }

    private suspend fun saveMasterTreeMutation(
        conversationId: Uuid,
        updatedMaster: Conversation,
    ) {
        val children = conversationRepo.getChildConversations(updatedMaster.id).associateBy { it.id }
        val plan = planSubAssistantRetention(updatedMaster, children, json)
        conversationRepo.updateConversationTree(
            master = updatedMaster,
            retainedChildren = plan.retainedChildren,
            deletedChildren = plan.deletedChildren,
        )
        updateConversation(conversationId, updatedMaster)
        val session = getOrCreateSession(conversationId)
        session.markPersisted(session.currentRevision())
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
        fun copyLocalFileIfNeeded(url: String): String {
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
