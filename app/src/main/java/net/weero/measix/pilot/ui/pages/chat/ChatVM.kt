package net.weero.measix.pilot.ui.pages.chat

import net.weero.measix.pilot.utils.logDiagnosticFailure

import me.rerere.common.configuration.ConfigurationReference
import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
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
import net.weero.measix.pilot.utils.userVisibleDiagnostic
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
    private val remoteWorkspace: net.weero.measix.pilot.service.remoteworkspace.RemoteWorkspaceService,
) : ViewModel() {
    internal val remoteWorkspaceSummary = remoteWorkspace.summary
    internal suspend fun refreshRemoteWorkspace(target: ConversationAssistantTarget) {
        requireConfigurationTarget(target)
        if (target.conversation.selection.access is net.weero.measix.pilot.data.enterprise.RealmAccess.Enterprise)
            remoteWorkspace.refresh(target.conversation.selection)
    }
    internal suspend fun remoteWorkspaceNavigation(target: ConversationAssistantTarget): net.weero.measix.pilot.data.enterprise.RealmSelection {
        requireConfigurationTarget(target)
        remoteWorkspace.requireNavigation(target.conversation.selection, filesRequired = true)
        requireConfigurationTarget(target)
        return target.conversation.selection
    }
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
    internal data class ConversationHandoff(
        val selection: net.weero.measix.pilot.data.enterprise.RealmSelection,
        val continuation: net.weero.measix.pilot.service.ConversationContinuation? = null,
        val diagnostic: String? = null,
        val failure: Throwable? = null,
    )
    private val _handoff = MutableStateFlow<ConversationHandoff?>(null)
    internal val handoff = _handoff.asStateFlow()

    internal fun consumeHandoff(expected: ConversationHandoff, navigate: (ConversationHandoff) -> Unit) {
        if (expected.continuation == null) return
        viewModelScope.launch {
            if (conversationQueryService.observeCurrentSelection().first() != expected.selection) {
                _handoff.compareAndSet(expected, null)
                return@launch
            }
            if (_handoff.compareAndSet(expected, null)) navigate(expected)
        }
    }
    private val initializationOwner = Any()
    private var initializationJob: Job? = null
    private var initializationAttempt = 0L
    private val readRevision = MutableStateFlow(0L)
    internal val readRetryRevision = readRevision.asStateFlow()

    private fun <T> fromPage(empty: T, source: (PageState.Open) -> Flow<T>): Flow<T> =
        page.flatMapLatest { state -> if (state is PageState.Open) source(state) else flowOf(empty) }

    val conversationState: StateFlow<ConversationReadState> = page.flatMapLatest { state ->
        when (state) {
            PageState.Loading -> flowOf(ConversationReadState.Loading)
            PageState.Missing -> flowOf(ConversationReadState.Missing)
            is PageState.Failed -> flowOf(ConversationReadState.Failed(state.error))
            is PageState.Open -> readRevision.flatMapLatest {
                combine(
                    conversationQueryService.observeConversation(state.lease),
                    conversationQueryService.conversationUiModel(state.lease).onStart { emit(null) },
                ) { read, model ->
                    if (read is ConversationReadState.Ready) {
                        if (model == null) ConversationReadState.Loading
                        else ConversationReadState.Ready(model.snapshot, model)
                    } else read
                }.onStart { emit(ConversationReadState.Loading) }
                    .catch { error ->
                        if (error is CancellationException) throw error
                        logDiagnosticFailure("ChatVM", "Conversation projection failed for ${request.id}", error)
                        emit(ConversationReadState.Failed(error))
                    }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ConversationReadState.Loading)

    val snapshot: StateFlow<ConversationPresentationSnapshot?> = conversationState
        .map { (it as? ConversationReadState.Ready)?.snapshot }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val favoriteNodeIds: StateFlow<Set<Uuid>> = fromPage(emptySet()) { state ->
        readRevision.flatMapLatest {
            conversationQueryService.observeForView(state.lease, emptySet()) {
                favoriteService.observeNodeIds(state.lease.commandTarget)
            }.catch { error ->
                if (error is CancellationException) throw error
                reportCommandError(state, error, solution = net.weero.measix.pilot.service.ChatErrorSolution.RetryConversationReads)
                emit(emptySet())
            }
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
    private var inputSubmissionPending = false
    private var initialInputConsumed = false

    suspend fun initializeInput(text: String?, files: List<Uri>) = initialInputMutex.withLock {
        if (initialInputConsumed) return@withLock
        val current = requirePage()
        if (!currentSnapshot().header.newConversation) {
            initialInputConsumed = true
            return@withLock
        }
        try {
            val decoded = text?.base64Decode()
            if (!decoded.isNullOrEmpty() && inputState.textContent.text.isEmpty()) {
                inputState.setMessageText(decoded)
            }
            // This one-time share is attempted once; read retries must not replay an interrupted import.
            initialInputConsumed = true
            val imported = current.imports.importUrisOrThrow(files)
            check(requirePage() === current) { "conversation_view_unavailable" }
            if (imported.isNotEmpty()) {
                inputState.messageContent = inputState.messageContent + imported.map { artifact ->
                    when {
                        artifact.mimeType.startsWith("image/") -> UIMessagePart.Image(url = artifact.uri.toString())
                        artifact.mimeType.startsWith("video/") -> UIMessagePart.Video(url = artifact.uri.toString())
                        artifact.mimeType.startsWith("audio/") -> UIMessagePart.Audio(url = artifact.uri.toString())
                        else -> UIMessagePart.Document(
                            url = artifact.uri.toString(), fileName = artifact.displayName, mime = artifact.mimeType,
                        )
                    }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            // Shared input is consumed once; a read retry must not silently repeat imports.
            if (page.value === current) initialInputConsumed = true
            reportCommandError(current, error)
            throw error
        }
    }

    val turnPresentation: StateFlow<ConversationPresentation> = conversationState
        .map { (it as? ConversationReadState.Ready)?.uiModel?.presentation ?: ConversationPresentation.IDLE }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ConversationPresentation.IDLE)

    val conversationUiModel: StateFlow<ConversationUiModel?> = conversationState
        .map { (it as? ConversationReadState.Ready)?.uiModel }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init { acquireViewLease() }

    fun retryConversationLoad() {
        synchronized(initializationOwner) {
            if (page.value is PageState.Open && conversationState.value != ConversationReadState.Missing) {
                // The restarted query revalidates the original owner before subscribing or publishing.
                readRevision.update { it + 1 }
                return
            }
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
                                    try {
                                        conversationApplicationService.rememberConversation(lease)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (error: Exception) {
                                        reportRecentConversationFailure(state, error)
                                    }
                                }
                        }
                        conversationQueryService.observeViewAccess(lease).first { !it }
                        page.compareAndSet(state, PageState.Failed(IllegalStateException("conversation_view_unavailable")))
                        coroutineContext.cancelChildren()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: net.weero.measix.pilot.service.runtime.ConversationNotFoundException) {
                    val existing = request as? net.weero.measix.pilot.service.ConversationOpenRequest.OpenExisting
                    val resume = existing?.startupResume
                    if (resume == null) publish(PageState.Missing)
                    else {
                        try {
                            val continuation = conversationApplicationService.startupContinuation(existing)
                            _handoff.value = ConversationHandoff(
                                net.weero.measix.pilot.data.enterprise.RealmSelection(existing.access, resume.selectionRevision), continuation)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) {
                            logDiagnosticFailure("ChatVM", "Startup conversation continuation failed", error)
                            publish(PageState.Failed(error))
                        }
                    }
                } catch (error: Exception) {
                    logDiagnosticFailure("ChatVM", "Conversation page initialization failed for ${request.id} with ${request.access}", error)
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

    private suspend fun mayReportFailure(state: PageState.Open?, error: Throwable): Boolean {
        if (state == null || page.value !== state) return false
        try {
            conversationQueryService.requireViewAccess(state.lease)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: net.weero.measix.pilot.data.enterprise.EnterpriseConfigurationException) { return false }
        catch (validation: Exception) {
            if (validation is IllegalStateException && validation.message in
                setOf("conversation_view_closed", "conversation_view_revoked")) return false
            if (validation !== error) error.addSuppressed(validation)
        }
        return page.value === state
    }

    private suspend fun reportRecentConversationFailure(state: PageState.Open, error: Exception) {
        if (!mayReportFailure(state, error)) return
        logDiagnosticFailure("ChatVM", "Recent conversation preference write failed for ${request.id}", error)
        chatErrorStore.add(ChatError(
            detail = context.getString(R.string.chat_recent_conversation_save_failed, error.userVisibleDiagnostic()),
            conversationId = _conversationId,
            retention = net.weero.measix.pilot.service.ChatErrorRetention.UNTIL_DISMISSED,
        ))
    }

    val settings: StateFlow<Settings> =
        settingsStore.userSettings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    // 错误状态
    val errors: StateFlow<List<ChatError>> = fromPage(emptyList()) { state ->
        conversationQueryService.observeForView(state.lease, emptyList()) {
            combine(chatErrorStore.errorsFor(_conversationId), conversationState) { current, read ->
                // Only Ready identifies the selected tree; temporary read states cannot retire errors.
                val ready = read as? ConversationReadState.Ready ?: return@combine current
                val selectedMessages = ready.snapshot.currentMessages().mapTo(mutableSetOf()) { it.id }
                current.filter { it.sourceMessageId == null || it.sourceMessageId in selectedMessages }
            }
        }
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
            modelId = message.modelId,
        )?.let(chatErrorStore::add)
    }

    internal val completedSpeech: Flow<net.weero.measix.pilot.service.ConversationSpeechCompletion> =
        fromPage<net.weero.measix.pilot.service.ConversationSpeechCompletion?>(null) { state ->
            conversationQueryService.observeForView<net.weero.measix.pilot.service.ConversationSpeechCompletion?>(state.lease, null) {
                turnService.completedSpeech.filter { it.conversationId == _conversationId }
            }
        }.filterNotNull()

    fun updateSettings(transform: (Settings) -> Settings): Job {
        val original = page.value as? PageState.Open
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
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { reportCommandError(original, error) }
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

    internal suspend fun importAssistantUsageImage(target: ConversationAssistantTarget, uri: android.net.Uri, avatar: Boolean) {
        requireConfigurationTarget(target)
        configurationApplicationService.importAssistantImage(target, uri, avatar)
    }

    internal suspend fun changeAssistantPreference(target: ConversationAssistantTarget, change: AssistantPreferenceChange): Result<Unit> =
        runConfigurationCommand(target) { configurationApplicationService.changeAssistantPreference(target, change) }

    internal suspend fun selectSearchService(target: ConversationAssistantTarget, reference: ConfigurationReference): Result<Unit> =
        runConfigurationCommand(target) {
            configurationApplicationService.selectConversationSearch(target, reference)
        }

    private suspend fun runConfigurationCommand(target: ConversationAssistantTarget, action: suspend () -> Unit): Result<Unit> = try {
        requireConfigurationTarget(target)
        action()
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        val visibleError = if (error is net.weero.measix.pilot.service.WorkspacePreferenceException)
            IllegalStateException(context.getString(R.string.workspace_preference_partial_failure), error) else error
        Result.failure(visibleError)
    }

    internal fun requireConfigurationTarget(target: ConversationAssistantTarget) {
        val opened = requirePage()
        check(opened.lease.commandTarget === target.conversation && snapshot.value?.header?.assistantId == target.assistantId) {
            "conversation_configuration_target_changed"
        }
    }

    internal suspend fun selectStarter(target: ConversationAssistantTarget, starter: net.weero.measix.pilot.service.ConversationStarterUiModel) =
        initialInputMutex.withLock {
            requireConfigurationTarget(target)
            val original = requirePage()
            val configuration = requireNotNull(conversationUiModel.value?.configuration)
            fun accept(prompt: String) {
                check(requirePage() === original) { "conversation_view_unavailable" }
                requireConfigurationTarget(target)
                val spacer = if (inputState.textContent.text.isBlank()) "" else "\n\n"
                inputState.appendText(spacer + prompt)
            }
            if (currentSnapshot().header.newConversation) {
                conversationApplicationService.selectDraftStarter(target, starter.reference, configuration.opening?.selectionToken,
                    Uuid.random(), ::accept)
            } else conversationApplicationService.appendStarterPrompt(target, starter.reference, ::accept)
        }

    internal suspend fun openingDetails(target: ConversationAssistantTarget): net.weero.measix.pilot.service.StarterOpeningDetailUiModel {
        requireConfigurationTarget(target)
        return conversationApplicationService.openingDetails(target).also { requireConfigurationTarget(target) }
    }

    internal suspend fun refreshOpening(target: ConversationAssistantTarget) = initialInputMutex.withLock {
        requireConfigurationTarget(target)
        val token = requireNotNull(conversationUiModel.value?.configuration?.opening?.selectionToken)
        conversationApplicationService.refreshDraftOpening(target, token, Uuid.random())
        requireConfigurationTarget(target)
    }

    internal suspend fun clearOpening(target: ConversationAssistantTarget) = initialInputMutex.withLock {
        requireConfigurationTarget(target)
        val token = requireNotNull(conversationUiModel.value?.configuration?.opening?.selectionToken)
        conversationApplicationService.clearDraftOpening(target, token)
        requireConfigurationTarget(target)
    }

    internal fun detailSource(target: ConversationAssistantTarget): net.weero.measix.pilot.service.ConversationViewLease? =
        (page.value as? PageState.Open)?.takeIf { it.lease.commandTarget === target.conversation }?.lease

    internal fun importsFor(target: ConversationAssistantTarget): ArtifactDraftScope? =
        (page.value as? PageState.Open)?.takeIf { it.lease.commandTarget === target.conversation }?.imports

    // Update checker — 共享 UpdateChecker 的缓存 StateFlow，App 生命周期内只请求一次
    val updateState = updateChecker.updateState

    /**
     * 处理消息发送
     *
     * @param answer 是否触发消息生成，如果为false，则仅添加消息到消息列表中
     * @return 已持久提交的消息身份；提交前失败或页面已变化返回 null。
     */
    internal suspend fun handleMessageSend(target: ConversationAssistantTarget, answer: Boolean = true): net.weero.measix.pilot.service.SendMessageReceipt? {
        return submitInput(target) { opened, submission ->
            turnService.sendMessage(opened.lease.commandTarget, submission.contents, answer, opened.imports)
        }
    }

    private suspend fun <T : Any> submitInput(
        target: ConversationAssistantTarget,
        submit: suspend (PageState.Open, ChatInputState.Submission) -> T?,
    ): T? {
        val original = page.value as? PageState.Open
        if (inputSubmissionPending) return null
        inputSubmissionPending = true
        try {
            return initialInputMutex.withLock {
                val opened = (page.value as? PageState.Open)?.takeIf {
                    it.lease.commandTarget === target.conversation && snapshot.value?.header?.assistantId == target.assistantId
                } ?: return@withLock null
                val submission = inputState.captureSubmission()
                val result = submit(opened, submission)
                    ?: return@withLock null
                if (page.value !== opened) return@withLock null
                inputState.completeSubmission(submission)
                result
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            reportCommandError(original, error)
            return null
        } finally {
            inputSubmissionPending = false
        }
    }

    internal suspend fun handleMessageEdit(target: ConversationAssistantTarget): Boolean =
        submitInput(target) { opened, submission ->
            val messageId = submission.editingMessage ?: return@submitInput null
            if (submission.contents.isEmptyInputMessage()) return@submitInput null
            conversationApplicationService.editMessage(opened.lease.commandTarget, messageId, submission.contents, opened.imports)
            true
        } == true

    /** Editing USER input is released only after its replacement variant has committed. */
    internal suspend fun handleMessageEditAndSend(target: ConversationAssistantTarget): net.weero.measix.pilot.service.SendMessageReceipt? =
        submitInput(target) { opened, submission ->
            val messageId = submission.editingMessage ?: return@submitInput null
            turnService.editAndResend(opened.lease.commandTarget, messageId, submission.contents, opened.imports)
        }

    fun handleCompressContext(additionalPrompt: String, targetTokens: Int, keepRecentMessages: Int): Job {
        val original = requirePage()
        val target = original.lease.commandTarget
        return launchCommand(target) {
            conversationApplicationService.compress(
                target,
                additionalPrompt,
                targetTokens,
                keepRecentMessages
            ).onFailure { reportCommandError(original, it, title = context.getString(R.string.error_title_compress_conversation)) }
        }
    }

    suspend fun forkMessage(message: UIMessage): Uuid? {
        val original = page.value as? PageState.Open ?: return null
        return try { conversationApplicationService.forkAtMessage(original.lease.commandTarget, message.id) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { reportCommandError(original, error); null }
    }

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
        val original = page.value as? PageState.Open ?: return null
        val target = original.lease.commandTarget
        return { locator, decision ->
            try {
                turnService.submitToolDecision(target, locator, decision)
                true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { reportCommandError(original, error); false }
        }
    }

    fun subAssistantAnswerHandler(): (suspend (String, String, String) -> Boolean)? {
        val original = page.value as? PageState.Open ?: return null
        val target = original.lease.commandTarget
        return { runId, interactionId, answer ->
            try {
                check(conversationApplicationService.answerSubAssistant(target, runId, interactionId, answer)) {
                    "sub_assistant_interaction_unavailable"
                }
                true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { reportCommandError(original, error); false }
        }
    }

    private fun launchCommand(target: ConversationCommandTarget, action: suspend () -> Unit): Job {
        val original = (page.value as? PageState.Open)?.takeIf { it.lease.commandTarget.selection == target.selection }
        return viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { reportCommandError(original, error) }
        }
    }

    private fun launchPageCommand(action: suspend (PageState.Open) -> Unit) {
        val opened = page.value as? PageState.Open ?: return
        launchCommand(opened.lease.commandTarget) { action(opened) }
    }

    private suspend fun reportCommandError(
        original: PageState.Open?, error: Throwable,
        title: String = context.getString(R.string.error_title_operation),
        solution: net.weero.measix.pilot.service.ChatErrorSolution? = null,
    ) {
        if (!mayReportFailure(original, error)) {
            logDiagnosticFailure("ChatVM", "Operation ended after its originating page closed", error)
            return
        }
        chatErrorStore.add(error = error, conversationId = _conversationId, title = title, solution = solution,
            retention = if (solution == null) net.weero.measix.pilot.service.ChatErrorRetention.TRANSIENT
                else net.weero.measix.pilot.service.ChatErrorRetention.UNTIL_DISMISSED)
    }

    internal suspend fun reportReadFailure(target: ConversationAssistantTarget, diagnostic: String) {
        val original = (page.value as? PageState.Open)?.takeIf { it.lease.commandTarget === target.conversation } ?: return
        if (!mayReportFailure(original, IllegalStateException(diagnostic))) return
        chatErrorStore.add(ChatError(detail = diagnostic, conversationId = _conversationId,
            solution = net.weero.measix.pilot.service.ChatErrorSolution.RetryConversationReads,
            retention = net.weero.measix.pilot.service.ChatErrorRetention.UNTIL_DISMISSED))
    }

    fun stopGeneration() {
        launchPageCommand { opened -> conversationApplicationService.stopGeneration(opened.lease.commandTarget) }
    }

    fun updateTitle(title: String) {
        launchPageCommand { opened -> conversationApplicationService.updateTitle(opened.lease.commandTarget, title) }
    }

    fun deleteConversation(conversation: ConversationSummary) {
        val original = (page.value as? PageState.Open)?.takeIf { it.lease.commandTarget.selection == conversation.commandTarget.selection }
            ?: return
        val current = conversation.id == request.id
        if (_handoff.value != null) return
        if (current) _handoff.value = ConversationHandoff(conversation.commandTarget.selection)
        viewModelScope.launch {
            var committed: net.weero.measix.pilot.service.ConversationDeletionReceipt? = null
            try {
                val receipt = conversationApplicationService.delete(conversation.commandTarget)
                committed = receipt
                if (current) {
                    val continuation = conversationApplicationService.deletionContinuation(receipt)
                    _handoff.value = ConversationHandoff(receipt.selection, continuation,
                        receipt.maintenanceFailure?.userVisibleDiagnostic())
                } else receipt.maintenanceFailure?.let { reportCommandError(original, it) }
            } catch (cancelled: CancellationException) {
                if (current) _handoff.value = null
                throw cancelled
            }
            catch (error: Exception) {
                if (current && committed != null) {
                    logDiagnosticFailure("ChatVM", "Deleted conversation continuation failed", error)
                    _handoff.value = ConversationHandoff(conversation.commandTarget.selection, failure = error)
                }
                else { if (current) _handoff.value = null; reportCommandError(original, error) }
            }
        }
    }

    fun updatePinnedStatus(conversation: ConversationSummary) {
        val target = conversation.commandTarget
        launchCommand(target) { conversationApplicationService.togglePin(target) }
    }

    internal suspend fun moveConversationToAssistant(target: ConversationAssistantTarget, targetAssistantId: ConfigurationReference): Result<Unit> =
        initialInputMutex.withLock { runConfigurationCommand(target) {
            conversationApplicationService.moveToAssistant(target, targetAssistantId, selectForNewChats = true)
        } }

    fun generateTitle(conversation: ConversationSummary, force: Boolean = false) {
        val target = conversation.commandTarget
        launchCommand(target) {
            conversationApplicationService.generateTitle(target, force)
        }
    }

    fun selectNode(nodeId: Uuid, selectIndex: Int) {
        launchPageCommand { opened -> conversationApplicationService.selectNode(opened.lease.commandTarget, nodeId, selectIndex) }
    }

    internal suspend fun updateCustomSystemPrompt(target: ConversationAssistantTarget, prompt: String?): Result<Unit> =
        runConfigurationCommand(target) { conversationApplicationService.updateCustomSystemPrompt(target, prompt) }

    internal suspend fun updateModeInjectionIds(target: ConversationAssistantTarget, ids: Set<ConfigurationReference>): Result<Unit> =
        runConfigurationCommand(target) { conversationApplicationService.updateModeInjectionIds(target, ids) }

    internal suspend fun updateWorkspaceCwd(target: ConversationAssistantTarget, expectedWorkspaceId: Uuid?, cwd: String?): Result<Unit> =
        runConfigurationCommand(target) { conversationApplicationService.updateWorkspaceCwd(target, expectedWorkspaceId, cwd) }

    fun toggleMessageFavorite(node: MessageNode) {
        launchPageCommand { opened ->
            favoriteService.toggleNode(opened.lease.commandTarget, node.id)
        }
    }

}
