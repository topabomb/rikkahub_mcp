package me.rerere.tts.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextChunkerTest {
    @Test
    fun `unpunctuated text is always split below the hard limit`() {
        val chunks = TextChunker(maxChunkLength = 16).split("a".repeat(41))

        assertEquals(listOf(16, 16, 9), chunks.map { it.text.length })
        assertEquals("a".repeat(41), chunks.joinToString("") { it.text })
    }

    @Test
    fun `hard split never separates a surrogate pair`() {
        val text = "1234567😀89abcdefghij"
        val chunks = TextChunker(maxChunkLength = 8).split(text)

        assertEquals(text, chunks.joinToString("") { it.text })
        assertTrue(chunks.all { it.text.length <= 8 })
        assertTrue(chunks.none { it.text.last().isHighSurrogate() })
        assertTrue(chunks.none { it.text.first().isLowSurrogate() })
    }
}
