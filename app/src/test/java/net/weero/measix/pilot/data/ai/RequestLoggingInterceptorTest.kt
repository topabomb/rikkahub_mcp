package net.weero.measix.pilot.data.ai

import me.rerere.common.android.Logging
import me.rerere.common.http.PrivateRequest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class RequestLoggingInterceptorTest {
    @Test fun `private request and failure bypass visible logging while personal requests remain visible`() {
        Logging.clear()
        Logging.setRequestLoggingEnabled(true)
        val client = OkHttpClient.Builder().addInterceptor(RequestLoggingInterceptor()).addInterceptor { chain ->
            if (chain.request().url.encodedPath == "/failure") throw java.io.IOException("private-server-detail")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("X-Private-Reply", "private-value").body("ok".toResponseBody()).build()
        }.build()
        try {
            for (path in listOf("success", "failure")) {
                val request = Request.Builder().url("https://private.test/$path")
                    .header("X-Private", "private-value").tag(PrivateRequest::class.java, PrivateRequest).build()
                try { client.newCall(request).execute().close() }
                catch (error: java.io.IOException) { assertEquals("private-server-detail", error.message) }
                assertTrue(Logging.getRequestLogs().isEmpty())
            }
            client.newCall(Request.Builder().url("https://personal.test/success").build()).execute().close()
            assertEquals(1, Logging.getRequestLogs().size)
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            Logging.setRequestLoggingEnabled(false)
            Logging.clear()
        }
    }
}
