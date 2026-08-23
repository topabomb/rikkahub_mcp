package net.weero.measix.pilot.service

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.service.finalizeInterruptedRunSafely
import org.junit.Assert.assertNull
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
}
