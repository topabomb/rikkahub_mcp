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
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.ImageEditParams
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

    @Test fun `real generation and multipart edit preserve private credentials without rotation or serialization`() = runBlocking {
        for (edit in listOf(false, true)) for (credential in listOf("opaque, image secret", null)) {
            val cache = temporary.newFolder()
            val context = mockk<Context> { every { cacheDir } returns cache }
            val requests = mutableListOf<Request>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                requests += chain.request()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(503).message("Fixture end").body("fixture".toResponseBody()).build()
            }.build()
            try {
                val provider = OpenAIProvider(client, context)
                val setting = ProviderSetting.OpenAI(baseUrl = "https://provider.test/v1", apiKey = "unused,user,keys")
                val model = Model(modelId = "image-original")
                val fixed = RequestCredentials.fixed(credential)
                val custom = if (credential == null) listOf(CustomHeader("Authorization", "Private header")) else emptyList()
                val png = temporary.newFile("image-${edit}-${credential == null}.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
                val generate = ImageGenerationParams(model, "draw", customHeaders = custom, credentials = fixed)
                val modify = ImageEditParams(model, "edit", listOf(png.path), customHeaders = custom, credentials = fixed)
                try {
                    if (edit) provider.editImage(setting, modify).collect() else provider.generateImage(setting, generate).collect()
                    fail("Fixture HTTP rejection must propagate")
                } catch (_: Exception) { /* Inspect the actual outgoing builder below. */ }
                assertEquals(1, requests.size)
                val request = requests.single()
                assertEquals(if (edit) "/v1/images/edits" else "/v1/images/generations", request.url.encodedPath)
                assertTrue(request.isPrivate)
                assertEquals(listOf(credential?.let { "Bearer $it" } ?: "Private header"), request.headers.values("Authorization"))
                assertFalse(cache.resolve("lru_key_roulette.json").exists())
                if (credential != null) {
                    assertFalse(Json.encodeToString(ImageGenerationParams.serializer(), generate).contains(credential))
                    assertFalse(Json.encodeToString(ImageEditParams.serializer(), modify).contains(credential))
                    assertFalse(generate.toString().contains(credential))
                    assertFalse(modify.toString().contains(credential))
                }
                val body = okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                assertTrue(body.contains("image-original"))
                if (edit) assertTrue(body.contains("filename="))
            } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }

    @Test fun `image authentication conflict is rejected before either real endpoint`() = runBlocking {
        for (edit in listOf(false, true)) {
            var requests = 0
            val client = OkHttpClient.Builder().addInterceptor { requests++; error("unexpected request") }.build()
            try {
                val provider = OpenAIProvider(client)
                val setting = ProviderSetting.OpenAI(baseUrl = "https://provider.test/v1", apiKey = "unused")
                val model = Model(modelId = "image")
                val headers = listOf(CustomHeader("authorization", "duplicate"))
                val fixed = RequestCredentials.fixed("fixed")
                val png = temporary.newFile("conflict-$edit.png").apply { writeBytes(byteArrayOf(1)) }
                try {
                    if (edit) provider.editImage(setting, ImageEditParams(model, "edit", listOf(png.path), customHeaders = headers, credentials = fixed)).collect()
                    else provider.generateImage(setting, ImageGenerationParams(model, "draw", customHeaders = headers, credentials = fixed)).collect()
                    fail("duplicate image authentication accepted")
                } catch (error: IllegalStateException) { assertEquals("request_authentication_header_conflict", error.message) }
                assertEquals(0, requests)
            } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }

    @Test fun `private image result downloads keep privacy without forwarding enterprise credentials`() = runBlocking {
        for (edit in listOf(false, true)) {
            val requests = mutableListOf<Request>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                requests += chain.request()
                val body = if (chain.request().method == "POST")
                    """{"data":[{"url":"https://download.test/image.png?signature=private"}]}"""
                else "image bytes"
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(body.toResponseBody()).build()
            }.build()
            try {
                val provider = OpenAIProvider(client)
                val setting = ProviderSetting.OpenAI(baseUrl = "https://provider.test/v1")
                val model = Model(modelId = "image")
                val headers = listOf(CustomHeader("X-Tenant", "private tenant"))
                val fixed = RequestCredentials.fixed("private credential")
                val png = temporary.newFile("download-$edit.png").apply { writeBytes(byteArrayOf(1)) }
                if (edit) provider.editImage(setting, ImageEditParams(model, "edit", listOf(png.path), customHeaders = headers, credentials = fixed)).collect()
                else provider.generateImage(setting, ImageGenerationParams(model, "draw", customHeaders = headers, credentials = fixed)).collect()
                assertEquals(2, requests.size)
                assertTrue(requests.all { it.isPrivate })
                assertEquals("GET", requests.last().method)
                assertEquals("download.test", requests.last().url.host)
                assertNull(requests.last().header("Authorization"))
                assertNull(requests.last().header("X-Tenant"))
            } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }

    @Test fun `routed grok generation preserves caller size while personal grok keeps legacy omission`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(503).message("Fixture end").body("fixture".toResponseBody()).build()
        }.build()
        try {
            val provider = OpenAIProvider(client)
            val setting = ProviderSetting.OpenAI(
                baseUrl = "https://api.x.ai/v1",
                apiKey = "personal-key",
            )
            val params = ImageGenerationParams(
                model = Model(modelId = "grok-imagine"),
                prompt = "draw",
                size = "1024x768",
            )

            try {
                provider.generateImage(
                    setting,
                    params.copy(
                        credentials = RequestCredentials.Routed(
                            "https://relay.test/runtime/v1/resources/image/images/generations",
                            "relay-token",
                        ),
                    ),
                ).collect()
                fail("fixture rejection must propagate")
            } catch (_: Exception) { /* Inspect the routed request body below. */ }
            try {
                provider.generateImage(setting, params).collect()
                fail("fixture rejection must propagate")
            } catch (_: Exception) { /* Inspect the personal request body below. */ }

            assertEquals(2, requests.size)
            val routedBody = okio.Buffer().also { requests[0].body!!.writeTo(it) }.readUtf8()
            val personalBody = okio.Buffer().also { requests[1].body!!.writeTo(it) }.readUtf8()
            assertTrue(routedBody.contains("\"size\":\"1024x768\""))
            assertFalse(personalBody.contains("\"size\""))
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test fun `routed generation size cannot be replaced by custom body`() = runBlocking {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { requests++; error("unexpected request") }.build()
        try {
            val provider = OpenAIProvider(client)
            val setting = ProviderSetting.OpenAI(baseUrl = "https://api.x.ai/v1")
            val params = ImageGenerationParams(
                model = Model(modelId = "grok-imagine"),
                prompt = "draw",
                size = "1024x768",
                customBody = listOf(CustomBody("size", JsonPrimitive("1x1"))),
                credentials = RequestCredentials.Routed(
                    "https://relay.test/runtime/v1/resources/image/images/generations",
                    "relay-token",
                ),
            )
            try {
                provider.generateImage(setting, params).collect()
                fail("custom body replaced routed size")
            } catch (error: me.rerere.ai.util.CustomBodyReservedKeyException) {
                assertEquals(listOf("size"), error.conflictingKeys)
            }
            assertEquals(0, requests)
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test fun `routed remote image uses strict downloader while fixed behavior stays compatible`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body("""{"data":[{"url":"http://127.0.0.1/private.png"}]}""".toResponseBody())
                .build()
        }.build()
        try {
            val provider = OpenAIProvider(client)
            val setting = ProviderSetting.OpenAI(baseUrl = "https://provider.test/v1")
            try {
                provider.generateImage(
                    setting,
                    ImageGenerationParams(
                        model = Model(modelId = "image"),
                        prompt = "draw",
                        credentials = RequestCredentials.Routed(
                            "https://relay.test/runtime/v1/resources/image/images/generations",
                            "relay-token",
                        ),
                    ),
                ).collect()
                fail("routed HTTP image URL was accepted")
            } catch (error: java.io.IOException) {
                assertEquals("routed_image_unsafe_url", error.message)
            }
            assertEquals(1, requests.size)
            assertEquals("relay.test", requests.single().url.host)
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test fun `relay uses exact full paths bearer and frozen headers for all four streaming codecs`() = runBlocking {
        for (wire in Wire.entries) {
            val requests = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
            val capturedHeaders = java.util.concurrent.CopyOnWriteArrayList<com.sun.net.httpserver.Headers>()
            val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
            val events = when (wire) {
                Wire.CHAT -> listOf("""{"choices":[{"delta":{"content":"result"},"finish_reason":"stop"}]}""", "[DONE]")
                Wire.CLAUDE -> listOf("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"result"}}""",
                    """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""", """{"type":"message_stop"}""")
                Wire.GOOGLE -> listOf("""{"candidates":[{"content":{"parts":[{"text":"result"}]},"finishReason":"STOP"}]}""")
                Wire.RESPONSES -> listOf("""{"type":"response.output_text.delta","item_id":"message","delta":"result"}""",
                    """{"type":"response.completed","response":{"id":"response","status":"completed","output":[]}}""")
            }
            server.createContext("/") { exchange -> exchange.use {
                requests += it.requestURI.toString() to it.requestBody.readBytes().toString(Charsets.UTF_8)
                capturedHeaders += it.requestHeaders
                val bytes = events.joinToString("") { event -> "data: $event\n\n" }.toByteArray()
                it.responseHeaders.set("Content-Type", "text/event-stream")
                it.sendResponseHeaders(200, bytes.size.toLong()); it.responseBody.write(bytes)
            } }
            server.start()
            val client = OkHttpClient()
            try {
                val suffix = when (wire) {
                    Wire.CHAT -> "/v1/chat/completions"
                    Wire.RESPONSES -> "/v1/responses"
                    Wire.CLAUDE -> "/v1/messages"
                    Wire.GOOGLE -> "/v1beta/models/upstream:streamGenerateContent?alt=sse"
                }
                val path = "/runtime/v1/resources/mdl_fixture$suffix"
                val endpoint = "http://127.0.0.1:${server.address.port}$path"
                val (provider, setting) = consumer(wire, client, null)
                val params = TextGenerationParams(Model(modelId = "upstream"), credentials = RequestCredentials.Routed(endpoint, "fixture-token"),
                    customHeaders = listOf(CustomHeader("X-Measix-Managed-Generation", "42"),
                        CustomHeader("X-Measix-Interaction-Id", "int_frozen")))
                val chunks = mutableListOf<me.rerere.ai.ui.MessageChunk>()
                provider.streamText(setting, emptyList(), params).collect { chunks += it }
                assertEquals(1, requests.size)
                assertEquals(path, requests.single().first)
                val headers = capturedHeaders.single()
                assertEquals("Bearer fixture-token", headers.getFirst("Authorization"))
                assertEquals("42", headers.getFirst("X-Measix-Managed-Generation"))
                assertEquals("int_frozen", headers.getFirst("X-Measix-Interaction-Id"))
                assertNull(headers.getFirst("x-api-key")); assertNull(headers.getFirst("x-goog-api-key"))
                if (wire == Wire.CLAUDE) assertNotNull(headers.getFirst("anthropic-version"))
                val body = Json.parseToJsonElement(requests.single().second).toString()
                if (wire != Wire.GOOGLE) assertTrue(body.contains("\"stream\":true"))
                if (wire == Wire.RESPONSES) assertTrue(body.contains("\"store\":false"))
                assertTrue(chunks.any { chunk -> chunk.choices.any { it.finishReason != null } })
                assertTrue(chunks.any { chunk -> chunk.choices.any { it.delta?.toText() == "result" } })
            } finally { server.stop(0); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
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
