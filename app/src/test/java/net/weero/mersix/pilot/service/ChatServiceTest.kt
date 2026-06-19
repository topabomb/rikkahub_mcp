package net.weero.mersix.pilot.service

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ChatServiceTest {

    // region backgroundTextGenerationParams

    @Test
    fun `background generation params include model custom request configuration`() {
        val headers = listOf(CustomHeader(name = "X-Gateway-Token", value = "test-token"))
        val bodies = listOf(CustomBody(key = "gateway_mode", value = JsonPrimitive("strict")))
        val model = Model(
            modelId = "custom-chat-model",
            customHeaders = headers,
            customBodies = bodies,
        )

        val params = backgroundTextGenerationParams(model)

        assertEquals(model, params.model)
        assertEquals(ReasoningLevel.OFF, params.reasoningLevel)
        assertEquals(headers, params.customHeaders)
        assertEquals(bodies, params.customBody)
    }

    @Test
    fun `background generation params default reasoning level is OFF`() {
        val model = Model(modelId = "test-model")
        val params = backgroundTextGenerationParams(model)
        assertEquals(ReasoningLevel.OFF, params.reasoningLevel)
    }

    @Test
    fun `background generation params with custom reasoning level`() {
        val model = Model(modelId = "test-model")
        val params = backgroundTextGenerationParams(model, ReasoningLevel.LOW)
        assertEquals(ReasoningLevel.LOW, params.reasoningLevel)
    }

    @Test
    fun `background generation params with empty custom headers and bodies`() {
        val model = Model(modelId = "test-model")
        val params = backgroundTextGenerationParams(model)
        assertTrue(params.customHeaders.isEmpty())
        assertTrue(params.customBody.isEmpty())
    }

    @Test
    fun `background generation params preserves model id`() {
        val model = Model(modelId = "gpt-4o-mini")
        val params = backgroundTextGenerationParams(model)
        assertEquals("gpt-4o-mini", params.model.modelId)
    }

    // endregion

    // region ChatError

    @Test
    fun `chat error has unique id`() {
        val error1 = ChatError(error = RuntimeException("test1"))
        val error2 = ChatError(error = RuntimeException("test2"))
        assertTrue(error1.id != error2.id)
    }

    @Test
    fun `chat error preserves title and message`() {
        val error = ChatError(
            title = "Generation Failed",
            error = RuntimeException("API rate limit exceeded"),
            conversationId = Uuid.random()
        )
        assertEquals("Generation Failed", error.title)
        assertEquals("API rate limit exceeded", error.error.message)
        assertNotNull(error.conversationId)
    }

    @Test
    fun `chat error timestamp is set`() {
        val before = System.currentTimeMillis()
        val error = ChatError(error = RuntimeException("test"))
        val after = System.currentTimeMillis()
        assertTrue(error.timestamp in before..after)
    }

    // endregion
}
