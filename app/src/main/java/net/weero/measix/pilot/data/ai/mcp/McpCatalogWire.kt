package net.weero.measix.pilot.data.ai.mcp

import net.weero.measix.pilot.utils.StrictJsonValue

import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readUTF8Line
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

internal const val MAX_MCP_FRAME_BYTES = 16 * 1024 * 1024

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
        val envelope = StrictJsonValue.parse(text, MAX_MCP_FRAME_BYTES) as? JsonObject ?: error("MCP frame must be an object")
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

/** Bound JSON/error bodies before allocating the decoded frame. SSE events use the same decoder limit. */
internal suspend fun io.ktor.client.statement.HttpResponse.readMcpBody(): String {
    val channel = bodyAsChannel()
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = channel.readAvailable(buffer)
        if (count == -1) break
        check(output.size() + count <= MAX_MCP_FRAME_BYTES) { "MCP body exceeds byte limit" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray().decodeToString(throwOnInvalidSequence = true)
}


internal data class McpSseEvent(val data: String, val name: String?, val id: String?, val retryMillis: Long?)

/** Reads within the caller-owned streaming response; false ends that response immediately. */
internal suspend fun io.ktor.client.statement.HttpResponse.readMcpSseEvents(
    maxBytes: Int = MAX_MCP_FRAME_BYTES,
    consume: suspend (McpSseEvent) -> Boolean,
) {
    val channel = bodyAsChannel()
    val data = StringBuilder()
    var id: String? = null
    var name: String? = null
    var retry: Long? = null
    var frameBytes = 0
    var first = true
    while (true) {
        val line = try {
            channel.readUTF8Line(maxBytes)?.let { if (first) it.removePrefix("\uFEFF") else it }
        } catch (_: io.ktor.utils.io.charsets.TooLongLineException) {
            throw io.modelcontextprotocol.kotlin.sdk.shared.TooLongFrameException(maxBytes.toLong() + 1, maxBytes)
        } ?: return
        first = false
        if (line.isEmpty()) {
            if (!consume(McpSseEvent(data.toString().removeSuffix("\n"), name, id, retry))) return
            data.clear(); id = null; name = null; retry = null; frameBytes = 0
            continue
        }
        frameBytes += line.toByteArray(Charsets.UTF_8).size + 1
        if (frameBytes > maxBytes) throw io.modelcontextprotocol.kotlin.sdk.shared.TooLongFrameException(frameBytes.toLong(), maxBytes)
        val separator = line.indexOf(':')
        val field = if (separator < 0) line else line.substring(0, separator)
        val value = if (separator < 0) "" else line.substring(separator + 1).removePrefix(" ")
        when (field) {
            "data" -> data.append(value).append('\n')
            "event" -> name = value
            "id" -> if ('\u0000' !in value) id = value
            "retry" -> if (value.isNotEmpty() && value.all { it in '0'..'9' }) retry = value.toLongOrNull()
        }
    }
}
