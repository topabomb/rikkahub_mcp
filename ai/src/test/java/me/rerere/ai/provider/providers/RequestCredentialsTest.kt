package me.rerere.ai.provider.providers

import android.content.Context
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.RequestCredentials
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.common.http.isPrivate
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RequestCredentialsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Before fun logs() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
    }
    @After fun restoreLogs() { unmockkStatic(Log::class) }

    private enum class Wire(val header: String, val prefix: String = "") {
        CHAT("Authorization", "Bearer "), RESPONSES("Authorization", "Bearer "),
        CLAUDE("x-api-key"), GOOGLE("x-goog-api-key"),
    }

    @Test fun `all real text builders preserve fixed credentials without user cache in either mode`() = runBlocking {
        for (wire in Wire.entries) for (stream in listOf(false, true)) {
            val cache = temporary.newFolder()
            val context = mockk<Context> { every { cacheDir } returns cache }
            val requests = mutableListOf<Request>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                requests += chain.request()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(503).message("Fixture end").body("fixture".toResponseBody()).build()
            }.build()
            try {
                val (provider, setting) = consumer(wire, client, context)
                val credential = "opaque, value with spaces"
                val params = TextGenerationParams(Model(modelId = "test"), credentials = RequestCredentials.fixed(credential))
                try {
                    if (stream) provider.streamText(setting, emptyList(), params).collect()
                    else provider.generateText(setting, emptyList(), params)
                    fail("Fixture HTTP rejection must propagate")
                } catch (_: Exception) { /* The captured request is the assertion boundary. */ }
                assertEquals("$wire/$stream", 1, requests.size)
                assertTrue(requests.single().isPrivate)
                assertEquals(listOf(wire.prefix + credential), requests.single().headers.values(wire.header))
                assertFalse(cache.resolve("lru_key_roulette.json").exists())
                assertFalse(Json.encodeToString(TextGenerationParams.serializer(), params).contains(credential))
                assertFalse(params.toString().contains(credential))
            } finally {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
    }

    @Test fun `fixed automatic authentication rejects duplicate private header before sending`() = runBlocking {
        for (wire in Wire.entries) {
            var requests = 0
            val client = OkHttpClient.Builder().addInterceptor { requests++; error("unexpected request") }.build()
            try {
                val (provider, setting) = consumer(wire, client, null)
                val params = TextGenerationParams(Model(modelId = "test"),
                    customHeaders = listOf(CustomHeader(wire.header.lowercase(), "other")),
                    credentials = RequestCredentials.fixed("fixed"))
                try { provider.generateText(setting, emptyList(), params); fail("duplicate auth accepted") }
                catch (error: IllegalStateException) { assertEquals("request_authentication_header_conflict", error.message) }
                assertEquals(0, requests)
            } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun consumer(wire: Wire, client: OkHttpClient, context: Context?): Pair<Provider<ProviderSetting>, ProviderSetting> {
        val pair = when (wire) {
            Wire.CHAT, Wire.RESPONSES -> OpenAIProvider(client, context) to ProviderSetting.OpenAI(
                baseUrl = "https://provider.test/v1", apiKey = "unused-user-key", useResponseApi = wire == Wire.RESPONSES)
            Wire.CLAUDE -> ClaudeProvider(client, context) to ProviderSetting.Claude(baseUrl = "https://provider.test/v1", apiKey = "unused-user-key")
            Wire.GOOGLE -> GoogleProvider(client, context) to ProviderSetting.Google(baseUrl = "https://provider.test/v1beta", apiKey = "unused-user-key")
        }
        return pair.first as Provider<ProviderSetting> to pair.second
    }
}
