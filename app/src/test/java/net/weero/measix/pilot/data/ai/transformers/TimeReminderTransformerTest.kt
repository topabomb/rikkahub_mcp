package net.weero.measix.pilot.data.ai.transformers

import kotlinx.datetime.LocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.data.ai.request.SyntheticMessageKind
import org.junit.Assert.assertEquals
import org.junit.Test

class TimeReminderTransformerTest {
    private fun user(text: String, time: String) = UIMessage.user(text).copy(createdAt = LocalDateTime.parse(time))

    @Test
    fun `first real user uses message time and offset without gap`() {
        val message = user("question", "2026-02-22T10:00:00")
        assertEquals(listOf("<time_reminder>Message time: 2026-02-22T10:00:00+08:00</time_reminder>", "question"),
            applyTimeReminder(listOf(message), "Asia/Shanghai").map { it.toText() })
        assertEquals(emptyList<UIMessage>(), applyTimeReminder(emptyList(), "UTC"))
        val nonUser = listOf(UIMessage.system("s"), UIMessage.assistant("a"))
        assertEquals(nonUser, applyTimeReminder(nonUser, "UTC"))
    }

    @Test
    fun `hour threshold is strict and gaps use integer hours or days`() {
        val first = user("first", "2026-02-22T10:00:00")
        val cases = listOf(
            "2026-02-22T10:59:59" to null,
            "2026-02-22T11:00:00" to null,
            "2026-02-22T11:00:01" to "1 h",
            "2026-02-23T00:01:00" to "14 h",
            "2026-02-24T10:00:00" to "2 d",
        )
        for ((time, gap) in cases) {
            val result = applyTimeReminder(listOf(first, user("next", time)), "UTC")
            assertEquals(time, if (gap == null) 3 else 4, result.size)
            if (gap != null) assertEquals("<time_reminder>Message time: ${time}Z; gap: $gap</time_reminder>", result[2].toText())
        }
    }

    @Test
    fun `fractional threshold and out of order times do not round into another decision`() {
        val first = user("first", "2026-02-22T10:00:00")
        val fractional = applyTimeReminder(listOf(first, user("next", "2026-02-22T11:00:00.500")), "UTC")
        assertEquals(4, fractional.size)
        assertEquals("<time_reminder>Message time: 2026-02-22T11:00:00.5Z; gap: 1 h</time_reminder>", fractional[2].toText())
        val earlier = applyTimeReminder(listOf(first, user("next", "2026-02-22T09:00:00")), "UTC")
        assertEquals(3, earlier.size)
    }

    @Test
    fun `tool wait and synthetic or application users do not reset the real user clock`() {
        val first = user("first", "2026-02-22T10:00:00")
        val preset = user("preset", "2026-02-22T11:45:00")
        val summary = user("summary", "2026-02-22T12:00:00")
        val synthetic = user("injection", "2026-02-22T12:15:00")
        val completed = UIMessage.assistant("tool completed").copy(createdAt = LocalDateTime.parse("2026-02-22T12:29:00"))
        val next = user("next", "2026-02-22T12:30:00")
        val tracker = RequestMessageOriginTracker().apply {
            markApplicationHistory(preset.id)
            markApplicationHistory(summary.id)
            markSynthetic(synthetic, SyntheticMessageKind.PROMPT_INJECTION)
        }
        val result = applyTimeReminder(listOf(preset, first, summary, synthetic, completed, next), "UTC", tracker::isRealUser)
        assertEquals(8, result.size)
        assertEquals(preset, result.first())
        assertEquals("<time_reminder>Message time: 2026-02-22T12:30:00Z; gap: 2 h</time_reminder>", result[6].toText())
    }

    @Test
    fun `each real user updates the baseline even without a reminder`() {
        val result = applyTimeReminder(listOf(
            user("a", "2026-02-22T10:00:00"), user("b", "2026-02-22T10:45:00"),
            user("c", "2026-02-22T11:30:00"), user("d", "2026-02-24T10:00:00"),
        ), "UTC")
        assertEquals(6, result.size)
        assertEquals("<time_reminder>Message time: 2026-02-24T10:00:00Z; gap: 1 d</time_reminder>", result[4].toText())
    }
    @Test
    fun `window retains predecessor time from the complete branch`() {
        val retained = user("retained", "2026-02-22T10:30:00")
        val predecessor = RealUserTime(kotlin.uuid.Uuid.random(), LocalDateTime.parse("2026-02-22T10:00:00"))
        val result = applyTimeReminder(listOf(retained), "UTC", previousRealUserTimes = mapOf(retained.id to predecessor))
        assertEquals(listOf(retained), result)
        val first = applyTimeReminder(listOf(retained), "UTC", previousRealUserTimes = mapOf(retained.id to null))
        assertEquals(2, first.size)
    }

    @Test
    fun `different captured zones determine the real gap even for an off window predecessor`() {
        val first = user("first", "2026-09-27T10:00:00")
        val retained = user("retained", "2026-09-27T10:30:00")
        val zones = mapOf(first.id to "Asia/Shanghai", retained.id to "America/New_York")
        val result = applyTimeReminder(listOf(retained), "UTC",
            previousRealUserTimes = mapOf(retained.id to RealUserTime(first.id, first.createdAt)),
            admittedZone = zones::get)
        assertEquals("<time_reminder>Message time: 2026-09-27T10:30:00-04:00; gap: 12 h</time_reminder>",
            result.first().toText())
    }
}
