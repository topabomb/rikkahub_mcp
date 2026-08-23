package net.weero.measix.pilot.ui.components.message.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.View
import me.rerere.common.http.jsonObjectOrNull
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.ai.attachments.attachmentInspectionFailureStringRes
import net.weero.measix.pilot.data.ai.tools.ATTACHMENT_INSPECTION_TOOL_NAME
import net.weero.measix.pilot.ui.components.message.LocalAttachmentPreview
import net.weero.measix.pilot.ui.components.message.LocalConversationImages
import net.weero.measix.pilot.ui.components.richtext.ZoomableAsyncImage
import net.weero.measix.pilot.utils.jsonPrimitiveOrNull

private const val REQUEST_MAX_CHARS = 80
private const val THUMBNAIL_SIZE = 64

/**
 * `inspect_attachments` 薄渲染器：
 * 标题固定、`request` 1–2 行摘要、输入附件按参数顺序显示约 64dp 缩略图；
 * ref 解析不到本地资产时退化为 Image 占位，不让 UI 失败。
 * 失败信封 `{status:"failed", reason, detail?}` 在标题与详情中以分类文案呈现。
 * UI 只读取已存在的 attachment facts——不触发识别模型、Provider 或远程下载；
 * UI 可显示缩略图不代表当前模型收到图片像素（presentation 与 projection 解耦）。
 */
object AttachmentInspectionToolUI : ToolUIRenderer {
    override val toolName: String = ATTACHMENT_INSPECTION_TOOL_NAME

    override fun icon(context: ToolUIContext) = HugeIcons.View

    @Composable
    override fun title(context: ToolUIContext): String = when {
        context.resultStatus() == "failed" ->
            stringResource(attachmentInspectionFailureStringRes(context.resultReason()))

        context.loading -> stringResource(R.string.chat_message_tool_inspection_running)

        else -> stringResource(R.string.chat_message_tool_inspection)
    }

    override fun hasSummary(context: ToolUIContext): Boolean = true

    @Composable
    override fun Summary(context: ToolUIContext) {
        val request = context.arguments.getStringContent("request").orEmpty()
        val refs = (context.arguments.jsonObjectOrNull?.get("attachments") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
        val previewResolver = LocalAttachmentPreview.current
        val albumProvider = LocalConversationImages.current

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (request.isNotEmpty()) {
                Text(
                    text = request.take(REQUEST_MAX_CHARS),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (refs.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    refs.forEach { ref ->
                        val url = previewResolver(ref)
                        if (url != null) {
                            ZoomableAsyncImage(
                                model = url,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(THUMBNAIL_SIZE.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop,
                                albumProvider = albumProvider,
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(THUMBNAIL_SIZE.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = HugeIcons.Image02,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        if (context.resultStatus() != "failed") {
            DefaultToolPreview(context = context)
            return
        }
        Column(
            modifier = Modifier
                .fillMaxHeight(0.8f)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(attachmentInspectionFailureStringRes(context.resultReason())),
                style = MaterialTheme.typography.titleMedium,
            )
            inspectionFailureDetail(context)?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ToolCallJsonDetails(context = context, includeImages = false)
        }
    }
}

private fun inspectionFailureDetail(context: ToolUIContext): String? =
    context.content?.let { (it as? JsonObject)?.get("detail")?.jsonPrimitiveOrNull?.contentOrNull }
