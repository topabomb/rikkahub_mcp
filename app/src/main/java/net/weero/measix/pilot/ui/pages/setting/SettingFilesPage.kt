package net.weero.measix.pilot.ui.pages.setting

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Clean
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Image02
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.annotation.StringRes
import net.weero.measix.pilot.data.db.entity.GenMediaEntity
import net.weero.measix.pilot.R
import net.weero.measix.pilot.service.ArtifactDeleteImpactUiModel
import net.weero.measix.pilot.service.ArtifactDeleteOutcome
import net.weero.measix.pilot.service.ArtifactUiModel
import net.weero.measix.pilot.service.ArtifactUiOrigin
import net.weero.measix.pilot.service.ArtifactUseCase
import net.weero.measix.pilot.data.imggen.GeneratedMediaStore
import net.weero.measix.pilot.data.repository.GenMediaRepository
import net.weero.measix.pilot.ui.components.nav.BackButton
import net.weero.measix.pilot.ui.components.ui.ImagePreviewDialog
import net.weero.measix.pilot.ui.components.ui.ImagePreviewDeleteAction
import net.weero.measix.pilot.ui.components.ui.ImagePreviewDeleteResult
import net.weero.measix.pilot.ui.components.ui.generatedDeleteLabel
import net.weero.measix.pilot.ui.components.ui.rememberImageBackgroundHost
import net.weero.measix.pilot.ui.context.LocalSettings
import net.weero.measix.pilot.ui.context.LocalToaster
import net.weero.measix.pilot.ui.theme.CustomColors
import net.weero.measix.pilot.utils.fileSizeToString
import net.weero.measix.pilot.utils.toLocalDateTime
import org.koin.compose.koinInject
import java.io.File
import java.time.Instant

private enum class FileCategory {
    UPLOAD,
    GENERATED_IMAGES,
}

/** 上传文件删除流程状态机（防重入；Confirming → Executing → Idle） */
private sealed interface UploadDeleteState {
    data object Idle : UploadDeleteState
    data class Confirming(val target: ArtifactUiModel) : UploadDeleteState
    data class Executing(val target: ArtifactUiModel) : UploadDeleteState
}

@Composable
fun SettingFilesPage(
    artifactUseCase: ArtifactUseCase = koinInject(),
    genMediaRepository: GenMediaRepository = koinInject(),
    generatedMediaStore: GeneratedMediaStore = koinInject(),
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val uploadGridState = rememberLazyStaggeredGridState()
    val generatedGridState = rememberLazyStaggeredGridState()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val resources = LocalResources.current

    val deletedToast = stringResource(R.string.setting_files_page_deleted_toast)
    val deleteFailedToast = stringResource(R.string.setting_files_page_delete_failed_toast)
    val deleteInProgressToast = stringResource(R.string.setting_files_page_delete_in_progress_toast)
    val deleteAlreadyDeletedToast = stringResource(R.string.setting_files_page_delete_already_deleted_toast)
    val cleanedToast = stringResource(R.string.setting_files_page_cleaned_toast)
    val cleanFailedToast = stringResource(R.string.setting_files_page_clean_failed_toast)
    val generatedNoPrompt = stringResource(R.string.setting_files_page_generated_no_prompt)

    var selectedCategory by remember { mutableStateOf(FileCategory.UPLOAD) }
    var uploadDeleteState by remember { mutableStateOf<UploadDeleteState>(UploadDeleteState.Idle) }
    var pendingUploadDeleteImpact by remember {
        mutableStateOf<ArtifactDeleteImpactUiModel?>(null)
    }
    var pendingGeneratedDelete by remember { mutableStateOf<GenMediaEntity?>(null) }
    var showCleanDialog by remember { mutableStateOf(false) }
    var previewImages by remember { mutableStateOf<List<String>>(emptyList()) }
    var previewIndex by remember { mutableStateOf(-1) }
    val settings = LocalSettings.current
    val backgroundHost = rememberImageBackgroundHost(settings)
    val uploadFiles by artifactUseCase.observeUploads().collectAsState(initial = emptyList())
    val generatedImages by genMediaRepository.observeAllMedia().collectAsState(initial = emptyList())
    val isUpload = selectedCategory == FileCategory.UPLOAD
    val hasItems = if (isUpload) uploadFiles.isNotEmpty() else generatedImages.isNotEmpty()

    val pendingUploadDelete = (uploadDeleteState as? UploadDeleteState.Confirming)?.target
        ?: (uploadDeleteState as? UploadDeleteState.Executing)?.target
    if (pendingUploadDelete != null) {
        val target = pendingUploadDelete
        val executing = uploadDeleteState is UploadDeleteState.Executing
        LaunchedEffect(target.id) {
            pendingUploadDeleteImpact = try {
                artifactUseCase.inspect(target.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
        AlertDialog(
            onDismissRequest = {
                if (!executing) {
                    uploadDeleteState = UploadDeleteState.Idle
                    pendingUploadDeleteImpact = null
                }
            },
            title = { Text(stringResource(R.string.setting_files_page_delete_file_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(
                            R.string.setting_files_page_delete_upload_confirmation,
                            target.displayName,
                        )
                    )
                    pendingUploadDeleteImpact?.let { impact ->
                        if (impact.referencedByHistory) {
                            ImpactText(stringResource(R.string.setting_files_page_delete_impact_history))
                        }
                        if (impact.assistantBackgroundCount > 0) {
                            ImpactText(
                                stringResource(
                                    R.string.setting_files_page_delete_impact_backgrounds,
                                    impact.assistantBackgroundCount,
                                )
                            )
                        }
                        if (impact.assistantAvatarCount > 0) {
                            ImpactText(
                                stringResource(
                                    R.string.setting_files_page_delete_impact_avatars,
                                    impact.assistantAvatarCount,
                                )
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !executing,
                    onClick = {
                        if (uploadDeleteState is UploadDeleteState.Confirming) {
                            uploadDeleteState = UploadDeleteState.Executing(target)
                            scope.launch {
                                try {
                                    val toast = when (artifactUseCase.deleteUserRequested(target.id)) {
                                        ArtifactDeleteOutcome.Deleted,
                                        ArtifactDeleteOutcome.CleanupPending -> deletedToast
                                        ArtifactDeleteOutcome.InProgress -> deleteInProgressToast
                                        ArtifactDeleteOutcome.AlreadyDeleted -> deleteAlreadyDeletedToast
                                        is ArtifactDeleteOutcome.Failed -> deleteFailedToast
                                    }
                                    toaster.show(toast)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    toaster.show(deleteFailedToast)
                                } finally {
                                    uploadDeleteState = UploadDeleteState.Idle
                                    pendingUploadDeleteImpact = null
                                }
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.setting_files_page_delete_action))
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !executing,
                    onClick = {
                        uploadDeleteState = UploadDeleteState.Idle
                        pendingUploadDeleteImpact = null
                    }
                ) {
                    Text(stringResource(R.string.setting_files_page_cancel_action))
                }
            }
        )
    }

    if (pendingGeneratedDelete != null) {
        val target = pendingGeneratedDelete!!
        val fileLabel = generatedDeleteLabel(
            target.prompt,
            stringResource(R.string.setting_files_page_generated_no_prompt),
        )
        AlertDialog(
            onDismissRequest = { pendingGeneratedDelete = null },
            title = { Text(stringResource(R.string.setting_files_page_delete_file_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.setting_files_page_delete_generated_confirmation,
                        fileLabel,
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingGeneratedDelete = null
                        scope.launch {
                            try {
                                val ok = generatedMediaStore.delete(target.id)
                                toaster.show(if (ok) deletedToast else deleteFailedToast)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                toaster.show(deleteFailedToast)
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.setting_files_page_delete_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingGeneratedDelete = null }) {
                    Text(stringResource(R.string.setting_files_page_cancel_action))
                }
            }
        )
    }

    if (showCleanDialog) {
        AlertDialog(
            onDismissRequest = { showCleanDialog = false },
            title = { Text(stringResource(R.string.setting_files_page_clean_title)) },
            text = {
                Text(
                    stringResource(
                        if (isUpload) {
                            R.string.setting_files_page_clean_confirmation
                        } else {
                            R.string.setting_files_page_clean_generated_confirmation
                        },
                        if (isUpload) uploadFiles.size else generatedImages.size,
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val category = selectedCategory
                        showCleanDialog = false
                        scope.launch {
                            try {
                                val ok = if (category == FileCategory.UPLOAD) {
                                    when (artifactUseCase.deleteAllUploads()) {
                                        ArtifactDeleteOutcome.Deleted,
                                        ArtifactDeleteOutcome.CleanupPending -> true
                                        ArtifactDeleteOutcome.InProgress,
                                        ArtifactDeleteOutcome.AlreadyDeleted,
                                        is ArtifactDeleteOutcome.Failed -> false
                                    }
                                } else {
                                    generatedMediaStore.deleteAll()
                                }
                                toaster.show(if (ok) cleanedToast else cleanFailedToast)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                toaster.show(cleanFailedToast)
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.setting_files_page_clean_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCleanDialog = false }) {
                    Text(stringResource(R.string.setting_files_page_cancel_action))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_files_page_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(
                        onClick = { showCleanDialog = true },
                        enabled = hasItems,
                    ) {
                        Icon(
                            imageVector = HugeIcons.Clean,
                            contentDescription = stringResource(R.string.setting_files_page_clean_content_description),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    start = innerPadding.calculateStartPadding(layoutDirection),
                    end = innerPadding.calculateEndPadding(layoutDirection),
                )
        ) {
            CategoryRow(
                selected = selectedCategory,
                onSelected = { selectedCategory = it },
            )

            when (selectedCategory) {
                FileCategory.UPLOAD -> {
                    if (uploadFiles.isEmpty()) {
                        EmptyFiles()
                    } else {
                        // 当前 Tab 的图片文件组成一本相册(统一 file:// 前缀与 Coil 缓存键),
                        // 点击缩略图进入查看器; 不做磁盘存在性检查(缺失文件由加载错误态兜底)
                        val imagePaths = remember(uploadFiles) {
                            uploadFiles.mapNotNull { file ->
                                file.takeIf { it.mimeType.startsWith("image/") }
                                    ?.contentUri
                            }
                        }
                        val imagePathIndex = remember(imagePaths) {
                            imagePaths.withIndex().associate { (index, path) -> path to index }
                        }
                        FileGrid(
                            innerPadding = innerPadding,
                            gridState = uploadGridState,
                        ) {
                            items(uploadFiles, key = { it.id }) { file ->
                                FileItem(
                                    file = file,
                                    deleteExecuting = uploadDeleteState.let { state ->
                                        state is UploadDeleteState.Executing && state.target.id == file.id
                                    },
                                    onDelete = { uploadDeleteState = UploadDeleteState.Confirming(file) },
                                    onImageClick = file.contentUri.let { uri ->
                                        imagePathIndex[uri]?.let { index ->
                                            {
                                                previewImages = imagePaths
                                                previewIndex = index
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                FileCategory.GENERATED_IMAGES -> {
                    if (generatedImages.isEmpty()) {
                        EmptyFiles()
                    } else {
                        val imagePaths = remember(generatedImages) {
                            generatedImages.map { entity ->
                                "file://${generatedMediaStore.resolveCanonicalFile(entity).absolutePath}"
                            }
                        }
                        val imagePathIndex = remember(imagePaths) {
                            imagePaths.withIndex().associate { (index, path) -> path to index }
                        }
                        FileGrid(
                            innerPadding = innerPadding,
                            gridState = generatedGridState,
                        ) {
                            items(generatedImages, key = { it.id }) { entity ->
                                val fileOnDisk = generatedMediaStore.resolveCanonicalFile(entity)
                                GeneratedImageItem(
                                    entity = entity,
                                    fileOnDisk = fileOnDisk,
                                    onDelete = { pendingGeneratedDelete = entity },
                                    onImageClick = fileOnDisk.absolutePath.let { path ->
                                        imagePathIndex["file://$path"]?.let { index ->
                                            {
                                                previewImages = imagePaths
                                                previewIndex = index
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (previewIndex >= 0 && previewImages.isNotEmpty()) {
        ImagePreviewDialog(
            images = previewImages,
            onDismissRequest = { previewIndex = -1 },
            initialIndex = previewIndex.coerceIn(0, previewImages.lastIndex),
            extraActions = listOf(backgroundHost.action),
            deleteAction = ImagePreviewDeleteAction(
                confirmationText = { imageUrl ->
                    when (selectedCategory) {
                        FileCategory.UPLOAD -> {
                            val target = uploadFiles.firstOrNull { artifact ->
                                artifact.contentUri == imageUrl
                            } ?: error(deleteAlreadyDeletedToast)
                            val impact = artifactUseCase.inspect(target.id) ?: error(deleteAlreadyDeletedToast)
                            buildString {
                                append(
                                    resources.getString(
                                        R.string.setting_files_page_delete_upload_confirmation,
                                        target.displayName,
                                    )
                                )
                                if (impact.referencedByHistory) {
                                    append("\n\n")
                                    append(resources.getString(R.string.setting_files_page_delete_impact_history))
                                }
                                if (impact.assistantBackgroundCount > 0) {
                                    append("\n")
                                    append(
                                        resources.getString(
                                            R.string.setting_files_page_delete_impact_backgrounds,
                                            impact.assistantBackgroundCount,
                                        )
                                    )
                                }
                                if (impact.assistantAvatarCount > 0) {
                                    append("\n")
                                    append(
                                        resources.getString(
                                            R.string.setting_files_page_delete_impact_avatars,
                                            impact.assistantAvatarCount,
                                        )
                                    )
                                }
                            }
                        }

                        FileCategory.GENERATED_IMAGES -> {
                            val target = generatedImages.firstOrNull { entity ->
                                "file://${generatedMediaStore.resolveCanonicalFile(entity).absolutePath}" == imageUrl
                            } ?: error(deleteAlreadyDeletedToast)
                            resources.getString(
                                R.string.setting_files_page_delete_generated_confirmation,
                                generatedDeleteLabel(target.prompt, generatedNoPrompt),
                            )
                        }
                    }
                },
                delete = { imageUrl ->
                    when (selectedCategory) {
                        FileCategory.UPLOAD -> {
                            val target = uploadFiles.firstOrNull { artifact ->
                                artifact.contentUri == imageUrl
                            }
                            if (target == null) {
                                ImagePreviewDeleteResult.Deleted
                            } else {
                                when (artifactUseCase.deleteUserRequested(target.id)) {
                                    ArtifactDeleteOutcome.Deleted,
                                    ArtifactDeleteOutcome.CleanupPending,
                                    ArtifactDeleteOutcome.AlreadyDeleted -> ImagePreviewDeleteResult.Deleted
                                    ArtifactDeleteOutcome.InProgress ->
                                        ImagePreviewDeleteResult.Failed(deleteInProgressToast)
                                    is ArtifactDeleteOutcome.Failed ->
                                        ImagePreviewDeleteResult.Failed(deleteFailedToast)
                                }
                            }
                        }

                        FileCategory.GENERATED_IMAGES -> {
                            val target = generatedImages.firstOrNull { entity ->
                                "file://${generatedMediaStore.resolveCanonicalFile(entity).absolutePath}" == imageUrl
                            }
                            if (target == null || generatedMediaStore.delete(target.id)) {
                                ImagePreviewDeleteResult.Deleted
                            } else {
                                ImagePreviewDeleteResult.Failed(deleteFailedToast)
                            }
                        }
                    }
                },
            ),
            overlay = backgroundHost.overlay,
        )
    }
}

@Composable
private fun ImpactText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun EmptyFiles() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(stringResource(R.string.setting_files_page_no_files))
    }
}

@Composable
private fun FileGrid(
    innerPadding: PaddingValues,
    gridState: LazyStaggeredGridState,
    content: LazyStaggeredGridScope.() -> Unit,
) {
    LazyVerticalStaggeredGrid(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 16.dp,
            end = 16.dp,
            bottom = innerPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalItemSpacing = 8.dp,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        state = gridState,
        columns = StaggeredGridCells.Fixed(2),
        content = content,
    )
}

@Composable
private fun CategoryRow(
    selected: FileCategory,
    onSelected: (FileCategory) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FileCategory.entries.forEach { category ->
            FilterChip(
                selected = selected == category,
                onClick = { onSelected(category) },
                label = { Text(categoryDisplayName(category)) }
            )
        }
    }
}

@Composable
private fun categoryDisplayName(category: FileCategory): String = when (category) {
    FileCategory.UPLOAD -> stringResource(R.string.setting_files_page_folder_upload)
    FileCategory.GENERATED_IMAGES -> stringResource(R.string.setting_files_page_folder_generated_images)
}

@Composable
private fun FileItem(
    file: ArtifactUiModel,
    deleteExecuting: Boolean,
    onDelete: () -> Unit,
    onImageClick: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor)
    ) {
        Column {
            MediaThumb(
                model = file.contentUri.takeIf { file.mimeType.startsWith("image/") },
                contentDescription = file.displayName,
                onDelete = onDelete,
                deleteEnabled = !deleteExecuting,
                onClick = onImageClick,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Text(
                    text = file.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = file.mimeType,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = file.sizeBytes.fileSizeToString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(originLabel(file.origin)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@StringRes
private fun originLabel(origin: ArtifactUiOrigin): Int = when (origin) {
    ArtifactUiOrigin.USER -> R.string.setting_files_page_origin_user
    ArtifactUiOrigin.GENERATED -> R.string.setting_files_page_origin_generated
    ArtifactUiOrigin.SYSTEM -> R.string.setting_files_page_origin_system
}

@Composable
private fun GeneratedImageItem(
    entity: GenMediaEntity,
    fileOnDisk: File,
    onDelete: () -> Unit,
    onImageClick: (() -> Unit)? = null,
) {
    val prompt = entity.prompt.trim().ifBlank {
        stringResource(R.string.setting_files_page_generated_no_prompt)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor)
    ) {
        Column {
            MediaThumb(
                model = fileOnDisk,
                contentDescription = prompt,
                onDelete = onDelete,
                onClick = onImageClick,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Text(
                    text = prompt,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = entity.modelId.ifBlank { stringResource(R.string.setting_files_page_generated_unknown_model) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = Instant.ofEpochMilli(entity.createAt).toLocalDateTime(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MediaThumb(
    model: Any?,
    contentDescription: String,
    onDelete: () -> Unit,
    deleteEnabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = contentDescription,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .let { if (onClick != null) it.clickable(onClick = onClick) else it },
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = HugeIcons.Image02,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        IconButton(
            onClick = onDelete,
            enabled = deleteEnabled,
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            Icon(
                HugeIcons.Delete01,
                contentDescription = stringResource(R.string.setting_files_page_delete_content_description)
            )
        }
    }
}
