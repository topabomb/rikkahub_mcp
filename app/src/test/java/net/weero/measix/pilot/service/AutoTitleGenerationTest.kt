package net.weero.measix.pilot.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class AutoTitleGenerationTest {

    @Test
    fun `auto request proceeds when title is blank and under the attempt cap`() {
        assertEquals(
            AutoTitleGenerationDecision.Proceed,
            decideAutoTitleGeneration(
                force = false,
                titleBlank = true,
                inFlight = false,
                attempts = 0,
            ),
        )
    }

    @Test
    fun `auto request skips when title already exists`() {
        assertEquals(
            AutoTitleGenerationDecision.SkipHasTitle,
            decideAutoTitleGeneration(
                force = false,
                titleBlank = false,
                inFlight = false,
                attempts = 0,
            ),
        )
    }

    @Test
    fun `in-flight request wins over force and blank title`() {
        assertEquals(
            AutoTitleGenerationDecision.SkipInFlight,
            decideAutoTitleGeneration(
                force = true,
                titleBlank = true,
                inFlight = true,
                attempts = 0,
            ),
        )
    }

    @Test
    fun `auto request stops after the attempt cap`() {
        assertEquals(
            AutoTitleGenerationDecision.SkipAttemptsExhausted,
            decideAutoTitleGeneration(
                force = false,
                titleBlank = true,
                inFlight = false,
                attempts = MAX_AUTO_TITLE_GENERATION_ATTEMPTS,
            ),
        )
    }

    @Test
    fun `manual force bypasses existing title and attempt cap`() {
        assertEquals(
            AutoTitleGenerationDecision.Proceed,
            decideAutoTitleGeneration(
                force = true,
                titleBlank = false,
                inFlight = false,
                attempts = MAX_AUTO_TITLE_GENERATION_ATTEMPTS,
            ),
        )
    }

    @Test
    fun `blank generated title is not written`() {
        assertNull(
            resolveGeneratedTitleWrite(
                force = false,
                latestTitle = "",
                generatedTitle = "   ",
            ),
        )
    }

    @Test
    fun `auto write does not overwrite an existing title`() {
        assertNull(
            resolveGeneratedTitleWrite(
                force = false,
                latestTitle = "User title",
                generatedTitle = "Model title",
            ),
        )
    }

    @Test
    fun `force write replaces an existing title`() {
        assertEquals(
            "Model title",
            resolveGeneratedTitleWrite(
                force = true,
                latestTitle = "User title",
                generatedTitle = "  Model title  ",
            ),
        )
    }

    @Test
    fun `tracker deduplicates in-flight requests for the same conversation`() {
        val tracker = AutoTitleGenerationTracker()
        val conversationId = Uuid.random()

        assertEquals(
            AutoTitleGenerationDecision.Proceed,
            tracker.begin(conversationId, force = false, titleBlank = true),
        )
        assertEquals(
            AutoTitleGenerationDecision.SkipInFlight,
            tracker.begin(conversationId, force = false, titleBlank = true),
        )

        assertEquals(AutoTitleRetry(force = false), tracker.end(conversationId))
        assertNull(tracker.end(conversationId))
    }

    @Test
    fun `in-flight force is retried ahead of a queued auto request`() {
        val tracker = AutoTitleGenerationTracker()
        val conversationId = Uuid.random()

        assertEquals(
            AutoTitleGenerationDecision.Proceed,
            tracker.begin(conversationId, force = false, titleBlank = true),
        )
        assertEquals(
            AutoTitleGenerationDecision.SkipInFlight,
            tracker.begin(conversationId, force = false, titleBlank = true),
        )
        assertEquals(
            AutoTitleGenerationDecision.SkipInFlight,
            tracker.begin(conversationId, force = true, titleBlank = false),
        )

        assertEquals(AutoTitleRetry(force = true), tracker.end(conversationId))
    }

    @Test
    fun `tracker counts only recorded attempts toward the cap`() {
        val tracker = AutoTitleGenerationTracker()
        val conversationId = Uuid.random()

        repeat(MAX_AUTO_TITLE_GENERATION_ATTEMPTS) { index ->
            assertEquals(
                "attempt $index should proceed",
                AutoTitleGenerationDecision.Proceed,
                tracker.begin(conversationId, force = false, titleBlank = true),
            )
            tracker.recordAttempt(conversationId)
            assertNull(tracker.end(conversationId))
        }

        assertEquals(
            AutoTitleGenerationDecision.SkipAttemptsExhausted,
            tracker.begin(conversationId, force = false, titleBlank = true),
        )
    }

    @Test
    fun `missing-model style skip does not consume the attempt budget`() {
        val tracker = AutoTitleGenerationTracker()
        val conversationId = Uuid.random()

        repeat(MAX_AUTO_TITLE_GENERATION_ATTEMPTS + 2) {
            assertEquals(
                AutoTitleGenerationDecision.Proceed,
                tracker.begin(conversationId, force = false, titleBlank = true),
            )
            assertNull(tracker.end(conversationId))
        }
    }

    @Test
    fun `force still works after auto attempts are exhausted`() {
        val tracker = AutoTitleGenerationTracker()
        val conversationId = Uuid.random()

        repeat(MAX_AUTO_TITLE_GENERATION_ATTEMPTS) {
            tracker.begin(conversationId, force = false, titleBlank = true)
            tracker.recordAttempt(conversationId)
            tracker.end(conversationId)
        }

        assertEquals(
            AutoTitleGenerationDecision.Proceed,
            tracker.begin(conversationId, force = true, titleBlank = true),
        )
    }

    @Test
    fun `clear resets process-local state for that conversation`() {
        val tracker = AutoTitleGenerationTracker()
        val conversationId = Uuid.random()

        repeat(MAX_AUTO_TITLE_GENERATION_ATTEMPTS) {
            tracker.begin(conversationId, force = false, titleBlank = true)
            tracker.recordAttempt(conversationId)
            tracker.end(conversationId)
        }
        tracker.clear(conversationId)

        assertEquals(
            AutoTitleGenerationDecision.Proceed,
            tracker.begin(conversationId, force = false, titleBlank = true),
        )
    }
}
