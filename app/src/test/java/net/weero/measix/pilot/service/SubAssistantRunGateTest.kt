package net.weero.measix.pilot.service
import net.weero.measix.pilot.service.subassistant.SubAssistantRunGate
import net.weero.measix.pilot.service.subassistant.SubAssistantRunKey

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

class SubAssistantRunGateTest {
    @Test
    fun `same master and target is busy until lease cleanup then can run again`() = runTest {
        val gate = SubAssistantRunGate()
        val key = SubAssistantRunKey(Uuid.random(), Uuid.random())
        val masterJob = Job()
        val callerId = Uuid.random()
        val first = gate.acquireLease(key, "run-1", callerId, masterJob)

        assertNotNull(first)
        assertTrue(gate.isBusy(key))
        assertNull(gate.acquireLease(key, "run-2", callerId, masterJob))

        masterJob.cancel()
        first!!.job.join()
        assertTrue(first.job.isCancelled)

        first.close()
        assertFalse(gate.isBusy(key))
        val retry = gate.acquireLease(key, "run-3", callerId, Job())
        assertNotNull(retry)
        retry!!.close()
    }

    @Test
    fun `different masters may call the same target concurrently`() {
        val gate = SubAssistantRunGate()
        val targetId = Uuid.random()
        val firstKey = SubAssistantRunKey(Uuid.random(), targetId)
        val secondKey = SubAssistantRunKey(Uuid.random(), targetId)

        val first = gate.acquireLease(firstKey, "run-1", Uuid.random(), Job())
        val second = gate.acquireLease(secondKey, "run-2", Uuid.random(), Job())

        assertNotNull(first)
        assertNotNull(second)
        first!!.close()
        second!!.close()
    }

    @Test
    fun `deleting caller cancels its lease and waits for structured cleanup`() = runTest {
        val gate = SubAssistantRunGate()
        val callerId = Uuid.random()
        val key = SubAssistantRunKey(Uuid.random(), Uuid.random())
        val lease = gate.acquireLease(key, "run-1", callerId, Job())!!
        val cancellation = CompletableDeferred<Throwable?>()
        lease.job.invokeOnCompletion { cancellation.complete(it) }

        val deleteWaiter = launch { gate.cancelRunsForAssistant(callerId) }
        runCurrent()

        assertTrue(lease.job.isCancelled)
        assertEquals("target_access_revoked", cancellation.await()?.message)
        assertTrue(deleteWaiter.isActive)

        lease.close()
        runCurrent()
        assertTrue(deleteWaiter.isCompleted)
        assertFalse(gate.isBusy(key))
    }

    @Test
    fun `deleting target cancels only leases that use that target`() = runTest {
        val gate = SubAssistantRunGate()
        val targetId = Uuid.random()
        val targetKey = SubAssistantRunKey(Uuid.random(), targetId)
        val unrelatedKey = SubAssistantRunKey(Uuid.random(), Uuid.random())
        val targetLease = gate.acquireLease(targetKey, "run-target", Uuid.random(), Job())!!
        val unrelatedLease = gate.acquireLease(unrelatedKey, "run-other", Uuid.random(), Job())!!

        val deleteWaiter = launch { gate.cancelRunsForAssistant(targetId) }
        runCurrent()

        assertTrue(targetLease.job.isCancelled)
        assertTrue(unrelatedLease.job.isActive)

        targetLease.close()
        runCurrent()
        assertTrue(deleteWaiter.isCompleted)
        unrelatedLease.close()
    }

    @Test
    fun `startup cancellation waits for lease owner cleanup`() = runTest {
        val gate = SubAssistantRunGate()
        val key = SubAssistantRunKey(Uuid.random(), Uuid.random())
        val lease = gate.acquireLease(key, "run", Uuid.random(), Job())!!

        val recovery = launch { gate.cancelAllRuns("app_restarted") }
        runCurrent()

        assertTrue(lease.job.isCancelled)
        assertTrue(recovery.isActive)
        assertTrue(gate.isBusy(key))

        lease.close()
        runCurrent()
        assertTrue(recovery.isCompleted)
        assertFalse(gate.isBusy(key))
    }
}
