package me.rerere.ai.provider.providers

import android.util.Log
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.collect
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.RequestCredentials
import me.rerere.ai.provider.TextGenerationParams
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProviderResponseCancellationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Before fun mockLogs() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
    }

    @After fun restoreLogs() { unmockkStatic(Log::class) }

    @Test fun `all nonstreaming text providers cancel success and error response bodies`() = runBlocking {
        for (wire in listOf("chat", "responses", "claude", "google")) {
            for (status in listOf(200, 503)) {
                assertCancelledDuringBody(status = status) { client, endpoint ->
                    val (provider, setting) = textProvider(wire, client, endpoint)
                    provider.generateText(setting, emptyList(), TextGenerationParams(
                        Model(modelId = "fixture"), credentials = RequestCredentials.fixed("fixture-secret"),
                    ))
                }
            }
        }
    }

    @Test fun `image generation edits and result downloads cancel success and error response bodies`() = runBlocking {
        val image = temporary.newFile("input.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        for (edit in listOf(false, true)) for (download in listOf(false, true)) {
            for (status in listOf(200, 503)) {
                assertCancelledDuringBody(status, download) { client, endpoint ->
                    val provider = OpenAIProvider(client)
                    val setting = ProviderSetting.OpenAI(baseUrl = endpoint)
                    val credentials = RequestCredentials.fixed("fixture-secret")
                    if (edit) provider.editImage(setting, ImageEditParams(
                        Model(modelId = "fixture"), "edit", listOf(image.path), credentials = credentials,
                    )).collect()
                    else provider.generateImage(setting, ImageGenerationParams(
                        Model(modelId = "fixture"), "draw", credentials = credentials,
                    )).collect()
                }
            }
        }
    }

    private suspend fun assertCancelledDuringBody(
        status: Int,
        download: Boolean = false,
        operation: suspend (OkHttpClient, String) -> Unit,
    ) = coroutineScope {
        val consuming = CompletableDeferred<Call>()
        val cancelled = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val endpoint = "http://127.0.0.1:${server.address.port}"
        server.createContext("/") { exchange ->
            try {
                exchange.requestBody.use { it.readBytes() }
                if (download && exchange.requestMethod == "POST") {
                    val body = """{"data":[{"url":"$endpoint/download"}]}""".toByteArray()
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.write(body)
                } else {
                    exchange.sendResponseHeaders(status, 1024)
                    exchange.responseBody.write(1)
                    exchange.responseBody.flush()
                    release.await(30, TimeUnit.SECONDS)
                }
            } finally { exchange.close() }
        }
        server.start()
        val client = OkHttpClient.Builder().readTimeout(1, TimeUnit.MINUTES)
            .eventListener(object : EventListener() {
                override fun responseBodyStart(call: Call) {
                    if (!download || call.request().url.encodedPath == "/download") consuming.complete(call)
                }
            }).build()
        val request = launch(Dispatchers.IO) {
            try { operation(client, endpoint) }
            catch (error: CancellationException) { cancelled.complete(Unit); throw error }
        }
        try {
            val call = withTimeout(5_000) { consuming.await() }
            request.cancel()
            withTimeout(5_000) { request.join(); cancelled.await() }
            assertTrue("The original call must be cancelled during body consumption", call.isCanceled())
        } finally {
            release.countDown()
            request.cancel()
            request.join()
            server.stop(0)
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun textProvider(wire: String, client: OkHttpClient, endpoint: String): Pair<Provider<ProviderSetting>, ProviderSetting> {
        val pair = when (wire) {
            "chat", "responses" -> OpenAIProvider(client) to ProviderSetting.OpenAI(baseUrl = endpoint, useResponseApi = wire == "responses")
            "claude" -> ClaudeProvider(client) to ProviderSetting.Claude(baseUrl = endpoint)
            "google" -> GoogleProvider(client) to ProviderSetting.Google(baseUrl = endpoint)
            else -> error("unknown test wire")
        }
        return pair.first as Provider<ProviderSetting> to pair.second
    }
}
