package net.weero.measix.pilot.ui.components.ui

import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.service.ImageSource
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import net.weero.measix.pilot.service.ConfigurationQueryService
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.data.imggen.AssistantBackgroundTarget
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
import net.weero.measix.pilot.data.imggen.AssistantBackgroundService
import net.weero.measix.pilot.data.imggen.BackgroundUpdateResult
import net.weero.measix.pilot.ui.components.ai.AssistantPickerSheet
import org.koin.compose.koinInject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration

/**
 * 查看器底部栏的调用方动作。差异化逻辑留在调用方:
 * 查看器只负责画按钮并把当前页 url 与 Dialog 内 Toaster 交回来。
 */
data class ImagePreviewAction(
    val icon: ImageVector,
    val contentDescription: String,
    val onClick: (url: ImageSource, toaster: ToasterState) -> Unit,
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
    url: ImageSource,
    target: AssistantBackgroundTarget,
    backgroundService: AssistantBackgroundService,
): BackgroundUpdateResult = backgroundService.replaceUserSelectedBackground(target, url)

internal fun assistantDisplayName(name: String?, fallback: String): String =
    name?.trim().orEmpty().ifBlank { fallback }

private data class PendingBackgroundChoice(
    val url: ImageSource,
    val toaster: ToasterState,
    val target: AssistantBackgroundTarget,
    val assistantName: String,
)

private data class PendingAssistantPick(
    val selection: RealmSelection,
    val url: ImageSource,
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
internal fun rememberImageBackgroundHost(
    settings: Settings,
    assistantId: ConfigurationReference? = null,
    editSharedDefinition: Boolean = false,
): ImageBackgroundHost {
    val context = LocalContext.current
    val backgroundService: AssistantBackgroundService = koinInject()
    val queries: ConfigurationQueryService = koinInject()
    val catalog by remember(queries) { queries.observeAssistantCatalog() }.collectAsStateWithLifecycle(null)
    val catalogState = rememberUpdatedState(catalog)
    val definitionMode = rememberUpdatedState(editSharedDefinition)
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

    LaunchedEffect(catalog?.selection, assistantId, editSharedDefinition) {
        pendingPick = null
        pendingConfirm = null
    }
    val action = remember(description, defaultAssistantName, missingAssistantText) {
        ImagePreviewAction(
            icon = HugeIcons.ImageComposition,
            contentDescription = description,
            onClick = { url, toaster ->
                if (applying.get()) return@ImagePreviewAction
                val knownId = assistantIdState.value
                val current = catalogState.value
                if (knownId == null && !definitionMode.value && current != null) {
                    pendingPick = PendingAssistantPick(current.selection, url, toaster)
                    return@ImagePreviewAction
                }
                val assistant = if (definitionMode.value) knownId?.let { settingsState.value.getAssistantById(it) }
                    else current?.assistants?.get(knownId)
                val target = if (definitionMode.value && knownId is ConfigurationReference.User)
                    AssistantBackgroundTarget.Definition(knownId)
                else if (!definitionMode.value && knownId != null && current != null &&
                    current.resources.any { it.key.reference == knownId && it.access.canSelect })
                    AssistantBackgroundTarget.Page(current.selection, knownId)
                else null
                if (assistant == null || target == null) {
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
                    target = target,
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
            val currentCatalog = catalogState.value
            if (pick != null && currentCatalog?.selection == pick.selection) {
                AssistantPickerSheet(
                    settings = settingsState.value,
                    currentAssistantId = currentCatalog.selected.reference,
                    assistants = currentCatalog.assistants.values.toList(),
                    unavailableReasons = currentCatalog.resources.associate { it.key.reference to it.access.unavailableReason },
                    title = pickerTitle,
                    forceDialog = true,
                    allowManage = false,
                    onAssistantSelected = { assistant ->
                        pendingPick = null
                        pendingConfirm = PendingBackgroundChoice(
                            url = pick.url,
                            toaster = pick.toaster,
                            target = AssistantBackgroundTarget.Page(pick.selection, assistant.id),
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
                            target = confirm.target,
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
    url: ImageSource,
    target: AssistantBackgroundTarget,
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
            val result = applyImageAsBackground(url, target, backgroundService)
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
