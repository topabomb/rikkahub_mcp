package net.weero.measix.pilot.utils

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserVisibleDiagnosticTest {
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
