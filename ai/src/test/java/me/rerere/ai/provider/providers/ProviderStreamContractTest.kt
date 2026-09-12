package me.rerere.ai.provider.providers

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Before
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.ProviderToolCallSlot
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.ProviderTerminalStatus
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the actual SSE listener and Flow closure using an in-process HTTP response body. */
class ProviderStreamContractTest {
    @Test
    fun `cancelling during request assembly never starts an HTTP call`() = runBlocking {
        for (wire in Wire.entries) {
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val calls = java.util.concurrent.atomic.AtomicInteger()
            val headers = object : AbstractList<me.rerere.ai.provider.CustomHeader>() {
                override val size = 1
                override fun get(index: Int): me.rerere.ai.provider.CustomHeader {
                    entered.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    return me.rerere.ai.provider.CustomHeader("X-Test", "blocked-assembly")
                }
            }
            val client = OkHttpClient.Builder().eventListener(object : okhttp3.EventListener() {
                override fun callStart(call: okhttp3.Call) { calls.incrementAndGet() }
            }).build()
            val collecting = launch {
                flow(wire, client, params.copy(customHeaders = headers)).collect()
            }
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                }
                collecting.cancel()
                release.countDown()
                withTimeout(5_000) { collecting.join() }
                assertEquals(wire.name, 0, calls.get())
            } finally {
                release.countDown()
                collecting.cancel()
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
    }

    @Test
    fun `all request builders isolate assembly and use destination session contract`() = runBlocking {
        val caller = Thread.currentThread()
        val headers = object : AbstractList<me.rerere.ai.provider.CustomHeader>() {
            override val size = 1
            override fun get(index: Int): me.rerere.ai.provider.CustomHeader {
                assertTrue("Request assembly must leave caller thread", Thread.currentThread() !== caller)
                return me.rerere.ai.provider.CustomHeader("X-Test", "assembly")
            }
        }
        val generation = params.copy(providerSessionId = "conversation", customHeaders = headers)
        for (wire in Wire.entries) for (stream in listOf(false, true)) {
            for (vertex in if (wire == Wire.GOOGLE) listOf(false, true) else listOf(false)) {
            var captured: okhttp3.Request? = null
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                captured = chain.request()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(400).message("Rejected")
                    .body("{}".toResponseBody("application/json".toMediaType())).build()
            }.build()
            val openAI = ProviderSetting.OpenAI(baseUrl = "https://opencode.ai/v1", responsesPath = "/custom/responses", apiKey = "test")
            val claude = ProviderSetting.Claude(baseUrl = "https://opencode.ai/v1", apiKey = "test")
            val google = ProviderSetting.Google(baseUrl = "https://opencode.ai/v1beta", apiKey = "test", vertexAI = vertex)
            try {
                withTimeout(5_000) {
                    if (stream) {
                        val flow = when (wire) {
                            Wire.CHAT -> ChatCompletionsAPI(client, KeyRoulette.default()).streamText(openAI, emptyList(), generation)
                            Wire.RESPONSES -> ResponseAPI(client).streamText(openAI, emptyList(), generation)
                            Wire.CLAUDE -> ClaudeProvider(client).streamText(claude, emptyList(), generation)
                            Wire.GOOGLE -> GoogleProvider(client).streamText(google, emptyList(), generation)
                        }
                        flow.collect { assertTrue(Thread.currentThread() === caller) }
                    } else when (wire) {
                        Wire.CHAT -> ChatCompletionsAPI(client, KeyRoulette.default()).generateText(openAI, emptyList(), generation)
                        Wire.RESPONSES -> ResponseAPI(client).generateText(openAI, emptyList(), generation)
                        Wire.CLAUDE -> ClaudeProvider(client).generateText(claude, emptyList(), generation)
                        Wire.GOOGLE -> GoogleProvider(client).generateText(google, emptyList(), generation)
                    }
                }
                error("Expected controlled HTTP rejection")
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                val request = requireNotNull(captured)
                assertEquals("$wire stream=$stream vertex=$vertex", if (vertex) null else "conversation", request.header("x-opencode-session"))
                if (vertex) assertEquals("aiplatform.googleapis.com", request.url.host)
                if (wire == Wire.RESPONSES) assertEquals("/v1/custom/responses", request.url.encodedPath)
            } finally {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
            }
        }
    }

    @Before
    fun isolateAndroidLogging() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any<Throwable>()) } returns 0
        every { Log.e(any(), any()) } returns 0
    }

    @After
    fun restoreAndroidLogging() { unmockkStatic(Log::class) }

    private enum class Wire { CHAT, CLAUDE, GOOGLE, RESPONSES }
    private val params = TextGenerationParams(model = Model(modelId = "test-model"))

    private fun partial(wire: Wire): String = when (wire) {
        Wire.CHAT -> """{"choices":[{"delta":{"content":"partial"},"finish_reason":null}]}"""
        Wire.CLAUDE -> """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"partial"}}"""
        Wire.GOOGLE -> """{"candidates":[{"content":{"parts":[{"text":"partial"}]}}]}"""
        Wire.RESPONSES -> """{"type":"response.output_text.delta","item_id":"message","delta":"partial"}"""
    }

    private fun terminal(wire: Wire): List<String> = when (wire) {
        Wire.CHAT -> listOf("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""", "[DONE]")
        Wire.CLAUDE -> listOf("""{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""", """{"type":"message_stop"}""")
        Wire.GOOGLE -> listOf("""{"candidates":[{"finishReason":"STOP"}]}""")
        Wire.RESPONSES -> listOf("""{"type":"response.completed","response":{"id":"response","status":"completed","output":[]}}""")
    }

    private suspend fun flow(wire: Wire, client: OkHttpClient, params: TextGenerationParams = this.params): Flow<MessageChunk> = when (wire) {
        Wire.CHAT -> ChatCompletionsAPI(client, KeyRoulette.default()).streamText(
            ProviderSetting.OpenAI(baseUrl = "https://provider.test/v1", apiKey = "test"), emptyList(), params,
        )
        Wire.CLAUDE -> ClaudeProvider(client).streamText(
            ProviderSetting.Claude(baseUrl = "https://provider.test/v1", apiKey = "test"), emptyList(), params,
        )
        Wire.GOOGLE -> GoogleProvider(client).streamText(
            ProviderSetting.Google(baseUrl = "https://provider.test/v1beta", apiKey = "test"), emptyList(), params,
        )
        Wire.RESPONSES -> ResponseAPI(client).streamText(
            ProviderSetting.OpenAI(baseUrl = "https://provider.test/v1", apiKey = "test"), emptyList(), params,
        )
    }

    private fun collect(wire: Wire, events: List<String>): Pair<List<MessageChunk>, Throwable?> = runBlocking {
        val caller = Thread.currentThread()
        val body = events.joinToString("") { "data: $it\n\n" }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val chunks = mutableListOf<MessageChunk>()
        var error: Throwable? = null
        try {
            withTimeout(5_000) { flow(wire, client).collect {
                assertTrue("Collector must stay on the caller thread", Thread.currentThread() === caller)
                chunks += it
            } }
        } catch (failure: Throwable) {
            error = failure
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
        chunks to error
    }

    @Test
    fun `every provider rejects EOF without terminal evidence and retains streamed content`() {
        Wire.entries.forEach { wire ->
            val (chunks, error) = collect(wire, listOf(partial(wire)))
            assertTrue("$wire lost partial output", chunks.flatMap { it.choices }.any { it.delta?.toText() == "partial" })
            assertTrue("$wire returned $error", error is HttpException)
            assertEquals(wire.name, ProviderTerminalStatus.INCOMPLETE, (error as HttpException).terminalStatus)
        }
    }

    @Test
    fun `formal terminal evidence preserves the actual finish reason`() {
        val expected = listOf("stop", "end_turn", "STOP", "completed")
        Wire.entries.forEachIndexed { index, wire ->
            val (chunks, error) = collect(wire, listOf(partial(wire)) + terminal(wire))
            assertEquals("$wire failed", null, error)
            assertEquals(expected[index], chunks.flatMap { it.choices }.mapNotNull { it.finishReason }.last())
        }
    }

    @Test
    fun `chat DONE without finish reason and Claude stop without reason remain incomplete`() {
        listOf(Wire.CHAT to "[DONE]", Wire.CLAUDE to """{"type":"message_stop"}""").forEach { (wire, stop) ->
            val (_, error) = collect(wire, listOf(partial(wire), stop))
            assertNotNull(error)
            assertEquals(ProviderTerminalStatus.INCOMPLETE, (error as HttpException).terminalStatus)
        }
    }

    @Test
    fun `token limit termination remains incomplete instead of running emitted tools`() {
        val endings = listOf(
            Wire.CHAT to listOf("""{"choices":[{"delta":{},"finish_reason":"length"}]}""", "[DONE]"),
            Wire.CLAUDE to listOf("""{"type":"message_delta","delta":{"stop_reason":"max_tokens"}}""", """{"type":"message_stop"}"""),
        )
        endings.forEach { (wire, ending) ->
            val (chunks, error) = collect(wire, listOf(partial(wire)) + ending)
            assertEquals(ProviderTerminalStatus.INCOMPLETE, (error as HttpException).terminalStatus)
            assertTrue(chunks.flatMap { it.choices }.any { it.finishReason != null })
        }
    }

    @Test
    fun `Provider slots distinguish identical call IDs and preserve interleaved continuation identity`() {
        val cases = mapOf(
            Wire.CHAT to listOf(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"same","function":{"name":"a","arguments":"{"}},{"index":1,"id":"same","function":{"name":"b","arguments":"{"}}]}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"}"}}]}}]}""",
            ),
            Wire.CLAUDE to listOf(
                """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"same","name":"a","input":{}}}""",
                """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"same","name":"b","input":{}}}""",
                """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{}"}}""",
            ),
            Wire.GOOGLE to listOf(
                """{"candidates":[{"content":{"parts":[{"functionCall":{"id":"same","name":"a","args":{}}}]}}]}""",
                """{"candidates":[{"content":{"parts":[{"functionCall":{"id":"same","name":"b","args":{}}}]}}]}""",
            ),
            Wire.RESPONSES to listOf(
                """{"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","id":"item_a","call_id":"same","name":"a","arguments":""}}""",
                """{"type":"response.output_item.added","output_index":1,"item":{"type":"function_call","id":"item_b","call_id":"same","name":"b","arguments":""}}""",
                """{"type":"response.function_call_arguments.delta","output_index":0,"item_id":"item_a","delta":"{}"}""",
            ),
        )
        cases.forEach { (wire, events) ->
            val (chunks, error) = collect(wire, events + terminal(wire))
            assertEquals("$wire failed", null, error)
            val choices = chunks.flatMap { it.choices }.filter { it.toolCallSlots.isNotEmpty() }
            val slots = choices.flatMap { it.toolCallSlots }
            assertEquals("$wire collapsed calls", 2, slots.distinct().size)
            assertEquals(listOf("same", "same"), choices.flatMap { it.delta!!.getTools() }.take(2).map { it.providerCallId })
            if (wire != Wire.GOOGLE) assertEquals(slots.first(), slots.last())
            if (wire == Wire.RESPONSES) assertTrue(slots.first() is ProviderToolCallSlot.Item)
        }
    }
}
