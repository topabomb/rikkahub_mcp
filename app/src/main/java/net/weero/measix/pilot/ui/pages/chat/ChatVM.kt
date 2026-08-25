package net.weero.measix.pilot.ui.pages.chat

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.getConversationAssistant
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.Avatar
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.service.ChatError
import net.weero.measix.pilot.service.ChatErrorStore
import net.weero.measix.pilot.service.MasterTurnCoordinator
import net.weero.measix.pilot.service.ConversationApplicationService
import net.weero.measix.pilot.service.ConversationQueryService
import net.weero.measix.pilot.service.ConversationReadState
import net.weero.measix.pilot.service.ConversationSummary
import net.weero.measix.pilot.service.ConversationViewLease
import net.weero.measix.pilot.data.ai.mcp.McpManager
import net.weero.measix.pilot.service.ArtifactUseCase
import net.weero.measix.pilot.service.ArtifactDraftScope
import net.weero.measix.pilot.service.FavoriteService
import net.weero.measix.pilot.service.runtime.ConversationSnapshot
import net.weero.measix.pilot.service.runtime.ConversationTurnPresentation
import net.weero.measix.pilot.ui.components.ai.SearchMode
import net.weero.measix.pilot.ui.components.ai.searchModeEnablesBuiltIn
import net.weero.measix.pilot.ui.components.ai.searchModeEnablesLocal
import net.weero.measix.pilot.ui.hooks.writeStringPreference
import net.weero.measix.pilot.ui.hooks.ChatInputState
import net.weero.measix.pilot.utils.UpdateChecker
import kotlin.uuid.Uuid
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class ChatVM(
    id: String,
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val masterTurnCoordinator: MasterTurnCoordinator,
    private val conversationApplicationService: ConversationApplicationService,
    private val conversationQueryService: ConversationQueryService,
    val updateChecker: UpdateChecker,
    private val artifactUseCase: ArtifactUseCase,
    private val favoriteService: FavoriteService,
    private val chatErrorStore: ChatErrorStore,
    val mcpManager: McpManager,
) : ViewModel() {
    private val _conversationId: Uuid = Uuid.parse(id)
    private val cleared = AtomicBoolean(false)
    private val viewLease = AtomicReference<ConversationViewLease?>(null)
    private val initializationOwner = Any()
    private var initializationJob: Job? = null

    val conversationState: StateFlow<ConversationReadState> = conversationQueryService
        .observeConversation(_conversationId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, ConversationReadState.Loading)

    // 唯一内部事实流（nodes + activeTurn + header）；仅 Ready 状态产生 snapshot 投影。
    val snapshot: StateFlow<ConversationSnapshot?> = conversationState
        .map { state -> (state as? ConversationReadState.Ready)?.snapshot }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val favoriteNodeIds: StateFlow<Set<Uuid>> = favoriteService
        .observeNodeIds(_conversationId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun currentSnapshot(): ConversationSnapshot = requireNotNull(snapshot.value)

    fun attachmentPreviews(snapshot: ConversationSnapshot): Map<String, String> =
        conversationQueryService.attachmentPreviews(snapshot)
    var chatListInitialized by mutableStateOf(false) // 聊天列表是否已经滚动到底部

    // 聊天输入状态 - 保存在 ViewModel 中避免 TransactionTooLargeException
    val inputState = ChatInputState()
    val artifactDraftScope: ArtifactDraftScope = artifactUseCase.openDraftScope()

    // UI consumes the runtime's typed turn projection; coroutine ownership remains inside Runtime.
    val turnPresentation: StateFlow<ConversationTurnPresentation> =
        conversationQueryService
            .turnPresentation(_conversationId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, ConversationTurnPresentation.IDLE)

    val processingStatus: StateFlow<String?> =
        conversationQueryService
            .processingStatus(_conversationId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        acquireViewLease()

        // 记住对话ID, 方便下次启动恢复
        context.writeStringPreference("lastConversationId", _conversationId.toString())
    }

    fun retryConversationLoad() {
        if (viewLease.get() == null) acquireViewLease()
    }

    private fun acquireViewLease(): Job = synchronized(initializationOwner) {
        initializationJob?.takeIf(Job::isActive) ?: viewModelScope.launch {
            try {
                val acquired = conversationApplicationService.initialize(_conversationId)
                if (!viewLease.compareAndSet(null, acquired)) acquired.close()
                if (cleared.get()) viewLease.getAndSet(null)?.close()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // ConversationReadState.Failed is the user-visible authority; this log preserves diagnostics.
                Log.e("ChatVM", "Failed to initialize conversation $_conversationId", error)
            }
        }.also { initializationJob = it }
    }

    override fun onCleared() {
        cleared.set(true)
        viewLease.getAndSet(null)?.close()
        artifactDraftScope.close()
    }

    // 用户设置
    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    // 网络搜索(每个助手独立)
    val enableWebSearch = combine(settings, snapshot) { currentSettings, currentSnapshot ->
        currentSnapshot?.let { snapshot ->
            currentSettings.getConversationAssistant(snapshot.header.assistantId).enableWebSearch
        } ?: false
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // 错误状态
    val errors: StateFlow<List<ChatError>> = chatErrorStore.errors

    fun dismissError(id: Uuid) = chatErrorStore.dismiss(id)

    fun clearAllErrors() = chatErrorStore.clear()

    // 生成完成
    val generationDoneFlow: SharedFlow<Uuid> = masterTurnCoordinator.generationDoneFlow

    fun getTtsQueueSessionId(conversationId: Uuid): String? =
        conversationQueryService.ttsQueueSessionId(conversationId)

    // 更新设置
    fun updateSettings(transform: (Settings) -> Settings): Job {
        return viewModelScope.launch {
            var previousAvatar: Avatar? = null
            val committed = artifactUseCase.updateSettingsReferences { current ->
                val updated = transform(current)
                previousAvatar = current.displaySetting.userAvatar
                updated
            }
            previousAvatar?.let { oldAvatar ->
                if (oldAvatar != committed.displaySetting.userAvatar) artifactUseCase.maintainStorage()
            }
        }
    }

    suspend fun importUserAvatar(uri: Uri) {
        var previousAvatar: Avatar? = null
        val committedUri = artifactUseCase.importSettingsImage(uri) { current, localUri ->
            previousAvatar = current.displaySetting.userAvatar
            current.copy(
                displaySetting = current.displaySetting.copy(userAvatar = Avatar.Image(localUri.toString())),
            )
        }
        if (previousAvatar != Avatar.Image(committedUri.toString())) artifactUseCase.maintainStorage()
    }

    fun updateSearchMode(assistantId: Uuid, model: Model?, mode: SearchMode) {
        viewModelScope.launch {
            val enableWebSearch = searchModeEnablesLocal(mode)
            val enableBuiltIn = searchModeEnablesBuiltIn(mode)
            settingsStore.update { settings ->
                applySearchMode(
                    settings = settings,
                    assistantId = assistantId,
                    modelId = model?.id,
                    enableWebSearch = enableWebSearch,
                    enableBuiltIn = enableBuiltIn,
                )
            }
        }
    }

    // 设置聊天模型
    fun setChatModel(assistant: Assistant, model: Model) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    assistants = settings.assistants.map {
                        if (it.id == assistant.id) {
                            it.copy(
                                chatModelId = model.id
                            )
                        } else {
                            it
                        }
                    })
            }
        }
    }

    // Update checker — 共享 UpdateChecker 的缓存 StateFlow，App 生命周期内只请求一次
    val updateState = updateChecker.updateState

    /**
     * 处理消息发送
     *
     * @param content 消息内容
     * @param answer 是否触发消息生成，如果为false，则仅添加消息到消息列表中
     */
    fun handleMessageSend(content: List<UIMessagePart>,answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return

        masterTurnCoordinator.sendMessage(_conversationId, content, answer, artifactDraftScope)
    }

    fun handleMessageEdit(parts: List<UIMessagePart>, messageId: Uuid) {
        if (parts.isEmptyInputMessage()) return

        viewModelScope.launch {
            conversationApplicationService.editMessage(_conversationId, messageId, parts, artifactDraftScope)
        }
    }

    fun handleCompressContext(additionalPrompt: String, targetTokens: Int, keepRecentMessages: Int): Job {
        return viewModelScope.launch {
            conversationApplicationService.compress(
                currentSnapshot(),
                additionalPrompt,
                targetTokens,
                keepRecentMessages
            ).onFailure {
                chatErrorStore.add(it, title = context.getString(R.string.error_title_compress_conversation))
            }
        }
    }

    suspend fun forkMessage(message: UIMessage): Uuid {
        return conversationApplicationService.forkAtMessage(_conversationId, message.id)
    }

    fun deleteMessage(message: UIMessage) {
        viewModelScope.launch {
            conversationApplicationService.deleteMessage(_conversationId, message)
        }
    }

    fun showDeleteBlockedWhileGeneratingError() {
        chatErrorStore.add(
            error = IllegalStateException(context.getString(R.string.chat_page_delete_message_generating)),
            conversationId = _conversationId,
            title = context.getString(R.string.error_title_operation)
        )
    }

    fun regenerateAtMessage(
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        masterTurnCoordinator.regenerateAtMessage(_conversationId, message, regenerateAssistantMsg)
    }

    fun handleToolApproval(
        locator: ToolCallLocator,
        approved: Boolean,
        reason: String = ""
    ) {
        masterTurnCoordinator.handleToolApproval(_conversationId, locator, approved, reason)
    }

    fun handleToolAnswer(
        locator: ToolCallLocator,
        answer: String,
    ) {
        masterTurnCoordinator.handleToolApproval(_conversationId, locator, approved = true, answer = answer)
    }

    fun handleSubAssistantAnswer(runId: String, interactionId: String, answer: String): Boolean =
        masterTurnCoordinator.handleSubAssistantAnswer(runId, interactionId, answer)

    fun stopGeneration() {
        viewModelScope.launch {
            conversationApplicationService.stopGeneration(_conversationId)
        }
    }

    fun updateTitle(title: String) {
        viewModelScope.launch {
            conversationApplicationService.updateTitle(_conversationId, title)
        }
    }

    suspend fun deleteConversation(conversation: ConversationSummary) {
        conversationApplicationService.delete(conversation.id)
    }

    fun updatePinnedStatus(conversation: ConversationSummary) {
        viewModelScope.launch {
            conversationApplicationService.togglePin(conversation.id)
        }
    }

    fun moveConversationToAssistant(targetAssistantId: Uuid) {
        moveConversationToAssistant(_conversationId, targetAssistantId)
    }

    fun moveConversationToAssistant(conversationId: Uuid, targetAssistantId: Uuid) {
        viewModelScope.launch {
            conversationApplicationService.moveToAssistant(conversationId, targetAssistantId)
            if (conversationId == _conversationId) {
                settingsStore.updateAssistant(targetAssistantId)
            }
        }
    }

    fun generateTitle(conversation: ConversationSummary, force: Boolean = false) {
        viewModelScope.launch {
            conversationApplicationService.generateTitle(conversation.id, force)
        }
    }

    fun selectNode(nodeId: Uuid, selectIndex: Int) {
        viewModelScope.launch {
            conversationApplicationService.selectNode(_conversationId, nodeId, selectIndex)
        }
    }

    fun updateCustomSystemPrompt(prompt: String?) {
        viewModelScope.launch {
            conversationApplicationService.updateCustomSystemPrompt(_conversationId, prompt)
        }
    }

    fun updateModeInjectionIds(ids: Set<Uuid>) {
        viewModelScope.launch {
            conversationApplicationService.updateModeInjectionIds(_conversationId, ids)
        }
    }

    fun updateWorkspaceCwd(cwd: String?) {
        viewModelScope.launch {
            conversationApplicationService.updateWorkspaceCwd(_conversationId, cwd)
        }
    }

    fun toggleMessageFavorite(node: MessageNode) {
        viewModelScope.launch {
            favoriteService.toggleNode(
                conversationId = _conversationId,
                conversationTitle = requireNotNull(snapshot.value).header.title,
                node = node,
            )
        }
    }

}
