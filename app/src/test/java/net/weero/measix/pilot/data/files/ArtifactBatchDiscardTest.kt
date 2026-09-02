package net.weero.measix.pilot.data.files

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtifactBatchDiscardTest {
    @Test
    fun `batch discard attempts every resource and aggregates failures in reverse order`() = runTest {
        val attempted = mutableListOf<Int>()
        val failure = try {
            discardArtifactBatch(listOf(1, 2, 3)) { id ->
                attempted += id
                if (id >= 2) error("failed-$id")
            }
            null
        } catch (error: IllegalStateException) {
            error
        }

        assertEquals(listOf(3, 2, 1), attempted)
        assertEquals("failed-3", failure?.message)
        assertEquals(listOf("failed-2"), failure?.suppressed?.map { it.message })
    }
}
