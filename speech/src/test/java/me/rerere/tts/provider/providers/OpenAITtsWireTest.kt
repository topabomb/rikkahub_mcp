package me.rerere.tts.provider.providers

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenAITtsWireTest {
    @Test fun `speech wire carries the selected model and voice with the MP3 response profile`() {
        val request = buildOpenAiSpeechRequest("enterprise-model", "explicit-voice", "朗读内容")
        assertEquals(setOf("model", "voice", "input", "response_format"), request.keys)
        assertEquals(JsonPrimitive("enterprise-model"), request["model"])
        assertEquals(JsonPrimitive("explicit-voice"), request["voice"])
        assertEquals(JsonPrimitive("朗读内容"), request["input"])
        assertEquals(JsonPrimitive("mp3"), request["response_format"])
    }
}
