package net.weero.measix.pilot.ui.components.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import coil3.compose.rememberAsyncImagePainter
import com.dokar.sonner.ToastType
import com.jvziyaoyao.scale.image.pager.ImagePager
import com.jvziyaoyao.scale.zoomable.pager.PagerGestureScope
import com.jvziyaoyao.scale.zoomable.pager.rememberZoomablePagerState
import com.jvziyaoyao.scale.zoomable.zoomable.ZoomableViewState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.InformationCircle
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.files.FileFolders
import net.weero.measix.pilot.data.files.FilesManager
import net.weero.measix.pilot.data.imggen.GeneratedMediaStore
import net.weero.measix.pilot.ui.context.LocalToaster
import net.weero.measix.pilot.utils.fileSizeToString
import net.weero.measix.pilot.utils.toLocalDateTime
import org.koin.compose.koinInject
import java.io.File
import java.time.Instant
import kotlin.math.abs

// 拖拽关闭: 容器最大缩小比例
private const val DRAG_DISMISS_SCALE_FACTOR = 0.35f

// 拖拽关闭: 释放关闭的位移阈值(容器高度比例)
private const val DRAG_DISMISS_DISTANCE_RATIO = 0.2f

// 拖拽关闭: 释放关闭的竖直速度阈值(px/s)
private const val DRAG_DISMISS_VELOCITY_THRESHOLD = 2000f

// 判定竖直拖拽的主导系数: |dy| 须超过 |dx| 的该倍数
private const val VERTICAL_DOMINANCE_RATIO = 1.5f

@Composable
fun ImagePreviewDialog(
    images: List<String>,
    onDismissRequest: () -> Unit,
    initialIndex: Int = 0,
) {
    val context = LocalContext.current
    val filesManager: FilesManager = koinInject()
    // 空列表直接不组合, 防止 0 页查看器与保存按钮越界
    if (images.isEmpty()) return
    val pageCount = images.size
    val startIndex = initialIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    val state = rememberZoomablePagerState(initialPage = startIndex) { pageCount }
    val toaster = LocalToaster.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val savingToast = stringResource(R.string.image_viewer_saving)
    val savedToast = stringResource(R.string.image_viewer_saved)
    val saveFailedFormat = stringResource(R.string.image_viewer_save_failed)
    val saveActionDescription = stringResource(R.string.image_viewer_save_content_description)

    // 拖拽关闭的容器位移(px); 拖拽中直写状态, 收尾动画由 animate() 驱动
    val dragOffsetY = remember { mutableFloatStateOf(0f) }
    val dragOffsetX = remember { mutableFloatStateOf(0f) }
    val settleJobState = remember { mutableStateOf<Job?>(null) }
    // remember 避免 PagerGestureScope 参数不稳定导致翻页时浅重组
    val pagerGestureScope = remember(onDismissRequest) {
        PagerGestureScope(onTap = onDismissRequest)
    }

    // 图片信息面板: 翻页自动随当前页重查; IO 解析只读头不解码像素
    var infoVisible by remember { mutableStateOf(false) }
    val currentUrl = images.getOrNull(state.currentPage)
    var imageInfo by remember(currentUrl) { mutableStateOf<ImageInfo?>(null) }
    LaunchedEffect(currentUrl) {
        val url = currentUrl ?: return@LaunchedEffect
        imageInfo = withContext(Dispatchers.IO) { resolveImageInfo(context, url) }
    }
    val infoBlocked = rememberUpdatedState(infoVisible)

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 背景随拖拽进度渐隐
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = scrimAlpha(dragOffsetY.floatValue, size.height)
                    }
                    .background(Color.Black)
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // pointerInput 必须在 graphicsLayer 之外: 若在位移/缩放图层内侧,
                    // 指针坐标会被图层逆变换, 拖拽反馈反向且隔帧抖动
                    .pointerInput(state, onDismissRequest) {
                        detectDragDismissGesture(
                            scope = scope,
                            zoomableViewState = state.zoomableViewState,
                            dragOffsetX = dragOffsetX,
                            dragOffsetY = dragOffsetY,
                            settleJobState = settleJobState,
                            infoBlocked = infoBlocked,
                            onDismissRequest = onDismissRequest,
                        )
                    }
                    .graphicsLayer {
                        val progress = dragProgress(dragOffsetY.floatValue, size.height)
                        translationX = dragOffsetX.floatValue
                        translationY = dragOffsetY.floatValue
                        val scale = 1f - progress * DRAG_DISMISS_SCALE_FACTOR
                        scaleX = scale
                        scaleY = scale
                    }
            ) {
                ImagePager(
                    modifier = Modifier.fillMaxSize(),
                    pagerState = state,
                    detectGesture = pagerGestureScope,
                    imageLoader = { index ->
                        val painter = rememberAsyncImagePainter(images[index])
                        return@ImagePager Pair(painter, painter.intrinsicSize)
                    },
                )
            }

            // 叠加层统一放在全屏 Box 中, 以容器高度(而非 Text/Row 自身高度)换算淡出进度
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = overlayAlpha(dragOffsetY.floatValue, size.height)
                    }
            ) {
                if (pageCount > 1) {
                    Text(
                        text = "${state.currentPage + 1} / $pageCount",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 32.dp),
                    )
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    IconButton(
                        onClick = { infoVisible = true }
                    ) {
                        Icon(
                            HugeIcons.InformationCircle,
                            stringResource(R.string.image_viewer_info_content_description),
                            tint = Color.White
                        )
                    }
                    IconButton(
                        onClick = {
                            lifecycleOwner.lifecycleScope.launch {
                                runCatching {
                                    toaster.show(savingToast)
                                    val imgUrl = images[state.currentPage]
                                    filesManager.saveMessageImage(context, imgUrl)
                                    toaster.show(message = savedToast, type = ToastType.Success)
                                }.onFailure {
                                    it.printStackTrace()
                                    toaster.show(
                                        message = saveFailedFormat.format(it.message),
                                        type = ToastType.Error
                                    )
                                }
                            }
                        }
                    ) {
                        Icon(
                            HugeIcons.Download01,
                            saveActionDescription,
                            tint = Color.White
                        )
                    }
                }
            }

            // 图片信息面板: 点面板外 scrim 关闭; 面板自身消费点击防止误关
            if (infoVisible) {
                BackHandler { infoVisible = false }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { infoVisible = false }
                )
                ImageInfoPanel(
                    info = imageInfo,
                    onClose = { infoVisible = false },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

// 进度不设上限: 退出动画期间位移超过容器高, 背景与缩放按同进度继续变化
internal fun dragProgress(offsetY: Float, containerHeight: Float): Float {
    if (containerHeight <= 0f) return 0f
    return (abs(offsetY) / containerHeight).coerceAtLeast(0f)
}

internal fun scrimAlpha(offsetY: Float, containerHeight: Float): Float =
    (1f - dragProgress(offsetY, containerHeight) * 0.75f).coerceIn(0f, 1f)

internal fun overlayAlpha(offsetY: Float, containerHeight: Float): Float =
    (1f - dragProgress(offsetY, containerHeight) * 2f).coerceIn(0f, 1f)

/**
 * 未放大时竖直拖拽关闭手势。
 *
 * 在 Initial pass 中检测: 竖直主导(超过水平分量 [VERTICAL_DOMINANCE_RATIO] 倍)且当前页
 * 缩放率为 1 时消费指针, 使 ZoomableView/Pager 的手势检测因事件被消费而取消; 水平滑动与
 * 放大态不消费, 原有翻页/缩放/平移不受影响。释放时按位移或速度阈值决定关闭或回弹,
 * 手势被取消(双指/系统打断)时回弹。收尾动画在组合作用域 [scope] 中执行,
 * 不依赖可能已取消的 pointerInput 协程。
 */
private suspend fun PointerInputScope.detectDragDismissGesture(
    scope: CoroutineScope,
    zoomableViewState: State<ZoomableViewState?>,
    dragOffsetX: MutableFloatState,
    dragOffsetY: MutableFloatState,
    settleJobState: MutableState<Job?>,
    infoBlocked: State<Boolean>,
    onDismissRequest: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        // 信息面板打开时不接拖拽关闭手势(不消费事件, 翻页/缩放不受影响);
        // 不把 infoVisible 放进 pointerInput key, 避免手势中途重启检测器
        if (infoBlocked.value) return@awaitEachGesture
        var dragging = false
        var released = false
        var sumX = 0f
        var sumY = 0f
        val touchSlop = viewConfiguration.touchSlop
        val velocityTracker = VelocityTracker()
        try {
            loop@ while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                // 双指进入: 正在拖拽则回弹, 未开始则让给捏合手势
                if (event.changes.size > 1) break
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    // ACTION_CANCEL 到达时 change 已被标记消费, 视为中断(回弹)而非正常释放
                    if (!change.isConsumed) released = true
                    break
                }
                val delta = change.positionChange()
                if (delta == Offset.Zero) continue@loop
                velocityTracker.addPosition(change.uptimeMillis, change.position)
                if (!dragging) {
                    sumX += delta.x
                    sumY += delta.y
                    val verticalDominant = abs(sumY) > touchSlop &&
                        abs(sumY) > abs(sumX) * VERTICAL_DOMINANCE_RATIO
                    val horizontalDominant = abs(sumX) > touchSlop && abs(sumX) >= abs(sumY)
                    when {
                        verticalDominant -> {
                            val scale = zoomableViewState.value?.scale?.value
                            if (scale == null || abs(scale - 1f) > 0.01f) break
                            dragging = true
                            settleJobState.value?.cancel()
                        }

                        horizontalDominant -> break
                    }
                }
                if (dragging) {
                    change.consume()
                    dragOffsetY.floatValue += delta.y
                    dragOffsetX.floatValue += delta.x * 0.5f
                }
            }
        } finally {
            if (dragging) {
                val velocityY = runCatching {
                    velocityTracker.calculateVelocity().y
                }.getOrDefault(0f)
                val height = size.height.toFloat()
                val distanceDismiss =
                    abs(dragOffsetY.floatValue) > height * DRAG_DISMISS_DISTANCE_RATIO
                val velocityDismiss = released &&
                    abs(velocityY) > DRAG_DISMISS_VELOCITY_THRESHOLD &&
                    (velocityY > 0f) == (dragOffsetY.floatValue > 0f) &&
                    dragOffsetY.floatValue != 0f
                val dismiss = released && (distanceDismiss || velocityDismiss)
                settleJobState.value = settleDragAnimation(
                    scope = scope,
                    dragOffsetX = dragOffsetX,
                    dragOffsetY = dragOffsetY,
                    exitOffsetY = if (dragOffsetY.floatValue >= 0f) {
                        height * 1.2f
                    } else {
                        -height * 1.2f
                    },
                    dismiss = dismiss,
                    onDismissRequest = onDismissRequest,
                )
            }
        }
    }
}

private fun settleDragAnimation(
    scope: CoroutineScope,
    dragOffsetX: MutableFloatState,
    dragOffsetY: MutableFloatState,
    exitOffsetY: Float,
    dismiss: Boolean,
    onDismissRequest: () -> Unit,
): Job = scope.launch {
    val targetY = if (dismiss) exitOffsetY else 0f
    val targetX = if (dismiss) dragOffsetX.floatValue * 2f else 0f
    coroutineScope {
        launch {
            animate(
                initialValue = dragOffsetY.floatValue,
                targetValue = targetY,
                animationSpec = if (dismiss) tween(220) else spring(),
            ) { value, _ ->
                dragOffsetY.floatValue = value
            }
        }
        launch {
            animate(
                initialValue = dragOffsetX.floatValue,
                targetValue = targetX,
                animationSpec = if (dismiss) tween(220) else spring(),
            ) { value, _ ->
                dragOffsetX.floatValue = value
            }
        }
    }
    if (dismiss) {
        onDismissRequest()
    }
}

// ---- 图片信息面板 ----

internal enum class ImageInfoSource(val labelRes: Int) {
    Generated(R.string.image_viewer_info_source_generated),
    Upload(R.string.image_viewer_info_source_upload),
    Network(R.string.image_viewer_info_source_network),
    Inline(R.string.image_viewer_info_source_inline),
    Local(R.string.image_viewer_info_source_local),
}

internal data class ImageInfo(
    val source: ImageInfoSource,
    val fileName: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val sizeBytes: Long? = null,
    val mimeType: String? = null,
    val lastModifiedMs: Long? = null,
)

/**
 * 按 url 与应用目录推断图片来源: data: 前缀为内联, http(s) 为网络,
 * 位于 filesDir/images 为生成图片, filesDir/upload 为上传文件, 其余为本地文件
 */
internal fun classifyImageSource(url: String, filesDir: File): ImageInfoSource {
    if (url.startsWith("data:", ignoreCase = true)) return ImageInfoSource.Inline
    if (url.startsWith("http", ignoreCase = true)) return ImageInfoSource.Network
    val file = File(url.removePrefix("file://"))
    val generatedDir = File(filesDir, GeneratedMediaStore.IMAGES_DIR).absoluteFile.normalize()
    val uploadDir = File(filesDir, FileFolders.UPLOAD).absoluteFile.normalize()
    val normalized = file.absoluteFile.normalize()
    return when {
        normalized.path.startsWith(generatedDir.path + File.separator) -> ImageInfoSource.Generated
        normalized.path.startsWith(uploadDir.path + File.separator) -> ImageInfoSource.Upload
        else -> ImageInfoSource.Local
    }
}

private fun decodeBounds(bytes: ByteArray): Triple<Int, Int, String?>? = runCatching {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    Triple(opts.outWidth, opts.outHeight, opts.outMimeType.takeIf { it.isNotBlank() })
}.getOrNull()

private fun decodeBounds(file: File): Triple<Int, Int, String?>? = runCatching {
    file.inputStream().use { input ->
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(input, null, opts)
        Triple(opts.outWidth, opts.outHeight, opts.outMimeType.takeIf { it.isNotBlank() })
    }
}.getOrNull()

private val EXTENSION_MIME_MAP = mapOf(
    "png" to "image/png",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "webp" to "image/webp",
    "gif" to "image/gif",
    "bmp" to "image/bmp",
    "svg" to "image/svg+xml",
    "heic" to "image/heic",
    "heif" to "image/heif",
    "avif" to "image/avif",
)

private fun guessMimeFromExtension(extension: String?): String? =
    extension?.lowercase()?.takeIf { it.isNotBlank() }?.let(EXTENSION_MIME_MAP::get)

/** 只读图片头(不解码像素)与文件元数据, 全程 IO 线程 */
internal fun resolveImageInfo(context: Context, url: String): ImageInfo {
    val source = classifyImageSource(url, context.filesDir)
    return when {
        url.startsWith("data:", ignoreCase = true) -> {
            val bytes = runCatching {
                Base64.decode(url.substringAfter(',', ""), Base64.DEFAULT)
            }.getOrNull()
            val bounds = bytes?.let(::decodeBounds)
            ImageInfo(
                source = source,
                width = bounds?.first?.takeIf { it > 0 },
                height = bounds?.second?.takeIf { it > 0 },
                sizeBytes = bytes?.size?.toLong(),
                mimeType = bounds?.third
                    ?: url.substringBefore(';').removePrefix("data:").takeIf { it.isNotBlank() },
            )
        }

        source == ImageInfoSource.Network -> ImageInfo(
            source = source,
            fileName = url.substringAfterLast('/')
                .substringBefore('?')
                .takeIf { it.isNotBlank() },
            mimeType = guessMimeFromExtension(url.substringBefore('?').substringAfterLast('.')),
        )

        else -> {
            val file = File(url.removePrefix("file://"))
            val exists = file.exists()
            val bounds = if (exists) decodeBounds(file) else null
            ImageInfo(
                source = source,
                fileName = file.name.takeIf { it.isNotBlank() },
                width = bounds?.first?.takeIf { it > 0 },
                height = bounds?.second?.takeIf { it > 0 },
                sizeBytes = if (exists) file.length() else null,
                mimeType = bounds?.third ?: guessMimeFromExtension(file.extension),
                lastModifiedMs = if (exists && file.lastModified() > 0) file.lastModified() else null,
            )
        }
    }
}

@Composable
private fun ImageInfoPanel(
    info: ImageInfo?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val loaded = info
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(Color.Black.copy(alpha = 0.72f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { /* 消费点击, 防止点面板误关 */ }
            .padding(horizontal = 20.dp)
            .padding(top = 4.dp, bottom = 20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.image_viewer_info_title),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(
                    HugeIcons.Cancel01,
                    stringResource(R.string.image_viewer_info_close_content_description),
                    tint = Color.White,
                )
            }
        }
        ImageInfoRow(stringResource(R.string.image_viewer_info_source)) {
            loaded?.source?.let { stringResource(it.labelRes) }
        }
        ImageInfoRow(stringResource(R.string.image_viewer_info_filename)) { loaded?.fileName }
        ImageInfoRow(stringResource(R.string.image_viewer_info_dimensions)) {
            loaded?.takeIf { it.width != null && it.height != null }?.let { "${it.width} × ${it.height}" }
        }
        ImageInfoRow(stringResource(R.string.image_viewer_info_size)) {
            loaded?.sizeBytes?.takeIf { it > 0 }?.fileSizeToString()
        }
        ImageInfoRow(stringResource(R.string.image_viewer_info_format)) { loaded?.mimeType }
        ImageInfoRow(stringResource(R.string.image_viewer_info_modified)) {
            loaded?.lastModifiedMs?.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).toLocalDateTime() }
        }
    }
}

@Composable
private fun ImageInfoRow(label: String, value: @Composable () -> String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.6f),
        )
        Text(
            text = value() ?: "—",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White,
        )
    }
}

