package net.weero.measix.pilot.service

import org.junit.Assert.assertEquals
import org.junit.Test
import net.weero.measix.pilot.service.runtime.ConversationPresentation
import net.weero.measix.pilot.service.runtime.ConversationTurnPhase
import kotlin.uuid.Uuid

class ConversationActivityProjectionTest {
    @Test
    fun `response and title work remain distinct and can coexist`() {
        val responseOnly = Uuid.random()
        val approvalOnly = Uuid.random()
        val titleOnly = Uuid.random()
        val both = Uuid.random()

        assertEquals(
            mapOf(
                responseOnly to setOf(ConversationActivity.RESPONSE_GENERATION),
                approvalOnly to setOf(ConversationActivity.APPROVAL_REQUIRED),
                both to setOf(
                    ConversationActivity.APPROVAL_REQUIRED,
                    ConversationActivity.TITLE_GENERATION,
                ),
                titleOnly to setOf(ConversationActivity.TITLE_GENERATION),
            ),
            mergeConversationActivities(
                turnPresentations = mapOf(
                    responseOnly to ConversationPresentation(
                        activeRequestTurnId = responseOnly,
                        phase = ConversationTurnPhase.GENERATING,
                        processingText = null,
                        toolCallPhases = emptyMap(),
                    ),
                    approvalOnly to ConversationPresentation(
                        activeRequestTurnId = approvalOnly,
                        phase = ConversationTurnPhase.AWAITING_USER,
                        processingText = null,
                        toolCallPhases = emptyMap(),
                    ),
                    both to ConversationPresentation(
                        activeRequestTurnId = both,
                        phase = ConversationTurnPhase.AWAITING_USER,
                        processingText = null,
                        toolCallPhases = emptyMap(),
                    ),
                ),
                titleGenerationIds = setOf(both, titleOnly),
            ),
        )
    }
}
