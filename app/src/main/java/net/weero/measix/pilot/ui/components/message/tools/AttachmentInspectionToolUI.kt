package net.weero.measix.pilot.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.View
import me.rerere.common.http.jsonObjectOrNull
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.ai.tools.ATTACHMENT_INSPECTION_TOOL_NAME

private const val REQUEST_MAX_CHARS = 80

/**
 * `inspect_attachments` 薄渲染器（设计文档 §13.10）：
 * 标题固定、摘要显示 request 与附件数、详情回退默认 JSON 预览；
 * 不在 UI 层发起识别、不展示内部观察文本缓存（成功结果作为普通文本 Tool Result 已可见）。
 */
object AttachmentInspectionToolUI : ToolUIRenderer {
    override val toolName: String = ATTACHMENT_INSPECTION_TOOL_NAME

    override fun icon(context: ToolUIContext) = HugeIcons.View

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.chat_message_tool_inspect_attachments_title)

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.arguments.getStringContent("request") != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val request = context.arguments.getStringContent("request").orEmpty()
        val attachments = (context.arguments.jsonObjectOrNull?.get("attachments") as? kotlinx.serialization.json.JsonArray)
            ?.size ?: 0
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
            if (attachments > 0) {
                Text(
                    text = stringResource(
                        R.string.chat_message_tool_inspect_attachments_count,
                        attachments,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
