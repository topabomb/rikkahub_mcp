package net.weero.measix.pilot.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetFileNamesTest {
    @Test
    fun `four random draws use exact Long bounds in increasing format order`() {
        val bounds = mutableListOf<Long>()
        assertEquals(listOf("000000", "0000000", "00000000", "00000000"), AssetFileNames.candidates {
            bounds += it
            0L
        })
        assertEquals(listOf(2_176_782_336L, 78_364_164_096L, 2_821_109_907_456L, 218_340_105_584_896L), bounds)
        assertEquals(listOf("zzzzzz", "zzzzzzz", "zzzzzzzz", "ZZZZZZZZ"), AssetFileNames.candidates { it - 1 })
    }

    @Test
    fun `format boundaries preserve width and reject out of range draws`() {
        assertEquals(listOf("000010", "0000010", "00000010", "000000A0"), AssetFileNames.candidates { bound ->
            if (bound == 218_340_105_584_896L) 36L * 62L else 36L
        })
        assertTrue(runCatching { AssetFileNames.candidates { -1L } }.isFailure)
        assertTrue(runCatching { AssetFileNames.candidates { it } }.isFailure)
        repeat(100) {
            val names = AssetFileNames.candidates()
            assertTrue(names[0].matches(Regex("[0-9a-z]{6}")))
            assertTrue(names[1].matches(Regex("[0-9a-z]{7}")))
            assertTrue(names[2].matches(Regex("[0-9a-z]{8}")))
            assertTrue(names[3].matches(Regex("[0-9a-zA-Z]{8}")))
        }
    }

    @Test
    fun `file formatting preserves full stem case extension and collision suffix`() {
        assertEquals("abcdef.png", AssetFileNames.fileName("abcdef", "png"))
        assertEquals("aB1234Cd-2.png", AssetFileNames.fileName("aB1234Cd", "png", 2))
        assertEquals("abcdef-103.jpg", AssetFileNames.fileName("abcdef", "jpg", 103))
        assertTrue(runCatching { AssetFileNames.fileName("../evil", "png") }.isFailure)
        assertTrue(runCatching { AssetFileNames.fileName("abcdef", "../png") }.isFailure)
        assertTrue(runCatching { AssetFileNames.fileName("ABCDEF", "png") }.isFailure)
    }
}
