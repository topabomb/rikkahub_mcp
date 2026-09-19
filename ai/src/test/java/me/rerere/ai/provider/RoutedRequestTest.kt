package me.rerere.ai.provider

import me.rerere.common.http.RoutedHttpException
import me.rerere.common.http.MAX_ROUTED_REQUEST_BYTES

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test

class RoutedRequestTest {
    @Test fun `relay errors preserve diagnostic and cannot redirect authenticate or retry`() {
        for (status in listOf(307, 401, 428, 503)) {
            val calls = AtomicInteger()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            val endpoint = "http://127.0.0.1:${server.address.port}/runtime"
            server.createContext("/") { exchange -> exchange.use {
                calls.incrementAndGet(); it.requestBody.readBytes()
                it.responseHeaders.set("Location", "$endpoint/replayed")
                it.responseHeaders.set("Retry-After", "0")
                val body = """{"code":"runtime_diagnostic","requestId":"req_fixture","detail":"fixture-secret"}""".toByteArray()
                it.sendResponseHeaders(status, body.size.toLong()); it.responseBody.write(body)
            } }
            server.start()
            val original = OkHttpClient.Builder().authenticator { _, response ->
                error("relay must not use user authenticator: ${response.code}")
            }.build()
            try {
                val client = original.forCredentials(RequestCredentials.Routed(endpoint, "fixture-secret"))
                val error = assertThrows(RoutedHttpException::class.java) {
                    client.newCall(Request.Builder().url(endpoint).post("{}".toRequestBody()).build()).execute().close()
                }
                assertEquals(status, error.status)
                assertTrue(error.detail.contains("runtime_diagnostic")); assertTrue(error.detail.contains("req_fixture"))
                assertFalse(error.detail.contains("fixture-secret"))
                assertEquals(1, calls.get())
            } finally { server.stop(0); original.dispatcher.executorService.shutdown(); original.connectionPool.evictAll() }
        }
    }

    @Test fun `oversized relay payload is rejected before external IO`() {
        val client = OkHttpClient()
        try {
            val routed = client.forCredentials(RequestCredentials.Routed("http://127.0.0.1:1/runtime", "fixture"))
            val error = assertThrows(java.io.IOException::class.java) {
                routed.newCall(Request.Builder().url("http://127.0.0.1:1/runtime")
                    .post(ByteArray(10 * 1024 * 1024 + 1).toRequestBody()).build()).execute().close()
            }
            assertEquals("platform_request_body_limit_exceeded", error.message)
        } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }

    @Test fun `encoded image and history body uses the exact relay byte boundary`() {
        val calls = AtomicInteger()
        val receivedBytes = AtomicLong()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val endpoint = "http://127.0.0.1:${server.address.port}/runtime"
        server.createContext("/runtime") { exchange -> exchange.use {
            calls.incrementAndGet()
            receivedBytes.set(it.requestBody.readBytes().size.toLong())
            it.sendResponseHeaders(204, -1)
        } }
        server.start()
        val client = OkHttpClient()
        try {
            val routed = client.forCredentials(RequestCredentials.Routed(endpoint, "fixture"))
            val prefix = """{"messages":[{"role":"tool","content":"earlier result"},{"role":"user","content":[{"type":"image_url","image_url":{"url":"data:image/png;base64,""""
            val suffix = """"}}]}]}"""
            val imageChars = ((MAX_ROUTED_REQUEST_BYTES - prefix.length - suffix.length) / 4 * 4).toInt()
            val encoded = prefix + "A".repeat(imageChars) + suffix +
                " ".repeat((MAX_ROUTED_REQUEST_BYTES - prefix.length - imageChars - suffix.length).toInt())
            assertEquals(MAX_ROUTED_REQUEST_BYTES, encoded.toByteArray().size.toLong())
            routed.newCall(Request.Builder().url(endpoint).post(encoded.toRequestBody()).build()).execute().use {
                assertEquals(204, it.code)
            }
            val error = assertThrows(java.io.IOException::class.java) {
                routed.newCall(Request.Builder().url(endpoint).post((encoded + "A").toRequestBody()).build()).execute().close()
            }
            assertEquals("platform_request_body_limit_exceeded", error.message)
            assertEquals(1, calls.get())
            assertEquals(MAX_ROUTED_REQUEST_BYTES, receivedBytes.get())
        } finally {
            server.stop(0)
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
