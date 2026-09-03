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
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
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
import me.rerere.hugeicons.stroke.Repeat
import me.rerere.hugeicons.stroke.Scissor
import me.rerere.hugeicons.stroke.StopWatch
import me.rerere.hugeicons.stroke.Upload02
import me.rerere.hugeicons.stroke.Zap
import net.weero.measix.pilot.ui.context.LocalSettings
import net.weero.measix.pilot.utils.toFixed
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

private const val USAGE_TICK_INTERVAL_MILLIS = 1_000L

/**
 * 每个 Assistant turn 的两行低强调统计；所有值只来自同一个 durable usage snapshot。
 *
 * 第一行按关注度排列：前两项属于最近一次已关闭的 Provider 请求，后两项属于本 turn。
 */
@Composable
fun ChatMessageNerdLine(
    message: UIMessage,
    turnFinished: Boolean,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f),
) {
    if (!LocalSettings.current.displaySetting.showTokenUsage) return
    val display = message.usage.toNerdLineDisplay() ?: return

    var nowMillis by remember(message.id) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(message.id, turnFinished) {
        if (turnFinished) return@LaunchedEffect
        while (true) {
            delay(USAGE_TICK_INTERVAL_MILLIS)
            nowMillis = System.currentTimeMillis()
        }
    }
    val elapsedText = elapsedText(
        createdAt = message.createdAt,
        finishedAt = message.finishedAt?.takeIf { turnFinished },
        nowMillis = nowMillis,
        turnFinished = turnFinished,
    )
    val summaryItems = display.summaryItems(elapsedText)
    if (summaryItems.isEmpty()) return

    var expanded by remember(message.id) { mutableStateOf(false) }

    ProvideTextStyle(MaterialTheme.typography.labelSmall.copy(color = color)) {
        CompositionLocalProvider(LocalContentColor provides color) {
            Column(
                modifier = modifier.padding(horizontal = 4.dp).animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                FlowRow(
                    modifier = Modifier.clickable { expanded = !expanded },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    summaryItems.forEachIndexed { index, metric ->
                        if (index > 0) {
                            Text("·", maxLines = 1, softWrap = false)
                        }
                        UsageSummaryItem(metric)
                    }
                    Icon(
                        imageVector = HugeIcons.ArrowDown01,
                        contentDescription = if (expanded) "Collapse usage" else "Expand usage",
                        modifier = Modifier.size(12.dp).rotate(if (expanded) 180f else 0f),
                    )
                }
                AnimatedVisibility(visible = expanded) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        display.detailItems().forEach { UsageDetailItem(it) }
                    }
                }
            }
        }
    }
}

/**
 * 摘要项：与第二行 [UsageDetailItem] 同构。
 *
 * Icon 与文字放在同一个 Row 内按 CenterVertically 居中，不走 inline content：
 * inline placeholder 用 sp 而 Icon 用 dp，系统字体缩放非 1.0 时两者不等，
 * 图标会在 placeholder 方框内顶部对齐而整体偏上。
 */
@Composable
private fun UsageSummaryItem(metric: UsageSummaryMetric) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = when (metric.icon) {
                UsageSummaryIcon.CONTEXT -> HugeIcons.Layers01
                UsageSummaryIcon.CACHED -> HugeIcons.Database
                UsageSummaryIcon.TRIM -> HugeIcons.Scissor
                UsageSummaryIcon.TOTAL -> HugeIcons.Clock02
            },
            contentDescription = metric.icon.description,
            modifier = Modifier.size(12.dp),
            tint = when {
                metric.highlighted -> MaterialTheme.colorScheme.primary
                metric.muted -> LocalContentColor.current.copy(
                    alpha = LocalContentColor.current.alpha * 0.6f,
                )

                else -> LocalContentColor.current
            },
        )
        Text(metric.text, maxLines = 1, softWrap = false)
    }
}

/**
 * 本 turn 端到端耗时。turn 未终态时用当前时刻计时并取整到秒，终态后使用冻结的
 * `finishedAt` 并保留精度。
 */
internal fun elapsedText(
    createdAt: LocalDateTime,
    finishedAt: LocalDateTime?,
    nowMillis: Long,
    turnFinished: Boolean,
): String? {
    val created = createdAt.toJavaLocalDateTime()
    val millis = if (turnFinished) {
        finishedAt?.let { Duration.between(created, it.toJavaLocalDateTime()).toMillis() }
    } else {
        Duration.between(
            created,
            Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).toLocalDateTime(),
        ).toMillis()
    } ?: return null
    val normalized = millis.coerceAtLeast(0)
    return if (turnFinished) normalized.formatMillis() else "${normalized / 1_000L}s"
}

/** 摘要里的上下文；`exact` 为 false 表示值来自请求发送前的稳定估算。 */
internal data class NerdLineContext(
    val tokens: Long,
    val exact: Boolean,
)

/** 已关闭请求的 turn 级展示投影；未知值始终保持 null，不用零补齐。 */
internal data class NerdLineUsageDisplay(
    val context: NerdLineContext?,
    val cacheHitPercent: Double?,
    val trimBatches: Int?,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val cacheReadTokens: Long?,
    val providerDurationMillis: Long?,
    val requestCount: Int?,
    val tokensPerSecond: Double?,
    val ttftMillis: Long?,
) {
    /**
     * 第一行四项，分两种粒度，各自内部自洽：
     *
     * - `▤` 上下文是**状态量**：最近一次请求的输入有多大。它只能取单请求——累计输入没有"上下文多大"
     *   的含义。取不到实测 input 时退回该请求发送前的估算，加 `~` 前缀并去饱和，绝不把估算当实测。
     * - `⬡` 命中率是**效率量**：本 turn 累计 cache read ÷ 累计 input。它的分子分母就是第二行的
     *   `⬡ Cached` 与 `⬆ Input`，两者可见时它才可见，因此随时可验算；任一缺失则一起缺席。
     *   用累计而不是"最近一次请求"，是因为单请求值只反映最后一次，前面全崩也可能照样显示高值，
     *   反而更容易误导；累计值随每个已关闭请求推进并收敛，更能代表整轮。
     * - `✂` 裁剪批次数与 `⏱` 本 turn 耗时同样是 turn 级总结。
     *
     * 因此 `▤` 与 `⬡` 不需要共用锚点：一个回答"现在多大"，一个回答"这轮省了多少"；
     * 命中率的分母不在它旁边，而在展开后的第二行。
     */
    internal fun summaryItems(elapsedText: String?): List<UsageSummaryMetric> = buildList {
        context?.let { ctx ->
            add(
                UsageSummaryMetric(
                    text = if (ctx.exact) {
                        ctx.tokens.formatTokenCount()
                    } else {
                        "~${ctx.tokens.formatTokenCount()}"
                    },
                    icon = UsageSummaryIcon.CONTEXT,
                    muted = !ctx.exact,
                ),
            )
        }
        cacheHitPercent?.let {
            add(UsageSummaryMetric("${it.formatCachePercent()}%", UsageSummaryIcon.CACHED))
        }
        trimBatches?.takeIf { it > 0 }?.let {
            add(UsageSummaryMetric(it.toString(), UsageSummaryIcon.TRIM, highlighted = true))
        }
        elapsedText?.let { add(UsageSummaryMetric(it, UsageSummaryIcon.TOTAL)) }
    }

    internal fun summaryText(elapsedText: String?): String =
        summaryItems(elapsedText).joinToString(" · ") { it.text }

    /**
     * 第二行：本 turn 累计在前，最近一次请求的性能在后；缺失项不占位。
     *
     * `⚡ tok/s` 与 `TTFT` 是单请求指标（速度无法累计）：前者取最近一次已关闭请求；
     * 后者取最近一次**实际产生首个有效模型输出**的请求，空输出请求不覆盖旧值。
     */
    internal fun detailItems(): List<UsageDetailMetric> = buildList {
        inputTokens?.let {
            add(UsageDetailMetric(value = it.formatTokenCount(), icon = UsageDetailIcon.INPUT))
        }
        outputTokens?.let {
            add(UsageDetailMetric(value = it.formatTokenCount(), icon = UsageDetailIcon.OUTPUT))
        }
        cacheReadTokens?.let {
            add(UsageDetailMetric(value = it.formatTokenCount(), icon = UsageDetailIcon.CACHED, label = "Cached"))
        }
        providerDurationMillis?.let {
            add(UsageDetailMetric(value = it.formatMillis(), icon = UsageDetailIcon.PROVIDER, label = "Provider"))
        }
        requestCount?.let {
            add(UsageDetailMetric(value = it.toString(), icon = UsageDetailIcon.REQUESTS, label = "Req"))
        }
        tokensPerSecond?.let {
            add(UsageDetailMetric(value = it.toFixed(1), icon = UsageDetailIcon.SPEED, label = "tok/s"))
        }
        ttftMillis?.let {
            add(UsageDetailMetric(value = it.formatMillis(), icon = UsageDetailIcon.TTFT, label = "TTFT"))
        }
    }

    internal fun detailsText(): String = detailItems().joinToString(" · ") {
        listOfNotNull(it.label, it.value).joinToString(" ")
    }
}

internal enum class UsageSummaryIcon(val description: String) {
    CONTEXT("Context"),
    CACHED("Cached"),
    TRIM("Tool trims"),
    TOTAL("Total"),
}

internal data class UsageSummaryMetric(
    val text: String,
    val icon: UsageSummaryIcon,
    val highlighted: Boolean = false,
    val muted: Boolean = false,
)

internal enum class UsageDetailIcon(val description: String) {
    INPUT("Input"),
    OUTPUT("Output"),
    CACHED("Cached"),
    PROVIDER("Provider"),
    REQUESTS("Requests"),
    SPEED("Output speed"),
    TTFT("Time to first token"),
}

internal data class UsageDetailMetric(
    val value: String,
    val icon: UsageDetailIcon? = null,
    /** 非空时显示可见短文字；与第一行同图标、或单位与缩写需要说明的项才需要。 */
    val label: String? = null,
) {
    val contentDescription: String
        get() = label ?: icon?.description ?: ""
}

/** 图标含义唯一时只显示图标与数值，否则补上短文字。 */
@Composable
private fun UsageDetailItem(metric: UsageDetailMetric) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        metric.icon?.let { icon ->
            Icon(
                imageVector = when (icon) {
                    UsageDetailIcon.INPUT -> HugeIcons.Upload02
                    UsageDetailIcon.OUTPUT -> HugeIcons.Download04
                    UsageDetailIcon.CACHED -> HugeIcons.Database
                    UsageDetailIcon.PROVIDER -> HugeIcons.Cloud
                    UsageDetailIcon.SPEED -> HugeIcons.Zap
                    UsageDetailIcon.REQUESTS -> HugeIcons.Repeat
                    UsageDetailIcon.TTFT -> HugeIcons.StopWatch
                },
                contentDescription = metric.contentDescription,
                modifier = Modifier.size(12.dp),
            )
        }
        metric.label?.let { Text(it, maxLines = 1, softWrap = false) }
        Text(metric.value, maxLines = 1, softWrap = false)
    }
}

/**
 * 从一个 turn-owned TokenUsage 原子投影两行统计。
 *
 * 粒度分工与 [NerdLineUsageDisplay.summaryItems] 的注释一致：上下文是状态量，取最近一次请求；
 * 命中率是效率量，取本 turn 累计；裁剪批次与耗时同为 turn 级。
 */
internal fun TokenUsage?.toNerdLineDisplay(): NerdLineUsageDisplay? {
    if (this == null) return null
    val context = latestRequestContextTokens?.let { NerdLineContext(tokens = it, exact = true) }
        ?: latestRequestEstimatedContextTokens?.let { NerdLineContext(tokens = it, exact = false) }
    // 命中率的分子分母就是第二行的 Cached 与 Input。三者共用同一组 completeness 门控，
    // 因此要么一起可见（用户可当场验算 Cached ÷ Input），要么一起缺席，不会出现有比例却无分母。
    val exactInput = inputTokens.takeIf { inputCompleteness == UsageCompleteness.COMPLETE }
    val exactCache = cacheReadInputTokens.takeIf { cacheReadCompleteness == UsageCompleteness.COMPLETE }
    val turnCacheHitPercent = if (
        exactInput != null && exactInput > 0L && exactCache != null && exactCache <= exactInput
    ) {
        exactCache.toDouble() / exactInput * 100.0
    } else {
        null
    }
    return NerdLineUsageDisplay(
        context = context,
        cacheHitPercent = turnCacheHitPercent,
        trimBatches = successfulToolOutputCompactionBatchCount,
        inputTokens = exactInput,
        outputTokens = outputTokens.takeIf { coreCompleteness == UsageCompleteness.COMPLETE },
        cacheReadTokens = exactCache,
        providerDurationMillis = providerRequestDurationMillis,
        requestCount = observedProviderRequestCount,
        tokensPerSecond = latestRequestTokensPerSecond,
        ttftMillis = latestRequestTimeToFirstOutputMillis,
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
