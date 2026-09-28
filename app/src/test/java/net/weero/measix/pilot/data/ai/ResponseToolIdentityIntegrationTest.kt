package net.weero.measix.pilot.data.ai

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ModelRequestMessage
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.ToolResultStatus
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.service.runtime.TurnTransition
import net.weero.measix.pilot.service.turn.StepOutputAccumulator
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

/** Exercises the public SSE API, app-owned identity assignment and the next wire request together. */
class ResponseToolIdentityIntegrationTest {
    @Test
    fun `missing item ids survive accumulation and lossless next request for each success terminal`() = runBlocking {
        for (terminal in listOf("[DONE]", """{"type":"response.completed","response":{"status":"completed"}}""",
            """{"type":"response.completed","response":{"output":[{"type":"function_call","call_id":"same","name":"first","arguments":"{\"a\":1}"},{"type":"function_call","call_id":"same","name":"second","arguments":"{\"b\":2}"}]}}""")) {
            val events = listOf(
                """{"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","call_id":"same","name":"first","arguments":""}}""",
                """{"type":"response.output_item.added","output_index":1,"item":{"type":"function_call","call_id":"same","name":"second","arguments":""}}""",
                """{"type":"response.function_call_arguments.delta","output_index":1,"delta":"{\"b\":2}"}""",
                """{"type":"response.function_call_arguments.done","output_index":1,"arguments":"{\"b\":2}"}""",
                """{"type":"response.function_call_arguments.done","output_index":0,"arguments":"{\"a\":1}"}""",
                """{"type":"response.function_call_arguments.done","output_index":0,"arguments":"{\"a\":1}"}""",
                terminal,
            )
            withServer(events) { api, setting, bodies ->
                val params = TextGenerationParams(model = Model(modelId = "test", displayName = "test"))
                val accumulator = StepOutputAccumulator()
                var active = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(TurnTransition.openStep(0)))
                withTimeout(10_000) {
                    api.streamText(setting, listOf(ModelRequestMessage.user("go")), params).collect {
                        active = accumulator.accumulate(active, it, null)
                    }
                }
                val calls = active.getTools()
                assertEquals(2, calls.map { it.localCallId }.distinct().size)
                assertEquals(listOf("same", "same"), calls.map { it.providerCallId })
                assertEquals(listOf("first", "second"), calls.map { it.toolName })
                assertEquals(listOf("{\"a\":1}", "{\"b\":2}"), calls.map { it.input })
                // The test performs the same boundary removal as RequestAssembler; no durable Step enters ai.
                val request = ModelRequestMessage(
                    role = active.role,
                    parts = calls.map { it.copy(resultStatus = ToolResultStatus.COMPLETED, output = listOf(UIMessagePart.Text("result-${it.toolName}"))) },
                    providerMetadata = active.providerMetadata,
                )
                withTimeout(10_000) { api.generateText(setting, listOf(request), params) }
                val input = Json.parseToJsonElement(bodies[1]).jsonObject["input"]!!.jsonArray.map { it.jsonObject }
                assertEquals(listOf("function_call", "function_call", "function_call_output", "function_call_output"), input.map { it["type"]!!.jsonPrimitive.content })
                assertEquals(listOf("{\"a\":1}", "{\"b\":2}"), input.take(2).map { it["arguments"]!!.jsonPrimitive.content })
                assertTrue(input.take(2).none { "id" in it })
                assertEquals(listOf("result-first", "result-second"), input.takeLast(2).map { it["output"]!!.jsonPrimitive.content })
            }
        }
    }

    @Test
    fun `reverse duplicate call order and missing argument completion fail at public terminal`() = runBlocking {
        val reverse = listOf(
            """{"type":"response.output_item.added","output_index":1,"item":{"id":"b","type":"function_call","call_id":"same","name":"second","arguments":"{}"}}""",
            """{"type":"response.output_item.added","output_index":0,"item":{"id":"a","type":"function_call","call_id":"same","name":"first","arguments":"{}"}}""",
            """{"type":"response.completed","response":{"output":[{"id":"a","type":"function_call","call_id":"same","name":"first","arguments":"{}"},{"id":"b","type":"function_call","call_id":"same","name":"second","arguments":"{}"}]}}""",
        )
        val missing = listOf(
            """{"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","call_id":"same","name":"first","arguments":""}}""",
            """{"type":"response.function_call_arguments.delta","output_index":0,"delta":"{}"}""",
            "[DONE]",
        )
        val callOnlyAdded = """{"type":"response.output_item.added","item":{"type":"function_call","call_id":"same","name":"first","arguments":""}}"""
        val callOnly = listOf(callOnlyAdded, callOnlyAdded,
            """{"type":"response.function_call_arguments.done","call_id":"same","arguments":"{}"}""", "[DONE]")
        for ((events, reason) in listOf(reverse to "responses_duplicate_call_order", missing to "responses_arguments_incomplete",
            missing.dropLast(1) to "closed before a terminal event", callOnly to "responses_identity_missing",
            (listOf(missing.first()) + callOnly) to "responses_identity_missing")) {
            withServer(events) { api, setting, bodies ->
                val acc = StepOutputAccumulator()
                var active = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(TurnTransition.openStep(0)))
                var failure: Throwable? = null
                try {
                    withTimeout(10_000) {
                        api.streamText(setting, listOf(ModelRequestMessage.user("go")), TextGenerationParams(Model(modelId = "test", displayName = "test"))).collect {
                            active = acc.accumulate(active, it, null)
                        }
                    }
                } catch (error: IllegalStateException) { failure = error }
                  catch (error: me.rerere.ai.util.HttpException) { failure = error }
                assertTrue("Expected $reason, got $failure", failure?.message?.contains(reason) == true)
                assertEquals(1, bodies.size)
                assertNull(active.providerMetadata)
            }
        }
    }


    @Test
    fun `remote failed and incomplete preserve diagnostic usage and partial content`() = runBlocking {
        for ((terminal, reason) in listOf(
            """{"type":"response.failed","response":{"status":"failed","error":{"code":"server_error","message":"original detail"},"usage":{"input_tokens":3,"output_tokens":2,"total_tokens":5}}}""" to "original detail",
            """{"type":"response.incomplete","response":{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"usage":{"input_tokens":3,"output_tokens":2,"total_tokens":5}}}""" to "max_output_tokens",
        )) {
            withServer(listOf(
                """{"type":"response.output_text.delta","item_id":"text","delta":"partial answer"}""",
                """{"type":"response.output_item.added","output_index":1,"item":{"type":"function_call","call_id":"call","name":"first","arguments":""}}""",
                terminal,
            )) { api, setting, _ ->
                val chunks = mutableListOf<me.rerere.ai.ui.MessageChunk>()
                var error: me.rerere.ai.util.HttpException? = null
                try {
                    withTimeout(10_000) {
                        api.streamText(setting, listOf(ModelRequestMessage.user("go")), TextGenerationParams(Model(modelId = "test", displayName = "test"))).collect { chunks += it }
                    }
                } catch (failure: me.rerere.ai.util.HttpException) { error = failure }
                assertTrue("Expected original remote reason, got $error", error?.message?.contains(reason) == true)
                assertEquals(5L, chunks.last().usage!!.totalTokens)
                assertEquals("partial answer", chunks.first().choices.single().delta!!.toText())
            }
        }
    }

    private suspend fun withServer(
        events: List<String>,
        block: suspend (ResponseAPI, ProviderSetting.OpenAI, List<String>) -> Unit,
    ) {
        val bodies = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val client = OkHttpClient()
        server.createContext("/responses") { exchange ->
            val stream = bodies.isEmpty()
            bodies += exchange.requestBody.use { String(it.readBytes(), Charsets.UTF_8) }
            val body = if (stream) events.joinToString("") { "data: $it\n\n" }
                else """{"id":"next","model":"test","status":"completed","output":[]}"""
            exchange.responseHeaders.add("Content-Type", if (stream) "text/event-stream" else "application/json")
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block(ResponseAPI(client), ProviderSetting.OpenAI(baseUrl = "http://127.0.0.1:${server.address.port}", apiKey = "test", useResponseApi = true), bodies)
        } finally {
            server.stop(0)
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }
}
