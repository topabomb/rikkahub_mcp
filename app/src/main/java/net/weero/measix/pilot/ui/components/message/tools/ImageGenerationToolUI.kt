package net.weero.measix.pilot.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiImage
import me.rerere.hugeicons.stroke.Copy01
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.ai.tools.local.GENERATE_IMAGE_TOOL_NAME
import net.weero.measix.pilot.data.ai.tools.local.ImageGenerationToolMetadata
import net.weero.measix.pilot.ui.components.richtext.HighlightCodeBlock
import net.weero.measix.pilot.ui.components.richtext.ZoomableAsyncImage
import net.weero.measix.pilot.ui.components.ui.FormItem
import net.weero.measix.pilot.utils.JsonInstant
import net.weero.measix.pilot.utils.JsonInstantPretty
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
        val images = context.tool.output.filterIsInstance<UIMessagePart.Image>()
        val filePath = ((context.content as? JsonObject)?.get("file") as? JsonObject)
            ?.get("path")
            ?.jsonPrimitiveOrNull
            ?.contentOrNull
        val prompt = context.arguments.getStringContent("prompt").orEmpty()
        var showTechnical by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .fillMaxHeight(0.8f)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            images.fastForEach { image ->
                ZoomableAsyncImage(
                    model = image.url,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(),
                )
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
                FormItem(label = { Text(stringResource(R.string.chat_message_tool_generate_image_prompt_label)) }) {
                    Text(text = prompt, style = MaterialTheme.typography.bodyMedium)
                }
            }
            BackgroundSummary(context)
            if (!filePath.isNullOrBlank()) {
                FormItem(
                    label = { Text(stringResource(R.string.chat_message_tool_generate_image_file_path)) },
                    tail = {
                        IconButton(onClick = { clipboard.setText(AnnotatedString(filePath)) }) {
                            Icon(
                                imageVector = HugeIcons.Copy01,
                                contentDescription = stringResource(
                                    R.string.chat_message_tool_generate_image_copy_path
                                ),
                            )
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
            TextButton(
                onClick = { showTechnical = !showTechnical },
                modifier = Modifier.align(Alignment.Start),
            ) {
                Text(stringResource(R.string.chat_message_tool_generate_image_technical_details))
            }
            if (showTechnical) {
                FormItem(
                    label = {
                        Text(stringResource(R.string.chat_message_tool_call_label, context.tool.toolName))
                    }
                ) {
                    HighlightCodeBlock(
                        code = JsonInstantPretty.encodeToString(context.arguments),
                        language = "json",
                        style = TextStyle(fontSize = 10.sp, lineHeight = 12.sp),
                    )
                }
                context.tool.output.filterIsInstance<UIMessagePart.Text>().fastForEach { part ->
                    HighlightCodeBlock(
                        code = runCatching {
                            JsonInstantPretty.encodeToString(JsonInstant.parseToJsonElement(part.text))
                        }.getOrElse { part.text },
                        language = "json",
                        style = TextStyle(fontSize = 10.sp, lineHeight = 12.sp),
                    )
                }
            }
        }
    }
}

private fun reasonString(reason: String?): Int = when (reason) {
    "invalid_arguments" -> R.string.chat_message_tool_generate_image_failed_invalid_arguments
    "image_model_unavailable" -> R.string.chat_message_tool_generate_image_failed_model_unavailable
    "tool_revoked" -> R.string.chat_message_tool_generate_image_failed_revoked
    "image_model_changed" -> R.string.chat_message_tool_generate_image_failed_model_changed
    "provider_error" -> R.string.chat_message_tool_generate_image_failed_provider
    "invalid_result" -> R.string.chat_message_tool_generate_image_failed_invalid_result
    "persistence_error" -> R.string.chat_message_tool_generate_image_failed_persistence
    "assistant_not_found" -> R.string.chat_message_tool_generate_image_failed_assistant_missing
    "artifact_missing" -> R.string.chat_message_tool_generate_image_failed_artifact_missing
    else -> R.string.chat_message_tool_generate_image_failed
}

private fun backgroundReasonString(reason: String?): Int = when (reason) {
    "assistant_not_found" -> R.string.chat_message_tool_generate_image_background_assistant_missing
    "background_copy_failed" -> R.string.chat_message_tool_generate_image_background_copy_failed
    "settings_write_failed" -> R.string.chat_message_tool_generate_image_background_settings_failed
    else -> R.string.chat_message_tool_generate_image_failed
}
