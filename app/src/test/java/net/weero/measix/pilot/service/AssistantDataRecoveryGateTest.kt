package net.weero.measix.pilot.service

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantDataRecoveryGateTest {
    @Test
    fun `live mutation waits until startup recovery completes`() = runTest {
        val gate = AssistantDataRecoveryGate()
        var started = false
        val mutation = launch {
            gate.awaitReady()
            started = true
        }

        runCurrent()
        assertTrue(mutation.isActive)
        assertTrue(!started)

        gate.complete()
        runCurrent()
        assertTrue(started)
        assertTrue(mutation.isCompleted)
    }
}
