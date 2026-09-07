package net.weero.measix.pilot.data.enterprise

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class EnrollmentMaterialParserTest {
    private val parser = EnrollmentMaterialParser()
    private val local = EnrollmentMaterial.LocalExample("local:example", "dep_example", "opaque-code", Instant.parse("2030-01-01T00:00:00Z"))
    private val text get() = EnrollmentMaterialParser.encodeLocal(local)

    @Test
    fun `shared artifacts match the pinned upstream digests and local sample stays input only`() {
        fun bytes(name: String) = requireNotNull(javaClass.getResourceAsStream("/contracts/enrollment/$name")).use { it.readBytes() }
        val manifest = kotlinx.serialization.json.Json.parseToJsonElement(bytes("consumer-manifest.json").toString(Charsets.UTF_8))
            as kotlinx.serialization.json.JsonObject
        val artifacts = manifest.getValue("artifacts") as kotlinx.serialization.json.JsonObject
        assertEquals(setOf("platform-v1.json", "local-v1.json", "cases.json"), artifacts.keys)
        artifacts.forEach { (name, metadata) ->
            val expected = ((metadata as kotlinx.serialization.json.JsonObject).getValue("sha256") as kotlinx.serialization.json.JsonPrimitive).content
            val actual = java.security.MessageDigest.getInstance("SHA-256").digest(bytes(name)).joinToString("") { "%02x".format(it) }
            assertEquals(name, expected, actual)
        }
        val sample = parser.parse(bytes("local-v1.json").toString(Charsets.UTF_8)) as EnrollmentMaterial.LocalExample
        assertEquals("local.example", sample.sourceNamespace)
        assertEquals("dep_example", sample.deploymentId)
        assertFalse(sample.toString().contains(sample.code))
    }

    @Test
    fun `canonical platform fixture is consumed as a distinct typed origin`() {
        val raw = requireNotNull(javaClass.getResourceAsStream("/contracts/enrollment/platform-v1.json")).bufferedReader().use { it.readText() }
        val value = parser.parse(raw) as EnrollmentMaterial.Platform
        assertEquals("https://platform.example", value.platformOrigin)
        assertEquals("fixture-only-not-a-live-enrollment", value.code)
        assertEquals(Instant.parse("2030-01-01T00:00:00Z"), value.expiresAt)
        assertFalse(value.toString().contains(value.code))
    }

    @Test
    fun `local wire has no user identity and whitespace is removed only around the original document`() {
        assertEquals(local, parser.parse(" \t\r\n$text\n "))
        assertFalse(text.contains("userId"))
        assertFalse(text.contains("enrollmentCode"))
        assertFalse(text.contains("expiresAtMillis"))
        assertFalse(local.toString().contains(local.code))
        val spaces = local.copy(code = " a b ")
        assertEquals(spaces, parser.parse(EnrollmentMaterialParser.encodeLocal(spaces)))
    }

    @Test
    fun `original UTF8 byte limit includes Chinese supplementary characters and surrounding whitespace`() {
        val unicode = local.copy(sourceNamespace = "中".repeat(128), deploymentId = "😀".repeat(128), code = "密".repeat(128))
        val raw = EnrollmentMaterialParser.encodeLocal(unicode)
        val padded = raw + " ".repeat(2048 - raw.toByteArray(Charsets.UTF_8).size)
        assertTrue(padded.length < 2048)
        assertEquals(unicode, parser.parse(padded))
        assertRejected(padded + " ", "enterprise_enrollment_too_large")
        assertRejected(padded + "\u3000", "enterprise_enrollment_too_large")
        assertRejected(" ".repeat(2048) + text, "enterprise_enrollment_too_large")
    }

    @Test
    fun `duplicate keys including escaped names are rejected before map construction`() {
        assertRejected(text.dropLast(1) + ",\"code\":\"other\"}", "duplicate_enterprise_enrollment_field")
        assertRejected(text.dropLast(1) + ",\"\\u0063ode\":\"other\"}", "duplicate_enterprise_enrollment_field")
        assertRejected(text.dropLast(1) + ",\"kind\":\"PLATFORM_ENROLLMENT\"}", "duplicate_enterprise_enrollment_field")
    }

    @Test
    fun `unknown mixed legacy missing null and mistyped fields cannot enter enrollment`() {
        listOf(
            text.dropLast(1) + ",\"userId\":\"alice\"}",
            text.dropLast(1) + ",\"platformUrl\":\"https://example.com\"}",
            text.dropLast(1) + ",\"configuration\":{}}",
            text.replace("\"code\":\"opaque-code\",", ""),
            text.replace("\"code\":\"opaque-code\"", "\"code\":null"),
            text.replace("\"code\":\"opaque-code\"", "\"code\":12"),
            text.replace("\"formatVersion\":1", "\"formatVersion\":\"1\""),
            text.replace("\"formatVersion\":1", "\"formatVersion\":1.0"),
            text.replace("\"formatVersion\":1", "\"formatVersion\":1e0"),
            text.replace("\"formatVersion\":1", "\"formatVersion\":01"),
            text.replace("\"expiresAt\":\"2030-01-01T00:00:00Z\"", "\"expiresAt\":1234"),
            "{\"formatVersion\":1,\"sourceNamespace\":\"local:example\",\"deploymentId\":\"dep_example\",\"userId\":\"alice\",\"enrollmentCode\":\"old\",\"expiresAtMillis\":1234}",
            text.dropLast(1) + ",}", "[$text]", text + "{}",
            text.replace("opaque-code", "raw\nline"),
            text.replace("opaque-code", "\\ud800"),
        ).forEach { assertRejected(it) }
        assertRejected(text.replace("\"formatVersion\":1", "\"formatVersion\":2"), "unsupported_enterprise_enrollment_version")
        assertRejected(text.replace("LOCAL_EXAMPLE_ENROLLMENT", "UNKNOWN"), "unsupported_enterprise_enrollment_kind")
    }

    @Test
    fun `each opaque local identifier is bounded by Unicode characters`() {
        listOf("sourceNamespace", "deploymentId", "code").forEach { field ->
            fun wire(value: String) = EnrollmentMaterialParser.encodeLocal(when (field) {
                "sourceNamespace" -> local.copy(sourceNamespace = value)
                "deploymentId" -> local.copy(deploymentId = value)
                else -> local.copy(code = value)
            })
            parser.parse(wire("😀".repeat(128)))
            assertRejected(wire(""))
            assertRejected(wire("x".repeat(129)))
            assertRejected(wire("😀".repeat(129)))
        }
    }

    @Test
    fun `expiry must be a real RFC3339 UTC timestamp`() {
        fun wire(time: String) = text.replace("2030-01-01T00:00:00Z", time)
        listOf("not-a-time", "2030-02-30T00:00:00Z", "2030-01-01", "2030-01-01T24:00:00Z",
            "2030-01-01T00:00:00+08:00", "2030-01-01T00:00:00-00:00", "2030-01-01 00:00:00Z",
            "2030-01-01T00:00:60Z", "2030-01-01T00:00:00.1234567891Z").forEach { assertRejected(wire(it)) }
        assertEquals(local.expiresAt, parser.parse(wire("2030-01-01t00:00:00+00:00")).expiresAt)
        val nanoseconds = parser.parse(wire("2030-01-01t00:00:00.123456789z")) as EnrollmentMaterial.LocalExample
        assertEquals(Instant.parse("2030-01-01T00:00:00.123456789Z"), nanoseconds.expiresAt)
        assertTrue(EnrollmentMaterialParser.encodeLocal(nanoseconds).contains("2030-01-01T00:00:00.123456789Z"))
    }

    @Test
    fun `platform accepts only HTTPS origins except explicitly enabled loopback development`() {
        fun wire(url: String) = """{"formatVersion":1,"kind":"PLATFORM_ENROLLMENT","platformUrl":"$url","code":"c","expiresAt":"2030-01-01T00:00:00Z"}"""
        assertEquals("https://example.com", (parser.parse(wire("https://EXAMPLE.com:443/")) as EnrollmentMaterial.Platform).platformOrigin)
        assertEquals("https://example.com:8443", (parser.parse(wire("https://example.com:8443")) as EnrollmentMaterial.Platform).platformOrigin)
        listOf("http://example.com", "http://127.0.0.1", "http://192.168.1.2", "https://user:secret@example.com",
            "https://example.com/path", "https://example.com/%2f", "https://example.com?", "https://example.com#",
            "https://example.com:0", "https://example.com:65536", "https://example.com:", "//example.com", "https:///example.com").forEach { assertRejected(wire(it)) }
        val development = EnrollmentMaterialParser(allowLoopbackHttp = true)
        assertEquals("http://127.0.0.1:8080", (development.parse(wire("http://127.0.0.1:8080/")) as EnrollmentMaterial.Platform).platformOrigin)
        assertEquals("http://[::1]", (development.parse(wire("http://[::1]")) as EnrollmentMaterial.Platform).platformOrigin)
        assertThrows(EnterpriseConfigurationException::class.java) { development.parse(wire("http://192.168.1.2")) }
        assertRejected(wire("https://" + "a".repeat(1024)))
    }

    private fun assertRejected(raw: String, reason: String? = null) {
        val error = assertThrows(EnterpriseConfigurationException::class.java) { parser.parse(raw) }
        if (reason != null) assertEquals(reason, error.reason)
        assertNull(error.cause)
        assertFalse(error.message.orEmpty().contains("opaque-code"))
    }
}
