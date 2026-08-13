package net.weero.measix.pilot.service

import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SubAssistantRunLeaseRegistryTest {
    @Test
    fun `same master and target is busy until lease cleanup then can run again`() = runTest {
        val registry = SubAssistantRunLeaseRegistry()
        val key = SubAssistantRunKey(Uuid.random(), Uuid.random())
        val masterJob = Job()
        val callerId = Uuid.random()
        val first = registry.tryAcquire(key, "run-1", callerId, masterJob)

        assertNotNull(first)
        assertTrue(registry.isBusy(key))
        assertNull(registry.tryAcquire(key, "run-2", callerId, masterJob))

        masterJob.cancel()
        first!!.job.join()
        assertTrue(first.job.isCancelled)

        registry.release(key, first)
        assertFalse(registry.isBusy(key))
        val retry = registry.tryAcquire(key, "run-3", callerId, Job())
        assertNotNull(retry)
        registry.release(key, retry!!)
    }

    @Test
    fun `different masters may call the same target concurrently`() {
        val registry = SubAssistantRunLeaseRegistry()
        val targetId = Uuid.random()
        val firstKey = SubAssistantRunKey(Uuid.random(), targetId)
        val secondKey = SubAssistantRunKey(Uuid.random(), targetId)

        val first = registry.tryAcquire(firstKey, "run-1", Uuid.random(), Job())
        val second = registry.tryAcquire(secondKey, "run-2", Uuid.random(), Job())

        assertNotNull(first)
        assertNotNull(second)
        registry.release(firstKey, first!!)
        registry.release(secondKey, second!!)
    }

    @Test
    fun `deleting caller cancels its lease and waits for structured cleanup`() = runTest {
        val registry = SubAssistantRunLeaseRegistry()
        val callerId = Uuid.random()
        val key = SubAssistantRunKey(Uuid.random(), Uuid.random())
        val lease = registry.tryAcquire(key, "run-1", callerId, Job())!!
        val cancellation = CompletableDeferred<Throwable?>()
        lease.job.invokeOnCompletion { cancellation.complete(it) }

        val deleteWaiter = launch { registry.cancelForAssistant(callerId) }
        runCurrent()

        assertTrue(lease.job.isCancelled)
        assertEquals("target_access_revoked", cancellation.await()?.message)
        assertTrue(deleteWaiter.isActive)

        registry.release(key, lease)
        runCurrent()
        assertTrue(deleteWaiter.isCompleted)
        assertFalse(registry.isBusy(key))
    }

    @Test
    fun `deleting target cancels only leases that use that target`() = runTest {
        val registry = SubAssistantRunLeaseRegistry()
        val targetId = Uuid.random()
        val targetKey = SubAssistantRunKey(Uuid.random(), targetId)
        val unrelatedKey = SubAssistantRunKey(Uuid.random(), Uuid.random())
        val targetLease = registry.tryAcquire(targetKey, "run-target", Uuid.random(), Job())!!
        val unrelatedLease = registry.tryAcquire(unrelatedKey, "run-other", Uuid.random(), Job())!!

        val deleteWaiter = launch { registry.cancelForAssistant(targetId) }
        runCurrent()

        assertTrue(targetLease.job.isCancelled)
        assertTrue(unrelatedLease.job.isActive)

        registry.release(targetKey, targetLease)
        runCurrent()
        assertTrue(deleteWaiter.isCompleted)
        registry.release(unrelatedKey, unrelatedLease)
    }

    @Test
    fun `startup cancellation waits for lease owner cleanup`() = runTest {
        val registry = SubAssistantRunLeaseRegistry()
        val key = SubAssistantRunKey(Uuid.random(), Uuid.random())
        val lease = registry.tryAcquire(key, "run", Uuid.random(), Job())!!

        val recovery = launch { registry.cancelAll("app_restarted") }
        runCurrent()

        assertTrue(lease.job.isCancelled)
        assertTrue(recovery.isActive)
        assertTrue(registry.isBusy(key))

        registry.release(key, lease)
        runCurrent()
        assertTrue(recovery.isCompleted)
        assertFalse(registry.isBusy(key))
    }
}
