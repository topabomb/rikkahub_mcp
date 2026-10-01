package net.weero.measix.pilot.utils

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserVisibleDiagnosticTest {
    @Test
    fun `native logs preserve full unicode stack and redact before chunk boundaries`() {
        io.mockk.mockkStatic(android.util.Log::class)
        try {
            val entries = mutableListOf<String>()
            io.mockk.every { android.util.Log.e("DiagnosticTest", capture(entries)) } returns 0
            val failure = IOException("文件😀".repeat(1300) + " tail token=private-token", IOException("root cause"))
            failure.addSuppressed(IOException("cleanup failed"))
            val header = "operation sk-abcdefghijklmnop failed"
            logDiagnosticFailure("DiagnosticTest", header, failure)
            org.junit.Assert.assertEquals(redactDiagnosticSecrets("$header\n${failure.stackTraceToString()}"), entries.joinToString(""))
            assertTrue(entries.size > 1)
            entries.forEach {
                assertTrue(it.toByteArray(Charsets.UTF_8).size <= 3000)
                assertFalse(it.first().isLowSurrogate())
                assertFalse(it.last().isHighSurrogate())
                assertFalse(it.contains("private-token"))
                assertFalse(it.contains("sk-abcdefghijklmnop"))
            }
        } finally {
            io.mockk.unmockkStatic(android.util.Log::class)
        }
    }

    @Test
    fun `bare key redaction keeps ordinary paths and resource identifiers`() {
        val paths = "/workspace/task-report-20261001.md disk-abcdefghijk mask-abcdefghijkl"
        val failure = IOException("Read failed: $paths; key=\"sk-abcdefghijklmnop\" (xai-abcdefghijklmnop)")
        val diagnostic = failure.userVisibleDiagnostic()
        assertTrue(diagnostic.contains(paths))
        assertFalse(diagnostic.contains("sk-abcdefghijklmnop"))
        assertFalse(diagnostic.contains("xai-abcdefghijklmnop"))
        val stack = redactDiagnosticSecrets(failure.stackTraceToString())
        assertTrue(stack.contains(paths))
        assertFalse(stack.contains("sk-abcdefghijklmnop"))
        assertFalse(stack.contains("xai-abcdefghijklmnop"))
    }

    @Test
    fun `image payload redaction stops before diagnostic suffixes and stack frames`() {
        val payload = "data:image/png;base64," + "A".repeat(80)
        val suffix = "\nCaused by: IOException: disk full\n\tat net.weero.measix.pilot.Files.read(Files.kt:42)"
        val diagnostic = IOException(payload + suffix).userVisibleDiagnostic()
        assertTrue(diagnostic.contains("IOException: …" + suffix))
        assertFalse(diagnostic.contains("A".repeat(80)))
        val stack = redactDiagnosticSecrets("IOException: $payload" + suffix)
        org.junit.Assert.assertEquals("IOException: …" + suffix, stack)
        assertTrue(redactDiagnosticSecrets("$payload suffix text").endsWith(" suffix text"))
    }

    @Test
    fun `diagnostic preserves long text while hiding bare provider keys and embedded images`() {
        val message = "Retain " + "x".repeat(600) + " tail\nsk-abcdefghijklmnop xai-abcdefghijklmnop\ndata:image/png;base64," + "A".repeat(80)
        val failure = IOException(message)
        val diagnostic = failure.userVisibleDiagnostic()
        assertTrue(diagnostic.startsWith("IOException: Retain " + "x".repeat(600) + " tail\n"))
        assertFalse(diagnostic.contains("sk-abcdefghijklmnop"))
        assertFalse(diagnostic.contains("xai-abcdefghijklmnop"))
        assertFalse(diagnostic.contains("A".repeat(80)))
        val stack = redactDiagnosticSecrets(failure.stackTraceToString())
        assertFalse(stack.contains("sk-abcdefghijklmnop"))
        assertFalse(stack.contains("xai-abcdefghijklmnop"))
        assertFalse(stack.contains("A".repeat(80)))
        assertTrue(stack.contains("UserVisibleDiagnosticTest"))
    }

    @Test
    fun `diagnostic retains nested cleanup causes once and terminates cyclic graphs`() {
        val root = IOException("write denied")
        val cleanup = IOException("delete failed token=secret", IllegalStateException("provider detail"))
        root.addSuppressed(cleanup)
        cleanup.addSuppressed(root)
        root.initCause(cleanup.cause)
        val diagnostic = root.userVisibleDiagnostic()
        assertTrue(diagnostic.contains("Suppressed: IOException: delete failed token=<redacted>"))
        assertFalse(diagnostic.contains("secret"))
        org.junit.Assert.assertEquals(1, Regex("provider detail").findAll(diagnostic).count())
        org.junit.Assert.assertEquals(1, Regex("write denied").findAll(diagnostic).count())
    }

    @Test
    fun `long cause chains keep the actionable root without recursive formatting`() {
        var root: Throwable = IOException("disk full")
        repeat(1000) { root = IllegalStateException("wrapper $it", root) }
        assertTrue(root.userVisibleDiagnostic().contains("IOException: disk full"))
    }

    @Test
    fun `diagnostic retains exception chain and redacts credentials`() {
        val failure = IllegalStateException(
            "request failed\nAuthorization: Bearer private-token",
            IOException("token=private-token connection reset; apiKey=another-secret"),
        )

        val diagnostic = failure.userVisibleDiagnostic()

        assertTrue(diagnostic.contains("IllegalStateException: request failed"))
        assertTrue(diagnostic.contains("IOException: token=<redacted> connection reset"))
        assertFalse("private-token" in diagnostic)
        assertFalse("another-secret" in diagnostic)
    }

    @Test
    fun `diagnostic redacts common header json and query credential forms`() {
        val diagnostic = IllegalArgumentException(
            "Authorization: Basic dXNlcjpwYXNz\n" +
                "Cookie: session=private-cookie\n" +
                "payload={\"apiKey\":\"json-secret\"}&access_token=query-secret",
        ).userVisibleDiagnostic()

        assertFalse(diagnostic.contains("dXNlcjpwYXNz"))
        assertFalse(diagnostic.contains("private-cookie"))
        assertFalse(diagnostic.contains("json-secret"))
        assertFalse(diagnostic.contains("query-secret"))
    }
}
