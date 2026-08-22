package net.weero.measix.pilot.ui.components.message.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.View
import me.rerere.common.http.jsonObjectOrNull
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.ai.tools.ATTACHMENT_INSPECTION_TOOL_NAME
import net.weero.measix.pilot.ui.components.message.LocalAttachmentPreview
import net.weero.measix.pilot.ui.components.message.LocalConversationImages
import net.weero.measix.pilot.ui.components.richtext.ZoomableAsyncImage

private const val REQUEST_MAX_CHARS = 80
private const val THUMBNAIL_SIZE = 64

/**
 * `inspect_attachments` 薄渲染器（设计文档 §8.7 / §13.10）：
 * 标题固定、`request` 1–2 行摘要、输入附件按参数顺序显示约 64dp 缩略图；
 * ref 解析不到本地资产时退化为 Image 占位，不让 UI 失败。
 * UI 只读取已存在的 attachment facts——不触发识别模型、Provider 或远程下载；
 * UI 可显示缩略图不代表当前模型收到图片像素（presentation 与 projection 解耦）。
 */
object AttachmentInspectionToolUI : ToolUIRenderer {
    override val toolName: String = ATTACHMENT_INSPECTION_TOOL_NAME

    override fun icon(context: ToolUIContext) = HugeIcons.View

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
}
