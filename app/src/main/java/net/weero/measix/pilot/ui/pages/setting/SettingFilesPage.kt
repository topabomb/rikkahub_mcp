package net.weero.measix.pilot.ui.pages.setting

import androidx.annotation.StringRes
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
import net.weero.measix.pilot.R
import net.weero.measix.pilot.service.ArtifactDeleteImpactUiModel
import net.weero.measix.pilot.service.ArtifactDeleteOutcome
import net.weero.measix.pilot.service.ArtifactUiOrigin
import net.weero.measix.pilot.service.FileCleanupCategory
import net.weero.measix.pilot.service.FileCleanupRange
import net.weero.measix.pilot.service.FileManagementApplicationService
import net.weero.measix.pilot.service.FileManagementQueryService
import net.weero.measix.pilot.service.ManagedFileKey
import net.weero.measix.pilot.service.ManagedFileUiModel
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
import java.time.Instant

private enum class FileCategory {
    UPLOAD,
    GENERATED_IMAGES,
}

/** 上传文件删除流程状态机（防重入；Confirming → Executing → Idle） */
private sealed interface UploadDeleteState {
    data object Idle : UploadDeleteState
    data class Confirming(val target: ManagedFileUiModel) : UploadDeleteState
    data class Executing(val target: ManagedFileUiModel) : UploadDeleteState
}

private val cleanupRanges = listOf(
    FileCleanupRange.OlderThanDays(7),
    FileCleanupRange.OlderThanDays(14),
    FileCleanupRange.OlderThanDays(30),
    FileCleanupRange.All,
)

@Composable
fun SettingFilesPage(
    applicationService: FileManagementApplicationService = koinInject(),
    queryService: FileManagementQueryService = koinInject(),
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
    var pendingGeneratedDeleteKey by remember { mutableStateOf<ManagedFileKey.Generated?>(null) }
    var cleanupCategory by remember { mutableStateOf<FileCleanupCategory?>(null) }
    var cleanupRange: FileCleanupRange by remember { mutableStateOf(FileCleanupRange.All) }
    var cleanupCandidateCount by remember { mutableStateOf(0) }
    var previewImages by remember { mutableStateOf<List<String>>(emptyList()) }
    var previewIndex by remember { mutableStateOf(-1) }
    val settings = LocalSettings.current
    val backgroundHost = rememberImageBackgroundHost(settings)
    val uploadFiles by queryService.observeUploads().collectAsState(initial = emptyList())
    val generatedImages by queryService.observeGenerated().collectAsState(initial = emptyList())
    val isUpload = selectedCategory == FileCategory.UPLOAD
    val hasItems = if (isUpload) uploadFiles.isNotEmpty() else generatedImages.isNotEmpty()

    // 清理确认只展示当前 query 候选数，不把全列表数冒充实际删除数
    LaunchedEffect(cleanupCategory, cleanupRange) {
        val category = cleanupCategory ?: return@LaunchedEffect
        cleanupCandidateCount = try {
            queryService.candidateCount(category, cleanupRange)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            0
        }
    }

    val pendingUploadDelete = (uploadDeleteState as? UploadDeleteState.Confirming)?.target
        ?: (uploadDeleteState as? UploadDeleteState.Executing)?.target
    if (pendingUploadDelete != null) {
        val target = pendingUploadDelete
        val executing = uploadDeleteState is UploadDeleteState.Executing
        LaunchedEffect(target.key) {
            pendingUploadDeleteImpact = try {
                queryService.inspectUpload(target.uploadKey())
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
                                    val toast = when (applicationService.deleteUpload(target.uploadKey())) {
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

    val pendingGeneratedDelete = pendingGeneratedDeleteKey?.let { key ->
        generatedImages.firstOrNull { it.key == key }
    }
    if (pendingGeneratedDelete != null) {
        val target = pendingGeneratedDelete
        val fileLabel = generatedDeleteLabel(
            target.prompt.orEmpty(),
            stringResource(R.string.setting_files_page_generated_no_prompt),
        )
        AlertDialog(
            onDismissRequest = { pendingGeneratedDeleteKey = null },
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
                        pendingGeneratedDeleteKey = null
                        scope.launch {
                            try {
                                val ok = applicationService.deleteGenerated(target.generatedKey())
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
                TextButton(onClick = { pendingGeneratedDeleteKey = null }) {
                    Text(stringResource(R.string.setting_files_page_cancel_action))
                }
            }
        )
    }

    cleanupCategory?.let { category ->
        AlertDialog(
            onDismissRequest = { cleanupCategory = null },
            title = { Text(stringResource(R.string.setting_files_page_clean_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(
                            if (category == FileCleanupCategory.UPLOAD) {
                                R.string.setting_files_page_clean_confirmation
                            } else {
                                R.string.setting_files_page_clean_generated_confirmation
                            },
                            cleanupCandidateCount,
                        )
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        cleanupRanges.forEach { range ->
                            FilterChip(
                                selected = cleanupRange == range,
                                onClick = { cleanupRange = range },
                                label = { Text(stringResource(rangeLabel(range))) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val confirmedCategory = category
                        val confirmedRange = cleanupRange
                        cleanupCategory = null
                        scope.launch {
                            try {
                                val result = applicationService.cleanup(confirmedCategory, confirmedRange)
                                // 部分成功不压成 Boolean：待清理/跳过/失败分别给用户可见的结果
                                val message = when {
                                    result.failed > 0 -> cleanFailedToast
                                    result.cleanupPending > 0 || result.skippedInProgress > 0 ->
                                        resources.getString(
                                            R.string.setting_files_page_clean_partial_result,
                                            result.deleted,
                                            result.cleanupPending,
                                            result.skippedInProgress,
                                            result.failed,
                                        )
                                    else -> cleanedToast
                                }
                                toaster.show(message)
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
                TextButton(onClick = { cleanupCategory = null }) {
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
                        onClick = {
                            cleanupRange = FileCleanupRange.All
                            cleanupCandidateCount = 0
                            cleanupCategory = if (isUpload) {
                                FileCleanupCategory.UPLOAD
                            } else {
                                FileCleanupCategory.GENERATED_IMAGES
                            }
                        },
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
                            items(uploadFiles, key = { it.key }) { file ->
                                FileItem(
                                    file = file,
                                    deleteExecuting = uploadDeleteState.let { state ->
                                        state is UploadDeleteState.Executing && state.target.key == file.key
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
                            generatedImages.map { it.contentUri }
                        }
                        val imagePathIndex = remember(imagePaths) {
                            imagePaths.withIndex().associate { (index, path) -> path to index }
                        }
                        FileGrid(
                            innerPadding = innerPadding,
                            gridState = generatedGridState,
                        ) {
                            items(generatedImages, key = { it.key }) { file ->
                                GeneratedImageItem(
                                    file = file,
                                    onDelete = { pendingGeneratedDeleteKey = file.generatedKey() },
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
                            val target = uploadFiles.firstOrNull { it.contentUri == imageUrl }
                                ?: error(deleteAlreadyDeletedToast)
                            val impact = queryService.inspectUpload(target.uploadKey())
                                ?: error(deleteAlreadyDeletedToast)
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
                            val target = generatedImages.firstOrNull { it.contentUri == imageUrl }
                                ?: error(deleteAlreadyDeletedToast)
                            resources.getString(
                                R.string.setting_files_page_delete_generated_confirmation,
                                generatedDeleteLabel(target.prompt.orEmpty(), generatedNoPrompt),
                            )
                        }
                    }
                },
                delete = { imageUrl ->
                    when (selectedCategory) {
                        FileCategory.UPLOAD -> {
                            val target = uploadFiles.firstOrNull { it.contentUri == imageUrl }
                            if (target == null) {
                                ImagePreviewDeleteResult.Deleted
                            } else {
                                when (applicationService.deleteUpload(target.uploadKey())) {
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
                            val target = generatedImages.firstOrNull { it.contentUri == imageUrl }
                            if (target == null || applicationService.deleteGenerated(target.generatedKey())) {
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

@StringRes
private fun rangeLabel(range: FileCleanupRange): Int = when (range) {
    is FileCleanupRange.OlderThanDays -> when (range.days) {
        7 -> R.string.setting_files_page_clean_range_7d
        14 -> R.string.setting_files_page_clean_range_14d
        else -> R.string.setting_files_page_clean_range_30d
    }
    FileCleanupRange.All -> R.string.setting_files_page_clean_range_all
}

private fun ManagedFileUiModel.uploadKey(): ManagedFileKey.Upload =
    key as? ManagedFileKey.Upload ?: error("upload projection has non-upload key: $key")

private fun ManagedFileUiModel.generatedKey(): ManagedFileKey.Generated =
    key as? ManagedFileKey.Generated ?: error("generated projection has non-generated key: $key")

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
    file: ManagedFileUiModel,
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
private fun originLabel(origin: ArtifactUiOrigin?): Int = when (origin) {
    ArtifactUiOrigin.USER -> R.string.setting_files_page_origin_user
    ArtifactUiOrigin.GENERATED -> R.string.setting_files_page_origin_generated
    ArtifactUiOrigin.SYSTEM -> R.string.setting_files_page_origin_system
    null -> R.string.setting_files_page_origin_system
}

@Composable
private fun GeneratedImageItem(
    file: ManagedFileUiModel,
    onDelete: () -> Unit,
    onImageClick: (() -> Unit)? = null,
) {
    val prompt = file.prompt.orEmpty().trim().ifBlank {
        stringResource(R.string.setting_files_page_generated_no_prompt)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor)
    ) {
        Column {
            MediaThumb(
                model = file.contentUri,
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
                    text = file.modelId.orEmpty().ifBlank { stringResource(R.string.setting_files_page_generated_unknown_model) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = Instant.ofEpochMilli(file.createdAt).toLocalDateTime(),
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
