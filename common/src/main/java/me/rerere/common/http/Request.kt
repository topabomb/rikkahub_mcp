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
