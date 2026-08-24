package net.weero.measix.pilot.service

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationActivityProjectionTest {
    @Test
    fun `response and title work remain distinct and can coexist`() {
        val responseOnly = Uuid.random()
        val titleOnly = Uuid.random()
        val both = Uuid.random()

        assertEquals(
            mapOf(
                responseOnly to setOf(ConversationActivity.RESPONSE_GENERATION),
                both to setOf(
                    ConversationActivity.RESPONSE_GENERATION,
                    ConversationActivity.TITLE_GENERATION,
                ),
                titleOnly to setOf(ConversationActivity.TITLE_GENERATION),
            ),
            mergeConversationActivities(
                responseGenerationIds = setOf(responseOnly, both),
                titleGenerationIds = setOf(both, titleOnly),
            ),
        )
    }
}
