package net.weero.measix.pilot.service.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class ModelExecutionLeaseTest {
    @Test fun `admitted request remains a child of the original worker and cancellation awaits cleanup`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val lease = ModelExecutionLease { it(ModelRequestTarget.LocalExample) }
        val worker = launch {
            lease.borrow { it(ModelRequestTarget.LocalExample) }.execute {
                entered.complete(Unit)
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { cleaning.complete(Unit); finish.await() } }
            }
        }
        entered.await()
        worker.cancel()
        cleaning.await()
        assertFalse(worker.isCompleted)
        finish.complete(Unit)
        worker.join()
        lease.release()
    }

    @Test fun `cancelled queued admission cannot start a request when its policy lock opens`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val permit = CompletableDeferred<Unit>()
        var requests = 0
        val lease = ModelExecutionLease { accept ->
            withContext(NonCancellable) { entered.complete(Unit); permit.await(); accept(ModelRequestTarget.LocalExample) }
        }
        val worker = launch { lease.execute { requests++ } }
        entered.await()
        worker.cancel()
        permit.complete(Unit)
        worker.cancelAndJoin()
        assertEquals(0, requests)
        lease.release()
    }
    @Test fun `borrowed roles share closure and retry one failed binding cleanup`() = runBlocking {
        var releases = 0
        val owner = ModelExecutionLease(releaseOwner = { if (++releases == 1) error("cleanup_failed") }) {
            it(ModelRequestTarget.LocalExample)
        }
        var admitted = 0
        val inspection = owner.borrow { admitted++; it(ModelRequestTarget.LocalExample) }
        val image = owner.borrow { admitted++; it(ModelRequestTarget.LocalExample) }
        assertTrue(owner.owns(inspection))
        assertTrue(owner.owns(image))
        inspection.execute { Unit }
        image.execute { Unit }
        try { owner.release(); fail("expected cleanup failure") } catch (error: IllegalStateException) {
            assertEquals("cleanup_failed", error.message)
        }
        for (view in listOf<ModelRequests>(owner, inspection, image)) {
            try { view.execute { fail("closed owner reached I/O") }; fail("closed view accepted") }
            catch (error: IllegalStateException) { assertEquals("model_execution_lease_closed", error.message) }
        }
        assertEquals(2, admitted)
        owner.release()
        owner.release()
        assertEquals(2, releases)
    }

}
