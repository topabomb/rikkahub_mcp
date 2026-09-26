package net.weero.measix.pilot.data.ai.transformers

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.R

/**
 * 提示词页变量芯片的展示元数据。占位符 **值** 的唯一请求侧来源是
 * `TurnContextFactory` 在 START 时冻结的 `placeholderValues`；这里不保留第二套
 * 取值逻辑，避免芯片列表与冻结值漂移。
 */
data class PlaceholderInfo(
    val displayName: @Composable () -> Unit,
)

object DefaultPlaceholderProvider {
    val placeholders: Map<String, PlaceholderInfo> = linkedMapOf(
        "cur_date" to PlaceholderInfo { Text(stringResource(R.string.placeholder_current_date)) },
        "model_id" to PlaceholderInfo { Text(stringResource(R.string.placeholder_model_id)) },
        "model_name" to PlaceholderInfo { Text(stringResource(R.string.placeholder_model_name)) },
        "locale" to PlaceholderInfo { Text(stringResource(R.string.placeholder_locale)) },
        "timezone" to PlaceholderInfo { Text(stringResource(R.string.placeholder_timezone)) },
        "system_version" to PlaceholderInfo { Text(stringResource(R.string.placeholder_system_version)) },
        "device_info" to PlaceholderInfo { Text(stringResource(R.string.placeholder_device_info)) },
        // cur_date 一天一变；更高频的时间、电量字段会持续破坏提示词缓存前缀，因此不提供。
        "nickname" to PlaceholderInfo { Text(stringResource(R.string.placeholder_nickname)) },
        "char" to PlaceholderInfo { Text(stringResource(R.string.placeholder_char)) },
        "description" to PlaceholderInfo { Text(stringResource(R.string.placeholder_description)) },
        "user" to PlaceholderInfo { Text(stringResource(R.string.placeholder_user)) },
    )
}

/** Values are substituted once; braces in an inserted value are literal data. */
private val promptPlaceholderPattern = Regex("\\{\\{([^{}]+)\\}\\}|\\{([^{}]+)\\}")

fun renderPromptPlaceholders(text: String, values: Map<String, String>): String {
    val keys = values.mapKeys { it.key.lowercase(java.util.Locale.ROOT) }
    return promptPlaceholderPattern.replace(text) { match ->
        val key = match.groups[1]?.value ?: requireNotNull(match.groups[2]).value
        keys[key.lowercase(java.util.Locale.ROOT)] ?: match.value
    }
}

/** Store only variables actually used by this declared template, not the entire captured environment. */
internal fun usedPromptPlaceholderValues(text: String, values: Map<String, String>): Map<String, String> {
    val keys = promptPlaceholderPattern.findAll(text).map {
        (it.groups[1]?.value ?: requireNotNull(it.groups[2]).value).lowercase(java.util.Locale.ROOT)
    }.toSet()
    return values.filterKeys { it.lowercase(java.util.Locale.ROOT) in keys }
}

object PlaceholderTransformer : InputMessageTransformer {
    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> =
        messages.map { message ->
            if (ctx.requestOrigins.isApplicationHistory(message.id)) return@map message
            message.copy(parts = message.parts.map { part ->
                if (part is UIMessagePart.Text && !ctx.requestOrigins.isOpaque(part)) {
                    part.copy(text = renderPromptPlaceholders(part.text, ctx.promptInputs.placeholderValues))
                } else part
            })
        }
}
