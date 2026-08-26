package net.weero.measix.pilot.ui.pages.backup.tabs

import net.weero.measix.pilot.ui.pages.backup.RestoreConfirmDialog
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.FileImport
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.weero.measix.pilot.R
import net.weero.measix.pilot.ui.components.ui.CardGroup
import net.weero.measix.pilot.ui.components.ui.StickyHeader
import net.weero.measix.pilot.ui.context.LocalToaster
import net.weero.measix.pilot.ui.pages.backup.BackupVM
import net.weero.measix.pilot.service.BackupRestoreOperationInProgressException
import net.weero.measix.pilot.service.BackupRestorePendingRestartConflictException
import java.io.File
import java.io.FileInputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun ImportExportTab(
    vm: BackupVM,
    onShowRestartDialog: () -> Unit
) {
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var isExporting by remember { mutableStateOf(false) }
    var isRestoring by remember { mutableStateOf(false) }
    var pendingRestoreUri by remember { mutableStateOf<android.net.Uri?>(null) }

    val backupSuccess = stringResource(R.string.backup_page_backup_success)
    val restoreSuccess = stringResource(R.string.backup_page_restore_success)
    val restoreFailedFmt = stringResource(R.string.backup_page_restore_failed)
    val unknownError = stringResource(R.string.backup_page_unknown_error)
    val restoreInProgress = stringResource(R.string.backup_page_restore_in_progress)
    val restorePendingRestart = stringResource(R.string.backup_page_restore_pending_restart)

    // 创建文件保存的launcher
    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let { targetUri ->
            scope.launch {
                isExporting = true
                var exportFile: File? = null
                try {
                    val prepared = vm.exportToFile()
                    exportFile = prepared
                    context.contentResolver.openOutputStream(targetUri)?.use { outputStream ->
                        FileInputStream(prepared).use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    } ?: error("Unable to open the selected backup destination")
                    toaster.show(backupSuccess, type = ToastType.Success)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    toaster.show(error.message ?: unknownError, type = ToastType.Error)
                } finally {
                    exportFile?.delete()
                    isExporting = false
                }
            }
        }
    }

    // 创建文件选择的launcher
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { sourceUri ->
            // Selection is allowed before confirmation, but staging/download
            // only starts after the user confirms.
            pendingRestoreUri = sourceUri
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(16.dp)
    ) {
        stickyHeader {
            StickyHeader {
                Text(stringResource(R.string.backup_page_local_backup_export))
            }
        }

        item {
            CardGroup {
                item(
                    onClick = if (!isExporting) {
                        {
                            val timestamp = LocalDateTime.now()
                                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                            createDocumentLauncher.launch("measix_pilot_backup_$timestamp.zip")
                        }
                    } else null,
                    headlineContent = { Text(stringResource(R.string.backup_page_local_backup_export)) },
                    supportingContent = {
                        Text(
                            if (isExporting) {
                                stringResource(R.string.backup_page_exporting)
                            } else {
                                stringResource(R.string.backup_page_export_desc)
                            }
                        )
                    },
                    leadingContent = {
                        if (isExporting) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(HugeIcons.File01, null)
                        }
                    },
                )

                item(
                    onClick = if (!isRestoring) {
                        {
                            openDocumentLauncher.launch(arrayOf("application/zip"))
                        }
                    } else null,
                    headlineContent = { Text(stringResource(R.string.backup_page_local_backup_import)) },
                    supportingContent = {
                        Text(
                            if (isRestoring) {
                                stringResource(R.string.backup_page_importing)
                            } else {
                                stringResource(R.string.backup_page_import_desc)
                            }
                        )
                    },
                    leadingContent = {
                        if (isRestoring) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(HugeIcons.FileImport, null)
                        }
                    },
                )
            }
        }

    }

    pendingRestoreUri?.let { pendingUri ->
        RestoreConfirmDialog(
            onConfirm = {
                val uriToRestore = pendingRestoreUri
                pendingRestoreUri = null
                if (uriToRestore != null) {
                    scope.launch {
                        isRestoring = true
                        try {
                            vm.restoreFromLocalUri(uriToRestore)
                            toaster.show(restoreSuccess, type = ToastType.Success)
                            onShowRestartDialog()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            toaster.show(
                                when (error) {
                                    is BackupRestoreOperationInProgressException -> restoreInProgress
                                    is BackupRestorePendingRestartConflictException -> restorePendingRestart
                                    else -> restoreFailedFmt.format(error.message ?: "")
                                },
                                type = ToastType.Error,
                            )
                        } finally {
                            isRestoring = false
                        }
                    }
                }
            },
            onDismiss = {
                pendingRestoreUri = null
            },
        )
    }
}
