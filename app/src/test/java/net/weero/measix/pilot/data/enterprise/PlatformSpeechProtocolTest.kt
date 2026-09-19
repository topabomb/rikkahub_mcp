package net.weero.measix.pilot.data.enterprise

import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.tts.controller.TtsChunk
import me.rerere.tts.controller.TtsSynthesizer
import me.rerere.tts.provider.TTSManager
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformSpeechProtocolTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `realtime handshake keeps platform identity and does not replay service failures`() {
        listOf(428, 503).forEach { status ->
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            val received = CopyOnWriteArrayList<Received>()
            server.createContext("/") { exchange -> exchange.use {
                received += Received(it.requestURI.toASCIIString(), it.requestHeaders.getFirst("Authorization"),
                    it.requestHeaders.getFirst("X-Measix-Managed-Generation"), null, "")
                it.responseHeaders.set("Retry-After", "0")
                val bytes = "{\"detail\":\"original diagnostic\"}".toByteArray()
                it.sendResponseHeaders(status, bytes.size.toLong())
                it.responseBody.write(bytes)
            } }
            server.start()
            try {
                val resource = EnterpriseAsrResource("asr_${Uuid.random()}", "Realtime", true, "model /+中文", null,
                    EnterpriseAsrProtocol.DASHSCOPE, sampleRate = 16000, vadThreshold = 0.5, silenceDurationMs = 400)
                val (execution, version) = route(server, resource.id)
                val reference = me.rerere.common.configuration.ConfigurationReference.Enterprise(execution.connection.authority, resource.id)
                val target = EnterpriseSpeechTransport({ _, _ -> error("local transport used") })
                    .platformRealtimeTarget(execution, reference, resource, version, "int_${Uuid.random()}", "realtime-token")
                assertEquals(resource.modelId, target.request.url.queryParameter("model"))
                assertEquals(server.address.port, target.request.url.port)
                val failure = java.util.concurrent.CompletableFuture<Throwable>()
                val socket = target.sockets.newWebSocket(target.request, object : okhttp3.WebSocketListener() {
                    override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                        response?.close()
                        failure.complete(t)
                    }
                })
                try {
                    val error = failure.get(10, java.util.concurrent.TimeUnit.SECONDS)
                    assertTrue(error.toString(), error is me.rerere.common.http.RoutedHttpException)
                    assertEquals(status, (error as me.rerere.common.http.RoutedHttpException).status)
                    assertTrue(error.detail.contains("original diagnostic"))
                    assertEquals("Handshake replayed", 1, received.size)
                    assertEquals("Bearer realtime-token", received.single().authorization)
                    assertEquals("7", received.single().generation)
                } finally { socket.cancel() }
            } finally { server.stop(0) }
        }
    }

    @Test fun `cloud speech codecs preserve their wire profiles on a full platform route`() = runBlocking {
        listOf(EnterpriseTtsProtocol.OPENAI, EnterpriseTtsProtocol.GEMINI, EnterpriseTtsProtocol.MIMO).forEach { protocol ->
            server { server, received ->
                val definition = EnterpriseTtsResource("tts_${Uuid.random()}", "Speech", true, protocol,
                    modelId = "test-model", voice = "test-voice")
                val transport = EnterpriseSpeechTransport({ _, _ -> error("local transport used") })
                val (execution, version) = route(server, definition.id)
                val reference = me.rerere.common.configuration.ConfigurationReference.Enterprise(execution.connection.authority, definition.id)
                val target = transport.platformTarget(execution, reference, version, "int_${Uuid.random()}", "speech-token")
                val response = TtsSynthesizer(TTSManager(ApplicationProvider.getApplicationContext()))
                    .synthesize(definition.providerSetting(reference), TtsChunk(index = 0, text = "Hello"), target)
                assertArrayEquals(byteArrayOf(0, 1, 2, 3), response.audioData)
                val request = received.single()
                assertEquals("/runtime/v1/resources/${definition.id}/speech", request.path)
                assertEquals("Bearer speech-token", request.authorization)
                assertEquals("7", request.generation)
                assertNull(request.supplierKey)
                val body = Json.parseToJsonElement(request.body).jsonObject
                when (protocol) {
                    EnterpriseTtsProtocol.OPENAI -> assertEquals("Hello", body["input"]!!.jsonPrimitive.content)
                    EnterpriseTtsProtocol.GEMINI -> assertTrue("generationConfig" in body)
                    EnterpriseTtsProtocol.MIMO -> assertEquals("assistant", body["messages"]!!.jsonArray.last().jsonObject["role"]!!.jsonPrimitive.content)
                    else -> error("cloud protocol required")
                }
            }
        }
    }

    @Test fun `DashScope file ASR sends a WAV data URI and reads output text`() = runBlocking {
        server { server, received ->
            val resource = EnterpriseAsrResource("asr_${Uuid.random()}", "Transcription", true, "qwen-asr", "zh", EnterpriseAsrProtocol.DASHSCOPE_HTTP)
            val (execution, version) = route(server, resource.id)
            val reference = me.rerere.common.configuration.ConfigurationReference.Enterprise(execution.connection.authority, resource.id)
            val transport = EnterpriseSpeechTransport({ _, _ -> error("local transport used") })
            val audio = temporary.newFile().apply { writeBytes(byteArrayOf(0, 1, 2, 3)) }
            assertEquals("recognized", transport.transcribe(transport.platformTarget(execution, reference, version,
                "int_${Uuid.random()}", "asr-token"), resource, audio))
            val body = Json.parseToJsonElement(received.single().body).jsonObject
            val item = body["input"]!!.jsonObject["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray.single().jsonObject
            assertEquals("data:audio/wav;base64,AAECAw==", item["input_audio"]!!.jsonObject["data"]!!.jsonPrimitive.content)
            assertEquals("zh", body["parameters"]!!.jsonObject["language_hints"]!!.jsonArray.single().jsonPrimitive.content)
        }
    }

    private data class Received(val path: String, val authorization: String?, val generation: String?, val supplierKey: String?, val body: String)
    private suspend fun server(block: suspend (HttpServer, List<Received>) -> Unit) {
        val received = CopyOnWriteArrayList<Received>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> exchange.use {
            val body = it.requestBody.bufferedReader().readText()
            received += Received(it.requestURI.toString(), it.requestHeaders.getFirst("Authorization"),
                it.requestHeaders.getFirst("X-Measix-Managed-Generation"),
                it.requestHeaders.getFirst("api-key") ?: it.requestHeaders.getFirst("x-goog-api-key"), body)
            val json = Json.parseToJsonElement(body).jsonObject
            val (type, response) = when {
                "generationConfig" in json -> "application/json" to """{"candidates":[{"content":{"parts":[{"inlineData":{"data":"AAECAw==","mimeType":"audio/L16;rate=24000"}}]}}]}""".toByteArray()
                "messages" in json -> "text/event-stream" to "data: {\"choices\":[{\"delta\":{\"audio\":{\"data\":\"AAECAw==\"}}}]}\n\ndata: [DONE]\n\n".toByteArray()
                json["input"] is JsonObject -> "application/json" to """{"output":{"text":"recognized"}}""".toByteArray()
                else -> "audio/mpeg" to byteArrayOf(0, 1, 2, 3)
            }
            it.responseHeaders.set("Content-Type", type)
            it.sendResponseHeaders(200, response.size.toLong())
            it.responseBody.write(response)
        } }
        server.start()
        try { block(server, received) } finally { server.stop(0) }
    }

    private fun route(server: HttpServer, id: String): Pair<EnterpriseExecution.Platform, EnterpriseAppliedVersion> {
        val discovery = PlatformDiscovery(PlatformDiscoveryProduct.MEASIX_AGENT_PLATFORM, "1", "dep_${Uuid.random()}",
            "Speech test", "/api/client/v1", "/runtime/v1", listOf(4))
        val connection = PlatformConnection("http://127.0.0.1:${server.address.port}", discovery)
        return EnterpriseExecution.Platform(connection, "rel_${Uuid.random()}", "sha256:" + "0".repeat(64), mapOf(id to "/speech")) to
            EnterpriseAppliedVersion("test", 7, "test", "test")
    }
}
