package net.weero.measix.pilot.service.portal

import java.math.BigDecimal
import java.net.URI
import kotlinx.serialization.json.*

internal class PortalFailure(val code: String) : IllegalArgumentException(code)

internal sealed interface PortalCommand {
    data object GetStatus : PortalCommand
    data object Refresh : PortalCommand
    data object Close : PortalCommand
    data object Logout : PortalCommand
    data class OpenExternal(val url: URI) : PortalCommand
    data object CapturePhoto : PortalCommand
    data class RecordAudio(val maxDurationSeconds: Int) : PortalCommand
    data class ReadMedia(val mediaId: String, val offset: Int, val maxBytes: Int) : PortalCommand
    data class ReleaseMedia(val mediaId: String) : PortalCommand
    data class Cancel(val targetRequestId: String) : PortalCommand
}

internal data class PortalRequest(val documentId: String, val requestId: String, val command: PortalCommand)

/** The wire boundary accepts only the object/primitive grammar used by Bridge v3 requests. */
internal object PortalProtocol {
    const val VERSION = 3
    const val MAX_REQUEST_BYTES = 65536
    private val integer = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

    fun decode(raw: String, maxBytes: Int = MAX_REQUEST_BYTES): JsonObject {
        if (raw.length > maxBytes || raw.toByteArray(Charsets.UTF_8).size > maxBytes) fail("resource_limit")
        if (raw.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) != raw) fail()
        return Reader(raw).read()
    }

    /** Identifiers only correlate an already origin/frame-authorized message; they never authorize it. */
    fun identifier(fields: JsonObject, name: String): String = fields.text(name, 128)

    fun request(fields: JsonObject): PortalRequest {
        fields.keys(setOf("bridgeVersion", "documentId", "requestId", "method", "params"))
        val documentId = identifier(fields, "documentId")
        val requestId = identifier(fields, "requestId")
        if (fields.number("bridgeVersion", Long.MIN_VALUE, Long.MAX_VALUE) != VERSION.toLong()) fail("unsupported_version")
        val params = fields["params"] as? JsonObject ?: fail()
        val command = when (fields.text("method")) {
            "getStatus" -> params.empty(PortalCommand.GetStatus)
            "refresh" -> params.empty(PortalCommand.Refresh)
            "close" -> params.empty(PortalCommand.Close)
            "logout" -> params.empty(PortalCommand.Logout)
            "capturePhoto" -> params.empty(PortalCommand.CapturePhoto)
            "openExternal" -> {
                params.keys(setOf("url"))
                val uri = try { URI(params.text("url")) } catch (_: Exception) { fail() }
                if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrEmpty() || uri.rawUserInfo != null || uri.port == 0 ||
                    uri.port > 65535 || uri.rawAuthority.endsWith(':')) fail()
                PortalCommand.OpenExternal(uri)
            }
            "recordAudio" -> {
                params.keys(setOf("maxDurationSeconds"))
                PortalCommand.RecordAudio(params.number("maxDurationSeconds", 1, 60).toInt())
            }
            "readMedia" -> {
                params.keys(setOf("mediaId", "offset", "maxBytes"))
                PortalCommand.ReadMedia(params.text("mediaId", 128), params.number("offset", 0, 10485760).toInt(),
                    params.number("maxBytes", 1, 65536).toInt())
            }
            "releaseMedia" -> {
                params.keys(setOf("mediaId"))
                PortalCommand.ReleaseMedia(params.text("mediaId", 128))
            }
            "cancel" -> {
                params.keys(setOf("targetRequestId"))
                PortalCommand.Cancel(params.text("targetRequestId", 128))
            }
            else -> fail("unsupported_method")
        }
        return PortalRequest(documentId, requestId, command)
    }

    fun bootstrap(documentId: String): String = buildJsonObject {
        put("bridgeVersion", VERSION)
        put("documentId", documentId)
    }.toString()

    fun success(documentId: String, requestId: String, result: JsonObject): String = envelope(documentId, requestId) {
        put("result", result)
    }

    fun error(documentId: String, requestId: String, failure: PortalFailure): String = envelope(documentId, requestId) {
        putJsonObject("error") {
            put("code", failure.code)
            // Internal exception text, paths and credentials must never enter the public response.
            put("message", failure.code)
        }
    }

    private fun envelope(documentId: String, requestId: String, body: JsonObjectBuilder.() -> Unit): String = buildJsonObject {
        put("bridgeVersion", VERSION)
        put("documentId", documentId)
        put("requestId", requestId)
        body()
    }.toString()

    private fun JsonObject.keys(expected: Set<String>) { if (keys != expected) fail() }
    private fun <T> JsonObject.empty(value: T): T { keys(emptySet()); return value }
    private fun JsonObject.text(name: String, max: Int = 65536): String {
        val value = this[name] as? JsonPrimitive ?: fail()
        if (!value.isString || value.content.codePointCount(0, value.content.length) !in 1..max) fail()
        return value.content
    }
    private fun JsonObject.number(name: String, min: Long, max: Long): Long {
        val value = this[name] as? JsonPrimitive ?: fail()
        if (value.isString || !integer.matches(value.content)) fail()
        val number = try { BigDecimal(value.content).longValueExact() } catch (_: ArithmeticException) { fail() }
            catch (_: NumberFormatException) { fail() }
        if (number !in min..max) fail()
        return number
    }
    private fun fail(code: String = "invalid_request"): Nothing = throw PortalFailure(code)

    private class Reader(private val input: String) {
        private var position = 0
        fun read(): JsonObject = objectValue(0).also { whitespace(); if (position != input.length) fail() }
        private fun objectValue(depth: Int): JsonObject {
            if (depth > 1) fail()
            expect('{')
            val values = linkedMapOf<String, JsonElement>()
            whitespace()
            if (!take('}')) while (true) {
                val name = string()
                if (name in values) fail()
                expect(':'); whitespace()
                values[name] = when (input.getOrNull(position)) {
                    '{' -> objectValue(depth + 1)
                    '"' -> JsonPrimitive(string())
                    else -> {
                        val start = position
                        while (input.getOrNull(position)?.let { it !in " \t\r\n,}" } == true) position++
                        val token = input.substring(start, position)
                        if (token !in setOf("null", "true", "false") && !integer.matches(token)) fail()
                        try { Json.parseToJsonElement(token) } catch (_: IllegalArgumentException) { fail() }
                    }
                }
                whitespace()
                if (take('}')) break
                expect(',')
            }
            return JsonObject(values)
        }
        private fun string(): String {
            whitespace()
            val start = position
            expect('"')
            while (position < input.length) {
                val char = input[position++]
                if (char.code < 32) fail()
                if (char == '\\') {
                    if (position == input.length || input[position].code < 32) fail()
                    position++
                } else if (char == '"') {
                    val value = try { Json.decodeFromString<String>(input.substring(start, position)) }
                        catch (_: IllegalArgumentException) { fail() }
                    if (value.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) != value) fail()
                    return value
                }
            }
            fail()
        }
        private fun expect(char: Char) { whitespace(); if (!take(char)) fail() }
        private fun take(char: Char): Boolean = (input.getOrNull(position) == char).also { if (it) position++ }
        private fun whitespace() { while (input.getOrNull(position) in listOf(' ', '\t', '\r', '\n')) position++ }
    }
}
