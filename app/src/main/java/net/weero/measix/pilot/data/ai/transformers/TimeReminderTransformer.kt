package net.weero.measix.pilot.data.ai.transformers

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.data.ai.SyntheticMessageKind
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlin.time.Instant
import kotlin.time.toJavaInstant

private const val TIME_GAP_THRESHOLD_SECONDS = 3600L

/** Injects time-gap reminders using only the Turn's frozen timezone and locale. */
object TimeReminderTransformer : InputMessageTransformer {
    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> {
        if (!ctx.promptInputs.enableTimeReminder) return messages
        val transformed = applyTimeReminder(
            messages = messages,
            zoneId = ctx.promptInputs.zoneId,
            localeTag = ctx.promptInputs.localeTag,
        )
        ctx.requestOrigins.markNewMessages(
            before = messages,
            source = transformed,
            kind = SyntheticMessageKind.TIME_REMINDER,
        )
        return transformed
    }
}

internal fun applyTimeReminder(
    messages: List<UIMessage>,
    zoneId: String,
    localeTag: String,
): List<UIMessage> {
    val result = mutableListOf<UIMessage>()
    val timeZone = TimeZone.of(zoneId)
    val javaZone = ZoneId.of(zoneId)
    val locale = Locale.forLanguageTag(localeTag)
    var firstUserFound = false
    for (index in messages.indices) {
        val current = messages[index]
        if (current.role == MessageRole.USER) {
            val currentInstant = current.createdAt.toInstant(timeZone)
            if (!firstUserFound) {
                firstUserFound = true
                result += buildTimeReminderMessage(null, currentInstant, javaZone, locale)
            } else {
                val previousInstant = messages[index - 1].createdAt.toInstant(timeZone)
                val gapSeconds = (currentInstant - previousInstant).inWholeSeconds
                if (gapSeconds > TIME_GAP_THRESHOLD_SECONDS) {
                    result += buildTimeReminderMessage(gapSeconds, currentInstant, javaZone, locale)
                }
            }
        }
        result += current
    }
    return result
}

private fun buildTimeReminderMessage(
    gapSeconds: Long?,
    instant: Instant,
    zoneId: ZoneId,
    locale: Locale,
): UIMessage {
    val local = instant.toJavaInstant().atZone(zoneId)
    val dayOfWeek = local.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
    val time = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale).format(local)
    val content = if (gapSeconds == null) {
        "<time_reminder>Current time: $dayOfWeek, $time</time_reminder>"
    } else {
        "<time_reminder>Current time: $dayOfWeek, $time (${formatGap(gapSeconds)} since last message)</time_reminder>"
    }
    return UIMessage.user(content)
}

private fun formatGap(seconds: Long): String = when {
    seconds < 3600 -> "${seconds / 60} min"
    seconds < 86400 -> "${seconds / 3600} h"
    else -> "${seconds / 86400} d"
}
