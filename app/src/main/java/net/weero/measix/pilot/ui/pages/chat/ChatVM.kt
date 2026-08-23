package net.weero.measix.pilot.ui.pages.chat

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
import net.weero.measix.pilot.data.files.FilesManager
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.Avatar
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.model.NodeFavoriteTarget
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.data.repository.FavoriteRepository
import net.weero.measix.pilot.service.ChatError
import net.weero.measix.pilot.service.ChatService
import net.weero.measix.pilot.service.runtime.ConversationCommand
import net.weero.measix.pilot.service.runtime.ConversationSnapshot
import net.weero.measix.pilot.service.runtime.OptionalFolderId
import net.weero.measix.pilot.service.runtime.OptionalString
import net.weero.measix.pilot.service.runtime.OptionalUuidSet
import net.weero.measix.pilot.service.runtime.UpdateHeader
import net.weero.measix.pilot.service.runtime.toConversation
import net.weero.measix.pilot.ui.components.ai.SearchMode
import net.weero.measix.pilot.ui.components.ai.searchModeEnablesBuiltIn
import net.weero.measix.pilot.ui.components.ai.searchModeEnablesLocal
import net.weero.measix.pilot.ui.hooks.writeStringPreference
import net.weero.measix.pilot.ui.hooks.ChatInputState
import net.weero.measix.pilot.utils.UpdateChecker
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "ChatVM"

class ChatVM(
    id: String,
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val chatService: ChatService,
    val updateChecker: UpdateChecker,
    private val filesManager: FilesManager,
    private val favoriteRepository: FavoriteRepository,
) : ViewModel() {
    private val _conversationId: Uuid = Uuid.parse(id)

    // 唯一内部事实流（nodes + activeTurn + header），UI 主订阅源。
    // 需要 Conversation 形状的消费方经 [currentConversation] 按需转换（纯函数）。
    val snapshot: StateFlow<ConversationSnapshot> = chatService.getConversationSnapshot(_conversationId)

    /** 命令语义读取（turn 边界低频点）：内存快照 → Conversation 形状。 */
    fun currentConversation(): Conversation = snapshot.value.toConversation()
    var chatListInitialized by mutableStateOf(false) // 聊天列表是否已经滚动到底部

    // 聊天输入状态 - 保存在 ViewModel 中避免 TransactionTooLargeException
    val inputState = ChatInputState()

    // 异步任务 (从ChatService获取，响应式)
    val conversationJob: StateFlow<Job?> =
        chatService
            .getGenerationJobStateFlow(_conversationId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val processingStatus: StateFlow<String?> =
        chatService
            .getProcessingStatusFlow(_conversationId)

    val conversationJobs = chatService
        .getConversationJobs()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    init {
        // 添加对话引用
        chatService.addConversationReference(_conversationId)

        // 初始化对话
        viewModelScope.launch {
            chatService.initializeConversation(_conversationId)
        }

        // 记住对话ID, 方便下次启动恢复
        context.writeStringPreference("lastConversationId", _conversationId.toString())
    }

    override fun onCleared() {
        // 移除对话引用
        chatService.removeConversationReference(_conversationId)
    }

    // 用户设置
    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    // 网络搜索(每个助手独立)
    val enableWebSearch = combine(settings, snapshot) { currentSettings, currentSnapshot ->
        currentSettings.getConversationAssistant(currentSnapshot.header.assistantId).enableWebSearch
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // 错误状态
    val errors: StateFlow<List<ChatError>> = chatService.errors

    fun dismissError(id: Uuid) = chatService.dismissError(id)

    fun clearAllErrors() = chatService.clearAllErrors()

    // 生成完成
    val generationDoneFlow: SharedFlow<Uuid> = chatService.generationDoneFlow

    fun getTtsQueueSessionId(conversationId: Uuid): String? =
        chatService.getTtsQueueSessionId(conversationId)

    // MCP管理器
    val mcpManager = chatService.mcpManager

    // 更新设置
    fun updateSettings(transform: (Settings) -> Settings): Job {
        return viewModelScope.launch {
            var previousAvatar: Avatar? = null
            val committed = settingsStore.updateAtomicAndGet { current ->
                val updated = transform(current)
                previousAvatar = current.displaySetting.userAvatar
                updated
            }
            // 文件副作用只能发生在 DataStore 提交成功之后。
            previousAvatar?.let { oldAvatar ->
                checkUserAvatarDelete(oldAvatar, committed.displaySetting.userAvatar)
            }
        }
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

    // 检查用户头像删除
    private fun checkUserAvatarDelete(oldAvatar: Avatar, newAvatar: Avatar) {
        if (oldAvatar is Avatar.Image && oldAvatar != newAvatar) {
            filesManager.deleteChatFiles(listOf(oldAvatar.url.toUri()))
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

        chatService.sendMessage(_conversationId, content, answer)
    }

    fun handleMessageEdit(parts: List<UIMessagePart>, messageId: Uuid) {
        if (parts.isEmptyInputMessage()) return

        viewModelScope.launch {
            chatService.editMessage(_conversationId, messageId, parts)
        }
    }

    fun handleCompressContext(additionalPrompt: String, targetTokens: Int, keepRecentMessages: Int): Job {
        return viewModelScope.launch {
            chatService.sideEffects.compressConversation(
                _conversationId,
                currentConversation(),
                additionalPrompt,
                targetTokens,
                keepRecentMessages
            ).onFailure {
                chatService.addError(it, title = context.getString(R.string.error_title_compress_conversation))
            }
        }
    }

    suspend fun forkMessage(message: UIMessage): Conversation {
        return chatService.forkConversationAtMessage(_conversationId, message.id)
    }

    fun deleteMessage(message: UIMessage) {
        viewModelScope.launch {
            chatService.deleteMessage(_conversationId, message)
        }
    }

    fun showDeleteBlockedWhileGeneratingError() {
        chatService.addError(
            error = IllegalStateException("请先停止生成再删除消息"),
            conversationId = _conversationId,
            title = context.getString(R.string.error_title_operation)
        )
    }

    fun regenerateAtMessage(
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        chatService.regenerateAtMessage(_conversationId, message, regenerateAssistantMsg)
    }

    fun handleToolApproval(
        locator: ToolCallLocator,
        approved: Boolean,
        reason: String = ""
    ) {
        chatService.handleToolApproval(_conversationId, locator, approved, reason)
    }

    fun handleToolAnswer(
        locator: ToolCallLocator,
        answer: String,
    ) {
        chatService.handleToolApproval(_conversationId, locator, approved = true, answer = answer)
    }

    fun handleSubAssistantAnswer(runId: String, interactionId: String, answer: String): Boolean =
        chatService.handleSubAssistantAnswer(runId, interactionId, answer)

    fun stopGeneration() {
        viewModelScope.launch {
            chatService.stopGeneration(_conversationId)
        }
    }

    fun updateTitle(title: String) {
        viewModelScope.launch {
            chatService.updateConversationTitle(_conversationId, title)
        }
    }

    fun deleteConversation(conversation: Conversation): Job =
        viewModelScope.launch {
            chatService.deleteConversation(conversation)
        }

    fun updatePinnedStatus(conversation: Conversation) {
        viewModelScope.launch {
            chatService.togglePinStatus(conversation.id)
        }
    }

    fun moveConversationToAssistant(conversation: Conversation, targetAssistantId: Uuid) {
        viewModelScope.launch {
            val conversationFull = if (conversation.id == _conversationId) {
                // 活跃会话以内存状态为准，避免 DB 中的旧快照覆盖尚未落库的更新。
                currentConversation()
            } else {
                conversationRepo.getConversationById(conversation.id) ?: conversation
            }
            if (conversationFull.assistantId == targetAssistantId) return@launch

            // 文件夹是助手内分组，切换助手后原文件夹在新助手下不可见，需清空归属避免会话丢失。
            // UpdateHeader 命令（内存 + 窄列原子提交）
            if (conversation.id == _conversationId) {
                submit(
                    UpdateHeader(
                        assistantId = targetAssistantId,
                        folderId = OptionalFolderId.Clear,
                    )
                )
                settingsStore.updateAssistant(targetAssistantId)
            } else {
                val updated = conversationFull.withAssistant(targetAssistantId)
                chatService.updatePersistedConversation(updated)
            }
        }
    }

    fun generateTitle(conversation: Conversation, force: Boolean = false) {
        viewModelScope.launch {
            val conversationFull = conversationRepo.getConversationById(conversation.id) ?: return@launch
            chatService.sideEffects.generateTitle(conversationFull, force)
        }
    }

    fun generateSuggestion(conversation: Conversation) {
        viewModelScope.launch {
            chatService.sideEffects.generateSuggestion(_conversationId, conversation)
        }
    }

    /**
     * header 级整对象回调的命令分解（UI 组件保持 (Conversation) -> Unit
     * 回调签名不重写；差异字段经 UpdateHeader 三态字段提交，不再整对象回写）。
     */
    fun updateConversationHeader(next: Conversation) {
        val current = currentConversation()
        submit(
            UpdateHeader(
                title = next.title.takeIf { it != current.title },
                suggestions = next.chatSuggestions.takeIf { it != current.chatSuggestions },
                isPinned = next.isPinned.takeIf { it != current.isPinned },
                folderId = when {
                    next.folderId == current.folderId -> OptionalFolderId.Keep
                    next.folderId == null -> OptionalFolderId.Clear
                    else -> OptionalFolderId.SetTo(next.folderId)
                },
                assistantId = next.assistantId.takeIf { it != current.assistantId },
                customSystemPrompt = if (next.customSystemPrompt != current.customSystemPrompt) {
                    OptionalString.Set(next.customSystemPrompt)
                } else {
                    OptionalString.Keep
                },
                modeInjectionIds = if (next.modeInjectionIds != current.modeInjectionIds) {
                    OptionalUuidSet.Set(next.modeInjectionIds)
                } else {
                    OptionalUuidSet.Keep
                },
                workspaceCwd = if (next.workspaceCwd != current.workspaceCwd) {
                    OptionalString.Set(next.workspaceCwd)
                } else {
                    OptionalString.Keep
                },
            )
        )
    }

    // 命令提交入口（UI 结构性修改走 reducer 唯一路径）
    fun submit(command: ConversationCommand) {
        viewModelScope.launch {
            chatService.submitConversationCommand(_conversationId, command)
        }
    }

    fun toggleMessageFavorite(node: MessageNode) {
        viewModelScope.launch {
            val currentlyFavorited = favoriteRepository.isNodeFavorited(_conversationId, node.id)
            if (currentlyFavorited) {
                favoriteRepository.removeNodeFavorite(_conversationId, node.id)
            } else {
                favoriteRepository.addNodeFavorite(
                    NodeFavoriteTarget(
                        conversationId = _conversationId,
                        conversationTitle = snapshot.value.header.title,
                        nodeId = node.id,
                        node = node
                    )
                )
            }

            chatService.updateConversationState(_conversationId) { currentConversation ->
                currentConversation.copy(
                    messageNodes = currentConversation.messageNodes.map { existingNode ->
                        if (existingNode.id == node.id) {
                            existingNode.copy(isFavorite = !currentlyFavorited)
                        } else {
                            existingNode
                        }
                    }
                )
            }
        }
    }

}

internal fun Conversation.withAssistant(targetAssistantId: Uuid): Conversation =
    if (assistantId == targetAssistantId) {
        this
    } else {
        copy(
            assistantId = targetAssistantId,
            folderId = null,
        )
    }
