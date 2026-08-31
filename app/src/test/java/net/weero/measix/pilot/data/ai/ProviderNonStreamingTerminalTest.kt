package net.weero.measix.pilot.data.ai

import kotlinx.coroutines.test.runTest
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderResponseException
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.GoogleProvider
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.ProviderTerminalStatus
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderNonStreamingTerminalTest {
    @Test
    fun `Google HTTP 200 incomplete keeps decoded partial text and usage in typed failure`() = runTest {
        val body = """
            {"candidates":[{"content":{"role":"model","parts":[{"text":"private partial answer"}]},
            "finishReason":"MAX_TOKENS"}],
            "usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":20,"totalTokenCount":120}}
        """.trimIndent()
        val provider = GoogleProvider(client(body))
        val failure = runCatching {
            provider.generateText(
                ProviderSetting.Google(apiKey = "test", baseUrl = "https://fixture.invalid/v1beta"),
                listOf(UIMessage.user("hello")),
                TextGenerationParams(Model(modelId = "gemini-test")),
            )
        }.exceptionOrNull()

        assertTrue(failure is ProviderResponseException)
        failure as ProviderResponseException
        assertEquals(ProviderTerminalStatus.INCOMPLETE, (failure.cause as HttpException).terminalStatus)
        assertEquals("private partial answer", failure.response.choices.single().message!!.toText())
        assertEquals(100L, failure.response.usage!!.inputTokens)
        assertEquals(20L, failure.response.usage!!.outputTokens)
        assertEquals(120L, failure.response.usage!!.totalTokens)
        assertFalse(failure.stackTraceToString().contains("private partial answer"))
    }

    @Test
    fun `Responses HTTP 200 incomplete keeps partial output and usage while generateText still fails`() = runTest {
        val body = """
            {"id":"resp-test","status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},
            "output":[{"id":"msg-test","type":"message","role":"assistant",
            "content":[{"type":"output_text","text":"private partial answer"}]}],
            "usage":{"input_tokens":100,"output_tokens":20,"total_tokens":120}}
        """.trimIndent()
        val failure = responseFailure(body)

        assertEquals(ProviderTerminalStatus.INCOMPLETE, (failure.cause as HttpException).terminalStatus)
        assertEquals("private partial answer", failure.response.choices.single().message!!.toText())
        assertEquals(100L, failure.response.usage!!.inputTokens)
        assertEquals(20L, failure.response.usage!!.outputTokens)
        assertFalse(failure.stackTraceToString().contains("private partial answer"))
    }

    @Test
    fun `Responses HTTP 200 failed with null output keeps usage and original failure classification`() = runTest {
        val failure = responseFailure("""
            {"id":"resp-test","status":"failed","output":null,
            "error":{"code":"server_error","message":"upstream failed"},
            "usage":{"input_tokens":100,"output_tokens":0,"total_tokens":100}}
        """.trimIndent())

        assertEquals(ProviderTerminalStatus.FAILED, (failure.cause as HttpException).terminalStatus)
        assertTrue(failure.response.choices.isEmpty())
        assertEquals(100L, failure.response.usage!!.inputTokens)
        assertTrue(failure.message!!.contains("upstream failed"))
    }

    private suspend fun responseFailure(body: String): ProviderResponseException {
        val failure = runCatching {
            ResponseAPI(client(body)).generateText(
                ProviderSetting.OpenAI(apiKey = "test", baseUrl = "https://fixture.invalid/v1"),
                listOf(UIMessage.user("hello")),
                TextGenerationParams(Model(modelId = "test-model")),
            )
        }.exceptionOrNull()
        assertTrue(failure is ProviderResponseException)
        return failure as ProviderResponseException
    }

    private fun client(body: String): OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }.build()
}
