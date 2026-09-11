package net.weero.measix.pilot.data.enterprise

import net.weero.measix.pilot.data.ai.tools.TurnToolSetFactory
import net.weero.measix.pilot.data.ai.tools.ToolOutputStore
import net.weero.measix.pilot.data.ai.tools.local.LocalTools
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.ArtifactReadLease
import net.weero.measix.pilot.data.files.ArtifactRetentionLease
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.service.runtime.ModelExecutionLease
import net.weero.measix.pilot.service.runtime.ModelRequestTarget
import net.weero.measix.pilot.service.turn.TurnRunner
import net.weero.measix.pilot.service.turn.TurnOutcome
import net.weero.measix.pilot.test.TurnRunCapture
import net.weero.measix.pilot.test.turnRunInputsFixture
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.RequestMediaCapabilities
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import net.weero.measix.pilot.service.runtime.toSnapshot
import io.ktor.client.HttpClient
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import net.weero.measix.pilot.data.ai.mcp.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalEnterpriseMcpServiceTest {
    @get:Rule val temporary = TemporaryFolder()
    private var now = Instant.parse("2029-01-01T00:00:00Z").toEpochMilli()

    @Test fun `coordinator prepares and invokes original enterprise tools while new interactions follow Gateway preferences`() = runBlocking {
        withHarness { h ->
            val appScope = net.weero.measix.pilot.AppScope(Dispatchers.Default)
            val settings = mockk<net.weero.measix.pilot.data.datastore.SettingsStore>()
            var document = net.weero.measix.pilot.data.datastore.UserSettingsDocument.empty()
            every { settings.userMcpDefinitions } returns kotlinx.coroutines.flow.flowOf(emptyList())
            coEvery { settings.pendingMcpCatalogMigration() } returns null
            coEvery { settings.withExecutionConfiguration<Any?>(any(), any(), any()) } coAnswers {
                val state = secondArg<EnterpriseState>()
                thirdArg<suspend (net.weero.measix.pilot.data.datastore.ExecutionConfigurationSnapshot) -> Any?>().invoke(
                    net.weero.measix.pilot.data.datastore.ExecutionConfigurationSnapshot(net.weero.measix.pilot.data.datastore.Settings(),
                        net.weero.measix.pilot.data.configuration.ConfigurationResolver.resolve(document, h.access.scope, state), "test"))
            }
            val store = McpCatalogStore(androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = appScope,
                produceFile = { temporary.root.resolve("execution-catalog.preferences_pb") }), appScope, settings)
            val local = mockk<LocalEnterpriseMcpService>()
            every { local.createClient() } returns h.http
            val network = mockk<NetworkMonitor>()
            every { network.isOnline } returns kotlinx.coroutines.flow.MutableStateFlow(true)
            val manager = McpRuntimeCoordinator(settings, h.sessions, mockk(), local, store, appScope, mockk(), network,
                foregroundObserver = ForegroundObserver {}, oauthCallbackKeepAlive = NoOpOAuthCallbackKeepAlive)
            val owners = mutableListOf<Triple<net.weero.measix.pilot.service.runtime.ConversationRuntime, Uuid, Job>>()
            suspend fun prepare(): TurnMcpCapabilitySnapshot {
                val state = h.sessions.state.value as EnterpriseState.Available
                val configuration = net.weero.measix.pilot.data.configuration.ConfigurationResolver.resolve(document, h.access.scope, state)
                val assistant = configuration.assistants.getValue(requireNotNull(configuration.enterpriseIdentity).reference("asd_main"))
                val conversation = net.weero.measix.pilot.data.model.Conversation(assistantId = assistant.id, scope = h.access.scope, messageNodes = emptyList())
                val runtime = net.weero.measix.pilot.service.runtime.ConversationRuntime(conversation.id, conversation.toSnapshot(), appScope, {})
                val turnId = Uuid.random()
                val worker = Job()
                runtime.installTurnWorker(turnId, worker)
                owners += Triple(runtime, turnId, worker)
                val model = configuration.models.getValue(requireNotNull(configuration.assistantModel(assistant.id).reference)).model
                val captured = net.weero.measix.pilot.service.CapturedModelConfiguration(net.weero.measix.pilot.data.datastore.Settings(), configuration, assistant,
                    net.weero.measix.pilot.service.ModelExecutionSnapshot(model, mockk(), "test", state.manifest.applied), selectionRevision = h.sessions.selectionRevision.value, interactionId = turnId)
                return manager.prepareTurnCapabilities(h.access, captured, runtime, turnId, worker) { error("unexpected barrier") }
            }
            suspend fun invoke(tool: McpAvailableTool, args: JsonObject, metadata: suspend (JsonObject) -> Unit = {}) =
                manager.callTool(h.access, serverId = tool.serverId, toolName = tool.name, expectedDefinitionDigest = tool.definitionDigest, expectedNeedsApproval = tool.needsApproval, args = args,
                    interactionId = tool.interactionId, onResolvedTool = metadata, onArtifactCreated = {})
            try {
                val first = prepare()
                assertTrue(first.tools.any { it.name == "get_enterprise_profile" })
                verifyModelToolLoop(h.access, h.sessions.state.value, first, manager)
                val discover = first.tools.single { it.name == "discover_tools" }
                val discovery = invoke(discover, buildJsonObject { put("queries", buildJsonArray { add("公告") }) })
                val structured = discovery.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>().mapNotNull {
                    (Json.parseToJsonElement(it.text) as? JsonObject)?.get("structured_content")
                }.single()!!.jsonObject
                val ref = structured["results"]!!.jsonArray.single().jsonObject["matches"]!!.jsonArray.single().jsonObject["toolRef"]!!.jsonPrimitive.content
                var resolved: JsonObject? = null
                invoke(first.tools.single { it.name == "invoke_tool" }, buildJsonObject { put("toolRef", ref); put("arguments", buildJsonObject {}) }) { resolved = it }
                assertEquals("gtl_enterprise_updates", resolved!!["gatewayToolId"]!!.jsonPrimitive.content)
                val gatewayId = discover.serverId as me.rerere.common.configuration.ConfigurationReference.Enterprise
                document = document.copy(preferences = document.preferences.withGateway(h.access.scope,
                    net.weero.measix.pilot.data.configuration.GatewayPreference(gatewayId, false)))
                val second = prepare()
                assertFalse(second.tools.any { it.serverId == gatewayId })
                assertTrue(second.tools.any { it.name == "get_enterprise_profile" })
                assertNotEquals(first.tools.first().interactionId, second.tools.first().interactionId)
                assertTrue(invoke(discover, buildJsonObject { put("queries", buildJsonArray { add("公告") }) }).isNotEmpty())
            } finally {
                owners.forEach { (runtime, turn, worker) -> worker.cancel(); runtime.releaseTurnWorker(turn, worker, false) }
                assertTrue(manager.runtimeCapabilities.value.keys.none { it.access == h.access })
                appScope.coroutineContext[Job]!!.cancelAndJoin()
            }
        }
    }

    @Test fun `actual SDK and transport discover the complete Gateway surface and execute the shared Feed`() = runBlocking {
        withHarness { h -> h.connect { client, target ->
            val catalog = McpCatalogDiscovery.fetchCandidate(target.catalogKey, target.mcpDefinitionDigest(), client, target.managed)
            assertEquals(LocalEnterpriseMcpSurface.gatewayTools, catalog.tools)
            assertEquals(target.managed, catalog.managed)
            val discovery = client.call("discover_tools", """{"queries":["公告"]}""")
            assertFalse(discovery.isError == true)
            assertEquals(target.version.generation, discovery.structuredContent!!["catalogGeneration"]!!.jsonPrimitive.long)
            val match = discovery.structuredContent!!["results"]!!.jsonArray.single().jsonObject["matches"]!!.jsonArray.single().jsonObject
            assertEquals("gtl_enterprise_updates", match["gatewayToolId"]!!.jsonPrimitive.content)
            assertEquals("READ_ONLY", match["risk"]!!.jsonPrimitive.content)
            assertTrue(match["inputSchema"] is JsonObject)
            val result = client.invoke(match["toolRef"]!!.jsonPrimitive.content, buildJsonObject { put("limit", 2) })
            assertFalse(result.isError == true)
            val expected = EnterprisePackageCodec.json.encodeToJsonElement(h.sessions.listFeed(h.access, EnterpriseFeedQuery(limit = 2)).body)
            assertEquals(expected, result.structuredContent)
            val metadata = result.meta!!["com.measix/resolvedTool"]!!.jsonObject
            assertEquals(setOf("gatewayToolId", "name", "status", "requestId"), metadata.keys)
            assertEquals("get_enterprise_updates", metadata["name"]!!.jsonPrimitive.content)
            assertFalse(metadata.toString().contains("toolRef"))
        } }
    }

    @Test fun `refs are integrity protected interaction scoped expiring and cannot use name fallback`() = runBlocking {
        withHarness { h ->
            h.connect { first, _ ->
                val ref = first.discoverRef()
                assertCode("tool_ref_invalid", first.invoke(ref + "x"))
                assertCode("tool_arguments_invalid", first.call("invoke_tool", """{"toolRef":"$ref","arguments":{},"name":"get_enterprise_updates"}"""))
                h.connect { second, _ -> assertCode("tool_ref_scope_mismatch", second.invoke(ref)) }
                now += 5 * 60_000
                assertCode("tool_ref_expired", first.invoke(ref))
            }
        }
    }

    @Test fun `Gateway argument limits and types are enforced by the local service`() = runBlocking {
        withHarness { h -> h.connect { client, _ ->
            listOf("{}", "{\"queries\":[]}", "{\"queries\":[1]}", "{\"queries\":[\"公告\"],\"limitPerQuery\":\"1\"}",
                "{\"queries\":[\"公告\"],\"limitPerQuery\":6}", "{\"queries\":[\"公告\"],\"extra\":true}").forEach {
                assertCode("tool_arguments_invalid", client.call("discover_tools", it))
            }
            val ref = client.discoverRef()
            assertCode("tool_arguments_invalid", client.invoke(ref, buildJsonObject { put("limit", 21) }))
            assertCode("tool_arguments_invalid", client.invoke(ref, buildJsonObject { put("startDate", "not-a-date") }))
        } }
    }

    @Test fun `published generation change rejects the original interaction before forwarding and uses the shared barrier contract`() = runBlocking {
        val fixture = requireNotNull(javaClass.getResourceAsStream("/contracts/runtime/managed-snapshot-required.json")).bufferedReader().use { it.readText() }
        val shared = ManagedSnapshotRequired.parse(fixture)
        assertEquals(42L, shared.targetGeneration)
        withHarness { h -> h.connect { client, target ->
            val ref = client.discoverRef()
            val previous = requireNotNull(h.source.candidate(h.access.scope))
            h.source.changeConfiguration(h.access.scope, previous.revision) { it }
            assertEquals(target.version.generation, (h.sessions.state.value as EnterpriseState.Available).manifest.applied!!.generation)
            val failure = try { client.invoke(ref); error("old interaction must hit the generation barrier") }
                catch (error: Exception) { error }
            val barrier = requireNotNull(ManagedSnapshotRequired.find(failure)) { failure.toString() }
            assertEquals(target.version.generation + 1, barrier.targetGeneration)
            assertTrue(barrier.requestId.startsWith("req_"))
        } }
    }

    @Test fun `direct MCP uses standard discovery and Session exit seals access`() = runBlocking {
        withHarness { h -> h.connect(gateway = false) { client, target ->
            val catalog = McpCatalogDiscovery.fetchCandidate(target.catalogKey, target.mcpDefinitionDigest(), client, target.managed)
            assertEquals(listOf("get_enterprise_profile"), catalog.tools.map { it.name })
            assertTrue(catalog.tools.none { it.name in setOf("get_enterprise_updates", "read_enterprise_guide") })
            val result = client.call("get_enterprise_profile", "{}")
            assertFalse(result.isError == true)
            assertNull(result.meta)
            val exit = h.sessions.beginExit(requireNotNull(h.sessions.captureExitRequest()))
            val failure = try { client.call("get_enterprise_profile", "{}"); error("closed Session must reject") }
                catch (error: Exception) { error }
            assertTrue(McpProtocolFailureClassifier.isUnauthorized(failure))
            assertEquals(h.access, exit.access)
        } }
    }

    /** Real factory, model adapter, Step loop, SDK and local service; checkpoints are captured, not Room commits. */
    private suspend fun verifyModelToolLoop(access: RealmAccess.Enterprise, state: EnterpriseState, capabilities: TurnMcpCapabilitySnapshot, manager: McpRuntimeCoordinator) {
        val artifacts = mockk<ArtifactStore>()
        coEvery { artifacts.retainForRequest(any(), any()) } answers {
            ArtifactReadLease(emptyMap(), { null }, ArtifactRetentionLease {})
        }
        val localTools = mockk<LocalTools>()
        every { localTools.getTools(any(), any(), any()) } returns emptyList()
        val providers = mockk<ProviderManager>()
        val outputStore = ToolOutputStore(artifacts)
        val factory = TurnToolSetFactory(localTools, mockk(), mockk(), mockk(), mockk(), manager, providers, artifacts, outputStore)
        val model = Model(modelId = "example")
        val runner = TurnRunner(artifactStore = artifacts, context = androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            providerManager = providers, json = Json, attachmentResolver = mockk(), toolOutputStore = outputStore)
        for (stream in listOf(false, true)) for (prompt in listOf("查看企业公告", "查询企业指南", "查看企业信息")) {
            val assistant = Assistant(enableMemory = false, streamOutput = stream, localTools = emptyList(), enableRecentChatsReference = false)
            val settings = Settings(providers = listOf(ProviderSetting.OpenAI(models = listOf(model))), assistants = listOf(assistant))
            val tools = factory.buildTools(access, assistant, settings = settings,
                configuration = net.weero.measix.pilot.test.testResolvedConfiguration(settings, access.scope, state),
                capabilityModel = model, mcpCapabilities = capabilities)
            val capture = TurnRunCapture()
            val inputs = turnRunInputsFixture(Uuid.random(), settings, model, RequestMediaCapabilities.NONE,
                listOf(UIMessage.user(prompt)), assistant, tools = tools, maxSteps = 4, capture = capture)
            val lease = ModelExecutionLease { it(net.weero.measix.pilot.test.exampleModelTarget) }
            try {
                runner.run(inputs.copy(turnContext = inputs.turnContext.copy(realmAccess = access,
                    model = inputs.turnContext.model.copy(requests = lease))))
                val outcome = capture.result
                assertTrue("$prompt stream=$stream outcome=$outcome", outcome is TurnOutcome.Completed)
                val message = (outcome as TurnOutcome.Completed).assistantMessage!!
                val executed = message.getTools()
                assertEquals(if (prompt == "查看企业信息") 1 else 2, executed.size)
                assertTrue(executed.all { it.hasReplayResult && it.localCallId != Uuid.NIL && it.stepId != Uuid.NIL })
                assertEquals(executed.size + 1, message.parts.filterIsInstance<UIMessagePart.Step>().size)
                if (executed.size == 2) {
                    assertTrue(executed[0].toolName.endsWith("__discover_tools"))
                    assertTrue(executed[1].toolName.endsWith("__invoke_tool"))
                    assertNotNull(executed[1].metadata?.get("com.measix/resolvedTool"))
                    assertFalse(message.toText().contains("toolRef"))
                }
                assertTrue(message.toText().contains("已通过企业工具"))
            } finally { lease.release() }
        }
        io.mockk.verify { providers wasNot io.mockk.Called }
    }

    private suspend fun withHarness(block: suspend (Harness) -> Unit) = withTimeout(20_000) {
        val sessions = EnterpriseSessionController(EnterpriseAppliedStore(temporary.newFolder())) { now }
        val sourceRoot = temporary.newFolder()
        val source = LocalEnterpriseSource(
            { requireNotNull(javaClass.getResourceAsStream("/${LocalEnterpriseSource.EXAMPLE_ASSET}")) }, sessions,
            LocalEnrollmentAuthority(sourceRoot, { now }),
            { requireNotNull(javaClass.getResourceAsStream("/${LocalEnterpriseSource.IDENTITY_ASSET}")) },
            LocalEnterpriseConfigurationStore(sourceRoot), { now },
        )
        val enrolled = source.enrollExample()
        val session = requireNotNull(enrolled.manifest.session)
        val access = RealmAccess.Enterprise(session.identity.scope, session.id)
        val http = LocalEnterpriseMcpService(source, sessions, { now }).createClient()
        try { block(Harness(source, sessions, access, http)) } finally { http.close() }
    }

    private class Harness(val source: LocalEnterpriseSource, val sessions: EnterpriseSessionController, val access: RealmAccess.Enterprise, val http: HttpClient) {
        suspend fun connect(gateway: Boolean = true, block: suspend (Client, McpConnectionDefinition.Managed) -> Unit) {
            val bindings = sessions.captureBindings(access)
            val config = (sessions.state.value as EnterpriseState.Available).configuration!!
            val resource = if (gateway) config.gateways.single().id else config.mcpServers.first().id
            val name = if (gateway) config.gateways.single().name else config.mcpServers.first().name
            val target = McpConnectionDefinition.Managed(access,
                me.rerere.common.configuration.ConfigurationReference.Enterprise(access.scope.authority, resource), name,
                bindings.binding(resource), bindings.version, "int_${Uuid.random()}", if (gateway) config.gateways.single().surface else null)
            val factory = McpProtocolClientFactory(createHttpClient = { error("local service must not open user HTTP") },
                createManagedHttpClient = { error("local service must not open network HTTP") }, createLocalHttpClient = { http })
            val client = factory.createClient(target)
            val transport = factory.createTransport(target)
            try { client.connect(transport); block(client, target) }
            finally { try { client.close() } finally { transport.close(); bindings.release() } }
        }
    }
    private suspend fun Client.call(name: String, args: String): CallToolResult =
        callTool(CallToolRequest(CallToolRequestParams(name, Json.parseToJsonElement(args).jsonObject)))
    private suspend fun Client.invoke(ref: String, args: JsonObject = buildJsonObject { }): CallToolResult =
        callTool(CallToolRequest(CallToolRequestParams("invoke_tool", buildJsonObject { put("toolRef", ref); put("arguments", args) })))
    private suspend fun Client.discoverRef(): String = call("discover_tools", """{"queries":["公告"]}""")
        .structuredContent!!["results"]!!.jsonArray.single().jsonObject["matches"]!!.jsonArray.single().jsonObject["toolRef"]!!.jsonPrimitive.content
    private fun assertCode(expected: String, result: CallToolResult) {
        assertTrue(result.isError == true)
        assertEquals(expected, result.structuredContent!!["code"]!!.jsonPrimitive.content)
    }
}
