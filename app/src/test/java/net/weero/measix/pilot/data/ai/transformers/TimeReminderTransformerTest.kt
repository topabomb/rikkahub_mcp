package net.weero.measix.pilot.data.ai.transformers

import kotlinx.datetime.LocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeReminderTransformerTest {

    private fun userMessage(text: String, createdAt: LocalDateTime) = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text)),
        createdAt = createdAt,
    )

    private fun getMessageText(msg: UIMessage): String =
        msg.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }

    @Test
    fun `empty messages should return empty`() {
        val result = applyTimeReminder(emptyList())
        assertEquals(0, result.size)
    }

    @Test
    fun `single message should inject current time reminder`() {
        val messages = listOf(userMessage("Hello", LocalDateTime(2026, 2, 22, 10, 0, 0)))
        val result = applyTimeReminder(messages)
        // 实现逻辑: 第一条用户消息注入当前时间提醒（无间隔信息）
        assertEquals(2, result.size)
        val injected = getMessageText(result[0])
        assertTrue(injected.contains("<time_reminder>"))
        assertTrue(injected.contains("Current time"))
        assertFalse(injected.contains("since last message"))
        assertEquals("Hello", getMessageText(result[1]))
    }

    @Test
    fun `gap less than 1 hour should not inject gap reminder`() {
        val messages = listOf(
            userMessage("Hello", LocalDateTime(2026, 2, 22, 10, 0, 0)),
            userMessage("World", LocalDateTime(2026, 2, 22, 10, 30, 0)), // 30 分钟
        )
        val result = applyTimeReminder(messages)
        // 第一条注入当前时间，第二条间隔 < 1 小时不注入
        assertEquals(3, result.size)
        assertTrue(getMessageText(result[0]).contains("<time_reminder>"))
        assertEquals("Hello", getMessageText(result[1]))
        assertEquals("World", getMessageText(result[2]))
    }

    @Test
    fun `gap exactly 1 hour should not inject gap reminder`() {
        val messages = listOf(
            userMessage("Hello", LocalDateTime(2026, 2, 22, 10, 0, 0)),
            userMessage("World", LocalDateTime(2026, 2, 22, 11, 0, 0)), // 恰好 1 小时（不大于阈值）
        )
        val result = applyTimeReminder(messages)
        // 第一条注入当前时间，第二条间隔 = 1 小时（不 > 阈值）不注入
        assertEquals(3, result.size)
        assertTrue(getMessageText(result[0]).contains("<time_reminder>"))
        assertEquals("Hello", getMessageText(result[1]))
        assertEquals("World", getMessageText(result[2]))
    }

    @Test
    fun `gap more than 1 hour should inject time reminder before second message`() {
        val messages = listOf(
            userMessage("Hello", LocalDateTime(2026, 2, 22, 10, 0, 0)),
            userMessage("World", LocalDateTime(2026, 2, 22, 12, 0, 0)), // 2 小时
        )
        val result = applyTimeReminder(messages)
        // 第一条注入当前时间，第二条间隔 > 1 小时注入间隔提醒
        assertEquals(4, result.size)
        assertTrue(getMessageText(result[0]).contains("<time_reminder>"))
        assertEquals("Hello", getMessageText(result[1]))
        val injected = getMessageText(result[2])
        assertTrue(injected.contains("<time_reminder>"))
        assertTrue(injected.contains("since last message"))
        assertEquals("World", getMessageText(result[3]))
    }

    @Test
    fun `injected message should contain day of week and gap in hours`() {
        val messages = listOf(
            userMessage("Hello", LocalDateTime(2026, 2, 22, 10, 0, 0)),
            userMessage("World", LocalDateTime(2026, 2, 22, 12, 0, 0)), // 2 小时
        )
        val result = applyTimeReminder(messages)
        // 间隔提醒是 result[2]（第一条当前时间 + 第一条消息 + 间隔提醒）
        val injected = getMessageText(result[2])
        assertTrue(injected.contains("<time_reminder>"))
        assertTrue(injected.contains("2 h since last message"))
    }

    @Test
    fun `gap in days should format correctly`() {
        val messages = listOf(
            userMessage("Hello", LocalDateTime(2026, 2, 20, 10, 0, 0)),
            userMessage("World", LocalDateTime(2026, 2, 22, 10, 0, 0)), // 2 天
        )
        val result = applyTimeReminder(messages)
        val injected = getMessageText(result[2])
        assertTrue(injected.contains("2 d since last message"))
    }

    @Test
    fun `multiple large gaps should inject multiple reminders`() {
        val messages = listOf(
            userMessage("Msg 1", LocalDateTime(2026, 2, 20, 10, 0, 0)),
            userMessage("Msg 2", LocalDateTime(2026, 2, 21, 10, 0, 0)), // 1 天
            userMessage("Msg 3", LocalDateTime(2026, 2, 22, 10, 0, 0)), // 1 天
        )
        val result = applyTimeReminder(messages)
        // 3 条原始 + 1 条首条当前时间 + 2 条间隔提醒 = 6 条
        assertEquals(6, result.size)
        assertTrue(getMessageText(result[0]).contains("<time_reminder>"))
        assertEquals("Msg 1", getMessageText(result[1]))
        assertTrue(getMessageText(result[2]).contains("<time_reminder>"))
        assertEquals("Msg 2", getMessageText(result[3]))
        assertTrue(getMessageText(result[4]).contains("<time_reminder>"))
        assertEquals("Msg 3", getMessageText(result[5]))
    }

    @Test
    fun `non-user messages should not trigger injection`() {
        val messages = listOf(
            UIMessage(
                role = MessageRole.SYSTEM,
                parts = listOf(UIMessagePart.Text("System prompt")),
                createdAt = LocalDateTime(2026, 2, 22, 10, 0, 0),
            ),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("Assistant reply")),
                createdAt = LocalDateTime(2026, 2, 22, 10, 0, 0),
            ),
        )
        val result = applyTimeReminder(messages)
        // 没有 USER 消息，不注入任何时间提醒
        assertEquals(2, result.size)
    }

    @Test
    fun `first time reminder should not contain gap info`() {
        val messages = listOf(
            userMessage("Hello", LocalDateTime(2026, 2, 22, 10, 0, 0)),
        )
        val result = applyTimeReminder(messages)
        val injected = getMessageText(result[0])
        assertTrue(injected.contains("<time_reminder>"))
        assertFalse(injected.contains("since last message"))
    }
}
