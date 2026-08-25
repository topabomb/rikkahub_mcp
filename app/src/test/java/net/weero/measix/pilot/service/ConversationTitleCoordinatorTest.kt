package net.weero.measix.pilot.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationTitleCoordinatorTest {
    @Test
    fun `local title normalizes whitespace and truncates by Unicode code point`() {
        assertEquals(
            "hello world 😀…",
            deriveLocalConversationTitle(UIMessage.user("  hello\n world  😀x"), maxCodePoints = 13),
        )
    }

    @Test
    fun `blank generated title is never written`() {
        assertNull(normalizeGeneratedTitle("   "))
        assertEquals("Model title", normalizeGeneratedTitle(" Model title "))
    }

    @Test
    fun `persisted title is resolved while exact local title remains replaceable`() {
        assertEquals(ConversationTitlePhase.RESOLVED, resolveConversationTitlePhase("Manual", null))
        assertEquals(ConversationTitlePhase.LOCAL_FALLBACK, resolveConversationTitlePhase("Local", "Local"))
    }

    @Test
    fun `failed generation returns projection to stable phase`() {
        val coordinator = ConversationTitleCoordinator()
        val id = Uuid.random()
        coordinator.synchronize(id, "Local", "Local")
        val token = coordinator.granted(id, force = false, autoEligible = true, expectedTitle = "Local")

        coordinator.end(token)

        assertEquals(ConversationTitlePhase.LOCAL_FALLBACK, coordinator.phases.value[id])
    }

    @Test
    fun `coordinator deduplicates and retries an in-flight request`() {
        val coordinator = ConversationTitleCoordinator()
        val id = Uuid.random()
        coordinator.synchronize(id, "Local", "Local")
        val token = coordinator.granted(id, force = false, autoEligible = true, expectedTitle = "Local")

        assertEquals(
            ConversationTitleDecision.SkipInFlight,
            (coordinator.begin(id, false, true, "Local") as ConversationTitleBeginResult.Skipped).decision,
        )
        assertEquals(ConversationTitleRetry(force = false), coordinator.end(token))
    }

    @Test
    fun `resolved title skips automatic generation while force receives a CAS token`() {
        val coordinator = ConversationTitleCoordinator()
        val id = Uuid.random()
        coordinator.synchronize(id, "Manual", null)

        assertEquals(
            ConversationTitleDecision.SkipResolved,
            (coordinator.begin(id, false, false, "Manual") as ConversationTitleBeginResult.Skipped).decision,
        )
        val token = coordinator.granted(id, force = true, autoEligible = false, expectedTitle = "Manual")
        assertEquals("Manual", token.expectedTitle)
    }

    @Test
    fun `automatic attempt cap counts only the granted token`() {
        val coordinator = ConversationTitleCoordinator(maxAttempts = 1)
        val id = Uuid.random()
        coordinator.synchronize(id, "Local", "Local")
        val token = coordinator.granted(id, false, true, "Local")
        coordinator.recordAttempt(token)
        coordinator.end(token)

        assertEquals(
            ConversationTitleDecision.SkipAttemptsExhausted,
            (coordinator.begin(id, false, true, "Local") as ConversationTitleBeginResult.Skipped).decision,
        )
    }

    @Test
    fun `manual title invalidates active and queued model writes`() = runTest {
        val coordinator = ConversationTitleCoordinator()
        val id = Uuid.random()
        coordinator.synchronize(id, "Local", "Local")
        val token = coordinator.granted(id, false, true, "Local")
        coordinator.begin(id, force = true, autoEligible = false, expectedTitle = "Local")
        var generatedCommitted = false

        coordinator.commitManualTitle(id, "Manual") { }
        val accepted = coordinator.commitGeneratedTitle(token, "Model") { _, _ ->
            generatedCommitted = true
            true
        }

        assertFalse(accepted)
        assertFalse(generatedCommitted)
        assertNull(coordinator.end(token))
        assertEquals(ConversationTitlePhase.RESOLVED, coordinator.phaseOf(id))
    }

    @Test
    fun `manual commit serialized after generated CAS is the final owner`() = runTest {
        val coordinator = ConversationTitleCoordinator()
        val id = Uuid.random()
        coordinator.synchronize(id, "Local", "Local")
        val token = coordinator.granted(id, false, true, "Local")
        val enteredGeneratedCommit = CompletableDeferred<Unit>()
        val releaseGeneratedCommit = CompletableDeferred<Unit>()
        var durableTitle = "Local"

        val generated = async {
            coordinator.commitGeneratedTitle(token, "Model") { expected, title ->
                assertEquals(durableTitle, expected)
                enteredGeneratedCommit.complete(Unit)
                releaseGeneratedCommit.await()
                durableTitle = title
                true
            }
        }
        enteredGeneratedCommit.await()
        val manual = launch {
            coordinator.commitManualTitle(id, "Manual") { durableTitle = "Manual" }
        }
        runCurrent()
        assertEquals("Local", durableTitle)
        releaseGeneratedCommit.complete(Unit)
        generated.await()
        manual.join()

        assertEquals("Manual", durableTitle)
        assertEquals(ConversationTitlePhase.RESOLVED, coordinator.phaseOf(id))
    }

    @Test
    fun `generated commit accepts only current token and expected durable title`() = runTest {
        val coordinator = ConversationTitleCoordinator()
        val id = Uuid.random()
        coordinator.synchronize(id, "Local", "Local")
        val token = coordinator.granted(id, false, true, "Local")
        var durableTitle = "Manual"

        val accepted = coordinator.commitGeneratedTitle(token, "Model") { expected, title ->
            if (durableTitle != expected) return@commitGeneratedTitle false
            durableTitle = title
            true
        }

        assertFalse(accepted)
        assertEquals("Manual", durableTitle)
        coordinator.end(token)
        assertEquals(ConversationTitlePhase.LOCAL_FALLBACK, coordinator.phaseOf(id))
    }

    @Test
    fun `same-value generated CAS resolves local title provenance`() = runTest {
        val coordinator = ConversationTitleCoordinator()
        val id = Uuid.random()
        coordinator.synchronize(id, "Local", "Local")
        val token = coordinator.granted(id, false, true, "Local")

        val accepted = coordinator.commitGeneratedTitle(token, "Local") { expected, title ->
            assertEquals("Local", expected)
            assertEquals("Local", title)
            true
        }
        coordinator.end(token)

        assertTrue(accepted)
        assertEquals(ConversationTitlePhase.RESOLVED, coordinator.phaseOf(id))
    }

    @Test
    fun `clear removes projection and retry state`() {
        val coordinator = ConversationTitleCoordinator()
        val id = Uuid.random()
        coordinator.synchronize(id, "Local", "Local")
        coordinator.clear(id)

        assertNull(coordinator.phaseOf(id))
        assertTrue(coordinator.begin(id, false, true, "") is ConversationTitleBeginResult.Granted)
    }

    private fun ConversationTitleCoordinator.granted(
        id: Uuid,
        force: Boolean,
        autoEligible: Boolean,
        expectedTitle: String,
    ): ConversationTitleGenerationToken =
        (begin(id, force, autoEligible, expectedTitle) as ConversationTitleBeginResult.Granted).token
}
