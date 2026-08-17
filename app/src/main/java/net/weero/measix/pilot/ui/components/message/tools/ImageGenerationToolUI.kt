package net.weero.measix.pilot.ui.components.message.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import com.dokar.sonner.ToastType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiImage
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Copy01
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.ai.tools.local.GENERATE_IMAGE_TOOL_NAME
import net.weero.measix.pilot.data.ai.tools.local.ImageGenerationToolMetadata
import net.weero.measix.pilot.data.imggen.imageGenerationFailureStringRes
import net.weero.measix.pilot.ui.components.message.LocalConversationImages
import net.weero.measix.pilot.ui.components.message.isImagePartLoading
import net.weero.measix.pilot.ui.components.richtext.ZoomableAsyncImage
import net.weero.measix.pilot.ui.components.ui.FormItem
import net.weero.measix.pilot.ui.context.LocalToaster
import net.weero.measix.pilot.ui.modifier.shimmer
import net.weero.measix.pilot.utils.JsonInstant
import net.weero.measix.pilot.utils.jsonPrimitiveOrNull

internal fun ToolUIContext.imageGenerationMetadata(): ImageGenerationToolMetadata? {
    val raw = tool.metadata ?: return null
    return runCatching {
        JsonInstant.decodeFromJsonElement(ImageGenerationToolMetadata.serializer(), raw)
    }.getOrNull()?.takeIf { it.version == ImageGenerationToolMetadata.CURRENT_VERSION }
}

internal fun ToolUIContext.resultStatus(): String? =
    content?.let { (it as? JsonObject)?.get("status")?.jsonPrimitiveOrNull?.contentOrNull }

internal fun ToolUIContext.resultReason(): String? =
    content?.let { (it as? JsonObject)?.get("reason")?.jsonPrimitiveOrNull?.contentOrNull }

internal fun truncatePromptSummary(prompt: String, maxCodePoints: Int = 160): String {
    val codePoints = prompt.codePoints().toArray()
    return if (codePoints.size <= maxCodePoints) prompt else String(codePoints, 0, maxCodePoints) + "…"
}

object ImageGenerationToolUI : ToolUIRenderer {
    override val toolName: String = GENERATE_IMAGE_TOOL_NAME

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.AiImage

    @Composable
    override fun title(context: ToolUIContext): String {
        val metadata = context.imageGenerationMetadata()
        val status = context.resultStatus()
        val reason = context.resultReason()
        return when {
            status == "failed" || metadata?.status == "failed" -> stringResource(
                reasonString(reason ?: metadata?.reason)
            )
            status == "completed" || metadata?.phase == "completed" ->
                stringResource(R.string.chat_message_tool_generate_image_completed)
            metadata?.phase == "setting_background" ->
                stringResource(R.string.chat_message_tool_generate_image_setting_background)
            metadata?.phase == "persisting" ->
                stringResource(R.string.chat_message_tool_generate_image_persisting)
            metadata?.phase == "generating" ->
                stringResource(R.string.chat_message_tool_generate_image_generating)
            metadata?.phase == "queued" || context.loading ->
                stringResource(R.string.chat_message_tool_generate_image_queued)
            else -> stringResource(R.string.chat_message_tool_generate_image_queued)
        }
    }

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.arguments.getStringContent("prompt") != null ||
            context.arguments.getStringContent("set_as_background") != null ||
            context.content != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val prompt = context.arguments.getStringContent("prompt").orEmpty()
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (prompt.isNotEmpty()) {
                Text(
                    text = truncatePromptSummary(prompt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            BackgroundSummary(context)
        }
    }

    @Composable
    private fun BackgroundSummary(context: ToolUIContext) {
        val requestedBackground = context.arguments.getStringContent("set_as_background") == "true" ||
            ((context.content as? JsonObject)
                ?.get("background") as? JsonObject)
                ?.get("requested")
                ?.jsonPrimitiveOrNull
                ?.contentOrNull == "true"
        val backgroundUpdated = ((context.content as? JsonObject)
            ?.get("background") as? JsonObject)
            ?.get("updated")
            ?.jsonPrimitiveOrNull
            ?.contentOrNull == "true"
        val backgroundReason = ((context.content as? JsonObject)
            ?.get("background") as? JsonObject)
            ?.get("reason")
            ?.jsonPrimitiveOrNull
            ?.contentOrNull
        if (requestedBackground || backgroundUpdated || backgroundReason != null) {
            Text(
                text = when {
                    context.loading && requestedBackground && context.resultStatus() == null ->
                        stringResource(R.string.chat_message_tool_generate_image_background_pending)
                    backgroundUpdated ->
                        stringResource(R.string.chat_message_tool_generate_image_background_updated)
                    backgroundReason != null ->
                        stringResource(backgroundReasonString(backgroundReason))
                    else -> stringResource(R.string.chat_message_tool_generate_image_background_pending)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val metadata = context.imageGenerationMetadata()
        if (metadata == null) {
            DefaultToolPreview(context = context)
            return
        }
        val clipboard = LocalClipboardManager.current
        val toaster = LocalToaster.current
        val images = context.tool.output.filterIsInstance<UIMessagePart.Image>()
        val filePath = ((context.content as? JsonObject)?.get("file") as? JsonObject)
            ?.get("path")
            ?.jsonPrimitiveOrNull
            ?.contentOrNull
        val prompt = context.arguments.getStringContent("prompt").orEmpty()
        var showCallDetails by remember { mutableStateOf(false) }
        val promptCopiedToast = stringResource(R.string.chat_message_tool_generate_image_prompt_copied)
        val pathCopiedToast = stringResource(R.string.chat_message_tool_generate_image_path_copied)
        // 会话级时序相册: 稳定 provider, 点击期由 ZoomableAsyncImage 求值
        val conversationAlbum = LocalConversationImages.current
        Column(
            modifier = Modifier
                .fillMaxHeight(0.8f)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            images.fastForEach { image ->
                if (isImagePartLoading(image.url)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .shimmer(isLoading = true)
                    )
                } else {
                    ZoomableAsyncImage(
                        model = image.url,
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth(),
                        albumProvider = conversationAlbum,
                    )
                }
            }
            metadata.providerName?.takeIf { it.isNotBlank() }?.let { providerName ->
                Text(
                    text = stringResource(R.string.chat_message_tool_generate_image_provider, providerName),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            metadata.modelName?.takeIf { it.isNotBlank() }?.let { modelName ->
                Text(
                    text = stringResource(R.string.chat_message_tool_generate_image_model, modelName),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (prompt.isNotEmpty()) {
                FormItem(
                    label = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.chat_message_tool_generate_image_prompt_label),
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(prompt))
                                    toaster.show(message = promptCopiedToast, type = ToastType.Success)
                                }
                            ) {
                                Icon(
                                    imageVector = HugeIcons.Copy01,
                                    contentDescription = stringResource(
                                        R.string.chat_message_tool_generate_image_copy_prompt
                                    ),
                                )
                            }
                        }
                    },
                ) {
                    Text(text = prompt, style = MaterialTheme.typography.bodyMedium)
                }
            }
            BackgroundSummary(context)
            if (!filePath.isNullOrBlank()) {
                FormItem(
                    label = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.chat_message_tool_generate_image_file_path),
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(filePath))
                                    toaster.show(message = pathCopiedToast, type = ToastType.Success)
                                }
                            ) {
                                Icon(
                                    imageVector = HugeIcons.Copy01,
                                    contentDescription = stringResource(
                                        R.string.chat_message_tool_generate_image_copy_path
                                    ),
                                )
                            }
                        }
                    },
                ) {
                    Text(
                        text = filePath,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showCallDetails = !showCallDetails }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.chat_message_tool_generate_image_call_details),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (showCallDetails) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                    contentDescription = null,
                )
            }
            if (showCallDetails) {
                ToolCallJsonDetails(context = context, includeImages = false)
            }
        }
    }
}

private fun reasonString(reason: String?): Int = imageGenerationFailureStringRes(reason)

private fun backgroundReasonString(reason: String?): Int = when (reason) {
    "assistant_not_found" -> R.string.chat_message_tool_generate_image_background_assistant_missing
    "background_copy_failed" -> R.string.chat_message_tool_generate_image_background_copy_failed
    "settings_write_failed", "settings_write_rejected" ->
        R.string.chat_message_tool_generate_image_background_settings_failed
    else -> R.string.chat_message_tool_generate_image_failed
}
