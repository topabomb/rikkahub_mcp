package net.weero.measix.pilot.ui.pages.extensions.skills

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NetworkSkillGitHubSourceTest {
    @Test fun `only explicit HTTP rate limit signals classify as rate limited`() = runTest {
        for ((status, remaining, retry, detail, limited) in listOf(
            HttpCase(429, null, null, "quota", true),
            HttpCase(403, "0", null, "denied", true),
            HttpCase(403, null, "12", "secondary", true),
            HttpCase(403, null, null, "API rate limit exceeded", true),
            HttpCase(403, "10", null, "permission denied", false),
            HttpCase(404, null, null, "not found", false),
        )) {
            val connection = mockk<HttpURLConnection>(relaxed = true)
            every { connection.responseCode } returns status
            every { connection.getHeaderField("X-RateLimit-Remaining") } returns remaining
            every { connection.getHeaderField("Retry-After") } returns retry
            every { connection.errorStream } returns ByteArrayInputStream(detail.toByteArray())
            val source = NetworkSkillGitHubSource { connection }
            val error = runCatching { source.downloadBytes("https://example.invalid/data") }.exceptionOrNull() as SkillHttpException
            assertEquals(status, error.status)
            assertEquals(limited, error.rateLimited)
            assertTrue(error.message!!.contains(detail))
            verify(exactly = 1) { connection.disconnect() }
        }
    }

    @Test fun `transport exception is original and subsequent retry preserves binary bytes`() = runTest {
        val failure = IOException("socket detail", IllegalStateException("root cause"))
        val connection = mockk<HttpURLConnection>(relaxed = true)
        every { connection.responseCode } throws failure andThen 200
        val bytes = byteArrayOf(0, -1, -61, 40)
        every { connection.inputStream } returns ByteArrayInputStream(bytes)
        val source = NetworkSkillGitHubSource { connection }
        val caught = runCatching { source.downloadBytes("https://example.invalid/data") }.exceptionOrNull()!!
        assertTrue(caught is IOException)
        assertEquals("socket detail", caught.message)
        assertTrue(generateSequence(caught) { it.cause }.any { it === failure })
        assertArrayEquals(bytes, source.downloadBytes("https://example.invalid/data"))
        verify(exactly = 2) { connection.disconnect() }
    }

    @Test fun `cancellation is not translated into HTTP failure`() = runTest {
        val connection = mockk<HttpURLConnection>(relaxed = true)
        every { connection.responseCode } throws CancellationException("cancel request")
        val source = NetworkSkillGitHubSource { connection }
        assertTrue(runCatching { source.downloadBytes("https://example.invalid/data") }.exceptionOrNull() is CancellationException)
        verify { connection.disconnect() }
    }

    @Test fun `HTTP error body read failure preserves status cause and rate limit through retry`() = runTest {
        val failure = IOException("response detail read failed", IllegalStateException("stream cause"))
        var closed = false
        val body = object : InputStream() {
            override fun read(): Int = throw failure
            override fun close() { closed = true }
        }
        val connection = mockk<HttpURLConnection>(relaxed = true)
        every { connection.responseCode } returns 429 andThen 200
        every { connection.errorStream } returns body
        val bytes = byteArrayOf(0, -1, 42)
        every { connection.inputStream } returns ByteArrayInputStream(bytes)
        val source = NetworkSkillGitHubSource { connection }
        val caught = runCatching { source.downloadBytes("https://example.invalid/data") }.exceptionOrNull()
        val http = generateSequence(caught) { it.cause }.filterIsInstance<SkillHttpException>().first()
        assertEquals(429, http.status)
        assertTrue(http.rateLimited)
        assertTrue(generateSequence<Throwable>(http) { it.cause }.any { it === failure })
        assertTrue(http.message!!.contains("429"))
        assertTrue(closed)
        assertArrayEquals(bytes, source.downloadBytes("https://example.invalid/data"))
        verify(exactly = 2) { connection.disconnect() }
    }
    private data class HttpCase(val status: Int, val remaining: String?, val retry: String?, val detail: String, val limited: Boolean)
}
