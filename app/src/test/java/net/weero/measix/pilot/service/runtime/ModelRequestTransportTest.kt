package net.weero.measix.pilot.service.runtime

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.RequestCredentials
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class ModelRequestTransportTest {
    @Test fun `platform auxiliary generation aggregates streaming output metadata and partial usage`() = runBlocking {
        val provider = mockk<me.rerere.ai.provider.Provider<ProviderSetting.OpenAI>>()
        val providers = mockk<ProviderManager>()
        val setting = ProviderSetting.OpenAI()
        io.mockk.every { providers.getProviderByType(setting) } returns provider
        val params = TextGenerationParams(Model(modelId = "upstream"))
        io.mockk.coEvery { provider.streamText(setting, any(), any()) } returns kotlinx.coroutines.flow.flowOf(
            me.rerere.ai.ui.MessageChunk("response", "upstream", listOf(me.rerere.ai.ui.UIMessageChoice(0,
                UIMessage.assistant("hello "), null, null)), me.rerere.ai.core.ProviderUsageSnapshot(inputTokens = 7)),
            me.rerere.ai.ui.MessageChunk("response", "upstream", listOf(me.rerere.ai.ui.UIMessageChoice(0,
                UIMessage.assistant("world"), null, "stop")),
                me.rerere.ai.core.ProviderUsageSnapshot(outputTokens = 2, canDeriveTotalFromInputAndOutput = true)),
        )
        val target = ModelRequestTarget.Remote(setting,
            credentials = RequestCredentials.Routed("http://platform.test/runtime", "test"))
        val chunk = target.generateText(providers, emptyList(), params,
            net.weero.measix.pilot.data.configuration.ModelSelectionRole.TITLE)
        assertEquals("hello world", chunk.choices.single().message!!.toText())
        assertEquals("stop", chunk.choices.single().finishReason)
        assertFalse(chunk.choices.single().message!!.parts.any { it is UIMessagePart.Step })
        assertEquals(7L, chunk.usage!!.inputTokens)
        assertEquals(2L, chunk.usage!!.outputTokens)
    }

    @Test fun `only verified routed 428 becomes synchronization barrier`() {
        val body = """{"code":"managed_snapshot_required","targetManagedGeneration":42,"forwarded":false,"requestId":"req_fixture"}"""
        val valid = me.rerere.common.http.RoutedHttpException(428, body)
        assertEquals(42L, net.weero.measix.pilot.data.enterprise.ManagedSnapshotRequired.find(
            IllegalStateException("transport", valid))!!.targetGeneration)
        assertNull(net.weero.measix.pilot.data.enterprise.ManagedSnapshotRequired.find(
            me.rerere.common.http.RoutedHttpException(428, body.replace("false", "true"))))
    }

    @Test fun `managed user overrides cannot replace authentication routing or private header ownership`() = runBlocking {
        var sent = 0
        val client = OkHttpClient.Builder().addInterceptor { sent++; error("unexpected I/O") }.build()
        val providers = ProviderManager(client, mockk<Context>())
        val target = ModelRequestTarget.Remote(ProviderSetting.OpenAI(),
            listOf(CustomHeader("X-Tenant", "binding")), RequestCredentials.fixed("opaque"))
        val params = TextGenerationParams(Model(modelId = "fixed-model"))
        val conflicts = listOf("authorization", "X-API-Key", "x-Goog-Api-Key", "HOST", "x-TENANT", "Content-Length")
            .map { params.copy(customHeaders = listOf(CustomHeader(it, "user-value"))) } +
            listOf("models", "route", "provider").map {
                params.copy(customBody = listOf(CustomBody(it, JsonPrimitive("override"))))
            }
        try {
            for (candidate in conflicts) assertThrows(IllegalStateException::class.java) {
                runBlocking { target.streamText(providers, emptyList(), candidate).collect() }
            }
            assertEquals(0, sent)
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test fun `managed image requests reject user routing overrides before network IO`() = runBlocking {
        val providers = mockk<ProviderManager>()
        val target = ModelRequestTarget.Remote(ProviderSetting.OpenAI(),
            listOf(CustomHeader("X-Tenant", "binding")), RequestCredentials.fixed("fixed"))
        val model = Model(modelId = "image")
        assertThrows(IllegalStateException::class.java) {
            runBlocking { target.generateImage(providers, ImageGenerationParams(model, "draw",
                customBody = listOf(CustomBody("route", JsonPrimitive("override"))))).collect() }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { target.editImage(providers, ImageEditParams(model, "edit", listOf("unused.png"),
                customHeaders = listOf(CustomHeader("Authorization", "other")))).collect() }
        }
        io.mockk.verify { providers wasNot io.mockk.Called }
    }
}
