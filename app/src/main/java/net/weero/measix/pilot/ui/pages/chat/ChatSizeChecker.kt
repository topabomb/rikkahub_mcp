package net.weero.measix.pilot.ui.pages.chat

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import me.rerere.ai.core.MessageRole
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.model.MessageNode

// 消息节点数量警告阈值
const val MESSAGE_NODE_WARNING_THRESHOLD = 768
const val LAST_ASSISTANT_ESTIMATED_CONTEXT_TOKEN_WARNING_THRESHOLD = 300_000L

data class ConversationSizeInfo(
    val nodeCount: Int,
    val lastAssistantEstimatedContextTokens: Long,
    val exceedNodeCountThreshold: Boolean,
    val exceedInputTokenThreshold: Boolean,
    val showWarning: Boolean
)

private val DefaultSizeInfo = ConversationSizeInfo(
    nodeCount = 0,
    lastAssistantEstimatedContextTokens = 0L,
    exceedNodeCountThreshold = false,
    exceedInputTokenThreshold = false,
    showWarning = false
)

@Composable
fun rememberConversationSizeInfo(nodes: List<MessageNode>): ConversationSizeInfo {
    return remember(nodes) { calculateConversationSizeInfo(nodes) }
}

internal fun calculateConversationSizeInfo(nodes: List<MessageNode>): ConversationSizeInfo {
    val nodeCount = nodes.size
    val lastAssistantEstimatedContextTokens = nodes.asReversed()
        .map { it.currentMessage }
        .firstOrNull { it.role == MessageRole.ASSISTANT }
        ?.usage
        ?.latestRequestEstimatedContextTokens
        ?: 0L
    val exceedNodeCountThreshold = nodeCount > MESSAGE_NODE_WARNING_THRESHOLD
    val exceedInputTokenThreshold = lastAssistantEstimatedContextTokens >
        LAST_ASSISTANT_ESTIMATED_CONTEXT_TOKEN_WARNING_THRESHOLD
    return ConversationSizeInfo(
        nodeCount = nodeCount,
        lastAssistantEstimatedContextTokens = lastAssistantEstimatedContextTokens,
        exceedNodeCountThreshold = exceedNodeCountThreshold,
        exceedInputTokenThreshold = exceedInputTokenThreshold,
        showWarning = exceedNodeCountThreshold && exceedInputTokenThreshold
    )
}

@Composable
fun ConversationSizeWarningDialog(
    sizeInfo: ConversationSizeInfo,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = HugeIcons.Alert01,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary
            )
        },
        title = {
            Text(text = stringResource(R.string.chat_size_dialog_title))
        },
        text = {
            Text(text = stringResource(R.string.chat_size_dialog_content, sizeInfo.nodeCount))
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.confirm))
            }
        }
    )
}
