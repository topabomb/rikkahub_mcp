package net.weero.measix.pilot.ui.components.files

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import net.weero.measix.pilot.R
import com.jvziyaoyao.scale.image.viewer.ImageViewer
import com.jvziyaoyao.scale.zoomable.zoomable.ZoomableGestureScope
import com.jvziyaoyao.scale.zoomable.zoomable.rememberZoomableState
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
        bitmap?.recycle(); bitmap = null
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
        catch (error: Exception) { error.printStackTrace(); onFailure(error) }
        finally { rendered?.recycle() }
    }
    BoxWithConstraints(modifier) {
        val sideControls = maxWidth >= 600.dp && maxHeight < 480.dp
        val controls = if (count == 0) 0.dp else 48.dp
        val viewport = Modifier.fillMaxSize().padding(
            end = if (sideControls) controls else 0.dp,
            bottom = if (sideControls) 0.dp else controls,
        )
        bitmap?.let { key(file, page) {
            val zoom = rememberZoomableState(Size(it.width.toFloat(), it.height.toFloat()))
            val scope = rememberCoroutineScope()
            DisposableEffect(zoom) { onDispose { zoom.cancel() } }
            ImageViewer(viewport, it.asImageBitmap(), zoom,
                detectGesture = ZoomableGestureScope(onDoubleTap = { offset -> scope.launch { zoom.toggleScale(offset) } }))
        } }
            ?: Box(viewport, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        if (count > 0) FlowRow(
            Modifier.align(if (sideControls) Alignment.CenterEnd else Alignment.BottomCenter)
                .then(if (sideControls) Modifier.width(48.dp) else Modifier.height(48.dp)),
            maxItemsInEachRow = if (sideControls) 1 else 3,
            horizontalArrangement = Arrangement.Center,
            verticalArrangement = Arrangement.Center,
        ) {
            IconButton({ page-- }, enabled = page > 0) {
                Icon(HugeIcons.ArrowLeft01, stringResource(R.string.file_preview_previous_page))
            }
            Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                Text("${page + 1} / $count", style = MaterialTheme.typography.labelMedium)
            }
            IconButton({ page++ }, enabled = page + 1 < count) {
                Icon(HugeIcons.ArrowRight01, stringResource(R.string.file_preview_next_page))
            }
        }
    }
}
