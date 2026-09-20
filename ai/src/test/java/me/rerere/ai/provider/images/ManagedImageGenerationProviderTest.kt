package me.rerere.ai.provider.images

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.RequestCredentials
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.common.http.isPrivate
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedImageGenerationProviderTest {
    @Test
    fun `OpenAI request preserves existing typed wire`() = runBlocking {
        val requests = CopyOnWriteArrayList<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"data":[{"b64_json":"QQ=="}]}""".toResponseBody())
                .build()
        }.build()
        try {
            val provider = ManagedImageGenerationProvider(client)
            val endpoint = "https://relay.test/runtime/v1/resources/img_fixture/v1/images/generations"
            val result = provider.generate(
                ImageGenerationClientProtocol.OPENAI_IMAGES_GENERATIONS,
                ImageGenerationParams(Model(modelId = "gpt-image-1"), "draw", numOfImages = 1, size = "1024x1024"),
                emptyList(),
                RequestCredentials.Routed(endpoint, "relay-secret"),
            ).toList()

            assertEquals(listOf(ImageGenerationItem("QQ==", "image/png")), result)
            val request = requests.single()
            assertEquals(endpoint, request.url.toString())
            assertEquals("Bearer relay-secret", request.header("Authorization"))
            val actual = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            assertEquals(
                Json.parseToJsonElement("""{"model":"gpt-image-1","prompt":"draw","n":1,"size":"1024x1024"}"""),
                Json.parseToJsonElement(actual),
            )
            assertTrue(request.isPrivate)
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun `DashScope request uses exact routed endpoint and typed wire`() = runBlocking {
        val requests = CopyOnWriteArrayList<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"output":{"choices":[{"message":{"content":[{"image":"https://cdn.example/result.png"}]}}]}}""".toResponseBody())
                .build()
        }.build()
        try {
            val provider = ManagedImageGenerationProvider(client) { ImageGenerationItem("QQ==", "image/png") }
            val endpoint = "https://relay.test/runtime/v1/resources/img_fixture/api/v1/services/aigc/multimodal-generation/generation"
            val result = provider.generate(
                ImageGenerationClientProtocol.DASHSCOPE_MULTIMODAL_GENERATION,
                ImageGenerationParams(Model(modelId = "wan2.7-image"), "draw", numOfImages = 1, size = "1024x1024"),
                listOf(CustomHeader("X-Measix-Managed-Generation", "7")),
                RequestCredentials.Routed(endpoint, "relay-secret"),
            ).toList()

            assertEquals(listOf(ImageGenerationItem("QQ==", "image/png")), result)
            val request = requests.single()
            assertEquals(endpoint, request.url.toString())
            assertEquals("Bearer relay-secret", request.header("Authorization"))
            assertEquals("7", request.header("X-Measix-Managed-Generation"))
            val actual = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            assertEquals(
                Json.parseToJsonElement("""{"model":"wan2.7-image","input":{"messages":[{"role":"user","content":[{"text":"draw"}]}]},"parameters":{"size":"1024*1024","n":1,"watermark":false}}"""),
                Json.parseToJsonElement(actual),
            )
            assertTrue(request.isPrivate)
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun `DashScope response uses a small URL envelope limit for known and chunked bodies`() = runBlocking {
        val limit = maxRoutedDashScopeImageResponseBytes(1)
        assertTrue(limit < maxRoutedImageResponseBytes(1))
        val oversizedPayload = """{"padding":"${"a".repeat(limit.toInt())}"}""".toByteArray()
        val bodies = listOf(
            object : ResponseBody() {
                override fun contentType(): okhttp3.MediaType? = null
                override fun contentLength(): Long = limit + 1
                override fun source(): okio.BufferedSource = Buffer()
            },
            object : ResponseBody() {
                override fun contentType(): okhttp3.MediaType? = null
                override fun contentLength(): Long = -1
                override fun source(): okio.BufferedSource = Buffer().write(oversizedPayload)
            },
        )

        bodies.forEach { responseBody ->
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responseBody)
                    .build()
            }.build()
            try {
                val error = try {
                    ManagedImageGenerationProvider(client).generate(
                        ImageGenerationClientProtocol.DASHSCOPE_MULTIMODAL_GENERATION,
                        ImageGenerationParams(
                            Model(modelId = "wan2.7-image"),
                            "draw",
                            numOfImages = 1,
                            size = "1024x1024",
                        ),
                        emptyList(),
                        RequestCredentials.Routed(
                            "https://relay.test/api/v1/services/aigc/multimodal-generation/generation",
                            "relay-secret",
                        ),
                    ).toList()
                    null
                } catch (failure: IOException) {
                    failure
                }
                assertEquals("routed_image_response_limit_exceeded", error?.message)
            } finally {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
    }
}
