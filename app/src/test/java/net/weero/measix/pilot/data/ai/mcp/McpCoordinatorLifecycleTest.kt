package net.weero.measix.pilot.data.ai.mcp

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.data.configuration.ConfigurationAccess
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.configuration.ResolvedConfiguration
import net.weero.measix.pilot.data.datastore.ExecutionConfigurationSnapshot
import net.weero.measix.pilot.data.enterprise.EnterpriseAppliedVersion
import net.weero.measix.pilot.data.enterprise.EnterpriseConfigurationException
import net.weero.measix.pilot.data.enterprise.EnterpriseExecutionLease
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionPhase
import net.weero.measix.pilot.data.enterprise.EnterpriseState
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.exampleEnterprisePackage
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.service.CapturedModelConfiguration
import net.weero.measix.pilot.service.runtime.ConversationRuntime
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

/** Drives realm-bound runtimes through the same Turn admission entry as production. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class McpCoordinatorLifecycleTest : McpRuntimeCoordinatorTestBase() {
    private var authorityRevoked = false
    private var admissionFailure: Exception? = null
    private val borrowerAssistant = Assistant(mcpServers = setOf(SERVER_ID))
    private val turnLeases = mutableListOf<McpExecutionLease>()

    @Test
    fun `enterprise preparation waits for durable initialization but not a connection when catalog exists`() = runTest(dispatcher) {
        val config = serverConfig()
        emit(listOf(config))
        val fixture = enterpriseFixture()
        val ready = CompletableDeferred<Unit>()
        coEvery { catalogStore.awaitReady() } coAnswers { ready.await() }
        connectGates[config.id] = CompletableDeferred()
        val preparing = async { fixture.prepare() }
        runCurrent()
        assertFalse(preparing.isCompleted)
        catalogs.value = mapOf(CATALOG_KEY to cachedCatalog(config))
        ready.complete(Unit)
        runCurrent()
        assertTrue("a confirmed catalog must not await slow enterprise connect", preparing.isCompleted)
        assertEquals(McpServerCapabilityState.READY, preparing.await().serverOutcomes.single().state)
        assertEquals(listOf("search"), preparing.await().tools.map { it.name })
        assertFalse(requireNotNull(manager.runtimeCapabilities.value[fixture.key]).sessionCallable)
        turnLeases.forEach { it.release() }
    }

    @Test
    fun `enterprise preparation still bounds missing catalog discovery`() = runTest(dispatcher) {
        val config = serverConfig()
        emit(listOf(config))
        val fixture = enterpriseFixture()
        connectGates[config.id] = CompletableDeferred()
        val start = testScheduler.currentTime
        val result = fixture.prepare()
        assertEquals(McpRuntimeCoordinator.TURN_CAPABILITY_PREPARE_TIMEOUT_MS, testScheduler.currentTime - start)
        assertEquals(McpServerCapabilityState.TIMEOUT, result.serverOutcomes.single().state)
        turnLeases.forEach { it.release() }
    }

    @Test
    fun `clearing user authorization revokes all enterprise borrowers and personal maintenance`() = runTest(dispatcher) {
        val config = serverConfig(oauth = McpOAuthState(enabled = true, accessToken = "fixture-token"))
        emit(listOf(config))
        val first = enterpriseFixture()
        val second = enterpriseFixture()
        first.prepare()
        second.prepare()
        manager.prepareTurnCapabilities(first.assistant)
        runCurrent()
        val connected = createdTransports.toList()
        assertEquals(3, connected.size)
        manager.clearAuthorization(SERVER_ID as me.rerere.common.configuration.ConfigurationReference.User)
        runCurrent()
        assertTrue("every transport using the revoked token is closed", connected.all { it.closeCalls > 0 })
        for (key in listOf(first.key, second.key, McpRuntimeKey(SERVER_ID))) {
            val capability = requireNotNull(manager.runtimeCapabilities.value[key])
            assertFalse(capability.sessionCallable)
            assertEquals(McpStatus.NeedsAuthorization, capability.status)
        }
        turnLeases.forEach { it.release() }
    }

    @Test
    fun `disabling user definition tears down enterprise borrower`() = runTest(dispatcher) {
        val config = serverConfig()
        emit(listOf(config))
        val fixture = enterpriseFixture()
        fixture.prepare()
        val old = createdTransports.single()
        emit(listOf(config.copy(commonOptions = config.commonOptions.copy(enable = false))))
        runCurrent()
        assertTrue(old.closeCalls > 0)
        assertNull(manager.runtimeCapabilities.value[fixture.key])
        turnLeases.forEach { it.release() }
    }

    @Test
    fun `deleting user definition tears down enterprise borrower`() = runTest(dispatcher) {
        emit(listOf(serverConfig()))
        val fixture = enterpriseFixture()
        fixture.prepare()
        val old = createdTransports.single()
        emit(emptyList())
        runCurrent()
        assertTrue(old.closeCalls > 0)
        assertNull(manager.runtimeCapabilities.value[fixture.key])
        turnLeases.forEach { it.release() }
    }

    @Test
    fun `changing user connection replaces enterprise borrower transport`() = runTest(dispatcher) {
        val config = serverConfig()
        emit(listOf(config))
        val fixture = enterpriseFixture()
        fixture.prepare()
        val old = createdTransports.single()
        emit(listOf(config.copy(url = "https://changed.example/mcp")))
        runCurrent()
        assertTrue(old.closeCalls > 0)
        assertTrue(requireNotNull(manager.runtimeCapabilities.value[fixture.key]).sessionCallable)
        assertTrue(createdTransports.size >= 2)
        turnLeases.forEach { it.release() }
    }

    @Test
    fun `revoked enterprise admission does not terminate subsequent network recovery`() = runTest(dispatcher) {
        emit(listOf(serverConfig()))
        val fixture = enterpriseFixture()
        fixture.prepare()
        manager.prepareTurnCapabilities(fixture.assistant)
        runCurrent()
        val enterpriseTransport = createdTransports.first()
        authorityRevoked = true
        networkOnline.value = false
        runCurrent()
        networkOnline.value = true
        runCurrent()
        assertTrue(enterpriseTransport.closeCalls > 0)
        assertNull(manager.runtimeCapabilities.value[fixture.key])
        // A second event exercises the original long-lived collector after the failed realm.
        networkOnline.value = false
        runCurrent()
        createdTransports.last().simulateClose()
        runCurrent()
        val before = createdTransports.size
        networkOnline.value = true
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue("personal recovery remains subscribed", createdTransports.size > before)
        assertTrue(requireNotNull(manager.runtimeCapabilities.value[McpRuntimeKey(SERVER_ID)]).sessionCallable)
        turnLeases.forEach { it.release() }
    }

    @Test
    fun `enterprise authority loss during reconnect delay retires original connection`() = runTest(dispatcher) {
        emit(listOf(serverConfig()))
        val fixture = enterpriseFixture()
        fixture.prepare()
        val transport = createdTransports.single()
        transport.simulateClose()
        runCurrent()
        assertTrue(manager.runtimeCapabilities.value.getValue(fixture.key).status is McpStatus.RetryScheduled)

        authorityRevoked = true
        advanceTimeBy(5_000)
        runCurrent()

        assertTrue("the reconnect worker must close its original transport", transport.closeCalls > 0)
        val capability = manager.runtimeCapabilities.value.getValue(fixture.key)
        assertFalse(capability.sessionCallable)
        assertTrue(capability.status.toString(), capability.status is McpStatus.Error)
        assertTrue(capability.status.toString().contains("enterprise_data_access_unavailable"))
        assertEquals(1, createdTransports.size)
        turnLeases.forEach { it.release() }
    }

    @Test
    fun `enterprise authority loss before notification refresh retires original connection`() = runTest(dispatcher) {
        emit(listOf(serverConfig()))
        val fixture = enterpriseFixture()
        fixture.prepare()
        val transport = createdTransports.single()
        toolListChangedHandlers.getValue(SERVER_ID).invoke(
            io.modelcontextprotocol.kotlin.sdk.types.ToolListChangedNotification(),
        )
        runCurrent()
        assertEquals(McpCatalogRefresh.Refreshing, manager.runtimeCapabilities.value.getValue(fixture.key).catalogRefresh)

        authorityRevoked = true
        advanceTimeBy(McpServerRuntimePolicy.CATALOG_REFRESH_DEBOUNCE_MS)
        runCurrent()

        assertTrue("the refresh worker must close its original transport", transport.closeCalls > 0)
        val capability = manager.runtimeCapabilities.value.getValue(fixture.key)
        assertFalse(capability.sessionCallable)
        assertTrue(capability.status.toString(), capability.status is McpStatus.Error)
        assertTrue(capability.status.toString().contains("enterprise_data_access_unavailable"))
        assertEquals(McpCatalogRefresh.Idle, capability.catalogRefresh)
        turnLeases.forEach { it.release() }
    }

    @Test
    fun `unexpected enterprise admission failure closes connection and preserves diagnostic`() = runTest(dispatcher) {
        emit(listOf(serverConfig()))
        val fixture = enterpriseFixture()
        fixture.prepare()
        val transport = createdTransports.single()
        admissionFailure = IllegalArgumentException("fixture definition decode detail")
        networkOnline.value = false
        runCurrent()
        networkOnline.value = true
        runCurrent()
        assertTrue(transport.closeCalls > 0)
        val capability = requireNotNull(manager.runtimeCapabilities.value[fixture.key])
        assertFalse(capability.sessionCallable)
        assertTrue(capability.status.toString(), capability.status is McpStatus.Error)
        assertTrue(capability.status.toString().contains("fixture definition decode detail"))
        admissionFailure = null
        turnLeases.forEach { it.release() }
    }

    private fun cachedCatalog(config: McpServerConfig) = McpCatalogSnapshot(
        scope = ConfigurationScope.Personal,
        serverId = config.id,
        revision = 1,
        definitionDigest = config.mcpDefinitionDigest(),
        catalogDigest = "fixture",
        tools = listOf(McpCatalogTool("search", inputSchema = JsonObject(emptyMap()))),
    )

    private fun enterpriseFixture(): EnterpriseFixture {
        val packet = exampleEnterprisePackage()
        val access = RealmAccess.Enterprise(packet.identity.scope, "fixture-session")
        val version = EnterpriseAppliedVersion("fixture-revision", packet.configuration.generation, "config", "execution")
        val assistant = borrowerAssistant
        val configuration = mockk<ResolvedConfiguration> {
            every { scope } returns access.scope
            every { enterpriseConfiguration } returns packet.configuration
            every { catalog } returns emptyMap()
            every { assistants } returns mapOf(assistant.id to assistant)
            every { access(any(), any()) } returns ConfigurationAccess(canEditDefinition = true)
        }
        val state = mockk<EnterpriseState.Available> {
            every { manifest.phase } returns EnterpriseSessionPhase.READY
            every { manifest.applied } returns version
        }
        every { sessions.requirePublishedRealmAccess(access) } answers {
            if (authorityRevoked) throw EnterpriseConfigurationException("enterprise_data_access_unavailable")
        }
        coEvery { sessions.withAppliedConfiguration<Any?>(access, any()) } coAnswers {
            admissionFailure?.let { throw it }
            if (authorityRevoked) throw EnterpriseConfigurationException("enterprise_data_access_unavailable")
            secondArg<suspend (EnterpriseState.Available) -> Any?>().invoke(state)
        }
        coEvery { settingsStore.withExecutionConfiguration<Any?>(access.scope, state, any()) } coAnswers {
            thirdArg<suspend (ExecutionConfigurationSnapshot) -> Any?>().invoke(
                ExecutionConfigurationSnapshot(effective.snapshot, configuration, "fixture-user-revision"),
            )
        }
        val binding = mockk<EnterpriseExecutionLease> {
            every { this@mockk.version } returns version
            coEvery { release() } returns Unit
        }
        coEvery { sessions.captureExecution(access, version) } returns binding
        val owner = mockk<ConversationRuntime>(relaxed = true) {
            every { durable.header.scope } returns access.scope
        }
        val lease = slot<McpExecutionLease>()
        every { owner.bindMcpExecution(any(), any(), any(), capture(lease)) } answers { turnLeases += lease.captured }
        val captured = CapturedModelConfiguration(effective.snapshot, configuration, assistant,
            mockk { every { enterpriseVersion } returns version }, 0, Uuid.random())
        return EnterpriseFixture(access, assistant, captured, owner)
    }

    private inner class EnterpriseFixture(
        val access: RealmAccess.Enterprise,
        val assistant: Assistant,
        val captured: CapturedModelConfiguration,
        val owner: ConversationRuntime,
    ) {
        val key = McpRuntimeKey(SERVER_ID, access, "int_${captured.interactionId}")
        suspend fun prepare() = manager.prepareTurnCapabilities(access, captured, owner, Uuid.random(), Job()) {}
    }
}
