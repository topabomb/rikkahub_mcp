package net.weero.measix.pilot.ui.components.richtext

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.request.placeholder
import net.weero.measix.pilot.R
import net.weero.measix.pilot.ui.components.ui.ImagePreviewAction
import net.weero.measix.pilot.ui.components.ui.ImagePreviewDialog
import net.weero.measix.pilot.ui.components.ui.LocalExportContext
import net.weero.measix.pilot.ui.components.ui.LocalImagePreviewActions
import net.weero.measix.pilot.ui.components.ui.LocalImagePreviewOverlay
import net.weero.measix.pilot.ui.modifier.shimmer
import net.weero.measix.pilot.ui.theme.LocalDarkMode

@Composable
fun ZoomableAsyncImage(
    model: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = DefaultAlpha,
    albumProvider: (() -> List<String>)? = null,
    extraActions: List<ImagePreviewAction>? = null,
    overlay: (@Composable () -> Unit)? = null,
) {
    var showImageViewer by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val placeholder = if(LocalDarkMode.current) R.drawable.placeholder_dark else R.drawable.placeholder
    val export = LocalExportContext.current
    val coilModel = ImageRequest.Builder(context)
        .data(model)
        .placeholder(placeholder)
        .crossfade(false)
        .allowHardware(!export)
        .build()
    var loading by remember { mutableStateOf(false) }
    AsyncImage(
        model = coilModel,
        contentDescription = contentDescription,
        modifier = modifier
            .shimmer(isLoading = loading)
            .clickable {
                val album = albumProvider?.invoke().orEmpty()
                if (resolveViewerImages(album, model).first.isNotEmpty()) {
                    showImageViewer = true
                }
            },
        contentScale = contentScale,
        alpha = alpha,
        alignment = alignment,
        onLoading = {
            loading = true
        },
        onSuccess = {
            loading = false
        },
        onError = {
            loading = false
        },
    )
    if (showImageViewer) {
        // 点击期求值会话相册; 命中从该张浏览整本, 未命中或相册为空则单图
        val album = albumProvider?.invoke().orEmpty()
        val (viewerImages, startIndex) = resolveViewerImages(album, model)
        if (viewerImages.isNotEmpty()) {
            ImagePreviewDialog(
                images = viewerImages,
                onDismissRequest = { showImageViewer = false },
                initialIndex = startIndex,
                extraActions = extraActions ?: LocalImagePreviewActions.current,
                overlay = overlay ?: LocalImagePreviewOverlay.current,
            )
        }
    }
}

internal fun resolveViewerImages(album: List<String>, model: String?): Pair<List<String>, Int> {
    val url = model?.trim().orEmpty()
    if (url.isEmpty()) return emptyList<String>() to 0
    val index = album.indexOf(url)
    return if (index >= 0) album to index else listOf(url) to 0
}
