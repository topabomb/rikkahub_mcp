package net.weero.measix.pilot.ui.pages.chat

import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import androidx.core.net.toUri
import com.dokar.sonner.ToastType
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ModelType
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.appTempFolder
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.Menu03
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.hugeicons.stroke.PanelLeftOpen
import net.weero.measix.pilot.R
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.imggen.ImageGenerationSelectionResolver
import net.weero.measix.pilot.data.datastore.findProvider
import net.weero.measix.pilot.data.datastore.getAssistantById
import net.weero.measix.pilot.data.datastore.getChatModel
import net.weero.measix.pilot.data.datastore.getConversationAssistant
import net.weero.measix.pilot.service.ArtifactUseCase
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.repository.MemoryRepository
import net.weero.measix.pilot.service.workspace.WorkspaceQueryService
import net.weero.measix.pilot.service.ChatError
import net.weero.measix.pilot.service.ConversationReadState
import net.weero.measix.pilot.service.runtime.ConversationPresentation
import net.weero.measix.pilot.service.runtime.ConversationSnapshot
import net.weero.measix.pilot.ui.theme.ProvideChatSurfacePolicy
import net.weero.measix.pilot.ui.adaptive.AdaptiveLayoutDefaults
import net.weero.measix.pilot.ui.adaptive.AdaptiveModal
import net.weero.measix.pilot.ui.adaptive.ChatLayoutMode
import net.weero.measix.pilot.ui.adaptive.LocalAdaptiveLayoutInfo
import net.weero.measix.pilot.ui.components.ai.AssistantPickerSheet
import net.weero.measix.pilot.ui.components.ui.UIAvatar
import net.weero.measix.pilot.ui.components.ai.ChatInput
import net.weero.measix.pilot.ui.components.ai.FilesPicker
import net.weero.measix.pilot.ui.components.ai.WorkspaceSelectSheet
import net.weero.measix.pilot.ui.components.ai.rememberModelListState
import net.weero.measix.pilot.ui.components.ai.completion.WorkspaceCompletionProvider
import net.weero.measix.pilot.ui.components.ai.useCropLauncher
import net.weero.measix.pilot.ui.components.ui.permission.PermissionCamera
import net.weero.measix.pilot.ui.components.ui.permission.PermissionManager
import net.weero.measix.pilot.ui.components.ui.permission.rememberPermissionState
import net.weero.measix.pilot.ui.context.LocalNavController
import net.weero.measix.pilot.ui.context.LocalToaster
import net.weero.measix.pilot.ui.context.Navigator
import net.weero.measix.pilot.ui.hooks.ChatInputState
import net.weero.measix.pilot.ui.hooks.EditStateContent
import net.weero.measix.pilot.ui.hooks.rememberSharedPreferenceBoolean
import net.weero.measix.pilot.ui.hooks.useEditState
import net.weero.measix.pilot.ui.pages.assistant.detail.mergeAssistantDelta
import net.weero.measix.pilot.utils.ImageUtils
import net.weero.measix.pilot.utils.base64Decode
import net.weero.measix.pilot.utils.isAllowedFileType
import net.weero.measix.pilot.utils.navigateToChatPage
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import java.io.File
import kotlin.uuid.Uuid

@Composable
fun ChatPage(id: Uuid, text: String?, files: List<Uri>, nodeId: Uuid? = null) {
    val vm: ChatVM = koinViewModel(
        parameters = {
            parametersOf(id.toString())
        }
    )
    val artifactUseCase: ArtifactUseCase = koinInject()
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val fileReadFailedFormat = stringResource(R.string.chat_input_file_read_failed)

    val setting by vm.settings.collectAsStateWithLifecycle()
    val conversationState by vm.conversationState.collectAsStateWithLifecycle()
    val currentSnapshot = when (val state = conversationState) {
        ConversationReadState.Loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return
        }
        ConversationReadState.Missing -> {
            ConversationUnavailable(
                title = stringResource(R.string.chat_conversation_missing_title),
                message = stringResource(R.string.chat_conversation_missing_message),
                onRetry = vm::retryConversationLoad,
            )
            return
        }
        is ConversationReadState.Failed -> {
            val diagnostic = state.error.message ?: state.error::class.simpleName.orEmpty()
            ConversationUnavailable(
                title = stringResource(R.string.chat_conversation_load_failed_title),
                message = stringResource(R.string.chat_conversation_load_failed_message, diagnostic),
                onRetry = vm::retryConversationLoad,
            )
            return
        }
        is ConversationReadState.Ready -> state.snapshot
    }
    val turnPresentation by vm.turnPresentation.collectAsStateWithLifecycle()
    val enableWebSearch by vm.enableWebSearch.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()
    val favoriteNodeIds by vm.favoriteNodeIds.collectAsStateWithLifecycle()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val softwareKeyboardController = LocalSoftwareKeyboardController.current

    // Handle back press when drawer is open
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch {
            drawerState.close()
        }
    }

    // Hide keyboard when drawer is open
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) {
            softwareKeyboardController?.hide()
        }
    }

    val adaptiveLayoutInfo = LocalAdaptiveLayoutInfo.current
    val usesListDetailLayout = adaptiveLayoutInfo.chatLayoutMode == ChatLayoutMode.ListDetail
    // 在宽屏下会话侧栏可折叠；折叠后由 AnimatedPane 播放滑出动画，而非硬切换组合。
    var sidebarExpanded by rememberSharedPreferenceBoolean("chat_sidebar_expanded", true)
    val canCollapseSidebar = adaptiveLayoutInfo.canCollapseChatSidebar
    // 不可折叠时侧栏始终展开（如铰链双屏），可折叠时由 sidebarExpanded 决定。
    val sidebarVisible = usesListDetailLayout && (!canCollapseSidebar || sidebarExpanded)

    // 修复平板横竖屏旋转后模态抽屉残留问题
    LaunchedEffect(usesListDetailLayout) {
        if (usesListDetailLayout && drawerState.isOpen) {
            drawerState.close()
        }
    }

    val inputState = vm.inputState

    // 初始化输入状态（处理传入的 files 和 text 参数）
    LaunchedEffect(files, text) {
        if (files.isNotEmpty()) {
            try {
                val imported = vm.artifactDraftScope.importUrisOrThrow(files)
                inputState.messageContent = imported.mapNotNull { artifact ->
                    when {
                        artifact.mimeType.startsWith("image/") -> UIMessagePart.Image(url = artifact.uri.toString())
                        artifact.mimeType.startsWith("video/") -> UIMessagePart.Video(url = artifact.uri.toString())
                        artifact.mimeType.startsWith("audio/") -> UIMessagePart.Audio(url = artifact.uri.toString())
                        else -> null
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val displayName = artifactUseCase.displayName(files.first())
                    ?: files.first().lastPathSegment
                    ?: files.first().toString()
                Log.e("ChatPage", "Failed to import initial attachment", error)
                toaster.show(fileReadFailedFormat.format(displayName), type = ToastType.Error)
            }
        }
        text?.base64Decode()?.let { decodedText ->
            if (decodedText.isNotEmpty()) {
                inputState.setMessageText(decodedText)
            }
        }
    }

    val chatListState = rememberLazyListState()
    LaunchedEffect(nodeId, currentSnapshot.nodes.size) {
        if (!vm.chatListInitialized && currentSnapshot.nodes.isNotEmpty()) {
            if (nodeId != null) {
                val index = currentSnapshot.nodes.indexOfFirst { it.id == nodeId }
                if (index >= 0) {
                    chatListState.scrollToItem(index)
                }
            } else {
                chatListState.requestScrollToItem(currentSnapshot.currentMessages().size + 5)
            }
            vm.chatListInitialized = true
        }
    }

    val pageLayout: @Composable () -> Unit = {
        when (adaptiveLayoutInfo.chatLayoutMode) {
            ChatLayoutMode.ListDetail -> {
            // 侧栏宽度在 0 与目标值之间平滑动画；折叠时连续收窄到 0，展开时连续展开到目标宽度，
            // 配合 clipToBounds 裁剪溢出内容，避免 AnimatedVisibility"先占位再消失"的突兀感。
            val sidebarTargetWidth = if (canCollapseSidebar && !sidebarExpanded) {
                0.dp
            } else {
                adaptiveLayoutInfo.listPaneWidth
            }
            val sidebarWidth by animateDpAsState(
                targetValue = sidebarTargetWidth,
                animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy),
                label = "chatSidebarWidth",
            )
                Row(Modifier.fillMaxSize()) {
                    Surface(
                        modifier = Modifier
                            .width(sidebarWidth.coerceAtLeast(0.dp))
                            .fillMaxHeight()
                            .clipToBounds(),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        if (sidebarWidth > 0.dp) {
                            ChatDrawerContent(
                                navController = navController,
                                currentConversationId = currentSnapshot.conversationId,
                                vm = vm,
                                settings = setting,
                                permanent = true,
                                onCollapse = if (canCollapseSidebar) {
                                    { sidebarExpanded = false }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                    if (adaptiveLayoutInfo.verticalHingeSpacerWidth > 0.dp) {
                        Spacer(Modifier.width(adaptiveLayoutInfo.verticalHingeSpacerWidth))
                    }
                    // 聊天详情区从真实铰链右边界开始；平面宽屏则占满固定侧栏后的剩余宽度。
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        ChatPageContent(
                            inputState = inputState,
                            turnPresentation = turnPresentation,
                            setting = setting,
                            snapshot = currentSnapshot,
                            favoriteNodeIds = favoriteNodeIds,
                            drawerState = drawerState,
                            navController = navController,
                            vm = vm,
                            chatListState = chatListState,
                            enableWebSearch = enableWebSearch,
                            navigationAction = if (canCollapseSidebar && !sidebarExpanded) {
                                ChatNavigationAction.ExpandSidebar
                            } else {
                                ChatNavigationAction.None
                            },
                            onNavigationClick = { sidebarExpanded = true },
                            errors = errors,
                            onDismissError = { vm.dismissError(it) },
                            onClearAllErrors = { vm.clearAllErrors() },
                        )
                    }
                }
            }

            ChatLayoutMode.SinglePane -> {
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        ChatDrawerContent(
                            navController = navController,
                            currentConversationId = currentSnapshot.conversationId,
                            vm = vm,
                            settings = setting,
                            navigateFromDrawer = { navigate ->
                                scope.launch {
                                    drawerState.close()
                                    navigate()
                                }
                            },
                        )
                    }
                ) {
                    ChatPageContent(
                        inputState = inputState,
                        turnPresentation = turnPresentation,
                        setting = setting,
                        snapshot = currentSnapshot,
                        favoriteNodeIds = favoriteNodeIds,
                        drawerState = drawerState,
                        navController = navController,
                        vm = vm,
                        chatListState = chatListState,
                        enableWebSearch = enableWebSearch,
                        navigationAction = ChatNavigationAction.OpenDrawer,
                        errors = errors,
                        onDismissError = { vm.dismissError(it) },
                        onClearAllErrors = { vm.clearAllErrors() },
                    )
                }
            }
        }
    }

    val tabletopContentHeight = adaptiveLayoutInfo.tabletopContentHeight
    if (tabletopContentHeight != null) {
        Box(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(tabletopContentHeight)
                    .clipToBounds(),
            ) {
                pageLayout()
            }
        }
    } else {
        pageLayout()
    }
}

@Composable
private fun ConversationUnavailable(
    title: String,
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onRetry) {
            Text(stringResource(R.string.application_recovery_retry))
        }
    }
}

@Composable
private fun ChatPageContent(
    inputState: ChatInputState,
    turnPresentation: ConversationPresentation,
    setting: Settings,
    navigationAction: ChatNavigationAction,
    snapshot: ConversationSnapshot,
    favoriteNodeIds: Set<Uuid>,
    drawerState: DrawerState,
    navController: Navigator,
    vm: ChatVM,
    chatListState: LazyListState,
    enableWebSearch: Boolean,
    errors: List<ChatError>,
    onNavigationClick: (() -> Unit)? = null,
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val latestSnapshot by rememberUpdatedState(snapshot)
    val latestTurnPresentation by rememberUpdatedState(turnPresentation)
    val imeInsets = WindowInsets.ime
    val density = LocalDensity.current
    var appendScrollJob by remember(snapshot.conversationId) { mutableStateOf<Job?>(null) }
    DisposableEffect(snapshot.conversationId) {
        onDispose { appendScrollJob?.cancel() }
    }
    val toaster = LocalToaster.current
    val workspaceQueryService: WorkspaceQueryService = koinInject()
    val workspaces by workspaceQueryService.observeWorkspaces()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var previewMode by rememberSaveable { mutableStateOf(false) }
    val hazeState = rememberHazeState()
    val assistant = setting.getConversationAssistant(snapshot.header.assistantId)
    var showFilesSheet by remember { mutableStateOf(false) }
    var showWorkspaceSheet by remember { mutableStateOf(false) }
    var showAssistantPicker by remember { mutableStateOf(false) }
    val workspaceNamesById = remember(workspaces) {
        workspaces.mapNotNull { workspace ->
            runCatching { Uuid.parse(workspace.id) }
                .getOrNull()
                ?.let { it to workspace.name }
        }.toMap()
    }
    val memoryRepository: MemoryRepository = koinInject()
    val memoryCountFlow = remember(assistant.id, assistant.enableMemory, assistant.useGlobalMemory) {
        if (assistant.enableMemory) {
            if (assistant.useGlobalMemory) {
                memoryRepository.getGlobalMemoriesFlow()
            } else {
                memoryRepository.getMemoriesOfAssistantFlow(assistant.id.toString())
            }.map { it.size }
        } else {
            emptyFlow()
        }
    }
    val memoryCount by memoryCountFlow.collectAsStateWithLifecycle(initialValue = 0)
    val imageSelectionResolver: ImageGenerationSelectionResolver = koinInject()
    val imageGenerationAvailable = remember(setting) { imageSelectionResolver.isAvailable(setting) }
    val readiness = remember(setting, assistant, workspaceNamesById, memoryCount, imageGenerationAvailable) {
        setting.buildConversationReadiness(
            assistant = assistant,
            workspaceNamesById = workspaceNamesById,
            memoryCount = memoryCount,
            imageGenerationAvailable = imageGenerationAvailable,
        )
    }
    val latestReadiness by rememberUpdatedState(readiness)
    val latestAllowConversationSystemPrompt by rememberUpdatedState(
        setting.getAssistantById(snapshot.header.assistantId)?.allowConversationSystemPrompt == true,
    )
    val modelListState = rememberModelListState(
        modelId = assistant.chatModelId ?: setting.chatModelId,
        providers = setting.providers,
        type = ModelType.CHAT,
    )
    val modelRequiredMessage = stringResource(R.string.chat_readiness_model_required_toast)

    fun requestAppendScroll(requestContext: AppendScrollContext) {
        appendScrollJob?.cancel()
        appendScrollJob = scope.launch {
            chatListState.scrollToAppendedItem(
                requestContext = requestContext,
                currentSnapshot = { latestSnapshot },
                currentPresentation = { latestTurnPresentation },
                expectedItemCount = { current ->
                    expectedChatListItemCount(
                        snapshot = current,
                        loading = latestTurnPresentation.isActive,
                        readiness = latestReadiness,
                        allowConversationSystemPrompt = latestAllowConversationSystemPrompt,
                    )
                },
                currentImeBottom = { imeInsets.getBottom(density) },
            )
        }
    }

    val completionProviders = remember(assistant.workspaceId, snapshot.header.workspaceCwd, workspaceQueryService) {
        assistant.workspaceId?.let { workspaceId ->
            listOf(
                WorkspaceCompletionProvider(
                    workspaceId = workspaceId.toString(),
                    queryService = workspaceQueryService,
                    currentCwd = snapshot.header.workspaceCwd,
                )
            )
        }.orEmpty()
    }

    TTSAutoPlay(vm = vm, setting = setting, snapshot = snapshot)

    ProvideChatSurfacePolicy(
        assistant = assistant,
        displaySetting = setting.displaySetting,
    ) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) {
        AssistantBackground(assistant = assistant, modifier = Modifier.hazeSource(hazeState))
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            topBar = {
                TopBar(
                    settings = setting,
                    assistant = assistant,
                    snapshot = snapshot,
                    navigationAction = navigationAction,
                    onNavigationClick = onNavigationClick ?: {
                        scope.launch { drawerState.open() }
                        Unit
                    },
                    loading = turnPresentation.isActive,
                    previewMode = previewMode,
                    onNewChat = {
                        navigateToChatPage(navController)
                    },
                    onClickMenu = {
                        previewMode = !previewMode
                    },
                    onUpdateTitle = {
                        vm.updateTitle(it)
                    }
                )
            },
            bottomBar = {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    ChatInput(
                        modifier = Modifier
                            .widthIn(max = AdaptiveLayoutDefaults.ReadableContentMaxWidth)
                            .fillMaxWidth(),
                        state = inputState,
                        artifactDraftScope = vm.artifactDraftScope,
                        loading = turnPresentation.isActive,
                        settings = setting,
                        assistant = assistant,
                        modelListState = modelListState,
                        hazeState = hazeState,
                        completionProviders = completionProviders,
                        onCancelClick = {
                            vm.stopGeneration()
                        },
                        enableSearch = enableWebSearch,
                        onUpdateSearchMode = { mode ->
                            vm.updateSearchMode(
                                assistantId = assistant.id,
                                model = setting.getChatModel(assistant),
                                mode = mode,
                            )
                        },
                        onSendClick = {
                            if (!readiness.canSend) {
                                toaster.show(modelRequiredMessage, type = ToastType.Error)
                                return@ChatInput
                            }
                            if (inputState.isEditing()) {
                                vm.handleMessageEdit(
                                    parts = inputState.getContents(),
                                    messageId = inputState.editingMessage!!,
                                )
                            } else {
                                val contents = inputState.getContents()
                                scope.launch {
                                    vm.handleMessageSend(contents)?.let { receipt ->
                                        inputState.clearInput()
                                        requestAppendScroll(
                                            AppendScrollContext.from(
                                                snapshot = snapshot,
                                                targetMessageId = receipt.userMessageId,
                                                turnId = receipt.turnId,
                                            ),
                                        )
                                    }
                                }
                                return@ChatInput
                            }
                            inputState.clearInput()
                        },
                        onLongSendClick = {
                            if (!readiness.canSend) {
                                toaster.show(modelRequiredMessage, type = ToastType.Error)
                                return@ChatInput
                            }
                            if (inputState.isEditing()) {
                                vm.handleMessageEdit(
                                    parts = inputState.getContents(),
                                    messageId = inputState.editingMessage!!,
                                )
                            } else {
                                val contents = inputState.getContents()
                                scope.launch {
                                    vm.handleMessageSend(content = contents, answer = false)?.let { receipt ->
                                        inputState.clearInput()
                                        requestAppendScroll(
                                            AppendScrollContext.from(
                                                snapshot = snapshot,
                                                targetMessageId = receipt.userMessageId,
                                                turnId = receipt.turnId,
                                            ),
                                        )
                                    }
                                }
                                return@ChatInput
                            }
                            inputState.clearInput()
                        },
                        onUpdateChatModel = {
                            vm.setChatModel(assistant = assistant, model = it)
                        },
                        onUpdateAssistant = { updatedAssistant ->
                            vm.updateSettings { current ->
                                current.copy(
                                    assistants = current.assistants.map { latestAssistant ->
                                        if (latestAssistant.id == updatedAssistant.id) {
                                            mergeAssistantDelta(assistant, updatedAssistant, latestAssistant)
                                        } else {
                                            latestAssistant
                                        }
                                    }
                                )
                            }
                        },
                        onUpdateSearchService = { serviceId ->
                            vm.updateSettings { it.copy(selectedSearchServiceId = serviceId) }
                        },
                        onMoreClick = {
                            showFilesSheet = true
                        },
                    )
                }
            },
            containerColor = Color.Transparent,
        ) { innerPadding ->
            ChatList(
                innerPadding = innerPadding,
                snapshot = snapshot,
                favoriteNodeIds = favoriteNodeIds,
                state = chatListState,
                turnPresentation = turnPresentation,
                attachmentPreviews = vm.attachmentPreviews(snapshot),
                previewMode = previewMode,
                settings = setting,
                readiness = readiness,
                assistant = assistant,
                hazeState = hazeState,
                errors = errors,
                onDismissError = onDismissError,
                onClearAllErrors = onClearAllErrors,
                onRegenerate = {
                    vm.regenerateAtMessage(it)
                },
                onEdit = {
                    inputState.editingMessage = it.id
                    inputState.setContents(it.parts)
                },
                onForkMessage = {
                    scope.launch {
                        val fork = vm.forkMessage(message = it)
                        navigateToChatPage(navController, chatId = fork)
                    }
                },
                onDelete = {
                    if (turnPresentation.isActive) {
                        vm.showDeleteBlockedWhileGeneratingError()
                    } else {
                        vm.deleteMessage(it)
                    }
                },
                onShowTerminalError = vm::showTerminalError,
                onUpdateMessage = { newNode ->
                    vm.selectNode(newNode.id, newNode.selectIndex)
                },
                onClickSuggestion = { suggestion ->
                    inputState.editingMessage = null
                    inputState.setMessageText(suggestion)
                },
                onJumpToMessage = { index ->
                    previewMode = false
                    scope.launch {
                        chatListState.requestScrollToItem(index)
                    }
                },
                onToolApproval = { locator, approved, reason ->
                    vm.handleToolApproval(locator, approved, reason)
                },
                onToolAnswer = { locator, answer ->
                    vm.handleToolAnswer(locator, answer)
                },
                onSubAssistantAnswer = { runId, interactionId, answer ->
                    vm.handleSubAssistantAnswer(runId, interactionId, answer)
                },
                onToggleFavorite = { node ->
                    vm.toggleMessageFavorite(node)
                },
                onConversationSystemPromptChange = { newPrompt ->
                    vm.updateCustomSystemPrompt(newPrompt)
                },
                onProviderConfigClick = {
                    navController.navigate(Screen.SettingProvider)
                },
                onReadinessModelClick = {
                    modelListState.open()
                },
                onReadinessMcpClick = {
                    if (
                        readiness.mcpState == McpReadiness.NOT_CONFIGURED ||
                        readiness.mcpState == McpReadiness.ALL_DISABLED
                    ) {
                        navController.navigate(Screen.SettingMcp)
                    } else {
                        navController.navigate(Screen.AssistantMcp(assistant.id.toString()))
                    }
                },
                onReadinessLocalToolsClick = {
                    navController.navigate(Screen.AssistantLocalTool(assistant.id.toString()))
                },
                onReadinessWorkspaceClick = {
                    showWorkspaceSheet = true
                },
                onSwitchAssistant = { showAssistantPicker = true },
                onManageAssistant = {
                    navController.navigate(Screen.AssistantDetail(assistant.id.toString()))
                },
                onMemoryClick = {
                    navController.navigate(Screen.AssistantMemory(assistant.id.toString()))
                },
            )
        }

        if (showFilesSheet) {
            ChatFilesPickerSheet(
                inputState = inputState,
                setting = setting,
                snapshot = snapshot,
                assistant = assistant,
                vm = vm,
                onDismiss = { showFilesSheet = false },
            )
        }

        if (showAssistantPicker) {
            AssistantPickerSheet(
                settings = setting,
                currentAssistant = assistant,
                onAssistantSelected = { newAssistant ->
                    showAssistantPicker = false
                    vm.moveConversationToAssistant(newAssistant.id)
                },
                onDismiss = { showAssistantPicker = false },
            )
        }

        if (showWorkspaceSheet) {
            WorkspaceSelectSheet(
                assistant = assistant,
                workspaces = workspaces,
                onSelect = { workspaceId ->
                    val selectedWorkspaceId = workspaceId?.let { Uuid.parse(it) }
                    if (selectedWorkspaceId != assistant.workspaceId) {
                        vm.updateSettings { current ->
                            current.copy(
                                assistants = current.assistants.map {
                                    if (it.id == assistant.id) it.copy(workspaceId = selectedWorkspaceId) else it
                                }
                            )
                        }
                        if (snapshot.header.workspaceCwd != null) {
                            vm.updateWorkspaceCwd(null)
                        }
                    }
                    showWorkspaceSheet = false
                },
                onManage = {
                    showWorkspaceSheet = false
                    navController.navigate(Screen.Workspaces)
                },
                onDismiss = {
                    showWorkspaceSheet = false
                },
            )
        }
    }
    }
}

private suspend fun LazyListState.scrollToAppendedItem(
    requestContext: AppendScrollContext,
    currentSnapshot: () -> ConversationSnapshot,
    currentPresentation: () -> ConversationPresentation,
    expectedItemCount: (ConversationSnapshot) -> Int,
    currentImeBottom: () -> Int,
) {
    var activeRequestObserved = false
    val readyItemCount = snapshotFlow {
        val snapshot = currentSnapshot()
        AppendScrollObservation(
            snapshot = snapshot,
            presentation = currentPresentation(),
            actualItemCount = layoutInfo.totalItemsCount,
            expectedItemCount = expectedItemCount(snapshot),
            imeBottom = currentImeBottom(),
        )
    }.map { observation ->
        activeRequestObserved = activeRequestObserved ||
            observation.presentation.activeRequestTurnId == requestContext.turnId
        val status = evaluateAppendScroll(
            requestContext = requestContext,
            snapshot = observation.snapshot,
            presentation = observation.presentation,
            activeRequestObserved = activeRequestObserved,
            actualItemCount = observation.actualItemCount,
            expectedItemCount = observation.expectedItemCount,
            imeBottom = observation.imeBottom,
        )
        if (status == AppendScrollStatus.INVALIDATED) {
            throw CancellationException("Conversation branch changed")
        }
        status to observation.actualItemCount
    }.first { (status, _) -> status == AppendScrollStatus.READY }.second

    requestScrollToItem(readyItemCount - 1)
    snapshotFlow { !canScrollForward }.first { it }
}

internal enum class AppendScrollStatus {
    WAITING_FOR_APPEND,
    WAITING_FOR_LAYOUT,
    WAITING_FOR_IME,
    READY,
    INVALIDATED,
}

private data class AppendScrollObservation(
    val snapshot: ConversationSnapshot,
    val presentation: ConversationPresentation,
    val actualItemCount: Int,
    val expectedItemCount: Int,
    val imeBottom: Int,
)

internal fun evaluateAppendScroll(
    requestContext: AppendScrollContext,
    snapshot: ConversationSnapshot,
    presentation: ConversationPresentation,
    actualItemCount: Int,
    expectedItemCount: Int,
    imeBottom: Int,
    activeRequestObserved: Boolean = false,
): AppendScrollStatus = when {
    !requestContext.matches(snapshot) -> AppendScrollStatus.INVALIDATED
    activeRequestObserved && !requestContext.ownsActiveRequest(presentation) &&
        !requestContext.hasTargetMessage(snapshot) -> AppendScrollStatus.INVALIDATED
    !requestContext.hasTargetMessage(snapshot) -> AppendScrollStatus.WAITING_FOR_APPEND
    actualItemCount != expectedItemCount -> AppendScrollStatus.WAITING_FOR_LAYOUT
    imeBottom != 0 -> AppendScrollStatus.WAITING_FOR_IME
    else -> AppendScrollStatus.READY
}

internal fun expectedChatListItemCount(
    snapshot: ConversationSnapshot,
    loading: Boolean,
    readiness: ConversationReadiness,
    allowConversationSystemPrompt: Boolean,
): Int {
    val nodeCount = snapshot.renderNodes.size
    val configurationItemCount = if (
        nodeCount == 0 || readiness.requiresProviderConfiguration || !readiness.canSend
    ) {
        1
    } else {
        0
    }
    val systemPromptItemCount = if (!loading && allowConversationSystemPrompt) 1 else 0
    val loadingItemCount = if (loading) 1 else 0
    val bottomSentinelItemCount = 1
    return configurationItemCount + nodeCount + systemPromptItemCount + loadingItemCount + bottomSentinelItemCount
}

internal data class AppendScrollContext(
    val conversationId: Uuid,
    val turnId: Uuid,
    val targetMessageId: Uuid,
    val existingNodes: List<Pair<Uuid, Int>>,
) {
    fun matches(snapshot: ConversationSnapshot): Boolean =
        snapshot.conversationId == conversationId &&
                snapshot.nodes.size >= existingNodes.size &&
                snapshot.nodes.take(existingNodes.size).map { it.id to it.selectIndex } == existingNodes

    fun ownsActiveRequest(presentation: ConversationPresentation): Boolean =
        presentation.activeRequestTurnId == turnId

    fun hasTargetMessage(snapshot: ConversationSnapshot): Boolean =
        snapshot.nodes.any { node -> node.messages.any { it.id == targetMessageId } }

    companion object {
        fun from(
            snapshot: ConversationSnapshot,
            targetMessageId: Uuid,
            turnId: Uuid,
        ) = AppendScrollContext(
            conversationId = snapshot.conversationId,
            turnId = turnId,
            targetMessageId = targetMessageId,
            existingNodes = snapshot.nodes.map { it.id to it.selectIndex },
        )
    }
}

@Composable
private fun ChatFilesPickerSheet(
    inputState: ChatInputState,
    setting: Settings,
    snapshot: ConversationSnapshot,
    assistant: Assistant,
    vm: ChatVM,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val artifactUseCase: ArtifactUseCase = koinInject()
    val scope = rememberCoroutineScope()
    var showInjectionSheet by remember { mutableStateOf(false) }
    var showCompressDialog by remember { mutableStateOf(false) }
    val fileReadFailedFormat = stringResource(R.string.chat_input_file_read_failed)
    val unsupportedFileTypeFormat = stringResource(R.string.chat_input_unsupported_file_type)

    fun dismissAll() {
        showInjectionSheet = false
        showCompressDialog = false
        onDismiss()
    }

    fun attachmentDisplayName(uri: Uri): String = artifactUseCase.displayName(uri)
        ?: uri.lastPathSegment
        ?: uri.toString()

    suspend fun importAttachments(uris: List<Uri>): List<Uri>? = try {
        vm.artifactDraftScope.importUrisOrThrow(uris)
            .map { it.uri }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        val displayName = uris.firstOrNull()?.let(::attachmentDisplayName).orEmpty()
        Log.e("ChatFilesPicker", "Failed to import attachment", error)
        toaster.show(fileReadFailedFormat.format(displayName), type = ToastType.Error)
        null
    }

    fun deleteTemporaryFile(uri: Uri) {
        try {
            val file = uri.toFile()
            if (file.exists() && !file.delete()) {
                Log.w("ChatFilesPicker", "Failed to delete temporary file: $file")
            }
        } catch (error: RuntimeException) {
            Log.w("ChatFilesPicker", "Failed to delete temporary URI: $uri", error)
        }
    }

    val cameraPermission = rememberPermissionState(PermissionCamera)
    PermissionManager(permissionState = cameraPermission)

    var cameraOutputUri by remember { mutableStateOf<Uri?>(null) }
    var cameraOutputFile by remember { mutableStateOf<File?>(null) }
    val (_, launchCameraCrop) = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            scope.launch {
                try {
                    importAttachments(listOf(croppedUri))?.let { imported ->
                        inputState.addImages(imported)
                        dismissAll()
                    }
                } finally {
                    // CropLauncher 将输出文件交给此回调；登记成功、失败或取消后均由此处收口。
                    deleteTemporaryFile(croppedUri)
                }
            }
        },
        onCleanup = {
            cameraOutputFile?.delete()
            cameraOutputFile = null
            cameraOutputUri = null
        }
    )
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captureSuccessful ->
        if (captureSuccessful && cameraOutputUri != null) {
            if (setting.displaySetting.skipCropImage) {
                scope.launch {
                    val source = checkNotNull(cameraOutputUri)
                    try {
                        importAttachments(listOf(source))?.let { imported ->
                            inputState.addImages(imported)
                            dismissAll()
                        }
                    } finally {
                        cameraOutputFile?.delete()
                        cameraOutputFile = null
                        cameraOutputUri = null
                    }
                }
            } else {
                launchCameraCrop(cameraOutputUri!!)
            }
        } else {
            cameraOutputFile?.delete()
            cameraOutputFile = null
            cameraOutputUri = null
        }
    }
    val onLaunchCamera: () -> Unit = {
        if (cameraPermission.allRequiredPermissionsGranted) {
            cameraOutputFile = context.cacheDir.resolve("camera_${Uuid.random()}.jpg")
            cameraOutputUri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", cameraOutputFile!!
            )
            cameraLauncher.launch(cameraOutputUri!!)
        } else {
            cameraPermission.requestPermissions()
        }
    }

    var preCropTempFile by remember { mutableStateOf<File?>(null) }
    val (_, launchImageCrop) = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            scope.launch {
                try {
                    importAttachments(listOf(croppedUri))?.let { imported ->
                        inputState.addImages(imported)
                        dismissAll()
                    }
                } finally {
                    // CropLauncher 将输出文件交给此回调；登记成功、失败或取消后均由此处收口。
                    deleteTemporaryFile(croppedUri)
                }
            }
        },
        onCleanup = {
            preCropTempFile?.delete()
            preCropTempFile = null
        }
    )
    val imagePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                Log.d("ImagePickButton", "Selected URIs: $selectedUris")
                if (setting.displaySetting.skipCropImage) {
                    scope.launch {
                        importAttachments(selectedUris)?.let { imported ->
                            inputState.addImages(imported)
                            dismissAll()
                        }
                    }
                } else if (selectedUris.size == 1) {
                    val tempFile = File(context.appTempFolder, "pick_temp_${System.currentTimeMillis()}.jpg")
                    try {
                        val source = selectedUris.first()
                        // HEIF/HEIC（尤其 HDR HEIF）交给 UCrop 前先解码转为 JPEG，规避裁剪解码失败
                        val converted = ImageUtils.isHeifImage(context, source) &&
                            ImageUtils.convertHeifToJpeg(context, source, tempFile)
                        if (!converted) {
                            checkNotNull(context.contentResolver.openInputStream(source)).use { input ->
                                tempFile.outputStream().use { output -> input.copyTo(output) }
                            }
                        }
                        preCropTempFile = tempFile
                        launchImageCrop(tempFile.toUri())
                    } catch (error: Exception) {
                        tempFile.delete()
                        Log.w("ImagePickButton", "Failed to prepare local crop source; using selected URI", error)
                        launchImageCrop(selectedUris.first())
                    }
                } else {
                    scope.launch {
                        importAttachments(selectedUris)?.let { imported ->
                            inputState.addImages(imported)
                            dismissAll()
                        }
                    }
                }
            } else {
                Log.d("ImagePickButton", "No images selected")
            }
        }

    val videoPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                scope.launch {
                    importAttachments(selectedUris)?.let { imported ->
                        inputState.addVideos(imported)
                        dismissAll()
                    }
                }
            }
        }

    val audioPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                scope.launch {
                    importAttachments(selectedUris)?.let { imported ->
                        inputState.addAudios(imported)
                        dismissAll()
                    }
                }
            }
        }

    val filePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                scope.launch {
                    val accepted = uris.mapNotNull { uri ->
                        val fileName = attachmentDisplayName(uri)
                        val mime = artifactUseCase.mimeType(uri) ?: "text/plain"
                        if (isAllowedFileType(fileName, mime)) {
                            Triple(uri, fileName, mime)
                        } else {
                            toaster.show(
                                unsupportedFileTypeFormat.format(fileName),
                                type = ToastType.Error
                            )
                            null
                        }
                    }
                    val imported = if (accepted.isEmpty()) {
                        null
                    } else {
                        importAttachments(accepted.map { it.first })
                    }
                    if (imported != null) {
                        val documents = accepted.zip(imported) { (_, fileName, mime), localUri ->
                            UIMessagePart.Document(
                                url = localUri.toString(),
                                fileName = fileName,
                                mime = mime,
                            )
                        }
                        inputState.addFiles(documents)
                        dismissAll()
                    }
                }
            }
        }

    AdaptiveModal(
        onDismissRequest = { dismissAll() },
        dialogMaxHeight = 800.dp,
    ) {
        FilesPicker(
            conversationModeInjectionIds = snapshot.header.modeInjectionIds,
            messageNodeCount = snapshot.nodes.size,
            workspaceCwd = snapshot.header.workspaceCwd,
            state = inputState,
            assistant = assistant,
            mcpManager = vm.mcpManager,
            onCompressContext = { additionalPrompt, targetTokens, keepRecentMessages ->
                vm.handleCompressContext(additionalPrompt, targetTokens, keepRecentMessages)
            },
            onUpdateAssistant = { updatedAssistant ->
                vm.updateSettings { current ->
                    current.copy(
                        assistants = current.assistants.map { latestAssistant ->
                            if (latestAssistant.id == updatedAssistant.id) {
                                mergeAssistantDelta(assistant, updatedAssistant, latestAssistant)
                            } else {
                                latestAssistant
                            }
                        }
                    )
                }
            },
            onUpdateConversationModeInjectionIds = { ids ->
                vm.updateModeInjectionIds(ids)
            },
            onUpdateWorkspaceCwd = { cwd ->
                vm.updateWorkspaceCwd(cwd)
            },
            showInjectionSheet = showInjectionSheet,
            onShowInjectionSheetChange = { showInjectionSheet = it },
            showCompressDialog = showCompressDialog,
            onShowCompressDialogChange = { showCompressDialog = it },
            onDismiss = { dismissAll() },
            onTakePic = onLaunchCamera,
            onPickImage = { imagePickerLauncher.launch("image/*") },
            onPickVideo = { videoPickerLauncher.launch("video/*") },
            onPickAudio = { audioPickerLauncher.launch("audio/*") },
            onPickFile = { filePickerLauncher.launch(arrayOf("*/*")) },
        )
    }
}

private enum class ChatNavigationAction {
    None,
    OpenDrawer,
    ExpandSidebar,
}

@Composable
private fun TopBar(
    settings: Settings,
    assistant: Assistant,
    snapshot: ConversationSnapshot,
    navigationAction: ChatNavigationAction,
    onNavigationClick: () -> Unit,
    previewMode: Boolean,
    onClickMenu: () -> Unit,
    onNewChat: () -> Unit,
    onUpdateTitle: (String) -> Unit,
    loading: Boolean = false,
) {
    val toaster = LocalToaster.current
    val titleState = useEditState<String> {
        onUpdateTitle(it)
    }

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        navigationIcon = {
            when (navigationAction) {
                ChatNavigationAction.None -> Unit
                ChatNavigationAction.OpenDrawer -> {
                    IconButton(onClick = onNavigationClick) {
                        Icon(
                            imageVector = HugeIcons.Menu03,
                            contentDescription = stringResource(R.string.chat_navigation_open_drawer),
                        )
                    }
                }

                ChatNavigationAction.ExpandSidebar -> {
                    IconButton(onClick = onNavigationClick) {
                        Icon(
                            imageVector = HugeIcons.PanelLeftOpen,
                            contentDescription = stringResource(R.string.chat_sidebar_expand),
                        )
                    }
                }
            }
        },
        title = {
            val editTitleWarning = stringResource(R.string.chat_page_edit_title_warning)
            Surface(
                onClick = {
                    if (snapshot.nodes.isNotEmpty()) {
                        titleState.open(snapshot.header.title)
                    } else {
                        toaster.show(editTitleWarning, type = ToastType.Warning)
                    }
                },
                color = Color.Transparent,
            ) {
                val showAvatar = assistant.useAssistantAvatar &&
                    LocalAdaptiveLayoutInfo.current.chatLayoutMode == ChatLayoutMode.ListDetail
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (showAvatar) {
                        UIAvatar(
                            name = assistant.name,
                            value = assistant.avatar,
                            modifier = Modifier.size(40.dp),
                            loading = loading,
                        )
                    }
                    Column {
                        val model = settings.getChatModel(assistant)
                        val provider = model?.findProvider(providers = settings.providers, checkOverwrite = false)
                        Text(
                            text = snapshot.header.title.ifBlank { stringResource(R.string.chat_page_new_chat) },
                            maxLines = 1,
                            style = MaterialTheme.typography.titleMedium,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (model != null && provider != null) {
                            Text(
                                text = "${assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) }} / ${model.displayName} (${provider.name})",
                                overflow = TextOverflow.Ellipsis,
                                maxLines = 1,
                                color = LocalContentColor.current.copy(0.65f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        actions = {
            IconButton(
                onClick = {
                    onClickMenu()
                }
            ) {
                Icon(
                    if (previewMode) HugeIcons.Cancel01 else HugeIcons.LeftToRightListBullet,
                    stringResource(R.string.chat_page_chat_options)
                )
            }

            IconButton(
                onClick = {
                    onNewChat()
                }
            ) {
                Icon(HugeIcons.MessageAdd01, stringResource(R.string.chat_page_new_chat))
            }
        },
    )
    titleState.EditStateContent { title, onUpdate ->
        AlertDialog(
            onDismissRequest = {
                titleState.dismiss()
            },
            title = {
                Text(stringResource(R.string.chat_page_edit_title))
            },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = onUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        titleState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        titleState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }
}
