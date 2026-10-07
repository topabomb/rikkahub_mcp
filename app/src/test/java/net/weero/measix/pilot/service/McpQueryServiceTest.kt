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
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.Assert.assertEquals
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.data.ai.mcp.*
import net.weero.measix.pilot.data.configuration.ConfigurationResolver
import net.weero.measix.pilot.data.configuration.appliedConfiguration
import net.weero.measix.pilot.data.datastore.UserSettingsDocument
import net.weero.measix.pilot.data.datastore.ExecutionConfigurationSnapshot
import me.rerere.common.configuration.ConfigurationReference

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class McpQueryServiceTest {
    @Test fun `managed query and assistant picker show only effective reviewed subset and retain missing reasons`() {
        val original = exampleEnterprisePackage()
        val first = original.configuration.mcpServers.first()
        val read = McpCatalogTool("read", inputSchema = buildJsonObject { put("type", "object") })
        val changed = McpCatalogTool("changed", inputSchema = read.inputSchema)
        val grant = PlatformMcpToolGrant("read", read.contractHash(), PlatformMcpToolGrantApprovalPolicy.AUTO)
        val resource = first.copy(toolAccessMode = PlatformMcpDefinitionToolAccessMode.ALLOWLIST,
            allowedTools = listOf(grant, grant.copy(name = "changed", approvalPolicy = PlatformMcpToolGrantApprovalPolicy.REQUIRE_CONFIRMATION)))
        val definition = original.configuration.assistants.first()
        val binding = PlatformAssistantMcpBinding(resource.id, PlatformAssistantMcpBindingToolSelection.ALLOWLIST, listOf("read"))
        val packet = original.copy(configuration = original.configuration.copy(
            mcpServers = original.configuration.mcpServers.map { if (it.id == first.id) resource else it },
            assistants = original.configuration.assistants.map { if (it.id == definition.id) it.copy(mcpBindings = listOf(binding)) else it }))
        val document = UserSettingsDocument.empty()
        val resolved = ConfigurationResolver.resolve(document, packet.identity.scope, appliedConfiguration(packet))
        val access = RealmAccess.Enterprise(packet.identity.scope, "query")
        val id = packet.identity.reference(resource.id)
        val snapshot = ExecutionConfigurationSnapshot(document.personalSettings(), resolved, "user")
        val catalog = McpCatalogSnapshot(access.scope, id, 1, "definition", "catalog", listOf(read, changed,
            McpCatalogTool("unapproved", inputSchema = read.inputSchema)), McpManagedCatalog(packet.configuration.generation))
        val capability = McpRuntimeCapability(McpStatus.Ready(3, 1), catalog, true)
        val row = snapshot.mcpPresentations(access, mapOf(id to McpCatalogCapability(catalog, listOf(capability)))).single { it.serverId == id }
        assertEquals(listOf("read", "changed"), row.tools.map { it.name })
        assertEquals(1, row.tools.count { it.enabled })
        assertEquals(McpToolUnavailableReason.CONTRACT_CHANGED, row.tools.last().unavailableReason)
        assertTrue(row.tools.last().needsApproval)
        assertEquals(false, row.allowsAllTools)
        val target = ConversationAssistantTarget(ConversationCommandTarget(kotlin.uuid.Uuid.random(), RealmSelection(access, 1)) {},
            packet.identity.reference(definition.id))
        val choice = resolved.conversationConfiguration(target).mcpChoices(listOf(row)).single { it.serverId == id }
        assertEquals(listOf("read"), choice.tools.map { it.name })
        assertFalse(choice.canToggle)
        assertEquals(false, choice.selectsAllTools)
        val noDirectory = snapshot.mcpPresentations(access, emptyMap()).single { it.serverId == id }
        assertEquals(McpToolUnavailableReason.DIRECTORY_UNAVAILABLE, noDirectory.tools.first().unavailableReason)
        val missing = snapshot.mcpPresentations(access, mapOf(id to McpCatalogCapability(catalog.copy(tools = emptyList()), listOf(capability))))
            .single { it.serverId == id }
        assertTrue(missing.directoryConfirmed)
        assertTrue(missing.tools.all { !it.enabled && it.unavailableReason == McpToolUnavailableReason.MISSING })
        fun present(vararg connections: McpRuntimeCapability) = snapshot.mcpPresentations(access,
            mapOf(id to McpCatalogCapability(catalog, connections.toList()))).single { it.serverId == id }
        val idle = present()
        assertEquals(McpStatus.Idle, idle.status)
        assertTrue(idle.directoryConfirmed)
        assertFalse(idle.sessionCallable)
        val ready = present(capability, capability)
        assertEquals(capability.status, ready.status)
        assertTrue(ready.sessionCallable)
        assertFalse(ready.connectionsPartiallyReady)
        val failure = capability.copy(status = McpStatus.Error("HTTP 403", "Original HTTP 403 cause and stack"), sessionCallable = false)
        val partial = present(capability, failure)
        assertTrue(partial.connectionsPartiallyReady)
        assertEquals((failure.status as McpStatus.Error).detail, partial.connectionDiagnostic)
        assertEquals(partial, present(failure, capability))
        val partialChoice = resolved.conversationConfiguration(target).mcpChoices(listOf(partial)).single { it.serverId == id }
        assertTrue(partialChoice.connectionsPartiallyReady)
        assertEquals(partial.connectionDiagnostic, partialChoice.connectionDiagnostic)
        val retry = capability.copy(status = McpStatus.RetryScheduled(1, 3, 5_000), sessionCallable = false)
        assertEquals(retry.status, present(retry, retry.copy(status = McpStatus.RetryScheduled(1, 3, 10_000))).status)
        assertEquals(McpStatus.Connecting, present(retry, capability.copy(status = McpStatus.Connecting, sessionCallable = false)).status)
        assertEquals(failure.status, present(failure, retry).status)
        assertFalse(present(failure, retry).sessionCallable)
    }

    @Test
    fun `catalog identity mismatch is an error rather than fabricated discovery`() {
        val server = McpServerConfig.StreamableHTTPServer(
            commonOptions = McpCommonOptions(name = "measurement-server"),
            url = "https://example.test/mcp",
        )
        val presentation = server.toPresentation(
            runtime = net.weero.measix.pilot.data.ai.mcp.McpRuntimeCapability(
                status = McpStatus.Ready(toolCount = 1, catalogRevision = 1L),
                sessionCallable = true,
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
        assertFalse(presentation.isCallable)
    }
    @Test
    fun `scoped catalog recovers after a failed read and observes private revisions without editable managed copies`() = kotlinx.coroutines.test.runTest {
        val packet = net.weero.measix.pilot.data.enterprise.exampleEnterprisePackage()
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
            assertTrue(seen.single() is McpCatalogReadState.Failed)
            org.junit.Assert.assertEquals("read_failed", (seen.single() as McpCatalogReadState.Failed).error.message)
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
            assertTrue(managed.none { it.gatewayEnablement != null })
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
            val originalSelection = requireNotNull(query.catalog.value).selection
            val readFailure = java.io.IOException("configuration observation ended")
            io.mockk.every { queries.observe(scope) } returns kotlinx.coroutines.flow.flow { throw readFailure }
            query.retryCatalog(originalSelection)
            runCurrent()
            val reported = (query.catalog.value?.content as McpCatalogReadState.Failed).error
            org.junit.Assert.assertEquals(readFailure.javaClass, reported.javaClass)
            org.junit.Assert.assertEquals(readFailure.message, reported.message)
            io.mockk.every { queries.observe(scope) } returns kotlinx.coroutines.flow.MutableStateFlow(resolved)
            query.retryCatalog(originalSelection)
            runCurrent()
            assertTrue(query.catalog.value?.content is McpCatalogReadState.Available)
            firstView.cancel()
            runCurrent()
            org.junit.Assert.assertNull(query.catalog.value)

            val personalResolved = net.weero.measix.pilot.data.configuration.ConfigurationResolver.resolve(document, personal.scope, enterprise)
            val personalSnapshot = net.weero.measix.pilot.data.datastore.ExecutionConfigurationSnapshot(document.personalSettings(), personalResolved, "user")
            io.mockk.every { queries.observe(personal.scope) } returns kotlinx.coroutines.flow.MutableStateFlow(personalResolved)
            io.mockk.coEvery { queries.readExecution(personal) } returns personalSnapshot
            io.mockk.coEvery { coordinator.readCatalogCapabilities(personal, personalSnapshot) } returns emptyMap()
            selected.value = net.weero.measix.pilot.data.enterprise.RealmSelection(personal, 2)
            try {
                query.retryCatalog(originalSelection)
                org.junit.Assert.fail("old selection must not restart a replacement realm's query")
            } catch (_: IllegalStateException) { }
            val resumed = mutableListOf<McpCatalogUiModel?>()
            val secondView = backgroundScope.launch { query.catalog.collect { resumed += it } }
            runCurrent()
            assertTrue(resumed.filterNotNull().all { it.selection.access == personal && it.servers.all { row -> row.definition != null } })
            org.junit.Assert.assertEquals(personal, query.catalog.value?.selection?.access)
            secondView.cancel()
        } finally { appScope.cancel(); runCurrent() }
    }

}
