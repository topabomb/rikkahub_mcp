package net.weero.measix.pilot.data.ai.mcp

import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

internal data class McpToolPage(val tools: List<McpCatalogTool>, val nextCursor: String?)

/** A single SDK request owns this capture, including its timeout, cancellation and cleanup. */
internal class McpToolPageCapture private constructor() : AbstractCoroutineContextElement(Key) {
    private val response = AtomicReference<JsonObject?>()
    private var registration: Pair<McpCatalogWire, RequestId>? = null

    fun bind(wire: McpCatalogWire, id: RequestId) {
        check(registration == null) { "MCP catalog capture already belongs to a request" }
        registration = wire to id
        wire.register(id, this)
    }

    fun receive(result: JsonObject) {
        // The first matching response wins, just like the SDK response handler.
        response.compareAndSet(null, result)
    }

    private fun result(): McpToolPage {
        val result = checkNotNull(response.get()) { "MCP tools/list did not preserve its original response" }
        val tools = result["tools"] as? JsonArray ?: error("MCP tools/list must contain a tools array")
        val cursor = result["nextCursor"]
        check(cursor == null || cursor is JsonPrimitive && cursor.isString) { "MCP nextCursor must be a string" }
        return McpToolPage(tools.map {
            val definition = it as? JsonObject ?: error("MCP Tool must be an object")
            McpCatalogTool(definition)
        }, (cursor as? JsonPrimitive)?.content)
    }

    private fun close() {
        registration?.let { (wire, id) -> wire.remove(id, this) }
        registration = null
        response.set(null)
    }

    companion object Key : CoroutineContext.Key<McpToolPageCapture> {
        suspend fun read(request: suspend () -> Unit): McpToolPage {
            val capture = McpToolPageCapture()
            return try {
                withContext(capture) { request() }
                capture.result()
            } finally {
                capture.close()
            }
        }
    }
}

/** Transport-local in-flight bookkeeping, never a catalog or a second RPC request owner. */
internal class McpCatalogWire {
    private val captures = ConcurrentHashMap<RequestId, McpToolPageCapture>()

    suspend fun bind(message: JSONRPCMessage) {
        if (message is JSONRPCRequest && message.method == "tools/list") {
            currentCoroutineContext()[McpToolPageCapture]?.bind(this, message.id)
        }
    }

    fun register(id: RequestId, capture: McpToolPageCapture) {
        check(captures.putIfAbsent(id, capture) == null) { "Duplicate in-flight MCP request ID" }
    }

    fun remove(id: RequestId, capture: McpToolPageCapture) {
        captures.remove(id, capture)
    }

    fun close() = captures.clear()

    fun decode(text: String): JSONRPCMessage {
        val envelope = McpJsonFrame.parse(text) as? JsonObject ?: error("MCP frame must be an object")
        val message = McpJson.decodeFromJsonElement<JSONRPCMessage>(envelope)
        val result = envelope["result"] as? JsonObject
        if (result != null) {
            envelope["id"]?.let { id ->
                captures[McpJson.decodeFromJsonElement<RequestId>(id)]?.receive(result)
            }
        }
        return message
    }
}

/** Strict, bounded JSON with duplicate decoded keys rejected before they can collapse into a map. */
internal class McpJsonFrame private constructor(private val input: String) {
    private var position = 0

    private fun value(depth: Int): JsonElement {
        check(depth <= 128) { "MCP frame nesting is too deep" }
        whitespace()
        return when (input.getOrNull(position)) {
            '{' -> {
                position++
                val fields = linkedMapOf<String, JsonElement>()
                whitespace()
                if (!take('}')) {
                    do {
                        whitespace()
                        val key = scalar() as? JsonPrimitive ?: error("Invalid MCP object key")
                        check(key.isString) { "MCP object key must be a string" }
                        check(key.content !in fields) { "Duplicate MCP object key" }
                        whitespace()
                        check(take(':')) { "Invalid MCP object separator" }
                        fields[key.content] = value(depth + 1)
                        whitespace()
                    } while (take(','))
                    check(take('}')) { "Invalid MCP object" }
                }
                JsonObject(fields)
            }
            '[' -> {
                position++
                val values = mutableListOf<JsonElement>()
                whitespace()
                if (!take(']')) {
                    do {
                        values += value(depth + 1)
                        whitespace()
                    } while (take(','))
                    check(take(']')) { "Invalid MCP array" }
                }
                JsonArray(values)
            }
            else -> scalar()
        }
    }

    private fun scalar(): JsonElement {
        val start = position
        if (take('"')) {
            var closed = false
            while (position < input.length) {
                when (input[position++]) {
                    '\\' -> position++
                    '"' -> { closed = true; break }
                }
            }
            check(closed) { "Unterminated MCP JSON string" }
        } else {
            while (position < input.length && input[position] !in ",]}: \t\r\n") position++
        }
        check(position > start) { "Invalid MCP JSON value" }
        val token = input.substring(start, position)
        check(token.startsWith('"') || token in setOf("true", "false", "null") || NUMBER.matches(token)) {
            "Invalid MCP JSON scalar"
        }
        return Json.parseToJsonElement(token).also {
            check(it is JsonPrimitive) { "Invalid MCP JSON scalar" }
        }
    }

    private fun whitespace() {
        while (position < input.length && input[position] in " \t\r\n") position++
    }

    private fun take(char: Char): Boolean = (input.getOrNull(position) == char).also { if (it) position++ }

    companion object {
        const val MAX_BYTES = 16 * 1024 * 1024
        private val NUMBER = Regex("""-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?""")

        fun parse(text: String): JsonElement {
            check(text.length <= MAX_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
                "MCP frame exceeds byte limit"
            }
            val reader = McpJsonFrame(text)
            val result = reader.value(0)
            reader.whitespace()
            check(reader.position == text.length) { "Trailing MCP JSON input" }
            return result
        }
    }
}

/** Bound JSON/error bodies before allocating the decoded frame. SSE events use the same decoder limit. */
internal suspend fun io.ktor.client.statement.HttpResponse.readMcpBody(): String {
    val channel = bodyAsChannel()
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = channel.readAvailable(buffer)
        if (count == -1) break
        check(output.size() + count <= McpJsonFrame.MAX_BYTES) { "MCP body exceeds byte limit" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray().decodeToString(throwOnInvalidSequence = true)
}
