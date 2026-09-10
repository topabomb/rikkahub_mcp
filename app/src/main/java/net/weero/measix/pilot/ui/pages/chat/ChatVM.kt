package net.weero.measix.pilot.ui.pages.chat

import me.rerere.common.configuration.ConfigurationReference
import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsLockedException
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.Avatar
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.service.ChatError
import net.weero.measix.pilot.service.ConfigurationApplicationService
import net.weero.measix.pilot.service.ConversationAssistantTarget
import net.weero.measix.pilot.data.configuration.AssistantPreferenceChange
import net.weero.measix.pilot.data.configuration.ResourceSelectionSlot
import net.weero.measix.pilot.service.ChatErrorStore
import net.weero.measix.pilot.service.terminalChatError
import net.weero.measix.pilot.service.ConversationTurnService
import net.weero.measix.pilot.service.ConversationApplicationService
import net.weero.measix.pilot.service.ConversationQueryService
import net.weero.measix.pilot.service.ConversationReadState
import net.weero.measix.pilot.service.ConversationSummary
import net.weero.measix.pilot.service.ConversationUiModel
import net.weero.measix.pilot.service.ConversationCommandTarget
import net.weero.measix.pilot.service.ConversationViewLease
import net.weero.measix.pilot.service.ArtifactUseCase
import net.weero.measix.pilot.service.ArtifactDraftScope
import net.weero.measix.pilot.service.FavoriteService
import net.weero.measix.pilot.service.runtime.ConversationPresentation
import net.weero.measix.pilot.service.runtime.ConversationPresentationSnapshot
import net.weero.measix.pilot.service.runtime.ToolInteractionDecision
import net.weero.measix.pilot.ui.hooks.ChatInputState
import net.weero.measix.pilot.utils.UpdateChecker
import net.weero.measix.pilot.utils.base64Decode
import kotlin.uuid.Uuid
import java.util.concurrent.atomic.AtomicBoolean

class ChatVM internal constructor(
    private val request: net.weero.measix.pilot.service.ConversationOpenRequest,
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val turnService: ConversationTurnService,
    private val conversationApplicationService: ConversationApplicationService,
    private val conversationQueryService: ConversationQueryService,
    val updateChecker: UpdateChecker,
    private val artifactUseCase: ArtifactUseCase,
    private val favoriteService: FavoriteService,
    private val chatErrorStore: ChatErrorStore,
    private val configurationApplicationService: ConfigurationApplicationService,
) : ViewModel() {
    private val _conversationId: Uuid = request.id
    private sealed interface PageState {
        data object Loading : PageState
        data object Missing : PageState
        data class Failed(val error: Throwable) : PageState
        data class Open(val lease: ConversationViewLease, val imports: ArtifactDraftScope) : PageState {
            fun close() { imports.close(); lease.close() }
        }
    }

    private val cleared = AtomicBoolean(false)
    private val page = MutableStateFlow<PageState>(PageState.Loading)
    private val initializationOwner = Any()
    private var initializationJob: Job? = null
    private var initializationAttempt = 0L

    private fun <T> fromPage(empty: T, source: (PageState.Open) -> Flow<T>): Flow<T> =
        page.flatMapLatest { state -> if (state is PageState.Open) source(state) else flowOf(empty) }

    val conversationState: StateFlow<ConversationReadState> = page.flatMapLatest { state ->
        when (state) {
            PageState.Loading -> flowOf(ConversationReadState.Loading)
            PageState.Missing -> flowOf(ConversationReadState.Missing)
            is PageState.Failed -> flowOf(ConversationReadState.Failed(state.error))
            is PageState.Open -> conversationQueryService.observeConversation(state.lease)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ConversationReadState.Loading)

    val snapshot: StateFlow<ConversationPresentationSnapshot?> = conversationState
        .map { (it as? ConversationReadState.Ready)?.snapshot }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val favoriteNodeIds: StateFlow<Set<Uuid>> = fromPage(emptySet()) { state ->
        conversationQueryService.observeForView(state.lease, emptySet()) {
            favoriteService.observeNodeIds(_conversationId)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private fun requirePage(): PageState.Open = (page.value as? PageState.Open)
        ?.also { it.lease.requireOpen() } ?: error("conversation_view_unavailable")

    fun currentSnapshot(): ConversationPresentationSnapshot {
        requirePage()
        return requireNotNull(snapshot.value)
    }

    val inputState = ChatInputState()
    val artifactDraftScope: ArtifactDraftScope get() = requirePage().imports
    private val initialInputMutex = Mutex()
    private var initialInputConsumed = false

    suspend fun initializeInput(text: String?, files: List<Uri>) = initialInputMutex.withLock {
        if (initialInputConsumed) return@withLock
        val current = requirePage()
        if (!currentSnapshot().header.newConversation) {
            initialInputConsumed = true
            return@withLock
        }
        val decoded = text?.base64Decode()
        val imported = current.imports.importUrisOrThrow(files)
        check(requirePage() === current) { "conversation_view_unavailable" }
        if (files.isNotEmpty()) {
            inputState.messageContent = imported.mapNotNull { artifact ->
                when {
                    artifact.mimeType.startsWith("image/") -> UIMessagePart.Image(url = artifact.uri.toString())
                    artifact.mimeType.startsWith("video/") -> UIMessagePart.Video(url = artifact.uri.toString())
                    artifact.mimeType.startsWith("audio/") -> UIMessagePart.Audio(url = artifact.uri.toString())
                    else -> null
                }
            }
        }
        if (!decoded.isNullOrEmpty()) inputState.setMessageText(decoded)
        initialInputConsumed = true
    }

    val turnPresentation: StateFlow<ConversationPresentation> = fromPage(ConversationPresentation.IDLE) { state ->
        conversationQueryService.turnPresentation(state.lease)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ConversationPresentation.IDLE)

    val conversationUiModel: StateFlow<ConversationUiModel?> = fromPage<ConversationUiModel?>(null) { state ->
        conversationQueryService.conversationUiModel(state.lease)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init { acquireViewLease() }

    fun retryConversationLoad() {
        synchronized(initializationOwner) {
            initializationJob?.cancel()
            (page.value as? PageState.Open)?.close()
            discardInput()
            page.value = PageState.Loading
            initializationJob = null
            acquireViewLease()
        }
    }

    private fun acquireViewLease(): Job = synchronized(initializationOwner) {
        initializationJob?.takeIf(Job::isActive) ?: run {
            val attempt = ++initializationAttempt
            fun publish(next: PageState): Boolean = synchronized(initializationOwner) {
                if (cleared.get() || attempt != initializationAttempt) false else {
                    page.value = next
                    true
                }
            }
            viewModelScope.launch {
                var opened: PageState.Open? = null
                try {
                    val lease = conversationApplicationService.initialize(request)
                    val imports = try { artifactUseCase.openDraftScope(lease) } catch (error: Throwable) {
                        lease.close()
                        throw error
                    }
                    val state = PageState.Open(lease, imports)
                    opened = state
                    if (!publish(state)) return@launch
                    coroutineScope {
                        launch {
                            conversationQueryService.observeConversation(lease)
                                .map { it is ConversationReadState.Ready && !it.snapshot.header.newConversation }
                                .distinctUntilChanged().filter { it }.collect {
                                    conversationApplicationService.rememberConversation(lease)
                                }
                        }
                        conversationQueryService.observeViewAccess(lease).first { !it }
                        page.compareAndSet(state, PageState.Failed(IllegalStateException("conversation_view_unavailable")))
                        coroutineContext.cancelChildren()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: net.weero.measix.pilot.service.runtime.ConversationNotFoundException) {
                    publish(PageState.Missing)
                } catch (error: Exception) {
                    publish(PageState.Failed(error))
                } finally {
                    opened?.close()
                    synchronized(initializationOwner) {
                        if (attempt == initializationAttempt) discardInput()
                    }
                }
            }.also { initializationJob = it }
        }
    }

    override fun onCleared() {
        cleared.set(true)
        (page.value as? PageState.Open)?.close()
        discardInput()
    }

    private fun discardInput() {
        inputState.clearInput()
        initialInputConsumed = false
    }

    val settings: StateFlow<Settings> =
        settingsStore.userSettings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    // 错误状态
    val errors: StateFlow<List<ChatError>> = fromPage(emptyList()) { state ->
        conversationQueryService.observeForView(state.lease, emptyList()) { chatErrorStore.errorsFor(_conversationId) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun dismissError(id: Uuid) = chatErrorStore.dismiss(id)

    fun clearAllErrors() = chatErrorStore.clear(_conversationId)

    fun showTerminalError(message: UIMessage) {
        val status = message.terminalStatus ?: return
        terminalChatError(
            context = context,
            conversationId = _conversationId,
            messageId = message.id,
            status = status,
            reason = message.terminalReason,
            detail = message.terminalDetail,
        )?.let(chatErrorStore::add)
    }

    internal val completedSpeech: Flow<net.weero.measix.pilot.service.ConversationSpeechCompletion> =
        fromPage<net.weero.measix.pilot.service.ConversationSpeechCompletion?>(null) { state ->
            conversationQueryService.observeForView<net.weero.measix.pilot.service.ConversationSpeechCompletion?>(state.lease, null) {
                turnService.completedSpeech.filter { it.conversationId == _conversationId }
            }
        }.filterNotNull()

    fun updateSettings(transform: (Settings) -> Settings): Job {
        return viewModelScope.launch {
            try {
                var previousAvatar: Avatar? = null
                val committed = artifactUseCase.updateSettingsReferences { current ->
                    val updated = transform(current)
                    previousAvatar = current.displaySetting.userAvatar
                    updated
                }
                previousAvatar?.let { oldAvatar ->
                    if (oldAvatar != committed.displaySetting.userAvatar) artifactUseCase.maintainStorage()
                }
            } catch (error: SettingsLockedException) {
                reportLockedSettingsChange(error)
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

    internal suspend fun changeAssistantPreference(target: ConversationAssistantTarget, change: AssistantPreferenceChange): Boolean =
        runConfigurationCommand(target) { configurationApplicationService.changeAssistantPreference(target, change) }

    internal suspend fun selectSearchService(target: ConversationAssistantTarget, reference: ConfigurationReference): Boolean =
        runConfigurationCommand(target) {
            configurationApplicationService.selectConversationSearch(target, reference)
        }

    private suspend fun runConfigurationCommand(target: ConversationAssistantTarget, action: suspend () -> Unit): Boolean = try {
        requireConfigurationTarget(target)
        action()
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        if ((page.value as? PageState.Open)?.lease?.commandTarget === target.conversation) {
            val visibleError = if (error is net.weero.measix.pilot.service.WorkspacePreferenceException)
                IllegalStateException(context.getString(R.string.workspace_preference_partial_failure), error) else error
            chatErrorStore.add(error = visibleError, conversationId = target.conversation.conversationId,
                title = context.getString(R.string.error_title_operation))
        }
        false
    }

    internal fun requireConfigurationTarget(target: ConversationAssistantTarget) {
        val opened = requirePage()
        check(opened.lease.commandTarget === target.conversation && snapshot.value?.header?.assistantId == target.assistantId) {
            "conversation_configuration_target_changed"
        }
    }

    internal fun detailSource(target: ConversationAssistantTarget): net.weero.measix.pilot.service.ConversationViewLease? =
        (page.value as? PageState.Open)?.takeIf { it.lease.commandTarget === target.conversation }?.lease

    internal fun importsFor(target: ConversationAssistantTarget): ArtifactDraftScope? =
        (page.value as? PageState.Open)?.takeIf { it.lease.commandTarget === target.conversation }?.imports

    private fun reportLockedSettingsChange(error: SettingsLockedException) {
        chatErrorStore.add(
            error = error,
            conversationId = _conversationId,
            title = context.getString(R.string.error_title_operation),
        )
    }

    // Update checker — 共享 UpdateChecker 的缓存 StateFlow，App 生命周期内只请求一次
    val updateState = updateChecker.updateState

    /**
     * 处理消息发送
     *
     * @param content 消息内容
     * @param answer 是否触发消息生成，如果为false，则仅添加消息到消息列表中
     * @return 已接受请求的稳定消息身份；空输入返回 null，receipt 不代表 durable 提交已经成功
     */
    suspend fun handleMessageSend(content: List<UIMessagePart>, answer: Boolean = true) =
        requirePage().let { turnService.sendMessage(it.lease.commandTarget, content, answer, it.imports) }

    fun handleMessageEdit(parts: List<UIMessagePart>, messageId: Uuid) {
        if (parts.isEmptyInputMessage()) return
        launchPageCommand { opened ->
            conversationApplicationService.editMessage(opened.lease.commandTarget, messageId, parts, opened.imports)
        }
    }

    /** 编辑 USER 后发送：截断到该消息并启动新的 START；receipt 身份是新的 USER variant。 */
    suspend fun handleMessageEditAndSend(parts: List<UIMessagePart>, messageId: Uuid) =
        requirePage().let { turnService.editAndResend(it.lease.commandTarget, messageId, parts, it.imports) }

    fun handleCompressContext(additionalPrompt: String, targetTokens: Int, keepRecentMessages: Int): Job {
        val target = requirePage().lease.commandTarget
        return launchCommand(target) {
            conversationApplicationService.compress(
                target,
                additionalPrompt,
                targetTokens,
                keepRecentMessages
            ).onFailure {
                chatErrorStore.add(
                    error = it,
                    conversationId = target.conversationId,
                    title = context.getString(R.string.error_title_compress_conversation),
                )
            }
        }
    }

    suspend fun forkMessage(message: UIMessage): Uuid? = try {
        conversationApplicationService.forkAtMessage(requirePage().lease.commandTarget, message.id)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { reportCommandError(_conversationId, error); null }

    fun deleteMessage(message: UIMessage) {
        launchPageCommand { opened -> conversationApplicationService.deleteMessage(opened.lease.commandTarget, message) }
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
        launchPageCommand { turnService.regenerateAtMessage(it.lease.commandTarget, message, regenerateAssistantMsg) }
    }

    fun toolDecisionHandler(): (suspend (ToolCallLocator, ToolInteractionDecision) -> Boolean)? {
        val target = (page.value as? PageState.Open)?.lease?.commandTarget ?: return null
        return { locator, decision ->
            try {
                turnService.submitToolDecision(target, locator, decision)
                true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { reportCommandError(target.conversationId, error); false }
        }
    }

    fun subAssistantAnswerHandler(): (suspend (String, String, String) -> Boolean)? {
        val target = (page.value as? PageState.Open)?.lease?.commandTarget ?: return null
        return { runId, interactionId, answer ->
            try {
                check(conversationApplicationService.answerSubAssistant(target, runId, interactionId, answer)) {
                    "sub_assistant_interaction_unavailable"
                }
                true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { reportCommandError(target.conversationId, error); false }
        }
    }

    private fun launchCommand(target: ConversationCommandTarget, action: suspend () -> Unit): Job = viewModelScope.launch {
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { reportCommandError(target.conversationId, error) }
    }

    private fun launchPageCommand(action: suspend (PageState.Open) -> Unit) {
        val opened = page.value as? PageState.Open ?: return
        launchCommand(opened.lease.commandTarget) { action(opened) }
    }

    private fun reportCommandError(id: Uuid, error: Exception) {
        chatErrorStore.add(error = error, conversationId = id, title = context.getString(R.string.error_title_operation))
    }

    fun stopGeneration() {
        launchPageCommand { opened -> conversationApplicationService.stopGeneration(opened.lease.commandTarget) }
    }

    fun updateTitle(title: String) {
        launchPageCommand { opened -> conversationApplicationService.updateTitle(opened.lease.commandTarget, title) }
    }

    suspend fun deleteConversation(conversation: ConversationSummary): Boolean = try {
        conversationApplicationService.delete(conversation.commandTarget)
        true
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { reportCommandError(conversation.id, error); false }

    fun updatePinnedStatus(conversation: ConversationSummary) {
        val target = conversation.commandTarget
        launchCommand(target) { conversationApplicationService.togglePin(target) }
    }

    internal suspend fun moveConversationToAssistant(target: ConversationAssistantTarget, targetAssistantId: ConfigurationReference): Boolean =
        runConfigurationCommand(target) {
            conversationApplicationService.moveToAssistant(target, targetAssistantId, selectForNewChats = true)
        }

    fun generateTitle(conversation: ConversationSummary, force: Boolean = false) {
        val target = conversation.commandTarget
        launchCommand(target) {
            conversationApplicationService.generateTitle(target, force)
        }
    }

    fun selectNode(nodeId: Uuid, selectIndex: Int) {
        launchPageCommand { opened -> conversationApplicationService.selectNode(opened.lease.commandTarget, nodeId, selectIndex) }
    }

    internal suspend fun updateCustomSystemPrompt(target: ConversationAssistantTarget, prompt: String?): Boolean =
        runConfigurationCommand(target) { conversationApplicationService.updateCustomSystemPrompt(target, prompt) }

    internal suspend fun updateModeInjectionIds(target: ConversationAssistantTarget, ids: Set<ConfigurationReference>): Boolean =
        runConfigurationCommand(target) { conversationApplicationService.updateModeInjectionIds(target, ids) }

    internal suspend fun updateWorkspaceCwd(target: ConversationAssistantTarget, expectedWorkspaceId: Uuid?, cwd: String?): Boolean =
        runConfigurationCommand(target) { conversationApplicationService.updateWorkspaceCwd(target, expectedWorkspaceId, cwd) }

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
