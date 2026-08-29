package net.weero.measix.pilot.ui.pages.imggen

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import net.weero.measix.pilot.ui.adaptive.AdaptiveModal
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import androidx.paging.LoadState
import coil3.compose.AsyncImage
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.ModelType
import me.rerere.ai.ui.ImageGenSize
import me.rerere.common.android.appTempFolder
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Colors
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.FloppyDisk
import me.rerere.hugeicons.stroke.Image03
import me.rerere.hugeicons.stroke.Tools
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.files.FileUtils
import net.weero.measix.pilot.service.MediaExportService
import net.weero.measix.pilot.data.imggen.ImageGenerationSelectionResolver
import net.weero.measix.pilot.data.imggen.imageGenerationFailureStringRes
import net.weero.measix.pilot.ui.components.ai.ModelListSheet
import net.weero.measix.pilot.ui.components.ai.ModelSelectorButton
import net.weero.measix.pilot.ui.components.ai.rememberModelListState
import net.weero.measix.pilot.ui.components.nav.BackButton
import net.weero.measix.pilot.ui.components.ui.FormItem
import net.weero.measix.pilot.ui.components.ui.ImagePreviewDialog
import net.weero.measix.pilot.ui.components.ui.ImagePreviewDeleteAction
import net.weero.measix.pilot.ui.components.ui.ImagePreviewDeleteResult
import net.weero.measix.pilot.ui.components.ui.OutlinedNumberInput
import net.weero.measix.pilot.ui.components.ui.rememberImageBackgroundHost
import net.weero.measix.pilot.ui.components.ui.shortGeneratedLabel
import net.weero.measix.pilot.ui.context.LocalToaster
import net.weero.measix.pilot.utils.ImageUtils
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.io.File
import kotlin.uuid.Uuid

@Composable
fun ImageGenPage(
    modifier: Modifier = Modifier,
    vm: ImgGenVM = koinViewModel()
) {
    val pagerState = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()

    // error Toast 收集器放在 Page 顶层: 停在 Gallery 页(第 1 页)时生成页不组合,
    // 收集器若留在 ImageGenScreen 会漏掉删除失败等错误提示
    val error by vm.error.collectAsStateWithLifecycle()
    val errorMessage = imageGenerationErrorMessage(error)
    val pageToaster = LocalToaster.current
    LaunchedEffect(error) {
        error?.let {
            pageToaster.show(message = errorMessage, type = ToastType.Error)
            vm.clearError()
        }
    }

    val isGenerating by vm.isGenerating.collectAsStateWithLifecycle()
    var showCancelDialog by remember { mutableStateOf(false) }
    BackHandler(isGenerating) {
        showCancelDialog = true
    }
    if (showCancelDialog) {
        CancelDialog(
            onDismiss = { showCancelDialog = false },
            onConfirm = {
                showCancelDialog = false
                vm.cancelGeneration()
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.imggen_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    IconButton(onClick = vm::startNewSession) {
                        Icon(
                            imageVector = HugeIcons.Add01,
                            contentDescription = "New session"
                        )
                    }
                }
            )
        },
        bottomBar = {
            BottomBar(pagerState, scope)
        },
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) { page ->
            when (page) {
                0 -> ImageGenScreen(vm = vm)
                1 -> ImageGalleryScreen(vm = vm)
            }
        }
    }
}

@Composable
private fun CancelDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.imggen_page_cancel_generation_title)) },
        text = { Text(stringResource(R.string.imggen_page_cancel_generation_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.imggen_page_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.imggen_page_cancel))
            }
        }
    )
}

@Composable
private fun BottomBar(
    pagerState: PagerState,
    scope: CoroutineScope
) {
    NavigationBar {
        NavigationBarItem(
            selected = 0 == pagerState.currentPage,
            label = {
                Text(stringResource(R.string.imggen_page_title))
            },
            icon = {
                Icon(HugeIcons.Colors, null)
            },
            onClick = {
                scope.launch {
                    pagerState.animateScrollToPage(0)
                }
            }
        )

        NavigationBarItem(
            selected = 1 == pagerState.currentPage,
            label = {
                Text(stringResource(R.string.imggen_page_gallery))
            },
            icon = {
                Icon(HugeIcons.Image03, null)
            },
            onClick = {
                scope.launch {
                    pagerState.animateScrollToPage(1)
                }
            }
        )
    }
}

@Composable
private fun ImageGenScreen(
    vm: ImgGenVM,
) {
    val prompt by vm.prompt.collectAsStateWithLifecycle()
    val numberOfImages by vm.numberOfImages.collectAsStateWithLifecycle()
    val size by vm.size.collectAsStateWithLifecycle()
    val isGenerating by vm.isGenerating.collectAsStateWithLifecycle()
    val currentGeneratedImages by vm.currentGeneratedImages.collectAsStateWithLifecycle()
    val referenceImages by vm.referenceImages.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showSettingsSheet by remember { mutableStateOf(false) }
    var previewIndex by remember { mutableStateOf(-1) }
    val backgroundHost = rememberImageBackgroundHost(settings)
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .imePadding()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            // 当次生成结果全量展示(1–4 张), 两两一行
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                currentGeneratedImages.mapIndexed { index, image ->
                    index to image
                }.chunked(2).forEach { rowImages ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowImages.forEach { (index, image) ->
                            AsyncImage(
                                model = File(image.filePath),
                                contentDescription = null,
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { previewIndex = index },
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }
            }
            if (isGenerating) {
                ContainedLoadingIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
        InputBar(
            prompt = prompt,
            vm = vm,
            isGenerating = isGenerating,
            referenceImages = referenceImages,
            settings = settings,
            onShowSettings = { showSettingsSheet = true },
            modifier = Modifier
        )
    }

    LaunchedEffect(previewIndex, currentGeneratedImages.isEmpty()) {
        if (previewIndex >= 0 && currentGeneratedImages.isEmpty()) previewIndex = -1
    }
    if (previewIndex >= 0 && currentGeneratedImages.isNotEmpty()) {
        // 统一 file:// 前缀, 与网格的 File model 共享 Coil 缓存键
        ImagePreviewDialog(
            images = currentGeneratedImages.map { "file://${it.filePath}" },
            onDismissRequest = { previewIndex = -1 },
            initialIndex = previewIndex,
            extraActions = listOf(backgroundHost.action),
            overlay = backgroundHost.overlay,
        )
    }

    if (showSettingsSheet) {
        SettingsBottomSheet(
            vm = vm,
            numberOfImages = numberOfImages,
            size = size,
            scope = scope,
            sheetState = sheetState,
            onDismiss = { showSettingsSheet = false }
        )
    }
}

@Composable
private fun InputBar(
    prompt: String,
    vm: ImgGenVM,
    isGenerating: Boolean,
    referenceImages: List<String>,
    settings: Settings,
    onShowSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val importFailed = stringResource(R.string.imggen_page_reference_import_failed)
    val addReferenceImage = stringResource(R.string.imggen_page_add_reference_image)
    val imagePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                scope.launch {
                    val created = mutableListOf<String>()
                    var failed = 0
                    try {
                        selectedUris.forEach { uri ->
                            val path = try {
                                withContext(Dispatchers.IO) {
                                    val bitmap = ImageUtils.loadOptimizedBitmap(context, uri, maxSize = 2048)
                                        ?: error("Failed to decode image")
                                    val pngBytes = try {
                                        FileUtils.compressBitmapToPng(bitmap)
                                    } finally {
                                        bitmap.recycle()
                                    }
                                    val file = File(context.appTempFolder, "imggen_ref_${Uuid.random()}.png")
                                    try {
                                        file.writeBytes(pngBytes)
                                        file.absolutePath
                                    } catch (error: Throwable) {
                                        file.delete()
                                        throw error
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                failed += 1
                                null
                            }
                            path?.let(created::add)
                        }
                        failed += vm.addReferenceImages(created)
                        created.clear()
                        if (failed > 0) toaster.show(importFailed, type = ToastType.Error)
                    } catch (cancelled: CancellationException) {
                        withContext(NonCancellable + Dispatchers.IO) {
                            created.forEach { File(it).delete() }
                        }
                        throw cancelled
                    }
                }
            }
        }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (referenceImages.isNotEmpty()) {
            ReferenceImagesRow(
                images = referenceImages,
                onRemove = vm::removeReferenceImage
            )
        }

        OutlinedTextField(
            value = prompt,
            onValueChange = vm::updatePrompt,
            placeholder = { Text(stringResource(R.string.imggen_page_prompt_placeholder)) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 140.dp),
            minLines = 1,
            maxLines = 5,
            shape = MaterialTheme.shapes.large,
            textStyle = MaterialTheme.typography.bodySmall,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val resolver = koinInject<ImageGenerationSelectionResolver>()
            val imageProviders = remember(settings.providers, resolver) {
                settings.providers.filter { resolver.supportsImageGeneration(it) }
            }
            val imageModelListState = rememberModelListState(
                modelId = settings.imageGenerationModelId,
                providers = imageProviders,
                type = ModelType.IMAGE,
            )
            ModelSelectorButton(
                state = imageModelListState,
                onlyIcon = true,
            )
            ModelListSheet(
                state = imageModelListState,
                onSelect = { model -> vm.selectImageGenerationModel(model.id) },
            )

            IconButton(
                onClick = onShowSettings
            ) {
                Icon(HugeIcons.Tools, null)
            }

            IconButton(
                onClick = { imagePickerLauncher.launch("image/*") }
            ) {
                Icon(
                    imageVector = HugeIcons.Add01,
                    contentDescription = addReferenceImage,
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            val canSend = prompt.isNotBlank()
            Surface(
                onClick = {
                    if (!isGenerating) {
                        if (referenceImages.isEmpty()) {
                            vm.generateImage()
                        } else {
                            vm.editImage()
                        }
                    } else {
                        vm.cancelGeneration()
                    }
                },
                enabled = isGenerating || canSend,
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = when {
                    isGenerating -> MaterialTheme.colorScheme.errorContainer
                    !canSend -> MaterialTheme.colorScheme.surfaceContainerHigh
                    else -> MaterialTheme.colorScheme.primary
                },
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isGenerating) HugeIcons.Cancel01 else HugeIcons.ArrowUp02,
                        contentDescription = stringResource(R.string.imggen_page_generate_image),
                        tint = when {
                            isGenerating -> MaterialTheme.colorScheme.onErrorContainer
                            !canSend -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            else -> MaterialTheme.colorScheme.onPrimary
                        },
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ReferenceImagesRow(
    images: List<String>,
    onRemove: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        images.forEach { image ->
            Surface(
                modifier = Modifier.size(56.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Box {
                    AsyncImage(
                        model = File(image),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )

                    Surface(
                        onClick = { onRemove(image) },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(3.dp)
                            .size(20.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = HugeIcons.Delete01,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.inverseOnSurface,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageGalleryScreen(
    vm: ImgGenVM,
) {
    val generatedImages = vm.generatedImages.collectAsLazyPagingItems()
    val context = LocalContext.current
    val resources = LocalResources.current
    val mediaExportService: MediaExportService = koinInject()
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val pullToRefreshState = rememberPullToRefreshState()
    var previewIndex by remember { mutableStateOf(-1) }
    var pendingDelete by remember { mutableStateOf<GeneratedImage?>(null) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val backgroundHost = rememberImageBackgroundHost(settings)
    val generatedNoPrompt = stringResource(R.string.imggen_page_no_prompt)
    val imageDeleteFailed = stringResource(R.string.image_viewer_delete_failed)
    val refreshState = generatedImages.loadState.refresh

    PullToRefreshBox(
        isRefreshing = refreshState is LoadState.Loading && generatedImages.itemCount > 0,
        onRefresh = { generatedImages.refresh() },
        state = pullToRefreshState
    ) {
        if (refreshState is LoadState.Loading && generatedImages.itemCount == 0) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                ContainedLoadingIndicator()
            }
        } else if (refreshState is LoadState.Error && generatedImages.itemCount == 0) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.imggen_page_load_failed),
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    TextButton(onClick = generatedImages::retry) {
                        Text(stringResource(R.string.application_recovery_retry))
                    }
                }
            }
        } else if (generatedImages.itemCount == 0) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = HugeIcons.Image03,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.imggen_page_no_generated_images),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (refreshState is LoadState.Error) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.imggen_page_load_failed),
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(onClick = generatedImages::retry) {
                                Text(stringResource(R.string.application_recovery_retry))
                            }
                        }
                    }
                }
                items(
                    count = generatedImages.itemCount,
                    key = generatedImages.itemKey { it.id },
                    contentType = generatedImages.itemContentType { "GeneratedImage" }
                ) { index ->
                    val image = generatedImages[index]
                    image?.let {
                        val promptCopiedToast = stringResource(R.string.imggen_page_prompt_copied)
                        Card(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                AsyncImage(
                                    model = File(it.filePath),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f)
                                        .clickable { previewIndex = index },
                                    contentScale = ContentScale.Crop
                                )

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Column {
                                        Text(
                                            text = it.model,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = it.prompt.take(20) + if (it.prompt.length > 20) "..." else "",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2
                                        )
                                    }

                                    Row {
                                        IconButton(
                                            onClick = {
                                                clipboardManager.setText(AnnotatedString(it.prompt))
                                                toaster.show(
                                                    message = promptCopiedToast,
                                                    type = ToastType.Success
                                                )
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = HugeIcons.Copy01,
                                                contentDescription = stringResource(
                                                    R.string.imggen_page_copy_prompt
                                                ),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }

                                        val imageSavedSuccess = stringResource(R.string.imggen_page_image_saved_success)
                                        val saveFailedFmt = stringResource(R.string.imggen_page_save_failed)
                                        IconButton(
                                            onClick = {
                                                scope.launch {
                                                    try {
                                                        mediaExportService.saveImage(context, "file://${it.filePath}")
                                                        toaster.show(
                                                            message = imageSavedSuccess,
                                                            type = ToastType.Success
                                                        )
                                                    } catch (cancelled: CancellationException) {
                                                        throw cancelled
                                                    } catch (e: Exception) {
                                                        toaster.show(
                                                            message = saveFailedFmt.format(e.message),
                                                            type = ToastType.Error
                                                        )
                                                    }
                                                }
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = HugeIcons.FloppyDisk,
                                                contentDescription = stringResource(R.string.imggen_page_save),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }

                                        IconButton(
                                            onClick = { pendingDelete = it },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = HugeIcons.Delete01,
                                                contentDescription = stringResource(R.string.imggen_page_delete),
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (generatedImages.loadState.append is LoadState.Error) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            TextButton(onClick = generatedImages::retry) {
                                Text(stringResource(R.string.application_recovery_retry))
                            }
                        }
                    }
                } else if (generatedImages.loadState.append is LoadState.Loading) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(32.dp))
                        }
                    }
                }
            }
        }
    }

    if (previewIndex >= 0) {
        // 当前已加载项的快照组成一本相册, 从点击位进入; 用 id 定位以兼容占位 null 导致的下标偏移
        val snapshotItems = generatedImages.itemSnapshotList.items.filterNotNull()
        val urls = snapshotItems.map { "file://${it.filePath}" }
        val clicked = previewIndex.takeIf { it < generatedImages.itemCount }
            ?.let { generatedImages[it] }
        val startIndex = clicked
            ?.let { c -> snapshotItems.indexOfFirst { it.id == c.id } }
            ?.takeIf { it >= 0 }
        LaunchedEffect(previewIndex, urls.isEmpty(), startIndex) {
            if (urls.isEmpty() || startIndex == null) previewIndex = -1
        }
        if (urls.isNotEmpty() && startIndex != null) {
            ImagePreviewDialog(
                images = urls,
                onDismissRequest = { previewIndex = -1 },
                initialIndex = startIndex,
                extraActions = listOf(backgroundHost.action),
                deleteAction = ImagePreviewDeleteAction(
                    confirmationText = { imageUrl ->
                        val target = snapshotItems.firstOrNull { "file://${it.filePath}" == imageUrl }
                        resources.getString(
                            R.string.imggen_page_delete_image_confirmation,
                            shortGeneratedLabel(target?.prompt.orEmpty(), generatedNoPrompt),
                        )
                    },
                    delete = { imageUrl ->
                        val target = snapshotItems.firstOrNull { "file://${it.filePath}" == imageUrl }
                        if (target == null) {
                            ImagePreviewDeleteResult.Failed(imageDeleteFailed)
                        } else if (vm.deleteImage(target)) {
                            ImagePreviewDeleteResult.Deleted
                        } else {
                            ImagePreviewDeleteResult.Failed(imageDeleteFailed)
                        }
                    },
                ),
                overlay = backgroundHost.overlay,
            )
        }
    }

    pendingDelete?.let { target ->
        val promptLabel = shortGeneratedLabel(
            target.prompt,
            stringResource(R.string.imggen_page_no_prompt),
        )
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.imggen_page_delete_image_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.imggen_page_delete_image_confirmation,
                        promptLabel,
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        scope.launch {
                            if (!vm.deleteImage(target)) {
                                toaster.show(imageDeleteFailed, type = ToastType.Error)
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.imggen_page_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.imggen_page_cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsBottomSheet(
    vm: ImgGenVM,
    numberOfImages: Int,
    size: String,
    scope: CoroutineScope,
    sheetState: SheetState,
    onDismiss: () -> Unit
) {
    AdaptiveModal(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.imggen_page_settings_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            FormItem(
                label = { Text(stringResource(R.string.imggen_page_generation_count)) },
                description = { Text(stringResource(R.string.imggen_page_generation_count_desc)) }
            ) {
                OutlinedNumberInput(
                    value = numberOfImages,
                    onValueChange = vm::updateNumberOfImages,
                    modifier = Modifier.width(120.dp)
                )
            }

            FormItem(
                label = { Text(stringResource(R.string.imggen_page_image_size)) }
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ImageGenSize.entries.forEach { sizeOption ->
                        FilterChip(
                            selected = size == sizeOption.value,
                            onClick = { vm.updateSize(sizeOption.value) },
                            label = { Text(sizeOption.value) }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = size,
                        onValueChange = vm::updateSize,
                        label = { Text(stringResource(R.string.imggen_page_custom_size)) },
                        placeholder = { Text(stringResource(R.string.imggen_page_custom_size_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun imageGenerationErrorMessage(error: String?): String = when (error) {
    null -> ""
    "image_model_unavailable" -> stringResource(R.string.imggen_page_error_no_model)
    "unknown" -> stringResource(R.string.imggen_page_error_generic)
    else if error.startsWith("managed_configuration_locked:") -> stringResource(
        R.string.managed_configuration_locked,
        error.substringAfter(':').ifBlank { "managed" },
    )
    else -> stringResource(imageGenerationFailureStringRes(error))
}
