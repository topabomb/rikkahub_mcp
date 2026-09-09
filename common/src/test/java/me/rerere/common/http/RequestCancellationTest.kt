package me.rerere.common.http

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestCancellationTest {
    @Test fun `cancellation releases real requests before headers and during body consumption`() = runBlocking {
        for (bodyStarted in listOf(false, true)) {
            val reached = CompletableDeferred<Unit>()
            val consumingBody = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                try {
                    if (bodyStarted) {
                        exchange.sendResponseHeaders(200, 1024)
                        exchange.responseBody.write(1)
                        exchange.responseBody.flush()
                    }
                    reached.complete(Unit)
                    release.await(10, TimeUnit.SECONDS)
                } finally { exchange.close() }
            }
            server.start()
            val client = OkHttpClient.Builder().readTimeout(1, TimeUnit.MINUTES).build()
            val call = client.newCall(Request.Builder().url("http://127.0.0.1:${server.address.port}/").build())
            val reading = launch { call.readResponse { consumingBody.complete(Unit); it.body.bytes() } }
            try {
                withTimeout(5_000) { reached.await(); if (bodyStarted) consumingBody.await() }
                withTimeout(5_000) { reading.cancelAndJoin() }
                assertTrue(call.isCanceled())
                assertTrue(reading.isCancelled)
            } finally {
                release.countDown()
                reading.cancelAndJoin()
                server.stop(0)
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
            }
        }
    }

    @Test fun `await cancels the network call while waiting for headers`() = runBlocking {
        val reached = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try { reached.complete(Unit); release.await(10, TimeUnit.SECONDS) }
            finally { exchange.close() }
        }
        server.start()
        val client = OkHttpClient()
        val call = client.newCall(Request.Builder().url("http://127.0.0.1:${server.address.port}/").build())
        val reading = launch { call.await().close() }
        try {
            withTimeout(5_000) { reached.await() }
            withTimeout(5_000) { reading.cancelAndJoin() }
            assertTrue(call.isCanceled())
        } finally {
            release.countDown()
            reading.cancelAndJoin()
            server.stop(0)
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }
}
