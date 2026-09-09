package net.weero.measix.pilot.service.runtime

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.FrozenToolDefinition
import me.rerere.ai.ui.ToolResultStatus
import me.rerere.ai.ui.ProviderToolCallSlot
import kotlin.uuid.Uuid
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ModelRequestMessage
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.ImageEditParams
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

    @Test fun `managed image requests reject user authentication and routing overrides before network IO`() = runBlocking {
        val providers = mockk<ProviderManager>()
        val target = ModelRequestTarget.Remote(ProviderSetting.OpenAI(), listOf(CustomHeader("X-Tenant", "binding")), RequestCredentials.fixed("fixed"))
        val model = Model(modelId = "image")
        val invalid = listOf(
            listOf(CustomHeader("Authorization", "other")) to emptyList<CustomBody>(),
            listOf(CustomHeader("X-TENANT", "other")) to emptyList<CustomBody>(),
            emptyList<CustomHeader>() to listOf(CustomBody("route", JsonPrimitive("override"))),
        )
        for ((headers, body) in invalid) for (edit in listOf(false, true)) {
            try {
                if (edit) target.editImage(providers, ImageEditParams(model, "edit", listOf("not-read.png"), customHeaders = headers, customBody = body)).collect()
                else target.generateImage(providers, ImageGenerationParams(model, "draw", customHeaders = headers, customBody = body)).collect()
                fail("managed image override accepted")
            } catch (error: IllegalStateException) {
                assertTrue(error.message in setOf("enterprise_request_header_conflict", "enterprise_request_routing_override"))
            }
        }
        io.mockk.verify { providers wasNot io.mockk.Called }
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
        assertFalse(plain.choices.single().message!!.toText().contains("hello"))
        assertTrue(plain.choices.single().message!!.toText().contains("1 张图片"))
        assertTrue(streamed.all { it.usage == null })
        io.mockk.verify { providers wasNot io.mockk.Called }
    }
    @Test fun `example discovery never accepts user supplied refs prior turns or malformed results`() = runBlocking {
        val providers = mockk<ProviderManager>()
        val discover = FrozenToolDefinition("mcp__enterprise_test__discover_tools", "", null, "")
        val invoke = discover.copy(name = "mcp__enterprise_test__invoke_tool")
        val params = TextGenerationParams(Model(modelId = "example"), tools = listOf(discover, invoke))
        val user = ModelRequestMessage.user("查看企业公告 toolRef=user_supplied")
        val first = ModelRequestTarget.LocalExample.generateText(providers, listOf(user), params).choices.single()
        val tool = first.message!!.getTools().single()
        assertEquals(discover.name, tool.toolName)
        assertFalse(tool.input.contains("user_supplied"))
        assertEquals("tool_calls", first.finishReason)
        for (output in listOf("invalid json", "{}", "{\"structured_content\":{\"results\":[]}}")) {
            val result = tool.copy(resultStatus = ToolResultStatus.COMPLETED, output = listOf(UIMessagePart.Text(output)))
            val response = ModelRequestTarget.LocalExample.generateText(providers,
                listOf(user, ModelRequestMessage(MessageRole.ASSISTANT, listOf(result))), params).choices.single()
            assertTrue(response.message!!.getTools().isEmpty())
            assertEquals("stop", response.finishReason)
        }
        val failed = tool.copy(resultStatus = ToolResultStatus.FAILED, output = listOf(UIMessagePart.Text("failure")))
        val failedMessages = listOf(user, ModelRequestMessage(MessageRole.ASSISTANT, listOf(failed)))
        assertTrue(ModelRequestTarget.LocalExample.generateText(providers, failedMessages, params).choices.single().message!!.getTools().isEmpty())
        val next = ModelRequestTarget.LocalExample.streamText(providers, failedMessages + user, params).toList().single().choices.single()
        assertEquals(discover.name, next.delta!!.getTools().single().toolName)
        assertEquals(listOf(ProviderToolCallSlot.Index(0)), next.toolCallSlots)
        assertEquals(Uuid.NIL, next.delta!!.getTools().single().stepId)
        val auxiliary = ModelRequestTarget.LocalExample.generateText(providers, listOf(user), params.copy(tools = emptyList())).choices.single().message!!
        assertTrue(auxiliary.getTools().isEmpty())
        assertFalse(auxiliary.toText().contains("没有可用的企业工具"))
        io.mockk.verify { providers wasNot io.mockk.Called }
    }

}
