package me.rerere.common.http

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.internal.closeQuietly
import okio.IOException
import kotlin.coroutines.resumeWithException

suspend fun Call.await(): Response {
    return suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { cause, _, _ ->
                    response.closeQuietly()
                }
            }
        })
    }
}

/** Owns the real Call through response consumption, including cancellation during blocking body reads. */
suspend fun <T> Call.readResponse(read: (Response) -> T): T = coroutineScope {
    val pending = async(Dispatchers.IO) {
        try { execute().use(read) }
        catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            throw error
        }
    }
    try { pending.await() }
    finally { cancel() }
}

/** Marks side-effecting request bodies as non-repeatable, including HTTP 408/503 follow-ups. */
fun okhttp3.Request.withSingleAttemptBody(): okhttp3.Request {
    val original = body ?: return this
    val oneShot = object : okhttp3.RequestBody() {
        override fun contentType() = original.contentType()
        override fun contentLength() = original.contentLength()
        override fun isOneShot() = true
        override fun isDuplex() = original.isDuplex()
        override fun writeTo(sink: okio.BufferedSink) = original.writeTo(sink)
    }
    return newBuilder().method(method, oneShot).build()
}

class RoutedHttpException(val status: Int, val detail: String) : IOException("HTTP $status: $detail")

const val MAX_ROUTED_REQUEST_BYTES = 10L * 1024 * 1024

/** Relay requests carry one explicit route; a failed request must not be redirected or replayed. */
fun okhttp3.OkHttpClient.withExplicitRoute(endpoint: String, token: String): okhttp3.OkHttpClient {
    return newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .authenticator(okhttp3.Authenticator.NONE).proxyAuthenticator(okhttp3.Authenticator.NONE)
        .addInterceptor { chain ->
            val original = chain.request()
            if (original.url.toString() != endpoint) throw IOException("routed_request_endpoint_changed")
            original.body?.let {
                val length = it.contentLength()
                if (length < 0 || length > MAX_ROUTED_REQUEST_BYTES) throw IOException("platform_request_body_limit_exceeded")
            }
            val request = original.withSingleAttemptBody()
            val response = chain.proceed(request)
            if (response.isSuccessful) response else {
                val detail = response.use {
                    val source = it.body.source()
                    source.request(128L * 1024)
                    source.readUtf8(minOf(source.buffer.size, 128L * 1024))
                        .replace(token, "[redacted]")
                }
                throw RoutedHttpException(response.code, detail.ifBlank { response.message })
            }
        }.build()
}
