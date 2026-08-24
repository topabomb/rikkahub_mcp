package net.weero.measix.pilot.utils

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CoroutineResultTest {
    @Test
    fun `ordinary failure remains a Result failure`() {
        val result = runCatchingPreservingCancellation<Int> { error("failed") }

        assertTrue(result.isFailure)
        assertEquals("failed", result.exceptionOrNull()?.message)
    }

    @Test
    fun `cancellation is never converted to a Result failure`() {
        assertThrows(CancellationException::class.java) {
            runCatchingPreservingCancellation<Unit> { throw CancellationException("cancelled") }
        }
    }
}
