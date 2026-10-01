package net.weero.measix.pilot.ui.components.message

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import net.weero.measix.pilot.R
import net.weero.measix.pilot.service.AttachmentPreview
import net.weero.measix.pilot.service.ChatError
import net.weero.measix.pilot.service.ChatErrorRetention
import net.weero.measix.pilot.ui.components.ui.ErrorCard

@Composable
internal fun AttachmentPreviewDiagnostic(preview: AttachmentPreview?) {
    val diagnostic = preview?.diagnostic ?: return
    val title = stringResource(R.string.chat_message_attachment_open_failed)
    val error = remember(preview, title) {
        ChatError(title = title, detail = diagnostic, retention = ChatErrorRetention.UNTIL_DISMISSED)
    }
    ErrorCard(error = error, onRetry = LocalAttachmentPreviewRetry.current)
}
