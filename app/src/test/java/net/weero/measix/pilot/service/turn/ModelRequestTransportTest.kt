package net.weero.measix.pilot.service.turn

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ModelRequestMessage
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.RequestCredentials
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.service.runtime.ModelRequestTarget
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class ModelRequestTransportTest {
    @Test fun `managed user overrides cannot replace authentication routing or private header ownership`() = runBlocking {
        var sent = 0
        val client = OkHttpClient.Builder().addInterceptor { sent++; error("unexpected I/O") }.build()
        val providers = ProviderManager(client, mockk<Context>())
        val target = ModelRequestTarget.Remote(ProviderSetting.OpenAI(), listOf(CustomHeader("X-Tenant", "binding")), RequestCredentials.fixed("opaque"))
        val params = TextGenerationParams(Model(modelId = "fixed-model"))
        val conflicts = listOf("authorization", "X-API-Key", "x-Goog-Api-Key", "HOST", "x-TENANT", "Content-Length")
            .map { params.copy(customHeaders = listOf(CustomHeader(it, "user-value"))) } +
            listOf("models", "route", "provider").map { params.copy(customBody = listOf(CustomBody(it, JsonPrimitive("override")))) }
        try {
            for (candidate in conflicts) for (stream in listOf(false, true)) {
                try {
                    if (stream) target.streamText(providers, emptyList(), candidate).collect()
                    else target.generateText(providers, emptyList(), candidate)
                    fail("managed override accepted")
                } catch (error: IllegalStateException) {
                    assertTrue(error.message in setOf("enterprise_request_header_conflict", "enterprise_request_routing_override"))
                }
            }
            assertEquals(0, sent)
        } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }

    @Test fun `local example consumes assembled input equally in streaming and nonstreaming modes without network`() = runBlocking {
        val providers = mockk<ProviderManager>()
        val messages = listOf(ModelRequestMessage(MessageRole.USER, listOf(UIMessagePart.Text("hello"), UIMessagePart.Image("file:///image.png"))))
        val params = TextGenerationParams(Model(modelId = "local-example"))
        val target = ModelRequestTarget.LocalExample
        val plain = target.generateText(providers, messages, params)
        val streamed = target.streamText(providers, messages, params).toList()
        assertEquals(plain.choices.single().message!!.toText(), streamed.flatMap { it.choices }.joinToString("") { it.delta?.toText().orEmpty() })
        assertEquals("stop", streamed.last().choices.single().finishReason)
        assertTrue(plain.choices.single().message!!.toText().contains("hello"))
        assertTrue(streamed.all { it.usage == null })
        io.mockk.verify { providers wasNot io.mockk.Called }
    }
}
