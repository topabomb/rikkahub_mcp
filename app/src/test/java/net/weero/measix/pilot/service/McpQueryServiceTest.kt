package net.weero.measix.pilot.service

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.cancel

import net.weero.measix.pilot.data.configuration.ConfigurationScope

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.weero.measix.pilot.data.ai.mcp.McpCatalogSnapshot
import net.weero.measix.pilot.data.ai.mcp.McpCatalogTool
import net.weero.measix.pilot.data.ai.mcp.McpCommonOptions
import net.weero.measix.pilot.data.ai.mcp.McpServerConfig
import net.weero.measix.pilot.data.ai.mcp.McpStatus
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class McpQueryServiceTest {
    @Test
    fun `catalog identity mismatch is an error rather than fabricated discovery`() {
        val server = McpServerConfig.StreamableHTTPServer(
            commonOptions = McpCommonOptions(name = "measurement-server"),
            url = "https://example.test/mcp",
        )
        val presentation = server.toPresentation(
            runtime = net.weero.measix.pilot.data.ai.mcp.McpRuntimeCapability(
                status = McpStatus.Ready(toolCount = 1, catalogRevision = 1L),
                catalog = McpCatalogSnapshot(ConfigurationScope.Personal,
                serverId = server.id,
                revision = 1L,
                definitionDigest = "wrong-definition",
                catalogDigest = "catalog",
                tools = listOf(
                    McpCatalogTool(
                        name = "measure",
                        inputSchema = buildJsonObject { put("type", "object") },
                    )
                ),
                ),
            ),
        )

        assertTrue(presentation.status is McpStatus.Error)
        assertTrue(presentation.tools.isEmpty())
    }
    @Test
    fun `scoped catalog recovers after a failed read and observes private revisions without editable managed copies`() = kotlinx.coroutines.test.runTest {
        val packet = net.weero.measix.pilot.data.enterprise.EnterprisePackageCodec.decode(
            requireNotNull(javaClass.getResourceAsStream("/enterprise.local.example.json")))
        val scope = packet.identity.scope
        val access = net.weero.measix.pilot.data.enterprise.RealmAccess.Enterprise(scope, "query")
        val manifest = net.weero.measix.pilot.data.enterprise.EnterpriseManifest.signedOut().copy(
            phase = net.weero.measix.pilot.data.enterprise.EnterpriseSessionPhase.READY,
            session = net.weero.measix.pilot.data.enterprise.EnterpriseSession("query", packet.identity, Long.MAX_VALUE),
            applied = net.weero.measix.pilot.data.enterprise.EnterpriseAppliedVersion("initial", packet.configuration.generation, "config", "binding"),
            selectedScope = scope,
        )
        val enterprise = net.weero.measix.pilot.data.enterprise.EnterpriseState.Available(manifest,
            packet.configuration.copy(policy = packet.configuration.policy.copy(allowLocalMcp = false),
                gateways = packet.configuration.gateways.map { it.copy(enablement = net.weero.measix.pilot.data.configuration.GatewayEnablementPolicy.REQUIRED) }))
        val states = kotlinx.coroutines.flow.MutableStateFlow<net.weero.measix.pilot.data.enterprise.EnterpriseState>(enterprise)
        val sessions = io.mockk.mockk<net.weero.measix.pilot.data.enterprise.EnterpriseSessionController>()
        io.mockk.every { sessions.state } returns states
        val server = McpServerConfig.StreamableHTTPServer(commonOptions = McpCommonOptions(name = "Shared"), url = "https://example.test/mcp")
        val document = net.weero.measix.pilot.data.datastore.UserSettingsDocument.empty().copy(
            configuration = net.weero.measix.pilot.data.datastore.UserConfiguration(mcpServers = listOf(server)))
        val resolved = net.weero.measix.pilot.data.configuration.ConfigurationResolver.resolve(document, scope, enterprise)
        val snapshot = net.weero.measix.pilot.data.datastore.ExecutionConfigurationSnapshot(document.personalSettings(), resolved, "user")
        val queries = io.mockk.mockk<ConfigurationQueryService>()
        io.mockk.every { queries.observe(scope) } returns kotlinx.coroutines.flow.MutableStateFlow(resolved)
        var failing = true
        io.mockk.coEvery { queries.readExecution(access) } coAnswers {
            check(!failing) { "read_failed" }
            snapshot
        }
        val coordinator = io.mockk.mockk<net.weero.measix.pilot.data.ai.mcp.McpRuntimeCoordinator>()
        io.mockk.every { coordinator.runtimeCapabilities } returns kotlinx.coroutines.flow.MutableStateFlow(emptyMap())
        io.mockk.every { coordinator.catalogs } returns kotlinx.coroutines.flow.MutableStateFlow(emptyMap())
        io.mockk.coEvery { coordinator.readCatalogCapabilities(access, snapshot) } returns emptyMap()
        val appScope = net.weero.measix.pilot.AppScope(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        try {
            val query = McpQueryService(io.mockk.mockk(), coordinator, queries, sessions, appScope)
            val seen = mutableListOf<McpCatalogReadState>()
            val collector = backgroundScope.launch { query.observe(access).collect { seen += it } }
            runCurrent()
            org.junit.Assert.assertEquals(listOf(McpCatalogReadState.Unavailable), seen)
            failing = false
            states.value = enterprise.copy(manifest = manifest.copy(applied = net.weero.measix.pilot.data.enterprise.EnterpriseAppliedVersion("rotated", packet.configuration.generation, "config", "binding2")))
            runCurrent()
            val rows = (seen.last() as McpCatalogReadState.Available).servers
            val user = rows.single { it.serverId == server.id }
            org.junit.Assert.assertEquals(server, user.definition)
            org.junit.Assert.assertEquals(access, user.access)
            org.junit.Assert.assertEquals(net.weero.measix.pilot.data.configuration.ConfigurationUnavailableReason.USER_CATEGORY_NOT_ALLOWED, user.unavailableReason)
            val managed = rows.filter { it.serverId is me.rerere.common.configuration.ConfigurationReference.Enterprise }
            assertTrue(managed.isNotEmpty() && managed.all { it.definition == null && it.scope == scope })
            assertTrue(managed.filter { it.gatewayEnablement == null }.all { it.requiredEnabled })
            org.junit.Assert.assertEquals(net.weero.measix.pilot.data.configuration.ResolvedGatewayEnablement(true, false), managed.single { it.gatewayEnablement != null }.gatewayEnablement)
            collector.cancel()

            val personal = net.weero.measix.pilot.data.enterprise.RealmAccess.Personal
            val selected = kotlinx.coroutines.flow.MutableStateFlow<net.weero.measix.pilot.data.enterprise.RealmSelection?>(
                net.weero.measix.pilot.data.enterprise.RealmSelection(access, 1))
            io.mockk.every { sessions.observeSelectedRealmSelection() } returns selected
            io.mockk.coEvery { queries.requireAccess(personal) } returns Unit
            io.mockk.coEvery { sessions.withSelectedRealmSelection<Any?>(any(), any()) } coAnswers {
                check(firstArg<net.weero.measix.pilot.data.enterprise.RealmSelection>() == selected.value)
                secondArg<suspend () -> Any?>().invoke()
            }
            val firstView = backgroundScope.launch { query.catalog.collect {} }
            runCurrent()
            org.junit.Assert.assertEquals(access, query.catalog.value?.selection?.access)
            firstView.cancel()
            runCurrent()
            org.junit.Assert.assertNull(query.catalog.value)

            val personalResolved = net.weero.measix.pilot.data.configuration.ConfigurationResolver.resolve(document, personal.scope, enterprise)
            val personalSnapshot = net.weero.measix.pilot.data.datastore.ExecutionConfigurationSnapshot(document.personalSettings(), personalResolved, "user")
            io.mockk.every { queries.observe(personal.scope) } returns kotlinx.coroutines.flow.MutableStateFlow(personalResolved)
            io.mockk.coEvery { queries.readExecution(personal) } returns personalSnapshot
            io.mockk.coEvery { coordinator.readCatalogCapabilities(personal, personalSnapshot) } returns emptyMap()
            selected.value = net.weero.measix.pilot.data.enterprise.RealmSelection(personal, 2)
            val resumed = mutableListOf<McpCatalogUiModel?>()
            val secondView = backgroundScope.launch { query.catalog.collect { resumed += it } }
            runCurrent()
            assertTrue(resumed.filterNotNull().all { it.selection.access == personal && it.servers.all { row -> row.definition != null } })
            org.junit.Assert.assertEquals(personal, query.catalog.value?.selection?.access)
            secondView.cancel()
        } finally { appScope.cancel(); runCurrent() }
    }

}
