package net.weero.measix.pilot.data.ai.mcp

import net.weero.measix.pilot.data.configuration.ConfigurationScope

import me.rerere.common.configuration.ConfigurationReference

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsResult
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import io.modelcontextprotocol.kotlin.sdk.types.ToolListChangedNotification
import io.modelcontextprotocol.kotlin.sdk.types.Method.Defined.NotificationsToolsListChanged
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.datastore.EffectiveSettingsSnapshot
import net.weero.measix.pilot.data.datastore.ManagedConfigurationState
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsAccessIndex
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.files.ArtifactStore
import org.junit.After
import org.junit.Before

/**
 * 用受控 transport/client seam 驱动真实 [McpRuntimeCoordinator] 状态机，覆盖 slot 互斥、
 * generation 失效与 reconcile 收敛行为；不复制生产逻辑。
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal abstract class McpRuntimeCoordinatorTestBase {
    protected val dispatcher = StandardTestDispatcher()
    protected val effective = MutableStateFlowHolder()
    protected val createdClients = mutableListOf<Client>()
    protected val createdTransports = mutableListOf<FakeTransport>()
    protected var connectFailure: Throwable? = null
    protected val connectGates = mutableMapOf<ConfigurationReference, CompletableDeferred<Unit>>()
    protected val connectStarted = linkedSetOf<ConfigurationReference>()
    protected val toolListChangedHandlers = mutableMapOf<ConfigurationReference, (ToolListChangedNotification) -> Deferred<Unit>>()
    protected var callToolGate: CompletableDeferred<Unit>? = null
    protected var callToolResponder: suspend () -> CallToolResult = {
        CallToolResult(content = listOf(TextContent("tool-result")))
    }
    protected var listToolsResponder: suspend (McpServerConfig, ListToolsRequest) -> ListToolsResult = { _, _ ->
        ListToolsResult(tools = listOf(serverTool("search")))
    }
    protected val settingsStore = mockk<SettingsStore>()
    protected val sessions = mockk<net.weero.measix.pilot.data.enterprise.EnterpriseSessionController>()
    protected val catalogs = MutableStateFlow<Map<McpCatalogKey, McpCatalogSnapshot>>(emptyMap())
    protected val catalogStore = mockk<McpCatalogStore>()
    protected lateinit var networkOnline: MutableStateFlow<Boolean>
    protected var foregroundAction: (() -> Unit)? = null
    protected lateinit var manager: McpRuntimeCoordinator
    protected lateinit var oauthCoordinator: McpOAuthCoordinator

    @Before
    fun setUp() {
        catalogs.value = emptyMap()
        connectGates.clear()
        connectStarted.clear()
        toolListChangedHandlers.clear()
        callToolGate = null
        foregroundAction = null
        callToolResponder = { CallToolResult(content = listOf(TextContent("tool-result"))) }
        listToolsResponder = { _, _ -> ListToolsResult(tools = listOf(serverTool("search"))) }
        effective.snapshot = snapshotOf(emptyList())
        stubMcpUserDefinitions(settingsStore, effective.flow)
        every { catalogStore.catalogs } returns catalogs
        coEvery { catalogStore.awaitReady() } returns Unit
        coEvery { catalogStore.commitCandidate(any()) } coAnswers {
            val candidate = firstArg<McpCatalogCandidate>()
            val previous = catalogs.value[candidate.key]
            if (candidate.tools.isEmpty()) {
                McpCatalogCommitResult.RejectedEmpty(
                    previous?.takeIf { it.definitionDigest == candidate.definitionDigest }
                )
            } else {
                val snapshot = McpCatalogSnapshot(candidate.scope,
                    serverId = candidate.serverId,
                    revision = (previous?.revision ?: 0L) + 1,
                    definitionDigest = candidate.definitionDigest,
                    catalogDigest = candidate.tools.joinToString { it.name },
                    tools = candidate.tools,
                )
                catalogs.value = catalogs.value + (candidate.key to snapshot)
                McpCatalogCommitResult.Committed(snapshot, previous, snapshot.revision)
            }
        }
        coEvery { catalogStore.rollbackCommitted(any(), any(), any()) } coAnswers {
            val committed = firstArg<McpCatalogSnapshot>()
            val previous = secondArg<McpCatalogSnapshot?>()
            if (catalogs.value[committed.key] == committed) {
                catalogs.value = if (previous == null) {
                    catalogs.value - committed.key
                } else {
                    catalogs.value + (committed.key to previous)
                }
            }
        }
        coEvery { catalogStore.remove(any()) } returns Unit
        coEvery { settingsStore.updateLocal(any()) } coAnswers {
            val transform = firstArg<(Settings) -> Settings>()
            val next = transform(effective.snapshot.settings)
            effective.publish(next)
            next
        }
        networkOnline = MutableStateFlow(true)
        val networkMonitor = mockk<NetworkMonitor>(relaxed = true)
        every { networkMonitor.isOnline } returns networkOnline
        oauthCoordinator = McpOAuthCoordinator(
            settingsStore = settingsStore,
            appScope = AppScope(dispatcher),
            oauthClient = mockk(relaxed = true),
            oauthCallbackKeepAlive = NoOpOAuthCallbackKeepAlive,
            ioDispatcher = dispatcher,
            logger = { _, _ -> },
        )
        manager = McpRuntimeCoordinator(
            sessions = sessions,
            localMcp = io.mockk.mockk(),
            synchronization = io.mockk.mockk(),
            settingsStore = settingsStore,
            catalogStore = catalogStore,
            appScope = AppScope(dispatcher),
            artifactStore = mockk<ArtifactStore>(relaxed = true),
            networkMonitor = networkMonitor,
            foregroundObserver = ForegroundObserver { },
            ioDispatcher = dispatcher,
            transportOverride = { FakeTransport().also(createdTransports::add) },
            clientOverride = { config -> fakeClient(config) },
            oauthCallbackKeepAlive = NoOpOAuthCallbackKeepAlive,
            retryJitter = { it },
        )
    }

    @After
    fun tearDown() {
        connectFailure = null
    }

    protected fun fakeClient(config: McpServerConfig): Client {
        val client = mockk<Client>(relaxed = true)
        val notificationHandler = slot<(ToolListChangedNotification) -> Deferred<Unit>>()
        every {
            client.setNotificationHandler<ToolListChangedNotification>(
                NotificationsToolsListChanged,
                capture(notificationHandler),
            )
        } answers {
            toolListChangedHandlers[config.id] = notificationHandler.captured
        }
        val transportSlot = io.mockk.slot<AbstractTransport>()
        coEvery { client.connect(capture(transportSlot)) } coAnswers {
            connectStarted += config.id
            connectGates[config.id]?.await()
            connectFailure?.let { throw it }
        }
        every { client.transport } answers { if (transportSlot.isCaptured) transportSlot.captured else null }
        every { client.serverCapabilities } returns ServerCapabilities(
            tools = ServerCapabilities.Tools(),
        )
        coEvery { client.close() } returns Unit
        coEvery { client.listTools(any()) } coAnswers {
            val wire = McpCatalogWire()
            val request = io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest(method = "tools/list")
            wire.bind(request)
            listToolsResponder(config, firstArg()).also { result ->
                wire.decode(io.modelcontextprotocol.kotlin.sdk.types.McpJson.encodeToString(
                    io.modelcontextprotocol.kotlin.sdk.types.JSONRPCResponse(request.id, result)
                ))
            }
        }
        coEvery {
            client.callTool(any<CallToolRequest>(), any<RequestOptions>())
        } coAnswers {
            callToolGate?.await()
            callToolResponder()
        }
        createdClients.add(client)
        return client
    }

    protected fun snapshotOf(servers: List<McpServerConfig>): EffectiveSettingsSnapshot =
        EffectiveSettingsSnapshot(
            settings = Settings(mcpServers = servers),
            access = SettingsAccessIndex(),
            revision = effective.revision.incrementAndGet(),
            managedState = ManagedConfigurationState.ABSENT,
        )

    protected fun emit(servers: List<McpServerConfig>) {
        effective.snapshot = snapshotOf(servers)
    }

    protected fun serverConfig(
        url: String = "https://example.com/mcp",
        enable: Boolean = true,
        oauth: McpOAuthState? = null,
        policies: List<McpToolPolicy> = emptyList(),
        headers: List<Pair<String, String>> = emptyList(),
    ) = McpServerConfig.StreamableHTTPServer(
        id = SERVER_ID,
        commonOptions = McpCommonOptions(
            enable = enable,
            name = "demo",
            headers = headers,
            oauth = oauth,
            toolPolicies = policies,
        ),
        url = url,
    )

    protected fun serverTool(name: String) = Tool(
        name = name,
        inputSchema = ToolSchema(properties = JsonObject(emptyMap())),
    )

    companion object {
        val SERVER_ID = ConfigurationReference.random()
        val CATALOG_KEY = McpCatalogKey(ConfigurationScope.Personal, SERVER_ID)
    }
}

internal class FakeTransport : AbstractTransport() {
    override suspend fun start() = Unit
    override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) = Unit
    var closeCalls = 0
    var closeAction: suspend () -> Unit = {}
    override suspend fun close() {
        closeCalls++
        closeAction()
    }

    fun simulateClose() = invokeOnCloseCallback()
    fun simulateError(error: Throwable) {
        _onError(error)
    }
}

/** 快照容器：revision 只增不减，保证 collector 每次写入都收到新值。 */
internal class MutableStateFlowHolder {
    val revision = java.util.concurrent.atomic.AtomicLong(0)
    private val _flow = kotlinx.coroutines.flow.MutableStateFlow(
        EffectiveSettingsSnapshot(
            settings = Settings(),
            access = SettingsAccessIndex(),
            revision = 0,
            managedState = ManagedConfigurationState.ABSENT,
        ),
    )
    val flow: kotlinx.coroutines.flow.StateFlow<EffectiveSettingsSnapshot> = _flow

    var snapshot: EffectiveSettingsSnapshot
        get() = _flow.value
        set(value) {
            _flow.value = value
        }

    fun publish(settings: Settings) {
        snapshot = snapshot.copy(settings = settings, revision = revision.incrementAndGet())
    }
}

/** Test-only status projection; production exposes only the atomic runtime capability map. */
internal val McpRuntimeCoordinator.syncingStatus: TestStatusSnapshot
    get() = TestStatusSnapshot(runtimeCapabilities.value.mapKeys { it.key.serverId }.mapValues { it.value.status })

internal data class TestStatusSnapshot(val value: Map<ConfigurationReference, McpStatus>)

internal fun stubMcpUserDefinitions(store: SettingsStore, definitions: StateFlow<EffectiveSettingsSnapshot>) {
    every { store.userMcpDefinitions } returns definitions.map { it.settings.mcpServers }
    coEvery { store.withUserMcpDefinitions<Any?>(any()) } coAnswers {
        firstArg<suspend (List<McpServerConfig>) -> Any?>().invoke(definitions.value.settings.mcpServers)
    }
}
