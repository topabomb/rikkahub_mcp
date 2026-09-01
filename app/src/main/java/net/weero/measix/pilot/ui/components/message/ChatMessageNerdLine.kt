package net.weero.measix.pilot.ui.components.message

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.toJavaLocalDateTime
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.UsageCompleteness
import me.rerere.ai.ui.UIMessage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.Cloud
import me.rerere.hugeicons.stroke.Database
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.Layers01
import me.rerere.hugeicons.stroke.Scissor
import me.rerere.hugeicons.stroke.StopWatch
import me.rerere.hugeicons.stroke.Upload02
import me.rerere.hugeicons.stroke.Zap
import net.weero.measix.pilot.ui.context.LocalSettings
import net.weero.measix.pilot.utils.toFixed
import java.time.Duration
import java.util.Locale

/** 每个 Assistant turn 的两行低强调统计；所有值只来自同一个 durable usage snapshot。 */
@Composable
fun ChatMessageNerdLine(
    message: UIMessage,
    turnFinished: Boolean,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f),
) {
    if (!LocalSettings.current.displaySetting.showTokenUsage) return
    val display = message.usage.toNerdLineDisplay()
    if (!display.hasSummary) return
    val totalMillis = message.finishedAt?.takeIf { turnFinished }?.let { finishedAt ->
        Duration.between(
            message.createdAt.toJavaLocalDateTime(),
            finishedAt.toJavaLocalDateTime(),
        ).toMillis().coerceAtLeast(0)
    }
    var expanded by remember(message.id) { mutableStateOf(false) }

    ProvideTextStyle(MaterialTheme.typography.labelSmall.copy(color = color)) {
        CompositionLocalProvider(LocalContentColor provides color) {
            Column(
                modifier = modifier.padding(horizontal = 4.dp).animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    modifier = Modifier.clickable { expanded = !expanded },
                    text = buildAnnotatedString {
                        display.summaryItems().forEachIndexed { index, metric ->
                            if (index > 0) append(" · ")
                            appendInlineContent(summaryMetricInlineId(index))
                            append('\u00a0')
                            append(metric.text)
                        }
                        append(' ')
                        appendInlineContent(SUMMARY_TOGGLE_INLINE_ID)
                    },
                    inlineContent = buildMap {
                        display.summaryItems().forEachIndexed { index, metric ->
                            put(
                                summaryMetricInlineId(index),
                                InlineTextContent(
                                    placeholder = summaryIconPlaceholder(),
                                ) {
                                    Icon(
                                        imageVector = when (metric.icon) {
                                            UsageSummaryIcon.CONTEXT -> HugeIcons.Layers01
                                            UsageSummaryIcon.CACHED -> HugeIcons.Database
                                            UsageSummaryIcon.SPEED -> HugeIcons.Zap
                                            UsageSummaryIcon.TTFT -> HugeIcons.StopWatch
                                            UsageSummaryIcon.TRIM -> HugeIcons.Scissor
                                        },
                                        contentDescription = metric.contentDescription,
                                        modifier = Modifier.size(12.dp),
                                        tint = if (metric.highlighted) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            LocalContentColor.current
                                        },
                                    )
                                },
                            )
                        }
                        put(SUMMARY_TOGGLE_INLINE_ID, InlineTextContent(
                            placeholder = Placeholder(
                                width = 12.sp,
                                height = 12.sp,
                                placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                            ),
                        ) {
                            Icon(
                                imageVector = HugeIcons.ArrowDown01,
                                contentDescription = if (expanded) "Collapse usage" else "Expand usage",
                                modifier = Modifier.size(12.dp).rotate(if (expanded) 180f else 0f),
                            )
                        })
                    },
                    softWrap = true,
                )
                AnimatedVisibility(visible = expanded) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        display.detailItems(totalMillis).forEach { UsageDetailItem(it) }
                    }
                }
            }
        }
    }
}

/** 摘要尾部的展开图标使用 inline content，保证始终紧贴最后一个统计项。 */
private const val SUMMARY_TOGGLE_INLINE_ID = "usage-summary-toggle"

private fun summaryMetricInlineId(index: Int): String = "usage-summary-metric-$index"

private fun summaryIconPlaceholder() = Placeholder(
    width = 12.sp,
    height = 12.sp,
    placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
)

/** 已关闭请求的 turn 级展示投影；未知值始终保持 null，不用零补齐。 */
internal data class NerdLineUsageDisplay(
    val latestContextTokens: Long?,
    val latestCacheHitPercent: Double?,
    val latestTokensPerSecond: Double?,
    val latestTtftMillis: Long?,
    val successfulToolTrimBatches: Int?,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val cacheReadInputTokens: Long?,
    val providerDurationMillis: Long?,
    val requestCount: Int?,
) {
    val hasSummary: Boolean
        get() = summaryItems().isNotEmpty()

    /** 第一行：各自按明确触发点刷新的请求摘要，以及本 turn 已提交滚动裁剪批次数。 */
    fun summaryText(): String = summaryItems().joinToString(" · ") { it.text }

    /** 缺失项隐藏；显式 cache hit=0 保留为图标后的 `0.0%`。 */
    internal fun summaryItems(): List<UsageSummaryMetric> = buildList {
        latestContextTokens?.let {
            add(UsageSummaryMetric(it.formatTokenCount(), UsageSummaryIcon.CONTEXT, "Context"))
        }
        latestCacheHitPercent?.let {
            add(UsageSummaryMetric("${it.formatCachePercent()}%", UsageSummaryIcon.CACHED, "Cached"))
        }
        latestTokensPerSecond?.let {
            add(UsageSummaryMetric("${it.toFixed(1)} tok/s", UsageSummaryIcon.SPEED, "Output speed"))
        }
        latestTtftMillis?.let {
            add(UsageSummaryMetric("TTFT ${it.formatMillis()}", UsageSummaryIcon.TTFT, "Time to first token"))
        }
        successfulToolTrimBatches?.takeIf { it > 0 }?.let {
            add(UsageSummaryMetric(it.toString(), UsageSummaryIcon.TRIM, "Tool trims", highlighted = true))
        }
    }

    /** 第二行的响应式独立项目；含义足够明确的项目只显示紧凑图标与数值。 */
    fun detailItems(totalMillis: Long?): List<UsageDetailMetric> = listOf(
        UsageDetailMetric("Input", inputTokens?.formatTokenCount() ?: "—", UsageDetailIcon.INPUT),
        UsageDetailMetric("Output", outputTokens?.formatTokenCount() ?: "—", UsageDetailIcon.OUTPUT),
        UsageDetailMetric("Cached", cacheReadInputTokens?.formatTokenCount() ?: "—", UsageDetailIcon.CACHED),
        UsageDetailMetric("Provider", providerDurationMillis?.formatMillis() ?: "—", UsageDetailIcon.PROVIDER),
        UsageDetailMetric("Total", totalMillis?.formatMillis() ?: "—", UsageDetailIcon.TOTAL),
        UsageDetailMetric("Req", (requestCount ?: "—").toString()),
    )

    internal fun detailsText(totalMillis: Long?): String = detailItems(totalMillis).joinToString(" · ") {
        "${it.label} ${it.value}"
    }
}

internal enum class UsageSummaryIcon { CONTEXT, CACHED, SPEED, TTFT, TRIM }

internal data class UsageSummaryMetric(
    val text: String,
    val icon: UsageSummaryIcon,
    val contentDescription: String,
    val highlighted: Boolean = false,
)

internal enum class UsageDetailIcon { INPUT, OUTPUT, CACHED, PROVIDER, TOTAL }

internal data class UsageDetailMetric(
    val label: String,
    val value: String,
    val icon: UsageDetailIcon? = null,
)

/** 图标含义不唯一的项目保留短文字，避免只靠猜测理解指标。 */
@Composable
private fun UsageDetailItem(metric: UsageDetailMetric) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (metric.icon) {
            UsageDetailIcon.INPUT -> Icon(HugeIcons.Upload02, metric.label, Modifier.size(12.dp))
            UsageDetailIcon.OUTPUT -> Icon(HugeIcons.Download04, metric.label, Modifier.size(12.dp))
            UsageDetailIcon.CACHED -> Icon(HugeIcons.Database, metric.label, Modifier.size(12.dp))
            UsageDetailIcon.PROVIDER -> Icon(HugeIcons.Cloud, metric.label, Modifier.size(12.dp))
            UsageDetailIcon.TOTAL -> Icon(HugeIcons.Clock02, metric.label, Modifier.size(12.dp))
            null -> Text(metric.label, maxLines = 1, softWrap = false)
        }
        Text(metric.value, maxLines = 1, softWrap = false)
    }
}

/**
 * 从一个 turn-owned TokenUsage 原子投影两行统计。
 * 第一行字段按各自触发点在本 turn 内保留最近值；第二行使用本 turn 累计值。
 */
internal fun TokenUsage?.toNerdLineDisplay(): NerdLineUsageDisplay {
    val coreComplete = this?.coreCompleteness == UsageCompleteness.COMPLETE
    val inputComplete = this?.inputCompleteness == UsageCompleteness.COMPLETE
    val cacheComplete = this?.cacheReadCompleteness == UsageCompleteness.COMPLETE
    val exactInput = this?.inputTokens.takeIf { inputComplete }
    val exactOutput = this?.outputTokens.takeIf { coreComplete }
    val exactCache = this?.cacheReadInputTokens.takeIf { cacheComplete }
    return NerdLineUsageDisplay(
        latestContextTokens = this?.latestRequestEstimatedContextTokens,
        latestCacheHitPercent = this?.latestRequestCacheHitPercent,
        latestTokensPerSecond = this?.latestRequestTokensPerSecond,
        latestTtftMillis = this?.latestRequestTimeToFirstOutputMillis,
        successfulToolTrimBatches = this?.successfulToolOutputCompactionBatchCount?.takeIf { it > 0 },
        inputTokens = exactInput,
        outputTokens = exactOutput,
        cacheReadInputTokens = exactCache,
        providerDurationMillis = this?.providerRequestDurationMillis,
        requestCount = this?.observedProviderRequestCount,
    )
}

/** 缓存率固定保留一位小数；极小正值不伪装成 0。 */
internal fun Double.formatCachePercent(): String = when {
    this > 0.0 && this < 0.05 -> "<0.1"
    else -> String.format(Locale.ROOT, "%.1f", this)
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

/** 毫秒值使用紧凑稳定格式，整秒不保留无意义的小数。 */
private fun Long.formatMillis(): String = when {
    this < 1_000L -> "${this}ms"
    this % 1_000L == 0L -> "${this / 1_000L}s"
    else -> "${(this / 1_000.0).toFixed(1)}s"
}
