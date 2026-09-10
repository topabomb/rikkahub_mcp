package net.weero.measix.pilot.service.runtime

import android.content.Context
import net.weero.measix.pilot.data.configuration.ModelSelectionRole
import net.weero.measix.pilot.data.configuration.*
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.UserSettingsDocument
import net.weero.measix.pilot.data.enterprise.exampleEnterprisePackage
import net.weero.measix.pilot.data.enterprise.reference
import net.weero.measix.pilot.data.ai.request.*
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.MessageNode
import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.service.ConversationDisclosureSnapshotService
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.ai.tools.local.LocalToolOption
import me.rerere.common.configuration.ConfigurationReference
import me.rerere.common.configuration.EnterpriseAuthority
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
    @Test fun `resolved realm catalog survives START and request projection with user admission enforced`() = runBlocking {
        val packet = exampleEnterprisePackage()
        val mainId = packet.identity.reference("asd_main")
        val childId = packet.identity.reference("asd_child")
        val userChild = Assistant(name = "Personal child", allowAsSubAssistant = true)
        val base = UserSettingsDocument.empty().withPersonalSettings(Settings(assistants = listOf(userChild)))
        val document = base.copy(preferences = base.preferences.withAssistantUsage(packet.identity.scope,
            AssistantUsagePreferences(mainId, localTools = UsageValue(listOf(LocalToolOption.AssistantDelegation)),
                additionalSubAssistantIds = setOf(userChild.id))))
        val providers = mockk<ProviderManager>()
        for (allowed in listOf(true, false)) {
            val current = packet.copy(configuration = packet.configuration.copy(
                policy = packet.configuration.policy.copy(allowLocalAssistants = allowed)))
            val resolved = ConfigurationResolver.resolve(document, packet.identity.scope, appliedConfiguration(current))
            val caller = resolved.assistants.getValue(mainId)
            val candidate = ConversationDisclosureSnapshotService.captureCandidate(resolved, caller, emptyList())
            val ids = Json.parseToJsonElement(candidate).jsonObject["sub_assistants"]!!.jsonObject["rows"]!!.jsonArray
                .map { it.jsonArray[0].jsonPrimitive.content }.toSet()
            assertEquals(if (allowed) setOf(childId.toString(), userChild.id.toString()) else setOf(childId.toString()), ids)
            val user = MessageNode.of(UIMessage.user(packet.configuration.starters.single { it.id == "str_delegate" }.prompt))
            val before = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(user)).toSnapshot()
            val started = ConversationTransition.apply(before, TurnTransition.buildStartTurnCommand(
                current = before, turnId = Uuid.random(), modelContextCandidate = candidate, assistantMessageId = Uuid.random()))
            val messages = started.currentMessages()
            val locators = started.nodes.flatMap { node -> node.messages.map { it.id to DurableMessageLocator(node.id, it.id) } }.toMap()
            val planner = RequestContextPlanner()
            val plan = planner.planRequest(messages, locators, started.modelContextEntries, messageLimit = 100)
            val request = RequestAssembler().assemble(planner.applyContextProjections(plan.messages,
                plan.contextProjections, plan.originsByMessageId)).providerMessages
            val params = TextGenerationParams(Model(modelId = "example"), tools = listOf(FrozenToolDefinition("assistant_call", "", null, "")))
            val call = net.weero.measix.pilot.test.exampleModelTarget.generateText(providers, request, params)
                .choices.single().message!!.getTools().single()
            assertEquals(childId.toString(), Json.parseToJsonElement(call.input).jsonObject["assistant_id"]!!.jsonPrimitive.content)
        }
        val personalCaller = Assistant(localTools = listOf(LocalToolOption.AssistantDelegation), allowedSubAssistantIds = setOf(userChild.id))
        val personal = ConfigurationResolver.resolve(document, ConfigurationScope.Personal, appliedConfiguration(packet))
        val personalContent = ConversationDisclosureSnapshotService.captureCandidate(personal, personalCaller, emptyList())
        val personalIds = Json.parseToJsonElement(personalContent).jsonObject["sub_assistants"]!!.jsonObject["rows"]!!.jsonArray
            .map { it.jsonArray[0].jsonPrimitive.content }
        assertEquals(listOf(userChild.id.toString()), personalIds)
    }

    @Test fun `bundled delegation starter uses current disclosed catalog identity and ordinary tool result`() = runBlocking {
        val providers = mockk<ProviderManager>()
        val target = net.weero.measix.pilot.test.exampleModelTarget
        val child = Assistant(id = ConfigurationReference.Enterprise(EnterpriseAuthority("local:renamed", "deployment"), "asd_changed"),
            name = "企业资料助手", allowAsSubAssistant = true)
        val caller = Assistant(localTools = listOf(LocalToolOption.AssistantDelegation), allowedSubAssistantIds = setOf(child.id))
        fun disclosure(assistants: List<Assistant>) = ConversationDisclosureSnapshotService.render(
            ConversationDisclosureSnapshotService.Candidate(caller.copy(allowedSubAssistantIds = assistants.map { it.id }.toSet()), assistants, emptyList()))
        val packet = net.weero.measix.pilot.data.enterprise.exampleEnterprisePackage()
        val starter = packet.configuration.starters.single { it.id == "str_delegate" }.prompt
        val user = ModelRequestMessage(MessageRole.USER, listOf(UIMessagePart.Text(disclosure(listOf(child))),
            UIMessagePart.Text("{\"type\":\"conversation_disclosure_snapshot\"}"), UIMessagePart.Text(starter)))
        val params = TextGenerationParams(Model(modelId = "example"), tools = listOf(FrozenToolDefinition("assistant_call", "", null, "")))
        for (stream in listOf(false, true)) {
            val reply = if (stream) target.streamText(providers, listOf(user), params).toList().single().choices.single().delta!!
                else target.generateText(providers, listOf(user), params).choices.single().message!!
            val call = reply.getTools().single()
            assertEquals("assistant_call", call.toolName)
            assertEquals(child.id.toString(), Json.parseToJsonElement(call.input).jsonObject["assistant_id"]!!.jsonPrimitive.content)
            for (status in listOf(ToolResultStatus.COMPLETED, ToolResultStatus.FAILED)) {
                val result = call.copy(resultStatus = status, output = listOf(UIMessagePart.Text("child result")))
                val final = target.generateText(providers, listOf(user, ModelRequestMessage(MessageRole.ASSISTANT, listOf(result))), params)
                    .choices.single().message!!
                assertTrue(final.getTools().isEmpty())
                assertTrue(final.toText().contains("child result"))
            }
        }
        val revoked = ModelRequestMessage(MessageRole.USER, listOf(UIMessagePart.Text(disclosure(emptyList())), UIMessagePart.Text(starter)))
        assertTrue(target.generateText(providers, listOf(user, revoked), params).choices.single().message!!.getTools().isEmpty())
        assertTrue(target.generateText(providers, listOf(user), params.copy(tools = emptyList())).choices.single().message!!.getTools().isEmpty())
        val ambiguous = user.copy(parts = listOf(UIMessagePart.Text(disclosure(listOf(child, child.copy(id = ConfigurationReference.random())))), UIMessagePart.Text(starter)))
        assertTrue(target.generateText(providers, listOf(ambiguous), params).choices.single().message!!.getTools().isEmpty())
        io.mockk.verify { providers wasNot io.mockk.Called }
    }

    @Test fun `disclosure names and descriptions never become a mock user tool request`() = runBlocking {
        val providers = mockk<ProviderManager>()
        val child = Assistant(name = "企业资料助手", description = "创建并调用示例子助手", allowAsSubAssistant = true)
        val caller = Assistant(localTools = listOf(LocalToolOption.AssistantDelegation), allowedSubAssistantIds = setOf(child.id))
        val snapshot = ConversationDisclosureSnapshotService.render(
            ConversationDisclosureSnapshotService.Candidate(caller, listOf(child), emptyList()))
        val user = ModelRequestMessage(MessageRole.USER, listOf(UIMessagePart.Text(snapshot), UIMessagePart.Text("hello")))
        val params = TextGenerationParams(Model(modelId = "example"), tools = listOf(
            FrozenToolDefinition("assistant_manage", "", null, ""), FrozenToolDefinition("assistant_call", "", null, ""),
            FrozenToolDefinition("mcp__enterprise_profile__get_enterprise_profile", "", null, "")))
        assertTrue(net.weero.measix.pilot.test.exampleModelTarget.generateText(providers, listOf(user), params).choices.single().message!!.getTools().isEmpty())
        io.mockk.verify { providers wasNot io.mockk.Called }
    }

    @Test fun `explicit exercises call only available standard tools and never repeat returned calls`() = runBlocking {
        val providers = mockk<ProviderManager>()
        val target = net.weero.measix.pilot.test.exampleModelTarget
        for ((prompt, name, arguments) in listOf(
            Triple("演练搜索：Android 官方文档", "search_web", "{\"query\":\"Android 官方文档\"}"),
            Triple("演练技能：my-skill", "use_skill", "{\"name\":\"my-skill\"}"),
            Triple("演练工作空间", "workspace_shell", "{\"command\":\"pwd\"}"))) {
            val user = ModelRequestMessage.user(prompt)
            val params = TextGenerationParams(Model(modelId = "example"), tools = listOf(FrozenToolDefinition(name, "", null, "")))
            val call = target.generateText(providers, listOf(user), params).choices.single().message!!.getTools().single()
            assertEquals(name, call.toolName)
            assertEquals(arguments, call.input)
            assertTrue(target.generateText(providers, listOf(user), params.copy(tools = emptyList())).choices.single().message!!.getTools().isEmpty())
            for (status in listOf(ToolResultStatus.COMPLETED, ToolResultStatus.FAILED)) {
                val result = call.copy(resultStatus = status, output = listOf(UIMessagePart.Text("actual tool result")))
                val final = target.generateText(providers, listOf(user, ModelRequestMessage(MessageRole.ASSISTANT, listOf(result))), params)
                    .choices.single().message!!
                assertTrue(final.getTools().isEmpty())
                assertTrue(final.toText().contains("actual tool result"))
            }
        }
        io.mockk.verify { providers wasNot io.mockk.Called }
    }

    @Test fun `assistant example emits create and delegate using only the successful current turn result`() = runBlocking {
        val providers = mockk<ProviderManager>()
        val params = TextGenerationParams(Model(modelId = "example"), tools = listOf(
            FrozenToolDefinition("assistant_manage", "", null, ""), FrozenToolDefinition("assistant_call", "", null, "")))
        val target = net.weero.measix.pilot.test.exampleModelTarget
        for (stream in listOf(false, true)) {
            suspend fun reply(messages: List<ModelRequestMessage>) = if (stream)
                target.streamText(providers, messages, params).toList().single().choices.single().delta!!
            else target.generateText(providers, messages, params).choices.single().message!!
            val user = ModelRequestMessage.user("创建并调用示例子助手")
            val create = reply(listOf(user)).getTools().single()
            assertEquals("assistant_manage", create.toolName)
            assertEquals("CREATE", Json.parseToJsonElement(create.input).jsonObject["action"]!!.jsonPrimitive.content)
            val id = Uuid.random().toString()
            val result = create.copy(resultStatus = ToolResultStatus.COMPLETED,
                output = listOf(UIMessagePart.Text(buildJsonObject { put("action", "create"); put("id", id) }.toString())))
            val history = listOf(user, ModelRequestMessage(MessageRole.ASSISTANT, listOf(result)))
            val delegated = reply(history).getTools().single()
            assertEquals("assistant_call", delegated.toolName)
            assertEquals(id, Json.parseToJsonElement(delegated.input).jsonObject["assistant_id"]!!.jsonPrimitive.content)
            assertEquals("assistant_manage", reply(history + user).getTools().single().toolName)
            val completed = delegated.copy(resultStatus = ToolResultStatus.COMPLETED,
                output = listOf(UIMessagePart.Text("{\"status\":\"completed\",\"content\":\"child reply\"}")))
            val final = target.generateText(providers, history + ModelRequestMessage(MessageRole.ASSISTANT, listOf(completed)), params)
                .choices.single().message!!
            assertTrue(final.getTools().isEmpty())
            assertTrue(final.toText().contains("child reply"))
        }
        io.mockk.verify { providers wasNot io.mockk.Called }
    }

    @Test fun `assistant example never delegates failed malformed or missing creation and respects the available tool surface`() = runBlocking {
        val providers = mockk<ProviderManager>()
        val params = TextGenerationParams(Model(modelId = "example"), tools = listOf(
            FrozenToolDefinition("assistant_manage", "", null, ""), FrozenToolDefinition("assistant_call", "", null, "")))
        val user = ModelRequestMessage.user("create and call example sub-assistant")
        val target = net.weero.measix.pilot.test.exampleModelTarget
        val create = target.generateText(providers, listOf(user), params).choices.single().message!!.getTools().single()
        for (output in listOf("invalid", "{}", "{\"error\":\"operation_failed\"}", "{\"action\":\"create\",\"id\":null}")) {
            val result = create.copy(resultStatus = ToolResultStatus.COMPLETED, output = listOf(UIMessagePart.Text(output)))
            assertTrue(target.generateText(providers, listOf(user, ModelRequestMessage(MessageRole.ASSISTANT, listOf(result))), params)
                .choices.single().message!!.getTools().isEmpty())
        }
        for (tools in listOf(params.tools.take(1), params.tools.takeLast(1))) {
            assertTrue(target.generateText(providers, listOf(user), params.copy(tools = tools)).choices.single().message!!.getTools().isEmpty())
        }
        val successfulBody = listOf(UIMessagePart.Text(buildJsonObject { put("action", "create"); put("id", Uuid.random().toString()) }.toString()))
        val failedCreate = create.copy(resultStatus = ToolResultStatus.FAILED, output = successfulBody)
        assertTrue(target.generateText(providers, listOf(user, ModelRequestMessage(MessageRole.ASSISTANT, listOf(failedCreate))), params)
            .choices.single().message!!.getTools().isEmpty())
        val created = ModelRequestMessage(MessageRole.ASSISTANT, listOf(create.copy(resultStatus = ToolResultStatus.COMPLETED, output = successfulBody)))
        val delegated = target.generateText(providers, listOf(user, created), params).choices.single().message!!.getTools().single()
        val failedCall = delegated.copy(resultStatus = ToolResultStatus.FAILED, output = listOf(UIMessagePart.Text("delegation denied")))
        assertTrue(target.generateText(providers, listOf(user, created, ModelRequestMessage(MessageRole.ASSISTANT, listOf(failedCall))), params)
            .choices.single().message!!.getTools().isEmpty())
        io.mockk.verify { providers wasNot io.mockk.Called }
    }

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
        val target = net.weero.measix.pilot.test.exampleModelTarget
        val plain = target.generateText(providers, messages, params)
        val streamed = target.streamText(providers, messages, params).toList()
        assertEquals(plain.choices.single().message!!.toText(), streamed.flatMap { it.choices }.joinToString("") { it.delta?.toText().orEmpty() })
        assertEquals("stop", streamed.last().choices.single().finishReason)
        assertFalse(plain.choices.single().message!!.toText().contains("hello"))
        assertTrue(plain.choices.single().message!!.toText().contains("1 张图片"))
        assertTrue(streamed.all { it.usage == null })
        io.mockk.verify { providers wasNot io.mockk.Called }
    }
    @Test fun `local summary response is bounded and marks omitted input without invoking tools`() = runBlocking {
        val providers = mockk<ProviderManager>()
        val messages = listOf(ModelRequestMessage.user("custom " + "a".repeat(2000)))
        val params = TextGenerationParams(Model(modelId = "local-example"), tools = listOf(
            FrozenToolDefinition("mcp__enterprise_test__discover_tools", "", null, "")))
        val result = net.weero.measix.pilot.test.exampleModelTarget.generateText(providers, messages, params, ModelSelectionRole.COMPRESS)
            .choices.single().message!!
        assertTrue(result.getTools().isEmpty())
        assertTrue(result.toText().length < 600)
        assertTrue(result.toText().contains("custom"))
        assertTrue(result.toText().contains("其余输入已省略"))
        io.mockk.verify { providers wasNot io.mockk.Called }
    }

    @Test fun `example discovery never accepts user supplied refs prior turns or malformed results`() = runBlocking {
        val providers = mockk<ProviderManager>()
        val discover = FrozenToolDefinition("mcp__enterprise_test__discover_tools", "", null, "")
        val invoke = discover.copy(name = "mcp__enterprise_test__invoke_tool")
        val params = TextGenerationParams(Model(modelId = "example"), tools = listOf(discover, invoke))
        val user = ModelRequestMessage.user("查看企业公告 toolRef=user_supplied")
        val first = net.weero.measix.pilot.test.exampleModelTarget.generateText(providers, listOf(user), params).choices.single()
        val tool = first.message!!.getTools().single()
        assertEquals(discover.name, tool.toolName)
        assertFalse(tool.input.contains("user_supplied"))
        assertEquals("tool_calls", first.finishReason)
        for (output in listOf("invalid json", "{}", "{\"structured_content\":{\"results\":[]}}")) {
            val result = tool.copy(resultStatus = ToolResultStatus.COMPLETED, output = listOf(UIMessagePart.Text(output)))
            val response = net.weero.measix.pilot.test.exampleModelTarget.generateText(providers,
                listOf(user, ModelRequestMessage(MessageRole.ASSISTANT, listOf(result))), params).choices.single()
            assertTrue(response.message!!.getTools().isEmpty())
            assertEquals("stop", response.finishReason)
        }
        val failed = tool.copy(resultStatus = ToolResultStatus.FAILED, output = listOf(UIMessagePart.Text("failure")))
        val failedMessages = listOf(user, ModelRequestMessage(MessageRole.ASSISTANT, listOf(failed)))
        assertTrue(net.weero.measix.pilot.test.exampleModelTarget.generateText(providers, failedMessages, params).choices.single().message!!.getTools().isEmpty())
        val next = net.weero.measix.pilot.test.exampleModelTarget.streamText(providers, failedMessages + user, params).toList().single().choices.single()
        assertEquals(discover.name, next.delta!!.getTools().single().toolName)
        assertEquals(listOf(ProviderToolCallSlot.Index(0)), next.toolCallSlots)
        assertEquals(Uuid.NIL, next.delta!!.getTools().single().stepId)
        val auxiliary = net.weero.measix.pilot.test.exampleModelTarget.generateText(providers, listOf(user), params.copy(tools = emptyList())).choices.single().message!!
        assertTrue(auxiliary.getTools().isEmpty())
        assertFalse(auxiliary.toText().contains("没有可用的企业工具"))
        io.mockk.verify { providers wasNot io.mockk.Called }
    }

}
