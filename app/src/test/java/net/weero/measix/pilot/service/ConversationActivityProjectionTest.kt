package net.weero.measix.pilot.service

import org.junit.Assert.assertEquals
import org.junit.Test
import net.weero.measix.pilot.service.runtime.ConversationPresentation
import net.weero.measix.pilot.service.runtime.TurnLivePhase
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
                        activeTurnId = responseOnly,
                        phase = TurnLivePhase.MODEL_STREAMING,
                        processingText = null,
                        toolLivePhases = emptyMap(),
                    ),
                    approvalOnly to ConversationPresentation(
                        activeTurnId = approvalOnly,
                        phase = TurnLivePhase.AWAITING_USER,
                        processingText = null,
                        toolLivePhases = emptyMap(),
                    ),
                    both to ConversationPresentation(
                        activeTurnId = both,
                        phase = TurnLivePhase.AWAITING_USER,
                        processingText = null,
                        toolLivePhases = emptyMap(),
                    ),
                ),
                titleGenerationIds = setOf(both, titleOnly),
            ),
        )
    }
}
