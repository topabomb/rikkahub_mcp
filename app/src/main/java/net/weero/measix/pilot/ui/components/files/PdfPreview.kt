package net.weero.measix.pilot.ui.components.files

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.*

/** One bounded page at a time. The producing coroutine owns the renderer and file descriptor. */
@Composable
internal fun PdfPreview(file: File, modifier: Modifier = Modifier, onFailure: (Throwable) -> Unit) {
    var page by remember(file) { mutableIntStateOf(0) }
    var count by remember(file) { mutableIntStateOf(0) }
    var bitmap by remember(file) { mutableStateOf<Bitmap?>(null) }
    DisposableEffect(file) { onDispose { bitmap?.recycle(); bitmap = null } }
    LaunchedEffect(file, page) {
        var rendered: Bitmap? = null
        try {
            val result = withContext(Dispatchers.IO) {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        renderer.openPage(page).use { pdf ->
                            val scale = minOf(2f, 2000f / maxOf(pdf.width, pdf.height))
                            val width = (pdf.width * scale).toInt().coerceAtLeast(1)
                            val height = (pdf.height * scale).toInt().coerceAtLeast(1)
                            require(width.toLong() * height <= 4_000_000) { "workspace_pdf_pixel_limit" }
                            rendered = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            requireNotNull(rendered).eraseColor(android.graphics.Color.WHITE)
                            pdf.render(requireNotNull(rendered), null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            renderer.pageCount
                        }
                    }
                }
            }
            ensureActive()
            bitmap?.recycle(); bitmap = rendered; rendered = null; count = result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { onFailure(error) }
        finally { rendered?.recycle() }
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.weight(1f).fillMaxWidth()) }
            ?: Box(Modifier.weight(1f)) { CircularProgressIndicator() }
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ page-- }, enabled = page > 0) { Text("‹") }
            Text("${page + 1} / $count")
            TextButton({ page++ }, enabled = page + 1 < count) { Text("›") }
        }
    }
}
