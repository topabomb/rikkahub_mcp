package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.Json
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.util.configureSessionHeader
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ResponseEndpointTest {
    @Test
    fun `redirected requests lose only automatically scoped session headers`() {
        val interceptor = me.rerere.ai.util.ProviderSessionHeaderInterceptor()
        val automatic = Request.Builder().url("https://opencode.ai/v1")
            .configureSessionHeader("conversation").build()
        val redirected = automatic.newBuilder().url("https://other.example/v1").build()
        val custom = Request.Builder().url("https://other.example/v1").header("x-opencode-session", "user-defined").build()
        listOf(automatic to "conversation", redirected to null, custom to "user-defined").forEach { (request, expected) ->
            val chain = io.mockk.mockk<okhttp3.Interceptor.Chain>()
            io.mockk.every { chain.request() } returns request
            io.mockk.every { chain.proceed(any()) } answers {
                val sent = firstArg<Request>()
                assertEquals(expected, sent.header("x-opencode-session"))
                okhttp3.Response.Builder().request(sent).protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200).message("OK").build()
            }
            interceptor.intercept(chain)
        }
    }

    @Test
    fun `old settings retain default endpoint and custom path survives serialization`() {
        val old = Json.decodeFromString<ProviderSetting.OpenAI>("""{"baseUrl":"https://example.com/v1"}""")
        assertEquals("https://example.com/v1/responses", responsesEndpoint(old))
        val custom = old.copy(baseUrl = "https://example.com/v1/", responsesPath = "/custom/responses")
        val restored = Json.decodeFromString<ProviderSetting.OpenAI>(Json.encodeToString(custom))
        assertEquals(custom, restored)
        assertEquals("https://example.com/v1/custom/responses", responsesEndpoint(restored))
    }

    @Test
    fun `responses path cannot change host escape base path or add query`() {
        listOf("", "responses", "https://other.com/responses", "//other.com/responses", "/../responses",
            "/%2E./responses", "/a/%2e%2e/responses", "/a%2fb", "/a%5cb", "/a\\b",
            "/responses?key=value", "/responses#fragment", "/res\nponses", "/res ponses").forEach { path ->
            assertThrows(path, IllegalArgumentException::class.java) {
                responsesEndpoint(ProviderSetting.OpenAI(responsesPath = path))
            }
        }
    }

    @Test
    fun `session header uses actual destination and overwrites custom identity only there`() {
        val request = Request.Builder().url("https://opencode.ai/zen/go/v1/responses")
            .header("x-opencode-session", "custom").configureSessionHeader("conversation-id").build()
        assertEquals(listOf("conversation-id"), request.headers.values("x-opencode-session"))
        listOf("https://api.openai.com/v1/responses", "https://opencode.ai.example/v1", "https://aiplatform.googleapis.com/v1")
            .forEach { destination ->
                assertNull(Request.Builder().url(destination).configureSessionHeader("conversation-id").build().header("x-opencode-session"))
            }
        assertNull(Request.Builder().url("https://opencode.ai/v1").configureSessionHeader(null).build().header("x-opencode-session"))
        assertThrows(IllegalArgumentException::class.java) {
            Request.Builder().url("https://opencode.ai/v1").configureSessionHeader("bad\nidentity")
        }
    }
}
