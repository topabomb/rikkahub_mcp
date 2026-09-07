package net.weero.measix.pilot.service.subassistant

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.data.enterprise.RealmAccess
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class SubAssistantRunGateTest {
    @Test fun `cancelled waiting owner rejects answers before its finally cleanup runs`() = runTest {
        val gate = SubAssistantRunGate()
        val root = Uuid.random()
        val registered = CompletableDeferred<Unit>()
        var cleaned = false
        val waiter = launch {
            val answer = gate.registerPendingInteraction(root, RealmAccess.Personal, "run", "ask", currentCoroutineContext()[Job]!!)
            registered.complete(Unit)
            try { answer.await() }
            finally { cleaned = true; gate.unregisterPendingInteraction("run", answer) }
        }
        registered.await()
        waiter.cancel()
        assertFalse(cleaned)
        assertFalse(gate.completeAnswer(root, RealmAccess.Personal, "run", "ask", "late"))
        waiter.join()
        assertTrue(cleaned)
    }

    @Test fun `removed pending cannot be answered and late cleanup cannot cancel its replacement`() = runTest {
        val gate = SubAssistantRunGate()
        val root = Uuid.random()
        val first = gate.registerPendingInteraction(root, RealmAccess.Personal, "run", "first", Job())
        gate.unregisterPendingInteraction("run", first)
        assertTrue(first.isCancelled)
        assertFalse(gate.completeAnswer(root, RealmAccess.Personal, "run", "first", "late"))
        val next = gate.registerPendingInteraction(root, RealmAccess.Personal, "run", "second", Job())
        gate.unregisterPendingInteraction("run", first)
        assertFalse(next.isCompleted)
        assertTrue(gate.completeAnswer(root, RealmAccess.Personal, "run", "second", "current"))
        assertEquals("current", next.await())
        gate.unregisterPendingInteraction("run", next)
    }

    @Test fun `recovery cancels all pending waiters and rejects late answers`() = runTest {
        val gate = SubAssistantRunGate()
        val root = Uuid.random()
        val answers = (1..3).associate { i -> i.toString() to gate.registerPendingInteraction(root, RealmAccess.Personal, i.toString(), "ask", Job()) }
        val waiters = answers.map { (run, answer) ->
            launch(Dispatchers.Unconfined) {
                try { answer.await() }
                finally { gate.unregisterPendingInteraction(run, answer) }
            }
        }
        gate.cancelPendingInteractions()
        waiters.joinAll()
        answers.forEach { (run, answer) ->
            assertTrue(answer.isCancelled)
            assertFalse(gate.completeAnswer(root, RealmAccess.Personal, run, "ask", "late"))
        }
    }

    @Test fun `concurrent responses have exactly one accepted answer`() = runTest {
        val gate = SubAssistantRunGate()
        val root = Uuid.random()
        val answer = gate.registerPendingInteraction(root, RealmAccess.Personal, "run", "ask", Job())
        val start = CompletableDeferred<Unit>()
        val responses = (1..8).map { index -> async(Dispatchers.Default) {
            start.await()
            index.toString().let { it to gate.completeAnswer(root, RealmAccess.Personal, "run", "ask", it) }
        } }
        start.complete(Unit)
        val accepted = responses.awaitAll().filter { it.second }
        assertEquals(1, accepted.size)
        assertEquals(accepted.single().first, answer.await())
        gate.unregisterPendingInteraction("run", answer)
    }
}
