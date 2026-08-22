package net.weero.measix.pilot.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import net.weero.measix.pilot.data.datastore.DEFAULT_ASSISTANT_ID
import net.weero.measix.pilot.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationSessionTest {
    @Test
    fun `tts queue id is reused only when resuming the same master turn`() {
        val scope = CoroutineScope(Job())
        val session = ConversationSession(
            id = Uuid.random(),
            initial = Conversation.ofId(Uuid.random(), assistantId = DEFAULT_ASSISTANT_ID),
            scope = scope,
            onIdle = {},
        )

        val firstTurn = session.getTtsQueueSessionId(resumeExistingTurn = false)
        val resumedTurn = session.getTtsQueueSessionId(resumeExistingTurn = true)
        val nextTurn = session.getTtsQueueSessionId(resumeExistingTurn = false)

        assertEquals(firstTurn, resumedTurn)
        assertNotEquals(firstTurn, nextTurn)
        scope.cancel()
    }

    @Test
    fun `completion of replaced job cannot clear current job`() = runTest {
        val cleanupGate = CompletableDeferred<Unit>()
        val firstJob = Job()
        val firstChild = CoroutineScope(firstJob).launch {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { cleanupGate.await() }
            }
        }
        val session = ConversationSession(
            id = Uuid.random(),
            initial = Conversation.ofId(Uuid.random(), assistantId = DEFAULT_ASSISTANT_ID),
            scope = this,
            onIdle = {},
        )
        val replacementJob = Job()

        runCurrent()
        session.setJob(firstJob)
        session.setJob(replacementJob)
        assertSame(replacementJob, session.getJob())

        cleanupGate.complete(Unit)
        firstChild.join()

        assertSame(replacementJob, session.getJob())
        replacementJob.cancel()
    }

    @Test
    fun `cancel reasons remain bound to their turn when a replacement job starts`() = runTest {
        val cleanupGate = CompletableDeferred<Unit>()
        val firstJob = Job()
        val firstChild = CoroutineScope(firstJob).launch {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { cleanupGate.await() }
            }
        }
        val session = ConversationSession(
            id = Uuid.random(),
            initial = Conversation.ofId(Uuid.random(), assistantId = DEFAULT_ASSISTANT_ID),
            scope = this,
            onIdle = {},
        )
        val firstTurn = Uuid.random()
        val secondTurn = Uuid.random()
        val replacementJob = Job()

        runCurrent()
        session.beginTurn(firstTurn)
        session.setJob(firstJob, firstTurn)
        session.requestCancel(firstTurn, "superseded_by_new_turn")
        session.setJob(replacementJob, secondTurn)
        session.requestCancel(secondTurn, "user_stop")

        assertEquals(secondTurn, session.currentTurnId())
        assertEquals("superseded_by_new_turn", session.consumeCancelReason(firstTurn))
        assertEquals("user_stop", session.peekCancelReason(secondTurn))

        cleanupGate.complete(Unit)
        firstChild.join()

        assertSame(replacementJob, session.getJob())
        assertEquals(secondTurn, session.currentTurnId())
        assertEquals("user_stop", session.consumeCancelReason(secondTurn))
        replacementJob.cancel()
    }

    @Test
    fun `dirty session is not idle-evicted until persisted`() = runTest {
        var idle = false
        val session = ConversationSession(
            id = Uuid.random(),
            initial = Conversation.ofId(Uuid.random(), assistantId = DEFAULT_ASSISTANT_ID),
            scope = this,
            onIdle = { idle = true },
        )

        session.acquire()
        session.bumpStateRevision()
        session.release()
        advanceTimeBy(6_000)
        assertTrue(session.isDirty())
        assertFalse(idle)

        session.markPersisted(session.currentRevision())
        session.acquire()
        session.release()
        advanceTimeBy(6_000)
        assertFalse(session.isDirty())
        assertTrue(idle)
    }

    @Test
    fun `marking a dirty idle session persisted reschedules eviction without a new reference`() = runTest {
        var idle = false
        val session = ConversationSession(
            id = Uuid.random(),
            initial = Conversation.ofId(Uuid.random(), assistantId = DEFAULT_ASSISTANT_ID),
            scope = this,
            onIdle = { idle = true },
        )

        session.acquire()
        session.bumpStateRevision()
        session.release()
        advanceTimeBy(6_000)
        assertTrue(session.isDirty())
        assertFalse(idle)

        session.markPersisted(session.currentRevision())
        advanceTimeBy(6_000)

        assertFalse(session.isDirty())
        assertTrue(idle)
    }
}
