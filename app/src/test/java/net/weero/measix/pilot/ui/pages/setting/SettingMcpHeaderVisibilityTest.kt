package net.weero.measix.pilot.ui.pages.setting

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingMcpHeaderVisibilityTest {
    @Test
    fun `removing a visible header does not reveal another header`() {
        val visibleIndices = setOf(1)

        val remapped = remapVisibleHeaderIndicesAfterRemoval(visibleIndices, removedIndex = 1)

        assertEquals(emptySet<Int>(), remapped)
    }

    @Test
    fun `visible headers after the removed row follow their original header`() {
        val visibleIndices = setOf(0, 2, 4)

        val remapped = remapVisibleHeaderIndicesAfterRemoval(visibleIndices, removedIndex = 1)

        assertEquals(setOf(0, 1, 3), remapped)
    }

    @Test
    fun `visible headers before the removed row keep their indices`() {
        val visibleIndices = setOf(0, 1)

        val remapped = remapVisibleHeaderIndicesAfterRemoval(visibleIndices, removedIndex = 3)

        assertEquals(visibleIndices, remapped)
    }
}
