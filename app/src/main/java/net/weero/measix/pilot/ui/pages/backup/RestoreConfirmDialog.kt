package net.weero.measix.pilot.ui.pages.backup

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import net.weero.measix.pilot.R

/**
 * Shared confirmation dialog for all backup restore entry points.
 *
 * The actual archive download, local file copy, and [stageRestore] must only
 * start after the user confirms. Selecting a file or navigating a backup list
 * may happen before confirmation without side effects.
 */
@Composable
fun RestoreConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.backup_page_restore_confirm_title))
        },
        text = {
            Text(stringResource(R.string.backup_page_restore_confirm_message))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.backup_page_restore_now))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
