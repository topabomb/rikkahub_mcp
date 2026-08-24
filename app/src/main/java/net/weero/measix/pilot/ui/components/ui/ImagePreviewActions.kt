package net.weero.measix.pilot.ui.components.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.dokar.sonner.ToastType
import com.dokar.sonner.ToasterState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ImageComposition
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.getAssistantById
import net.weero.measix.pilot.data.datastore.getCurrentAssistant
import net.weero.measix.pilot.data.imggen.AssistantBackgroundService
import net.weero.measix.pilot.data.imggen.BackgroundUpdateResult
import net.weero.measix.pilot.ui.components.ai.AssistantPickerSheet
import org.koin.compose.koinInject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.uuid.Uuid

/**
 * 查看器底部栏的调用方动作。差异化逻辑留在调用方:
 * 查看器只负责画按钮并把当前页 url 与 Dialog 内 Toaster 交回来。
 */
data class ImagePreviewAction(
    val icon: ImageVector,
    val contentDescription: String,
    val onClick: (url: String, toaster: ToasterState) -> Unit,
)

private val EmptyImagePreviewActions: List<ImagePreviewAction> = emptyList()

val LocalImagePreviewActions = compositionLocalOf { EmptyImagePreviewActions }

val LocalImagePreviewOverlay = compositionLocalOf<(@Composable () -> Unit)?> { null }

internal const val IMAGE_VIEWER_SAVE_TOAST_ID = "image-viewer-save"
internal const val IMAGE_VIEWER_BACKGROUND_TOAST_ID = "image-viewer-background"

internal fun backgroundFailureMessageRes(reason: String?): Int = when (reason) {
    "assistant_not_found" -> R.string.chat_message_tool_generate_image_background_assistant_missing
    "background_copy_failed" -> R.string.chat_message_tool_generate_image_background_copy_failed
    "settings_write_failed", "settings_write_rejected" ->
        R.string.chat_message_tool_generate_image_background_settings_failed
    else -> R.string.image_viewer_background_failed
}

internal fun backgroundFailureMessage(context: Context, reason: String?): String {
    val res = backgroundFailureMessageRes(reason)
    return if (res == R.string.image_viewer_background_failed) {
        context.getString(res, reason.orEmpty().ifBlank { "unknown" })
    } else {
        context.getString(res)
    }
}

internal suspend fun applyImageAsBackground(
    url: String,
    assistantId: Uuid,
    backgroundService: AssistantBackgroundService,
): BackgroundUpdateResult = backgroundService.replaceUserSelectedBackground(assistantId, url)

internal fun assistantDisplayName(name: String?, fallback: String): String =
    name?.trim().orEmpty().ifBlank { fallback }

private data class PendingBackgroundChoice(
    val url: String,
    val toaster: ToasterState,
    val assistantId: Uuid,
    val assistantName: String,
)

private data class PendingAssistantPick(
    val url: String,
    val toaster: ToasterState,
)

class ImageBackgroundHost(
    val action: ImagePreviewAction,
    val overlay: @Composable () -> Unit,
)

/**
 * 设为背景的调用方宿主: 查看器只画按钮, 选助手 / 确认 / 写入都在这里。
 *
 * [assistantId] 非空(聊天 / Prompt 预览 / 子助手)时跳过选择器;
 * 为空(文生图橱窗 / 文件管理)时先弹选择器。助手确定后一律再确认一次。
 */
@Composable
fun rememberImageBackgroundHost(
    settings: Settings,
    assistantId: Uuid? = null,
): ImageBackgroundHost {
    val context = LocalContext.current
    val backgroundService: AssistantBackgroundService = koinInject()
    val scope = rememberCoroutineScope()
    val settingsState = rememberUpdatedState(settings)
    val assistantIdState = rememberUpdatedState(assistantId)
    val defaultAssistantName = stringResource(R.string.assistant_page_default_assistant)
    val description = stringResource(R.string.image_viewer_set_as_background)
    val pickerTitle = stringResource(R.string.image_viewer_select_assistant_title)
    val missingAssistantText = stringResource(
        R.string.chat_message_tool_generate_image_background_assistant_missing
    )
    val applying = remember { AtomicBoolean(false) }
    var pendingPick by remember { mutableStateOf<PendingAssistantPick?>(null) }
    var pendingConfirm by remember { mutableStateOf<PendingBackgroundChoice?>(null) }

    val action = remember(description, defaultAssistantName, missingAssistantText) {
        ImagePreviewAction(
            icon = HugeIcons.ImageComposition,
            contentDescription = description,
            onClick = { url, toaster ->
                if (applying.get()) return@ImagePreviewAction
                val knownId = assistantIdState.value
                if (knownId == null) {
                    pendingPick = PendingAssistantPick(url, toaster)
                    return@ImagePreviewAction
                }
                val assistant = settingsState.value.getAssistantById(knownId)
                if (assistant == null) {
                    toaster.show(
                        message = missingAssistantText,
                        type = ToastType.Error,
                        id = IMAGE_VIEWER_BACKGROUND_TOAST_ID,
                    )
                    return@ImagePreviewAction
                }
                pendingConfirm = PendingBackgroundChoice(
                    url = url,
                    toaster = toaster,
                    assistantId = knownId,
                    assistantName = assistantDisplayName(assistant.name, defaultAssistantName),
                )
            },
        )
    }

    val overlay: @Composable () -> Unit = remember(pickerTitle, defaultAssistantName) {
        {
            DisposableEffect(Unit) {
                onDispose {
                    pendingPick = null
                    pendingConfirm = null
                }
            }
            val pick = pendingPick
            if (pick != null) {
                AssistantPickerSheet(
                    settings = settingsState.value,
                    currentAssistant = settingsState.value.getCurrentAssistant(),
                    title = pickerTitle,
                    forceDialog = true,
                    allowManage = false,
                    onAssistantSelected = { assistant ->
                        pendingPick = null
                        pendingConfirm = PendingBackgroundChoice(
                            url = pick.url,
                            toaster = pick.toaster,
                            assistantId = assistant.id,
                            assistantName = assistantDisplayName(assistant.name, defaultAssistantName),
                        )
                    },
                    onDismiss = { pendingPick = null },
                )
            }
            val confirm = pendingConfirm
            if (confirm != null) {
                ConfirmDialog(
                    show = true,
                    title = stringResource(R.string.image_viewer_background_confirm_title),
                    confirmText = stringResource(R.string.image_viewer_background_confirm_action),
                    dismissText = stringResource(R.string.common_cancel),
                    onConfirm = {
                        pendingConfirm = null
                        setBackgroundWithFeedback(
                            scope = scope,
                            context = context,
                            url = confirm.url,
                            assistantId = confirm.assistantId,
                            assistantName = confirm.assistantName,
                            toaster = confirm.toaster,
                            backgroundService = backgroundService,
                            applying = applying,
                        )
                    },
                    onDismiss = { pendingConfirm = null },
                ) {
                    Text(stringResource(R.string.image_viewer_background_confirm_message, confirm.assistantName))
                }
            }
        }
    }

    return remember(action, overlay) {
        ImageBackgroundHost(
            action = action,
            overlay = overlay,
        )
    }
}

internal fun setBackgroundWithFeedback(
    scope: CoroutineScope,
    context: Context,
    url: String,
    assistantId: Uuid,
    assistantName: String,
    toaster: ToasterState,
    backgroundService: AssistantBackgroundService,
    applying: AtomicBoolean,
) {
    if (!applying.compareAndSet(false, true)) return
    scope.launch {
        val toastId = IMAGE_VIEWER_BACKGROUND_TOAST_ID
        try {
            toaster.show(
                message = context.getString(R.string.image_viewer_background_setting),
                id = toastId,
                duration = Duration.INFINITE,
            )
            val result = applyImageAsBackground(url, assistantId, backgroundService)
            if (result.updated) {
                toaster.show(
                    message = context.getString(R.string.image_viewer_background_set, assistantName),
                    type = ToastType.Success,
                    id = toastId,
                )
            } else {
                toaster.show(
                    message = backgroundFailureMessage(context, result.reason),
                    type = ToastType.Error,
                    id = toastId,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            toaster.show(
                message = backgroundFailureMessage(context, error.message),
                type = ToastType.Error,
                id = toastId,
            )
        } finally {
            applying.set(false)
        }
    }
}

internal fun generatedDeleteLabel(prompt: String?, fallback: String): String =
    shortGeneratedLabel(prompt.orEmpty(), fallback)

internal fun shortGeneratedLabel(prompt: String, fallback: String, maxChars: Int = 24): String {
    val trimmed = prompt.trim()
    if (trimmed.isEmpty()) return fallback
    val codePoints = trimmed.codePoints().toArray()
    return if (codePoints.size <= maxChars) trimmed else String(codePoints, 0, maxChars) + "…"
}
