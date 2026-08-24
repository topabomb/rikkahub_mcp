package net.weero.measix.pilot.service

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.service.finalizeInterruptedRunSafely
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAssistantFinalizationTest {
    @Test
    fun `child timeout is contained and terminal metadata is still persisted`() = runTest {
        var metadataPersisted = false

        val failures = finalizeInterruptedRunSafely(
            timeoutMillis = 100,
            finalizeChild = { awaitCancellation() },
            finalizeMetadata = { metadataPersisted = true },
        )

        assertTrue(failures.child is TimeoutCancellationException)
        assertNull(failures.metadata)
        assertTrue(metadataPersisted)
    }

    @Test
    fun `durable finalization failure is propagated with both causes`() {
        val child = IllegalStateException("child")
        val metadata = IllegalArgumentException("metadata")
        val thrown = assertThrows(IllegalStateException::class.java) {
            InterruptedRunFinalizationFailures(child, metadata)
                .throwIfAny(kotlin.uuid.Uuid.random(), "run")
        }

        assertTrue(thrown.cause === child)
        assertTrue(thrown.suppressed.single() === metadata)
    }
}
