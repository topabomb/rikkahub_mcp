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
            lease.execute {
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
}
