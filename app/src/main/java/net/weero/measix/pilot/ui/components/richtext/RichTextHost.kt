package net.weero.measix.pilot.ui.components.richtext

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.weero.measix.pilot.R
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.service.*
import net.weero.measix.pilot.ui.context.LocalNavController
import net.weero.measix.pilot.ui.context.LocalToaster
import org.koin.compose.koinInject

val LocalRenderedContentSource = staticCompositionLocalOf<RenderedContentSource?> { null }
internal val LocalRichTextActions = staticCompositionLocalOf<RichTextActions?> { null }

internal class RichTextActions(
    val uriHandler: UriHandler,
    val exportText: (String, String, String) -> Unit,
    val preview: (String) -> Unit,
)

/** One pending picker belongs to the host, not to a recomposing code block or table cell. */
@Composable
fun RichTextHost(
    source: RenderedContentSource?,
    vararg values: ProvidedValue<*>,
    files: FileManagementApplicationService = koinInject(),
    exports: MediaExportService = koinInject(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val exportFailure = androidx.compose.ui.res.stringResource(R.string.workspace_detail_export_failed)
    val openFailure = androidx.compose.ui.res.stringResource(R.string.chat_message_attachment_open_failed)
    val previewUnavailable = androidx.compose.ui.res.stringResource(R.string.rendered_content_unavailable)
    val toaster = LocalToaster.current
    val navigator = LocalNavController.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<TextDocumentExport?>(null) }
    fun reportExportFailure() {
        toaster.show(exportFailure, type = ToastType.Error)
    }
    val picker = rememberLauncherForActivityResult(CreateTextDocument) { uri ->
        val request = pending
        pending = null
        if (uri != null) scope.launch(start = kotlinx.coroutines.CoroutineStart.ATOMIC) {
            try {
                exports.saveTextDocument(context, uri, request)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { reportExportFailure() }
        }
    }
    val handler = remember(source, scope, exports, context, toaster, openFailure) {
        object : UriHandler {
            override fun openUri(uri: String) {
                scope.launch {
                    try { exports.openContentLink(context, requireNotNull(source) { "content_source_unavailable" }, uri) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { toaster.show(openFailure, type = ToastType.Error) }
                }
            }
        }
    }
    val actions = RichTextActions(
        uriHandler = handler,
        exportText = { text, fileName, mime ->
            if (pending == null && source != null) {
                val request = TextDocumentExport(source, text, fileName, mime)
                pending = request
                try { picker.launch(request) }
                catch (_: Exception) { pending = null; reportExportFailure() }
            }
        },
        preview = { html ->
            if (source != null) scope.launch {
                try {
                    files.requireContentAccess(source)
                    navigator.navigate(Screen.ContentPreview(document = RenderedContent(source, html)))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { toaster.show(previewUnavailable, type = ToastType.Error) }
            }
        },
    )
    val imageResolver: suspend (String) -> ImageSource? = remember(source, files) {
        { url -> source?.let { files.resolveContentImage(it, url) } }
    }
    CompositionLocalProvider(
        *values,
        LocalRenderedContentSource provides source,
        LocalRichTextActions provides actions,
        LocalUriHandler provides handler,
        LocalImageSourceResolver provides imageResolver,
        content = content,
    )
}

private object CreateTextDocument : ActivityResultContract<TextDocumentExport, Uri?>() {
    override fun createIntent(context: Context, input: TextDocumentExport): Intent =
        ActivityResultContracts.CreateDocument(input.mimeType).createIntent(context, input.fileName)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent?.data?.takeIf { resultCode == Activity.RESULT_OK }
}

/** Render-only trees have no navigation authority or platform picker. */
internal object UnavailableContentUriHandler : UriHandler {
    override fun openUri(uri: String) = Unit
}
