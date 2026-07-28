package net.weero.measix.pilot.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantContextLimitTest {

    @Test
    fun `effective limit should normalize persisted values`() {
        assertEquals(0, Assistant(contextMessageLimit = 0).effectiveContextMessageLimit())
        assertEquals(0, Assistant(contextMessageLimit = -1).effectiveContextMessageLimit())
        assertEquals(MIN_CONTEXT_MESSAGE_LIMIT, Assistant(contextMessageLimit = 20).effectiveContextMessageLimit())
        assertEquals(DEFAULT_CONTEXT_MESSAGE_LIMIT, Assistant(contextMessageLimit = 80).effectiveContextMessageLimit())
        assertEquals(MAX_CONTEXT_MESSAGE_LIMIT, Assistant(contextMessageLimit = 600).effectiveContextMessageLimit())
    }

    @Test
    fun `legacy context size should not silently enable the new policy`() {
        val json = Json { ignoreUnknownKeys = true }

        val assistant = json.decodeFromString<Assistant>("""{"contextMessageSize":32}""")

        assertEquals(0, assistant.contextMessageLimit)
        assertEquals(0, assistant.effectiveContextMessageLimit())
    }
}
