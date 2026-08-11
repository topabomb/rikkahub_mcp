package net.weero.measix.pilot.utils

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun `one-shot state flow does not restart after collectors leave`() = runBlocking {
        var starts = 0
        val state = flow {
            starts++
            emit(1)
        }.stateInOnce(this, 0)

        assertEquals(1, state.first { it == 1 })
        assertEquals(1, state.first { it == 1 })
        assertEquals(1, starts)
    }
}
