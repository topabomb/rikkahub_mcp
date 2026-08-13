package net.weero.measix.pilot.ui.components.message

import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAssistantCallCardLogicTest {
    @Test
    fun `preview uses two lines on short screens and three otherwise`() {
        assertEquals(2, subAssistantCardPreviewLines(compactHeight = true))
        assertEquals(3, subAssistantCardPreviewLines(compactHeight = false))
    }

    @Test
    fun `clipPreviewForCard keeps tail lines and marks truncation`() {
        val text = "one\ntwo\nthree\nfour"
        assertEquals("…three\nfour", clipPreviewForCard(text, maxLines = 2))
        assertEquals(text, clipPreviewForCard(text, maxLines = 4))
    }

    @Test
    fun `clipPreviewForCard also bounds a single long line by characters`() {
        val long = "a".repeat(200)
        val clipped = clipPreviewForCard(long, maxLines = 3, maxChars = 20)
        assertTrue(clipped.startsWith("…"))
        assertTrue(clipped.codePointCount(0, clipped.length) <= 21)
    }

    @Test
    fun `clipPreviewForCard does not expose a detached combining mark`() {
        val clipped = clipPreviewForCard("e\u0301" + "a".repeat(20), maxLines = 3, maxChars = 20)

        assertTrue(clipped.startsWith("…a"))
    }

    @Test
    fun `preview char budget is smaller on compact width`() {
        assertEquals(72, subAssistantCardPreviewMaxChars(compactHeight = true, compactWidth = true))
        assertEquals(216, subAssistantCardPreviewMaxChars(compactHeight = false, compactWidth = false))
    }

    @Test
    fun `non-text placeholder is limited to completed calls without visible preview`() {
        assertTrue(
            shouldShowNonTextOutputPlaceholder(
                state = SubAssistantCallState.COMPLETED,
                preview = " ",
                hasNonTextOutput = true,
            )
        )
        assertFalse(
            shouldShowNonTextOutputPlaceholder(
                state = SubAssistantCallState.COMPLETED,
                preview = "visible text",
                hasNonTextOutput = true,
            )
        )
        assertFalse(
            shouldShowNonTextOutputPlaceholder(
                state = SubAssistantCallState.FAILED,
                preview = null,
                hasNonTextOutput = true,
            )
        )
    }

    @Test
    fun `card status prefers phase while running and reason when finished`() {
        assertEquals(
            "正在搜索",
            resolveCardStatusLabel(true, "正在搜索", null, "执行中"),
        )
        assertEquals(
            "执行中",
            resolveCardStatusLabel(true, null, "失败原因", "执行中"),
        )
        assertEquals(
            "模型不可用",
            resolveCardStatusLabel(false, null, "模型不可用", "已停止"),
        )
        assertEquals(
            "已完成",
            resolveCardStatusLabel(false, null, null, "已完成"),
        )
    }

    @Test
    fun `tool fallback removes control characters and formats separators`() {
        assertEquals("mcp server search", sanitizeToolNameForDisplay("mcp__server\u0000_search", "工具"))
    }

    @Test
    fun `tool fallback clips by code point without splitting emoji`() {
        val result = sanitizeToolNameForDisplay("😀".repeat(80), "工具")

        assertEquals(65, result.codePointCount(0, result.length))
        assertTrue(result.endsWith("…"))
    }

    @Test
    fun `blank tool fallback is supplied by localized caller`() {
        assertEquals("工具", sanitizeToolNameForDisplay("__\u0000__", "工具"))
    }
}
