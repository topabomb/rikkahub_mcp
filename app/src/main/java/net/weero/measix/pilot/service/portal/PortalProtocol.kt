package net.weero.measix.pilot.service.portal

import java.math.BigDecimal
import java.net.URI
import java.time.LocalDate
import kotlinx.serialization.json.*
import net.weero.measix.pilot.data.enterprise.EnterpriseFeedQuery

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
    data object GetLocalContext : PortalCommand
    data class ListLocalUpdates(val query: EnterpriseFeedQuery, val ifNoneMatch: String?) : PortalCommand
    data class GetLocalUpdate(val id: String) : PortalCommand
}

internal data class PortalRequest(val documentId: String, val requestId: String, val command: PortalCommand)

/** The wire boundary accepts only the object/primitive grammar used by Bridge v3 requests. */
internal object PortalProtocol {
    const val VERSION = 3
    const val LOCAL_READ_VERSION = 2
    const val LOCAL_ORIGIN = "https://local.measix.invalid"
    const val LOCAL_ENTRY = "$LOCAL_ORIGIN/portal/"
    private val integer = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
    private val date = Regex("\\d{4}-\\d{2}-\\d{2}")
    private val updateId = Regex("eup_[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

    fun decode(raw: String): JsonObject {
        if (raw.length > 65536 || raw.toByteArray(Charsets.UTF_8).size > 65536) fail("resource_limit")
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
            "getLocalContext" -> params.empty(PortalCommand.GetLocalContext)
            "openExternal" -> {
                params.keys(setOf("url"))
                val uri = try { URI(params.text("url")) } catch (_: Exception) { fail() }
                if (uri.scheme != "https" || uri.host.isNullOrEmpty() || uri.rawUserInfo != null || uri.port == 0 ||
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
            "listLocalUpdates" -> {
                if (params.keys.any { it !in setOf("startDate", "endDate", "limit", "ifNoneMatch") }) fail()
                val start = params.optionalDate("startDate")
                val end = params.optionalDate("endDate")
                if (start != null && end != null && start > end) fail()
                PortalCommand.ListLocalUpdates(EnterpriseFeedQuery(start, end,
                    if ("limit" in params) params.number("limit", 1, 20).toInt() else 10),
                    if ("ifNoneMatch" in params) params.text("ifNoneMatch", 256) else null)
            }
            "getLocalUpdate" -> {
                params.keys(setOf("enterpriseUpdateId"))
                PortalCommand.GetLocalUpdate(params.text("enterpriseUpdateId").also { if (!updateId.matches(it)) fail() })
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

    private fun JsonObject.optionalDate(name: String): String? = if (name !in this) null else text(name).also {
        if (!date.matches(it)) fail()
        try { LocalDate.parse(it) } catch (_: Exception) { fail() }
    }
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
