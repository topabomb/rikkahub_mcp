package net.weero.measix.pilot.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.datetime.toJavaLocalDateTime
import me.rerere.ai.core.CURRENT_TOKEN_USAGE_SEMANTICS_VERSION
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.UsageCompleteness
import me.rerere.ai.ui.UIMessage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.Upload02
import me.rerere.hugeicons.stroke.Zap
import net.weero.measix.pilot.ui.context.LocalSettings
import net.weero.measix.pilot.utils.toFixed
import java.time.Duration

/**
 * 显示消息的技术统计信息（如 token 使用量）
 */
@Composable
fun ChatMessageNerdLine(
    message: UIMessage,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f),
) {
    val settings = LocalSettings.current.displaySetting

    ProvideTextStyle(MaterialTheme.typography.labelSmall.copy(color = color)) {
        CompositionLocalProvider(LocalContentColor provides color) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
                modifier = modifier.padding(horizontal = 4.dp),
            ) {
                val usage = message.usage
                if (settings.showTokenUsage && usage != null) {
                    val display = usage.toNerdLineDisplay()
                    // Input tokens
                    if (display.inputTokens != null || display.cacheReadInputTokens != null) {
                        StatsItem(
                            icon = {
                                Icon(
                                    imageVector = HugeIcons.Upload02,
                                    contentDescription = "Input",
                                    tint = color,
                                    modifier = Modifier.size(12.dp)
                                )
                            },
                            content = {
                                display.inputTokens?.let { inputTokens ->
                                    Text(text = "${inputTokens.formatTokenCount()} tokens")
                                }
                                display.cacheReadInputTokens?.let { cacheReadTokens ->
                                    Text(text = "(${cacheReadTokens.formatTokenCount()} cached)")
                                }
                            }
                        )
                    }
                    // Output tokens
                    display.outputTokens?.let { outputTokens ->
                        StatsItem(
                            icon = {
                                Icon(
                                    imageVector = HugeIcons.Download04,
                                    contentDescription = "Output",
                                    modifier = Modifier.size(12.dp)
                                )
                            },
                            content = {
                                Text(text = "${outputTokens.formatTokenCount()} tokens")
                            }
                        )
                    }
                    display.tokensPerSecond?.let { tokensPerSecond ->
                        StatsItem(
                            icon = {
                                Icon(
                                    imageVector = HugeIcons.Zap,
                                    contentDescription = "Speed",
                                    modifier = Modifier.size(12.dp)
                                )
                            },
                            content = {
                                Text(text = "${tokensPerSecond.toFixed(1)} tok/s")
                            }
                        )
                    }
                    // The clock intentionally remains whole-message elapsed time.
                    message.finishedAt?.let { finishedAt ->
                        val duration = Duration.between(
                            message.createdAt.toJavaLocalDateTime(),
                            finishedAt.toJavaLocalDateTime()
                        )
                        val seconds = (duration.toMillis() / 1000f).toFixed(1)
                        StatsItem(
                            icon = {
                                Icon(
                                    imageVector = HugeIcons.Clock02,
                                    contentDescription = "Duration",
                                    modifier = Modifier.size(12.dp)
                                )
                            },
                            content = {
                                Text(text = "${seconds}s")
                            }
                        )
                    }
                }
            }
        }
    }
}

internal data class NerdLineUsageDisplay(
    val inputTokens: Long?,
    val outputTokens: Long?,
    val cacheReadInputTokens: Long?,
    val tokensPerSecond: Double?,
)

internal fun TokenUsage.toNerdLineDisplay(): NerdLineUsageDisplay {
    val legacySemantics = semanticsVersion < CURRENT_TOKEN_USAGE_SEMANTICS_VERSION
    val legacyCore = legacySemantics || coreCompleteness == UsageCompleteness.LEGACY
    val legacyCacheRead = legacySemantics || cacheReadCompleteness == UsageCompleteness.LEGACY
    val showCore = legacyCore || coreCompleteness == UsageCompleteness.COMPLETE
    val showCacheRead = (legacyCacheRead || cacheReadCompleteness == UsageCompleteness.COMPLETE) &&
        (cacheReadInputTokens ?: 0L) > 0L
    val displayedOutput = outputTokens.takeIf { showCore }
    val requestDuration = providerRequestDurationMillis?.takeIf { it > 0L }
    val hasExactV2Core = semanticsVersion >= CURRENT_TOKEN_USAGE_SEMANTICS_VERSION &&
        coreCompleteness == UsageCompleteness.COMPLETE
    val tokensPerSecond = if (hasExactV2Core && displayedOutput != null && requestDuration != null) {
        displayedOutput.toDouble() / requestDuration * 1000.0
    } else {
        null
    }
    return NerdLineUsageDisplay(
        inputTokens = inputTokens.takeIf { showCore } ?: 0L.takeIf { showCore && legacyCore },
        outputTokens = displayedOutput ?: 0L.takeIf { showCore && legacyCore },
        cacheReadInputTokens = cacheReadInputTokens.takeIf { showCacheRead },
        tokensPerSecond = tokensPerSecond,
    )
}

internal fun Long.formatTokenCount(): String {
    val absValue = kotlin.math.abs(this)
    val sign = if (this < 0) "-" else ""
    fun scaled(divisor: Long, suffix: String): String {
        val value = if (absValue % divisor == 0L) {
            (absValue / divisor).toString()
        } else {
            (absValue / divisor.toDouble()).toFixed(1)
        }
        return "$sign$value$suffix"
    }
    return when {
        absValue < 1_000L -> toString()
        absValue < 1_000_000L -> scaled(1_000L, "K")
        absValue < 1_000_000_000L -> scaled(1_000_000L, "M")
        else -> scaled(1_000_000_000L, "B")
    }
}

@Composable
fun StatsItem(
    icon: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        icon()
        content()
    }
}
