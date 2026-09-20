package net.weero.measix.pilot.data.enterprise

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class EnrollmentMaterialParserTest {
    private val parser = EnrollmentMaterialParser()
    private fun wire(
        origin: String = "https://platform.example",
        code: String = "opaque-code",
        time: String = "2030-01-01T00:00:00Z",
    ) = """{"formatVersion":1,"kind":"PLATFORM_ENROLLMENT","platformUrl":"$origin","code":"$code","expiresAt":"$time"}"""

    @Test
    fun `canonical platform enrollment is typed normalized and redacted`() {
        val value = parser.parse(wire("https://EXAMPLE.com:443/"))
        assertEquals("https://example.com", value.platformOrigin)
        assertEquals("opaque-code", value.code)
        assertEquals(Instant.parse("2030-01-01T00:00:00Z"), value.expiresAt)
        assertFalse(value.toString().contains(value.code))
    }

    @Test
    fun `unknown kinds fields and duplicate keys are rejected`() {
        assertRejected(
            """{"formatVersion":1,"kind":"OTHER","platformUrl":"https://platform.example","code":"c","expiresAt":"2030-01-01T00:00:00Z"}""",
            "unsupported_enterprise_enrollment_kind",
        )
        assertRejected(wire().dropLast(1) + ",\"userId\":\"u\"}", "invalid_enterprise_enrollment_fields")
        assertRejected(wire().dropLast(1) + ",\"code\":\"other\"}", "duplicate_enterprise_enrollment_field")
    }

    @Test
    fun `origin timestamp identifiers and original bytes are bounded`() {
        mapOf(
            "http://127.0.0.1:8080/" to "http://127.0.0.1:8080",
            "http://[::1]" to "http://[::1]",
            "http://[0:0:0:0:0:0:0:1]" to "http://[::1]",
            "HTTP://CORE.LAN:80/" to "http://core.lan",
            "https://192.168.1.2:8443" to "https://192.168.1.2:8443",
        ).forEach { (input, expected) -> assertEquals(expected, parser.parse(wire(input)).platformOrigin) }
        assertEquals("https://core.example", EnrollmentMaterialParser.normalizeOrigin(" HTTPS://Core.Example:443/ "))
        listOf(
            "ftp://example.com",
            "http://user:secret@example.com",
            "https://example.com/path",
            "https://example.com?",
            "https://example.com#",
            "https://example.com:0",
            "https://example.com:65536",
            "https:///example.com",
            "https://under_score.example",
            "http://[fe80::1%25eth0]",
        ).forEach { assertRejected(wire(it)) }
        listOf(
            "not-a-time",
            "2030-02-30T00:00:00Z",
            "2030-01-01T00:00:00+08:00",
            "2030-01-01T00:00:60Z",
            "2030-01-01T00:00:00.1234567891Z",
        ).forEach { assertRejected(wire(time = it)) }
        assertEquals(
            Instant.parse("2030-01-01T00:00:00.123456789Z"),
            parser.parse(wire(time = "2030-01-01t00:00:00.123456789z")).expiresAt,
        )
        assertRejected(wire(code = ""))
        assertRejected(wire(code = "x".repeat(129)))
        val padded = wire() + " ".repeat(2048 - wire().toByteArray().size)
        assertEquals("opaque-code", parser.parse(padded).code)
        assertRejected(padded + " ", "enterprise_enrollment_too_large")
    }

    private fun assertRejected(raw: String, reason: String? = null) {
        val error = assertThrows(EnterpriseConfigurationException::class.java) { parser.parse(raw) }
        if (reason != null) assertEquals(reason, error.reason)
        assertFalse(error.message.orEmpty().contains("opaque-code"))
    }
}
