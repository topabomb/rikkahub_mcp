package net.weero.measix.pilot.data.enterprise

import java.net.URI
import java.time.Instant
import java.util.Locale
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal sealed interface EnrollmentMaterial {
    val code: String
    val expiresAt: Instant

    data class Platform(val platformOrigin: String, override val code: String, override val expiresAt: Instant) : EnrollmentMaterial {
        override fun toString() = "PlatformEnrollmentMaterial(origin=$platformOrigin)"
    }

    data class LocalExample(
        val sourceNamespace: String,
        val deploymentId: String,
        override val code: String,
        override val expiresAt: Instant,
    ) : EnrollmentMaterial {
        override fun toString() = "LocalExampleEnrollmentMaterial(source=$sourceNamespace, deployment=$deploymentId)"
    }
}

/** Native scan/paste contract. Parsing never registers a source, contacts a server, or establishes a session. */
internal class EnrollmentMaterialParser(private val allowLoopbackHttp: Boolean = false) {
    fun parse(raw: String): EnrollmentMaterial {
        // Bound the original bytes, including whitespace, before any normalization or JSON allocation.
        if (raw.length > MAX_BYTES) fail("enterprise_enrollment_too_large")
        val bytes = raw.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) fail("enterprise_enrollment_too_large")
        if (bytes.toString(Charsets.UTF_8) != raw) fail("invalid_enterprise_enrollment_encoding")
        val fields = FlatObjectReader(raw.trim()).read()
        if (fields["formatVersion"] != Field.Number("1")) {
            if (fields["formatVersion"] is Field.Number) fail("unsupported_enterprise_enrollment_version")
            fail("invalid_enterprise_enrollment")
        }
        val kind = fields.string("kind")
        val expected = when (kind) {
            PLATFORM -> setOf("formatVersion", "kind", "platformUrl", "code", "expiresAt")
            LOCAL -> setOf("formatVersion", "kind", "sourceNamespace", "deploymentId", "code", "expiresAt")
            else -> fail("unsupported_enterprise_enrollment_kind")
        }
        if (fields.keys != expected) fail("invalid_enterprise_enrollment_fields")
        val code = fields.string("code").also(::requireIdentifier)
        val expiresAt = parseUtc(fields.string("expiresAt"))
        return when (kind) {
            PLATFORM -> EnrollmentMaterial.Platform(parseOrigin(fields.string("platformUrl")), code, expiresAt)
            else -> EnrollmentMaterial.LocalExample(
                fields.string("sourceNamespace").also(::requireIdentifier),
                fields.string("deploymentId").also(::requireIdentifier), code, expiresAt,
            )
        }
    }

    private fun parseOrigin(value: String): String {
        if (value.length !in 1..1024) fail("invalid_enterprise_platform_origin")
        val uri = try { URI(value) } catch (_: Exception) { fail("invalid_enterprise_platform_origin") }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT) ?: fail("invalid_enterprise_platform_origin")
        val loopback = host == "localhost" || host == "127.0.0.1" || host == "[::1]"
        if (scheme != "https" && !(allowLoopbackHttp && scheme == "http" && loopback)) fail("invalid_enterprise_platform_origin")
        if (uri.isOpaque || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
            uri.rawPath !in listOf("", "/") || uri.port !in -1..65535 || uri.port == 0 ||
            uri.rawAuthority.endsWith(':')) fail("invalid_enterprise_platform_origin")
        val port = uri.port.takeUnless { it == -1 || (scheme == "https" && it == 443) || (scheme == "http" && it == 80) }
        return "$scheme://$host${port?.let { ":$it" } ?: ""}"
    }

    private fun parseUtc(value: String): Instant {
        if (!UTC_TIMESTAMP.matches(value)) fail("invalid_enterprise_enrollment_time")
        val canonical = value.uppercase(Locale.ROOT).removeSuffix("+00:00").let {
            if (it.endsWith('Z')) it else "${it}Z"
        }
        return try { Instant.parse(canonical) } catch (_: Exception) { fail("invalid_enterprise_enrollment_time") }
    }

    private sealed interface Field {
        data class Text(val value: String) : Field
        data class Number(val token: String) : Field
    }

    /** A flat object is the complete grammar of this envelope; duplicate decoded keys are never collapsed. */
    private class FlatObjectReader(private val input: String) {
        private var position = 0

        fun read(): Map<String, Field> {
            val fields = linkedMapOf<String, Field>()
            expect('{')
            skipWhitespace()
            if (!take('}')) {
                while (true) {
                    val key = string()
                    if (key in fields) fail("duplicate_enterprise_enrollment_field")
                    expect(':')
                    skipWhitespace()
                    val value = if (input.getOrNull(position) == '"') Field.Text(string()) else {
                        val start = position
                        while (input.getOrNull(position)?.let { it in '0'..'9' || it == '-' } == true) position++
                        val token = input.substring(start, position)
                        if (!INTEGER.matches(token)) fail("invalid_enterprise_enrollment")
                        Field.Number(token)
                    }
                    fields[key] = value
                    skipWhitespace()
                    if (take('}')) break
                    expect(',')
                }
            }
            skipWhitespace()
            if (position != input.length) fail("invalid_enterprise_enrollment")
            return fields
        }

        private fun string(): String {
            skipWhitespace()
            val start = position
            expect('"')
            while (position < input.length) {
                val character = input[position++]
                if (character.code < 32) fail("invalid_enterprise_enrollment")
                if (character == '\\') {
                    if (position >= input.length || input[position].code < 32) fail("invalid_enterprise_enrollment")
                    position++
                } else if (character == '"') {
                    val value = try { Json.decodeFromString<String>(input.substring(start, position)) }
                        catch (_: IllegalArgumentException) { fail("invalid_enterprise_enrollment") }
                    if (value.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) != value) fail("invalid_enterprise_enrollment_encoding")
                    return value
                }
            }
            fail("invalid_enterprise_enrollment")
        }

        private fun expect(character: Char) {
            skipWhitespace()
            if (!take(character)) fail("invalid_enterprise_enrollment")
        }

        private fun take(character: Char): Boolean = (input.getOrNull(position) == character).also { if (it) position++ }
        private fun skipWhitespace() { while (input.getOrNull(position) in listOf(' ', '\t', '\r', '\n')) position++ }
    }

    companion object {
        const val MAX_BYTES = 2048
        const val PLATFORM = "PLATFORM_ENROLLMENT"
        const val LOCAL = "LOCAL_EXAMPLE_ENROLLMENT"
        private val INTEGER = Regex("-?(0|[1-9][0-9]*)")
        private val UTC_TIMESTAMP = Regex("\\d{4}-\\d{2}-\\d{2}[Tt](?:[01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d(?:\\.\\d{1,9})?(?:[Zz]|\\+00:00)")

        fun encodeLocal(material: EnrollmentMaterial.LocalExample): String = Json.encodeToString(buildJsonObject {
            put("formatVersion", 1)
            put("kind", LOCAL)
            put("sourceNamespace", material.sourceNamespace)
            put("deploymentId", material.deploymentId)
            put("code", material.code)
            put("expiresAt", material.expiresAt.toString())
        })

        private fun Map<String, Field>.string(name: String): String =
            (this[name] as? Field.Text)?.value ?: fail("invalid_enterprise_enrollment")

        private fun requireIdentifier(value: String) {
            if (value.codePointCount(0, value.length) !in 1..128) fail("invalid_enterprise_enrollment_identifier")
        }

        private fun fail(reason: String): Nothing = throw EnterpriseConfigurationException(reason)
    }
}
