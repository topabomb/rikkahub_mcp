package net.weero.measix.pilot.data.ai.transformers

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.data.ai.request.SyntheticMessageKind
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.uuid.Uuid

private const val TIME_GAP_THRESHOLD_SECONDS = 3600L

/** The reminder describes the actual user message, never the time at which the model answers. */
object TimeReminderTransformer : InputMessageTransformer {
    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> {
        if (!ctx.promptInputs.enableTimeReminder) return messages
        val transformed = applyTimeReminder(messages, ctx.promptInputs.zoneId,
            isRealUser = ctx.requestOrigins::isRealUser,
            previousRealUserTimes = ctx.requestOrigins.previousRealUserTimes(),
            admittedZone = ctx.requestOrigins::messageTimeZone,
            admittedText = ctx.requestOrigins::messageTimeText)
        ctx.requestOrigins.markNewMessages(messages, transformed, SyntheticMessageKind.TIME_REMINDER)
        transformed.forEachIndexed { index, message ->
            if (ctx.requestOrigins.source(message) == net.weero.measix.pilot.data.ai.request.RequestMessageOrigin.Synthetic(
                    SyntheticMessageKind.TIME_REMINDER)) {
                val source = transformed.getOrNull(index + 1)
                check(source != null && ctx.requestOrigins.isRealUser(source)) { "time_reminder_source_missing" }
                message.parts.forEach { ctx.requestOrigins.markPart(it, RequestPartSource.MessageTime(source)) }
            }
        }
        return transformed
    }
}

internal fun applyTimeReminder(
    messages: List<UIMessage>,
    zoneId: String,
    isRealUser: (UIMessage) -> Boolean = { it.role == MessageRole.USER },
    previousRealUserTimes: Map<Uuid, RealUserTime?> = emptyMap(),
    admittedZone: (Uuid) -> String? = { null },
    admittedText: (Uuid) -> String? = { null },
): List<UIMessage> {
    var previousUserInstant: Instant? = null
    return buildList {
        for (message in messages) {
            if (isRealUser(message)) {
                val messageZone = admittedZone(message.id) ?: zoneId
                val timeZone = TimeZone.of(messageZone)
                val instant = message.createdAt.toInstant(timeZone)
                val previous = if (message.id in previousRealUserTimes) {
                    previousRealUserTimes[message.id]?.let {
                        it.createdAt.toInstant(TimeZone.of(admittedZone(it.messageId) ?: zoneId))
                    }
                } else previousUserInstant
                val gap = previous?.let { instant - it }
                val original = admittedText(message.id)
                if (original != null) {
                    add(UIMessage.user(original))
                } else if (gap == null || gap > TIME_GAP_THRESHOLD_SECONDS.seconds) {
                    val time = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(instant.toJavaInstant().atZone(ZoneId.of(messageZone)))
                    val suffix = gap?.inWholeSeconds?.let { "; gap: ${if (it < 86400) "${it / 3600} h" else "${it / 86400} d"}" }.orEmpty()
                    add(UIMessage.user("<time_reminder>Message time: $time$suffix</time_reminder>"))
                }
                previousUserInstant = instant
            }
            add(message)
        }
    }
}
