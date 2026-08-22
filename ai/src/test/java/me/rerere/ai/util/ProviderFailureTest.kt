package me.rerere.ai.util

import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderFailureTest {
    @Test
    fun `OpenAI moderation_blocked is content_blocked without leaking check types`() {
        val error = formatProviderHttpError(
            400,
            """{"error":{"message":"Your request was rejected as a result of our safety system. Failed check: SAFETY_CHECK_TYPE_CSAM","type":"image_generation_user_error","param":null,"code":"moderation_blocked"}}""",
        )
        val classified = classifyProviderFailure(error)
        assertEquals(ProviderFailureKind.CONTENT_BLOCKED, classified.kind)
        assertEquals(CONTENT_BLOCKED_MODEL_DETAIL, classified.detail)
        assertFalse(classified.detail.contains("CSAM"))
        assertFalse(classified.detail.contains("moderation_blocked"))
    }

    @Test
    fun `OpenAI content_policy_violation is content_blocked`() {
        val error = formatProviderHttpError(
            400,
            """{"error":{"message":"Your input image may contain content that is not allowed by our safety system.","type":"invalid_request_error","code":"content_policy_violation"}}""",
        )
        assertEquals(ProviderFailureKind.CONTENT_BLOCKED, classifyProviderFailure(error).kind)
    }

    @Test
    fun `rate_limit_exceeded is rate_limited and keeps retry hint`() {
        val error = formatProviderHttpError(
            429,
            """{"error":{"message":"You've exceeded the rate limit, please slow down and try again after 2 seconds.","type":"invalid_request_error","code":"rate_limit_exceeded"}}""",
        )
        val classified = classifyProviderFailure(error)
        assertEquals(ProviderFailureKind.RATE_LIMITED, classified.kind)
        assertTrue(classified.detail.contains("2 seconds"))
    }

    @Test
    fun `quota codes are not treated as rate limits`() {
        val error = formatProviderHttpError(
            429,
            """{"error":{"message":"You exceeded your current quota, please check your plan and billing details.","type":"insufficient_quota","code":"credit_balance_exhausted"}}""",
        )
        val classified = classifyProviderFailure(error)
        assertEquals(ProviderFailureKind.QUOTA_EXHAUSTED, classified.kind)
        assertTrue(classified.detail.contains("quota") || classified.detail.contains("billing"))
    }

    @Test
    fun `401 redacts api keys and stays auth_failed`() {
        val error = formatProviderHttpError(
            401,
            """{"error":{"message":"Incorrect API key provided: sk-abcdefghijklmnopqrstuvwxyz","type":"invalid_request_error","code":"invalid_api_key"}}""",
        )
        val classified = classifyProviderFailure(error)
        assertEquals(ProviderFailureKind.AUTH_FAILED, classified.kind)
        assertFalse(classified.detail.contains("sk-abcdefghijklmnopqrstuvwxyz"))
    }

    @Test
    fun `403 is permission_denied`() {
        val error = formatProviderHttpError(
            403,
            """{"error":{"message":"Your API key or team doesn't have permission to perform the action.","code":"forbidden"}}""",
        )
        assertEquals(ProviderFailureKind.PERMISSION_DENIED, classifyProviderFailure(error).kind)
    }

    @Test
    fun `400 invalid size is invalid_request and keeps the provider message`() {
        val error = formatProviderHttpError(
            400,
            """{"error":{"message":"Invalid value: '99x99'. 'size' must be one of auto, 1024x1024","type":"invalid_request_error"}}""",
        )
        val classified = classifyProviderFailure(error)
        assertEquals(ProviderFailureKind.INVALID_REQUEST, classified.kind)
        assertTrue(classified.detail.contains("1024x1024"))
    }

    @Test
    fun `503 overloaded is provider_unavailable`() {
        val error = formatProviderHttpError(
            503,
            """{"error":{"message":"The engine is currently overloaded, please try again later"}}""",
        )
        assertEquals(ProviderFailureKind.PROVIDER_UNAVAILABLE, classifyProviderFailure(error).kind)
    }

    @Test
    fun `timeout is provider_unavailable`() {
        val classified = classifyProviderFailure(SocketTimeoutException("timeout"))
        assertEquals(ProviderFailureKind.PROVIDER_UNAVAILABLE, classified.kind)
    }

    @Test
    fun `unknown local exception is runtime_error`() {
        val classified = classifyProviderFailure(IllegalStateException("boom"))
        assertEquals(ProviderFailureKind.RUNTIME_ERROR, classified.kind)
        assertEquals("boom", classified.detail)
    }

    @Test
    fun `local missing image file is runtime_error not invalid_request`() {
        val classified = classifyProviderFailure(
            IllegalArgumentException("Image file does not exist: /tmp/ref.png"),
        )
        assertEquals(ProviderFailureKind.RUNTIME_ERROR, classified.kind)
        assertTrue(classified.detail.contains("does not exist"))
    }

    @Test
    fun `local master conversation missing is runtime_error`() {
        val classified = classifyProviderFailure(
            IllegalStateException("Master Conversation abc does not exist"),
        )
        assertEquals(ProviderFailureKind.RUNTIME_ERROR, classified.kind)
    }

    @Test
    fun `queue text containing 429 is not a rate limit`() {
        val classified = classifyProviderFailure(IllegalStateException("There are 429 items in the queue"))
        assertEquals(ProviderFailureKind.RUNTIME_ERROR, classified.kind)
    }

    @Test
    fun `embedded leftover image error json is classified`() {
        val leftover = IllegalStateException(
            """Failed to generate image: 400 {"error":{"message":"Your request was rejected as a result of our safety system.","code":"moderation_blocked"}}""",
        )
        assertEquals(ProviderFailureKind.CONTENT_BLOCKED, classifyProviderFailure(leftover).kind)
    }

    @Test
    fun `html bodies do not leak markup into detail`() {
        val error = formatProviderHttpError(502, "<html><body>Bad Gateway</body></html>")
        val classified = classifyProviderFailure(error)
        assertEquals(ProviderFailureKind.PROVIDER_UNAVAILABLE, classified.kind)
        assertEquals("The provider is temporarily unavailable. Retry later.", classified.detail)
        assertFalse(classified.detail.contains("<html>"))
        assertFalse(classified.detail.contains("HttpException"))
    }

    @Test
    fun `empty http bodies use canned detail not exception names`() {
        val error = formatProviderHttpError(400, "")
        val classified = classifyProviderFailure(error)
        assertEquals(ProviderFailureKind.INVALID_REQUEST, classified.kind)
        assertEquals("The provider rejected the request parameters.", classified.detail)
        assertFalse(classified.detail.contains("HttpException"))
    }

    @Test
    fun `long provider messages are clipped`() {
        val huge = "Please shorten the prompt. " + "x".repeat(2_000)
        val error = formatProviderHttpError(400, """{"error":{"message":"$huge","type":"invalid_request_error"}}""")
        val classified = classifyProviderFailure(error)
        assertEquals(ProviderFailureKind.INVALID_REQUEST, classified.kind)
        assertTrue(classified.detail.length <= PROVIDER_FAILURE_DETAIL_MAX_CHARS)
        assertTrue(classified.detail.endsWith("…"))
        assertTrue(classified.detail.startsWith("Please shorten the prompt."))
    }
}
