package net.weero.measix.pilot.ui.pages.setting

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Clean
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Image02
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import net.weero.measix.pilot.data.db.entity.GenMediaEntity
import net.weero.measix.pilot.data.db.entity.ManagedFileEntity
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.files.FileFolders
import net.weero.measix.pilot.data.files.FilesManager
import net.weero.measix.pilot.data.imggen.GeneratedMediaStore
import net.weero.measix.pilot.data.repository.GenMediaRepository
import net.weero.measix.pilot.ui.components.nav.BackButton
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

@Composable
fun SettingFilesPage(
    filesManager: FilesManager = koinInject(),
    genMediaRepository: GenMediaRepository = koinInject(),
    generatedMediaStore: GeneratedMediaStore = koinInject(),
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val uploadGridState = rememberLazyStaggeredGridState()
    val generatedGridState = rememberLazyStaggeredGridState()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current

    val deletedToast = stringResource(R.string.setting_files_page_deleted_toast)
    val deleteFailedToast = stringResource(R.string.setting_files_page_delete_failed_toast)
    val cleanedToast = stringResource(R.string.setting_files_page_cleaned_toast)
    val cleanFailedToast = stringResource(R.string.setting_files_page_clean_failed_toast)

    var selectedCategory by remember { mutableStateOf(FileCategory.UPLOAD) }
    var pendingUploadDelete by remember { mutableStateOf<ManagedFileEntity?>(null) }
    var pendingGeneratedDelete by remember { mutableStateOf<GenMediaEntity?>(null) }
    var showCleanDialog by remember { mutableStateOf(false) }
    val uploadFiles by filesManager.observe(FileFolders.UPLOAD).collectAsState(initial = emptyList())
    val generatedImages by genMediaRepository.observeAllMedia().collectAsState(initial = emptyList())
    val isUpload = selectedCategory == FileCategory.UPLOAD
    val hasItems = if (isUpload) uploadFiles.isNotEmpty() else generatedImages.isNotEmpty()

    if (pendingUploadDelete != null) {
        val target = pendingUploadDelete!!
        AlertDialog(
            onDismissRequest = { pendingUploadDelete = null },
            title = { Text(stringResource(R.string.setting_files_page_delete_file_title)) },
            text = { Text(target.displayName) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val ok = filesManager.delete(target.id, deleteFromDisk = true)
                            toaster.show(if (ok) deletedToast else deleteFailedToast)
                            pendingUploadDelete = null
                        }
                    }
                ) {
                    Text(stringResource(R.string.setting_files_page_delete_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingUploadDelete = null }) {
                    Text(stringResource(R.string.setting_files_page_cancel_action))
                }
            }
        )
    }

    if (pendingGeneratedDelete != null) {
        val target = pendingGeneratedDelete!!
        AlertDialog(
            onDismissRequest = { pendingGeneratedDelete = null },
            title = { Text(stringResource(R.string.setting_files_page_delete_file_title)) },
            text = { Text(stringResource(R.string.setting_files_page_delete_generated_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val ok = generatedMediaStore.delete(target.id)
                            toaster.show(if (ok) deletedToast else deleteFailedToast)
                            pendingGeneratedDelete = null
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
                        }
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCleanDialog = false
                        scope.launch {
                            val ok = if (isUpload) {
                                filesManager.deleteAll(FileFolders.UPLOAD)
                            } else {
                                generatedMediaStore.deleteAll()
                            }
                            toaster.show(if (ok) cleanedToast else cleanFailedToast)
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
                        FileGrid(
                            innerPadding = innerPadding,
                            gridState = uploadGridState,
                        ) {
                            items(uploadFiles, key = { it.id }) { file ->
                                FileItem(
                                    file = file,
                                    fileOnDisk = filesManager.getFile(file),
                                    onDelete = { pendingUploadDelete = file },
                                )
                            }
                        }
                    }
                }
                FileCategory.GENERATED_IMAGES -> {
                    if (generatedImages.isEmpty()) {
                        EmptyFiles()
                    } else {
                        FileGrid(
                            innerPadding = innerPadding,
                            gridState = generatedGridState,
                        ) {
                            items(generatedImages, key = { it.id }) { entity ->
                                GeneratedImageItem(
                                    entity = entity,
                                    fileOnDisk = generatedMediaStore.resolveCanonicalFile(entity),
                                    onDelete = { pendingGeneratedDelete = entity },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
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
    file: ManagedFileEntity,
    fileOnDisk: File,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor)
    ) {
        Column {
            MediaThumb(
                model = fileOnDisk.takeIf { file.mimeType.startsWith("image/") },
                contentDescription = file.displayName,
                onDelete = onDelete,
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
            }
        }
    }
}

@Composable
private fun GeneratedImageItem(
    entity: GenMediaEntity,
    fileOnDisk: File,
    onDelete: () -> Unit,
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
                model = fileOnDisk.takeIf { it.exists() },
                contentDescription = prompt,
                onDelete = onDelete,
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
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = contentDescription,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f),
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
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            Icon(
                HugeIcons.Delete01,
                contentDescription = stringResource(R.string.setting_files_page_delete_content_description)
            )
        }
    }
}
