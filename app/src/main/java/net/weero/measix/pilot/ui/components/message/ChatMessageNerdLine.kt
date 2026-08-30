package net.weero.measix.pilot.ui.components.message

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.datetime.toJavaLocalDateTime
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.UsageCompleteness
import me.rerere.ai.ui.UIMessage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.Upload02
import me.rerere.hugeicons.stroke.Zap
import net.weero.measix.pilot.ui.context.LocalSettings
import net.weero.measix.pilot.utils.toFixed
import java.time.Duration
import java.util.Locale

/** Low-emphasis message footer for Provider usage. */
@Composable
fun ChatMessageNerdLine(
    message: UIMessage,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f),
) {
    val settings = LocalSettings.current.displaySetting
    val display = message.usage?.toNerdLineDisplay()
    val summary = display?.takeIf { settings.showTokenUsage && it.hasCompactSummary }
    val elapsedSeconds = message.finishedAt?.let { finishedAt ->
        Duration.between(
            message.createdAt.toJavaLocalDateTime(),
            finishedAt.toJavaLocalDateTime(),
        ).toMillis().coerceAtLeast(0) / 1000.0
    }
    val showDetails = summary?.hasDetails(elapsedSeconds) == true
    var expanded by remember(message.id) { mutableStateOf(false) }

    if (summary == null) return

    ProvideTextStyle(MaterialTheme.typography.labelSmall.copy(color = color)) {
        CompositionLocalProvider(LocalContentColor provides color) {
            Column(
                modifier = modifier
                    .padding(horizontal = 4.dp)
                    .animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    modifier = Modifier
                        .then(
                            if (showDetails) Modifier.clickable { expanded = !expanded } else Modifier
                        ),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = summary.compactText(),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                    )
                    if (showDetails) {
                        Icon(
                            imageVector = HugeIcons.ArrowDown01,
                            contentDescription = if (expanded) "Collapse usage" else "Expand usage",
                            modifier = Modifier.size(12.dp).rotate(if (expanded) 180f else 0f),
                        )
                    }
                }

                AnimatedVisibility(visible = expanded && showDetails) {
                    UsageDetails(display = summary, elapsedSeconds = elapsedSeconds)
                }
            }
        }
    }
}

@Composable
private fun UsageDetails(display: NerdLineUsageDisplay, elapsedSeconds: Double?) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
        maxLines = 2,
    ) {
        if (display.hasTokenDetails) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                display.inputTokens?.let { value ->
                    StatsItem(
                        icon = { Icon(HugeIcons.Upload02, "Input", Modifier.size(12.dp)) },
                        content = { Text(value.formatTokenCount()) },
                    )
                }
                display.outputTokens?.let { value ->
                    StatsItem(
                        icon = { Icon(HugeIcons.Download04, "Output", Modifier.size(12.dp)) },
                        content = { Text(value.formatTokenCount()) },
                    )
                }
                display.cacheReadInputTokens?.let { value ->
                    Text("Cached ${value.formatTokenCount()}", maxLines = 1, softWrap = false)
                }
            }
        }
        if (display.hasTimingDetails(elapsedSeconds)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                display.tokensPerSecond?.let { value ->
                    StatsItem(
                        icon = { Icon(HugeIcons.Zap, "Speed", Modifier.size(12.dp)) },
                        content = { Text("${value.toFixed(1)} tok/s", maxLines = 1, softWrap = false) },
                    )
                }
                display.initialTtftMillis?.let { value ->
                    Text("TTFT ${value.formatMillis()}", maxLines = 1, softWrap = false)
                }
                elapsedSeconds?.let { value ->
                    StatsItem(
                        icon = { Icon(HugeIcons.Clock02, "Total time", Modifier.size(12.dp)) },
                        content = { Text("${value.toFixed(1)}s", maxLines = 1, softWrap = false) },
                    )
                }
            }
        }
    }
}

internal data class NerdLineUsageDisplay(
    val latestContextTokens: Long?,
    val latestCachePercent: Double?,
    val requestCount: Int?,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val cacheReadInputTokens: Long?,
    val tokensPerSecond: Double?,
    val initialTtftMillis: Long?,
) {
    val hasCompactSummary: Boolean
        get() = latestContextTokens != null || latestCachePercent != null || requestCount != null
    val hasTokenDetails: Boolean
        get() = inputTokens != null || outputTokens != null || cacheReadInputTokens != null
    fun hasTimingDetails(elapsedSeconds: Double?): Boolean =
        tokensPerSecond != null || initialTtftMillis != null || elapsedSeconds != null
    fun hasDetails(elapsedSeconds: Double?): Boolean = hasTokenDetails || hasTimingDetails(elapsedSeconds)
    fun compactText(): String = buildString {
        append("Context ")
        append(latestContextTokens?.formatTokenCount() ?: "—")
        append(" · Cache ")
        append(latestCachePercent?.let { "${it.formatCachePercent()}%" } ?: "—")
        append(" · Req ")
        append(requestCount ?: "—")
    }
}

internal fun TokenUsage.toNerdLineDisplay(): NerdLineUsageDisplay {
    val showCore = coreCompleteness == UsageCompleteness.COMPLETE
    val showCacheRead = cacheReadCompleteness == UsageCompleteness.COMPLETE
    val latestCachePercent = latestRequestCacheReadInputTokens?.let { cacheRead ->
        latestRequestContextTokens
            ?.takeIf { it > 0L && cacheRead in 0L..it }
            ?.let { context -> cacheRead.toDouble() / context * 100.0 }
    }
    val displayedOutput = outputTokens.takeIf { showCore }
    val requestDuration = providerRequestDurationMillis?.takeIf { it > 0L }
    val tokensPerSecond = if (displayedOutput != null && requestDuration != null) {
        displayedOutput.toDouble() / requestDuration * 1000.0
    } else {
        null
    }
    return NerdLineUsageDisplay(
        latestContextTokens = latestRequestContextTokens,
        latestCachePercent = latestCachePercent,
        requestCount = observedProviderRequestCount,
        inputTokens = inputTokens.takeIf { showCore },
        outputTokens = displayedOutput,
        cacheReadInputTokens = cacheReadInputTokens.takeIf { showCacheRead },
        tokensPerSecond = tokensPerSecond,
        initialTtftMillis = initialRequestTimeToFirstOutputMillis,
    )
}

internal fun Double.formatCachePercent(): String {
    val fractionDigits = when {
        this < 90.0 -> 0
        this < 99.0 -> 1
        else -> 2
    }
    return String.format(Locale.ROOT, "%.${fractionDigits}f", this)
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

private fun Long.formatMillis(): String = if (this < 1_000L) "${this}ms" else "${(this / 1000.0).toFixed(1)}s"

@Composable
fun StatsItem(icon: @Composable () -> Unit, content: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        icon()
        content()
    }
}
