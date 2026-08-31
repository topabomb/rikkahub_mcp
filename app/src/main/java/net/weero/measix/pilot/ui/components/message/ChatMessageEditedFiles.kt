package net.weero.measix.pilot.ui.components.message

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import net.weero.measix.pilot.ui.adaptive.AdaptiveModal
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Share08
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.service.workspace.WorkspaceApplicationService
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.compose.koinInject
import java.io.File

private const val DEFAULT_VISIBLE_COUNT = 3
private val WORKSPACE_FILE_TOOL_NAMES = setOf("workspace_write_file", "workspace_edit_file")

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EditedFilesList(
    editedFiles: List<String>,
    assistant: Assistant?,
) {
    val workspaceId = assistant?.workspaceId?.toString() ?: return
    if (editedFiles.isEmpty()) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val workspaceApplicationService: WorkspaceApplicationService = koinInject()

    var selectedPath by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    val visibleFiles = if (expanded) editedFiles else editedFiles.take(DEFAULT_VISIBLE_COUNT)
    val hasMore = editedFiles.size > DEFAULT_VISIBLE_COUNT

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val path = selectedPath.also { selectedPath = null } ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        val outputStream = context.contentResolver.openOutputStream(uri) ?: return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val (area, relativePath) = resolveWorkspacePath(path)
                outputStream.use { output ->
                    workspaceApplicationService.exportFile(workspaceId, area, relativePath, output)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Export failures leave the message content unchanged.
            }
        }
    }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        visibleFiles.forEach { path ->
            val fileName = remember(path) { path.substringAfterLast('/') }
            Surface(
                onClick = { selectedPath = path },
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.tertiaryContainer,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = HugeIcons.File02,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = fileName,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 200.dp),
                    )
                }
            }
        }
        if (hasMore && !expanded) {
            Surface(
                onClick = { expanded = true },
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Text(
                    text = "+${editedFiles.size - DEFAULT_VISIBLE_COUNT}",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }

    if (selectedPath != null) {
        val path = selectedPath!!
        val fileName = remember(path) { path.substringAfterLast('/') }
        AdaptiveModal(
            onDismissRequest = { selectedPath = null },
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Card(
                    onClick = {
                        val p = selectedPath ?: return@Card
                        exportLauncher.launch(p.substringAfterLast('/'))
                    },
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = HugeIcons.FileImport,
                            contentDescription = null,
                            modifier = Modifier.padding(4.dp),
                        )
                        Text(
                            text = stringResource(R.string.common_export),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
                Card(
                    onClick = {
                        val p = selectedPath ?: return@Card
                        selectedPath = null
                        scope.launch {
                            try {
                                val (area, relativePath) = resolveWorkspacePath(p)
                                val dir = File(context.cacheDir, "workspace_share").apply { mkdirs() }
                                val file = File(dir, p.substringAfterLast('/'))
                                file.outputStream().use { output ->
                                    workspaceApplicationService.exportFile(workspaceId, area, relativePath, output)
                                }
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file,
                                )
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/octet-stream"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, null))
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                // Export/share failures leave the message content unchanged.
                            }
                        }
                    },
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Share08,
                            contentDescription = null,
                            modifier = Modifier.padding(4.dp),
                        )
                        Text(
                            text = stringResource(R.string.common_share),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
        }
    }
}

internal fun editedWorkspaceFilePaths(parts: List<UIMessagePart>): List<String> =
    parts.filterIsInstance<UIMessagePart.Tool>()
        .mapNotNull(::successfulWorkspaceFilePath)
        .distinct()

/** A completed file mutation is described by its result, never inferred from requested arguments. */
private fun successfulWorkspaceFilePath(tool: UIMessagePart.Tool): String? {
    if (tool.toolName !in WORKSPACE_FILE_TOOL_NAMES ||
        tool.approvalState is ToolApprovalState.Denied || tool.approvalState is ToolApprovalState.Pending
    ) return null
    val text = (tool.output.singleOrNull() as? UIMessagePart.Text)?.text ?: return null
    val result = try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (_: SerializationException) {
        null
    } ?: return null
    if ("error" in result || "status" in result || "success" in result) return null
    val path = (result["path"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    if (!path.startsWith('/') || path.endsWith('/') || '\u0000' in path) return null
    fun number(key: String): Long? =
        (result[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
    if (number("sizeBytes")?.let { it >= 0 } != true || number("updatedAt") == null) return null
    val succeeded = when (tool.toolName) {
        "workspace_write_file" -> (result["isDirectory"] as? JsonPrimitive)
            ?.takeUnless { it.isString }?.booleanOrNull == false
        "workspace_edit_file" -> number("replacements")?.let { it > 0 } == true
        else -> false
    }
    return path.takeIf { succeeded }
}

private fun resolveWorkspacePath(path: String): Pair<WorkspaceStorageArea, String> {
    val trimmed = path.trimEnd('/')
    return if (trimmed == "/workspace" || trimmed.startsWith("/workspace/")) {
        WorkspaceStorageArea.FILES to trimmed.removePrefix("/workspace").trimStart('/')
    } else {
        WorkspaceStorageArea.LINUX to trimmed.trimStart('/')
    }
}
