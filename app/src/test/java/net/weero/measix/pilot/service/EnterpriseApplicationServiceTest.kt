package net.weero.measix.pilot.service

import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.configuration.GatewayEnablementPolicy
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.service.portal.PortalCloseReason
import net.weero.measix.pilot.service.portal.PortalDocument
import net.weero.measix.pilot.service.portal.PortalDocumentRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnterpriseApplicationServiceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    @Before fun installMain() { Dispatchers.setMain(main) }
    @After fun resetMain() { Dispatchers.resetMain(); main.close() }

    @Test(timeout = 30_000)
    fun `host shutdown exposes switching while the published and stored space remain unchanged`() = runBlocking(Dispatchers.Main) {
        fixture { f ->
            val original = f.selection()
            val originalManifest = f.store.readManifest()
            val portal = f.openBlockedPortal()
            val frames = Channel<EnterpriseOverview>(Channel.UNLIMITED)
            val observation = launch { f.service.observe().collect { frames.send(it) } }
            try {
                val initial = frames.receive()
                assertEquals(original, initial.selection)
                assertFalse(initial.switching)
                val pending = async { f.service.switchRealm(RealmSwitchRequest(original, RealmAccess.Personal)) }
                portal.host.entered.await()
                val busy = frames.receiveAsFlow().first { it.switching }
                assertEquals(original, busy.selection)
                assertEquals(EnterpriseSessionPhase.READY, busy.phase)
                assertEquals(originalManifest, (f.sessions.state.value as EnterpriseState.Available).manifest)
                assertEquals(originalManifest, f.store.readManifest())
                assertFalse(pending.isCompleted)
                assertTrue(portal.document.isClosed)

                portal.host.completed.complete(Unit)
                val selected = pending.await()
                val finished = frames.receiveAsFlow().first { !it.switching && it.selection?.access == RealmAccess.Personal }
                assertEquals(selected, finished.selection)
                assertEquals(ConfigurationScope.Personal, f.store.readManifest().selectedScope)
                assertEquals(originalManifest.session, f.store.readManifest().session)
                portal.document.awaitClosed()
            } finally {
                observation.cancelAndJoin()
                frames.close()
            }
        }
    }

    @Test(timeout = 30_000)
    fun `a previous selection cannot switch after leaving and returning to the same enterprise`() = runBlocking(Dispatchers.Main) {
        fixture { f ->
            val original = f.selection()
            val personal = f.service.switchRealm(RealmSwitchRequest(original, RealmAccess.Personal))
            val current = f.service.switchRealm(RealmSwitchRequest(personal, original.access))
            val manifest = f.store.readManifest()
            assertEquals(original.access, current.access)
            assertNotEquals(original.revision, current.revision)

            rejects("enterprise_selection_revoked") {
                f.service.switchRealm(RealmSwitchRequest(original, RealmAccess.Personal))
            }
            assertEquals(current, f.selection())
            assertEquals(manifest, f.store.readManifest())
        }
    }

    @Test(timeout = 30_000)
    fun `an unchanged personal selection cannot enter a replacement session through an old target`() = runBlocking(Dispatchers.Main) {
        fixture { f ->
            val original = f.selection()
            val personal = f.service.switchRealm(RealmSwitchRequest(original, RealmAccess.Personal))
            val identity = exampleEnterprisePackage().identity
            f.sessions.enrollLocal(identity, { identity }, { null })
            val replacement = f.store.readManifest()
            assertEquals(personal, f.selection())
            assertNotEquals((original.access as RealmAccess.Enterprise).sessionId, replacement.session?.id)

            rejects("enterprise_data_access_unavailable") {
                f.service.switchRealm(RealmSwitchRequest(personal, original.access))
            }
            assertEquals(personal, f.selection())
            assertEquals(replacement, f.store.readManifest())
        }
    }

    @Test(timeout = 30_000)
    fun `cancelling a switch waiter preserves the accepted application task and original host cleanup`() = runBlocking(Dispatchers.Main) {
        fixture { f ->
            val original = f.selection()
            val portal = f.openBlockedPortal()
            val request = RealmSwitchRequest(original, RealmAccess.Personal)
            val first = launch { f.service.switchRealm(request) }
            portal.host.entered.await()
            first.cancelAndJoin()
            assertTrue(first.isCancelled)
            assertEquals(original.access.scope, (f.sessions.state.value as EnterpriseState.Available).manifest.selectedScope)

            val second = async(start = CoroutineStart.UNDISPATCHED) { f.service.switchRealm(request) }
            assertFalse(second.isCompleted)
            portal.host.completed.complete(Unit)
            val selected = second.await()
            assertEquals(RealmAccess.Personal, selected.access)
            assertEquals(selected, f.selection())
            portal.document.awaitClosed()
            assertEquals(1, portal.host.closeCalls)
        }
    }

    @Test(timeout = 30_000)
    fun `local policy publication synchronizes without changing feed or session and stale edits cannot overwrite it`() = runBlocking(Dispatchers.Main) {
        fixture { f ->
            val original = f.service.localConfiguration(f.selection())
            val manifest = f.store.readManifest()
            val policy = original.policy.copy(allowLocalProviders = false, allowLocalTts = false,
                allowLocalAsr = false, allowLocalMcp = false, allowLocalAssistants = false)
            val changed = f.service.changeLocalConfiguration(original, LocalEnterpriseConfigurationChange.Policy(policy))
            assertTrue(changed.applied)
            assertEquals(original.generation + 1, changed.configuration.generation)
            assertEquals(policy, changed.configuration.policy)
            assertEquals(policy, (f.sessions.state.value as EnterpriseState.Available).configuration?.policy)
            assertEquals(manifest.feeds, f.store.readManifest().feeds)
            assertEquals(manifest.session, f.store.readManifest().session)
            rejects("local_enterprise_configuration_changed") {
                f.service.changeLocalConfiguration(original, LocalEnterpriseConfigurationChange.Policy(original.policy))
            }
            val gateway = changed.configuration.gateways.single()
            val required = f.service.changeLocalConfiguration(changed.configuration,
                LocalEnterpriseConfigurationChange.Gateway(gateway.id, GatewayEnablementPolicy.REQUIRED))
            assertTrue(required.applied)
            assertEquals(GatewayEnablementPolicy.REQUIRED, required.configuration.gateways.single().enablement)
            val personal = f.service.switchRealm(RealmSwitchRequest(f.selection(), RealmAccess.Personal))
            f.service.switchRealm(RealmSwitchRequest(personal, original.selection.access))
            rejects("enterprise_selection_revoked") {
                f.service.changeLocalConfiguration(required.configuration, LocalEnterpriseConfigurationChange.Policy(original.policy))
            }
            assertEquals(required.configuration.revision, f.source.candidate((original.selection.access as RealmAccess.Enterprise).scope)?.revision)
        }
    }

    @Test(timeout = 30_000)
    fun `model edits preserve stable ids private bindings and fixed references while invalid packages never publish`() = runBlocking(Dispatchers.Main) {
        fixture { f ->
            val original = f.service.localConfiguration(f.selection())
            val access = original.selection.access as RealmAccess.Enterprise
            val before = requireNotNull(f.source.candidate(access.scope))
            val referenced = before.packet.configuration.assistants.first().modelId
            rejects("invalid_assistant_model_reference") {
                f.service.changeLocalConfiguration(original, LocalEnterpriseConfigurationChange.DeleteModel(referenced))
            }
            assertEquals(before.revision, f.source.candidate(access.scope)?.revision)
            val added = f.service.changeLocalConfiguration(original, LocalEnterpriseConfigurationChange.AddExampleModel("Example B"))
            val newModel = added.configuration.models.single { it.id !in original.models.map { model -> model.id } }
            var current = f.service.changeLocalConfiguration(added.configuration, LocalEnterpriseConfigurationChange.RenameModel(newModel.id, "Renamed")).configuration
            assertEquals("Renamed", current.models.single { it.id == newModel.id }.name)
            current = f.service.changeLocalConfiguration(current, LocalEnterpriseConfigurationChange.ModelEnabled(newModel.id, false)).configuration
            assertFalse(current.models.single { it.id == newModel.id }.enabled)
            current = f.service.changeLocalConfiguration(current, LocalEnterpriseConfigurationChange.DeleteModel(newModel.id)).configuration
            val after = requireNotNull(f.source.candidate(access.scope))
            assertEquals(original.models, current.models)
            assertEquals(before.packet.runtimeBindings, after.packet.runtimeBindings)
            assertEquals(before.packet.configuration.assistants, after.packet.configuration.assistants)
            assertEquals(current.generation, f.store.readManifest().applied?.generation)
        }
    }

    @Test(timeout = 30_000)
    fun `failed client application reports published source pending and normal sync can retry without another generation`() = runBlocking(Dispatchers.Main) {
        fixture { f ->
            val original = f.service.localConfiguration(f.selection())
            val manifest = f.store.readManifest()
            f.failClientCommit = true
            val result = f.service.changeLocalConfiguration(original,
                LocalEnterpriseConfigurationChange.Policy(original.policy.copy(allowLocalMcp = false)))
            assertFalse(result.applied)
            assertEquals(original.generation + 1, result.configuration.generation)
            assertEquals(manifest, f.store.readManifest())
            f.failClientCommit = false
            f.service.synchronize(original.selection.access as RealmAccess.Enterprise)
            assertEquals(result.configuration.generation, f.store.readManifest().applied?.generation)
            assertEquals(result.configuration.revision, f.source.candidate(original.selection.access.scope)?.revision)
        }
    }

    @Test(timeout = 30_000)
    fun `joining an older in flight synchronization cannot claim the newly published generation is applied`() = runBlocking(Dispatchers.Main) {
        fixture { f ->
            val original = f.service.localConfiguration(f.selection())
            val access = original.selection.access as RealmAccess.Enterprise
            val oldCandidate = requireNotNull(f.source.candidate(access.scope))
            val captured = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val published = CompletableDeferred<Unit>()
            io.mockk.coEvery { f.source.candidate(access.scope) } coAnswers {
                captured.complete(Unit)
                release.await()
                oldCandidate
            }
            io.mockk.coEvery { f.source.changeConfiguration(access.scope, original.revision, any()) } coAnswers {
                val result = f.realSource.changeConfiguration(access.scope, original.revision, thirdArg())
                published.complete(Unit)
                result
            }
            val oldSync = async { f.synchronization.synchronize(access) }
            captured.await()
            val editing = async { f.service.changeLocalConfiguration(original,
                LocalEnterpriseConfigurationChange.Policy(original.policy.copy(allowLocalProviders = false))) }
            published.await()
            // Both callers are now eligible to join the same existing synchronization.
            yield()
            release.complete(Unit)
            oldSync.await()
            val result = editing.await()
            assertFalse(result.applied)
            assertEquals(original.generation + 1, result.configuration.generation)
            assertEquals(original.generation, f.store.readManifest().applied?.generation)
            val directory = LocalEnterpriseConfigurationStore(f.sourceRoot)
            assertEquals(result.configuration.generation, directory.installations()?.single()?.generation)
        }
    }

    private suspend fun rejects(reason: String, operation: suspend () -> Unit) {
        val failure = try { operation(); null } catch (error: EnterpriseConfigurationException) { error }
        assertEquals(reason, requireNotNull(failure) { "Expected rejected space switch" }.reason)
    }

    private suspend fun fixture(operation: suspend (Fixture) -> Unit) {
        val f = Fixture()
        try {
            f.sessions.enrollFixture(exampleEnterprisePackage())
            operation(f)
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                f.hosts.forEach { it.completed.complete(Unit) }
                f.documents.forEach { it.close(PortalCloseReason.HOST_DISPOSED) }
                f.scope.cancel()
                f.scope.coroutineContext[Job]?.join()
                f.documents.forEach { it.awaitClosed() }
            }
        }
    }

    private class BlockedHost {
        val entered = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Unit>()
        var closeCalls = 0
        fun close(): Deferred<Unit> {
            closeCalls++
            entered.complete(Unit)
            return completed
        }
    }

    private data class BlockedPortal(val document: PortalDocument, val host: BlockedHost)

    private inner class Fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var failClientCommit = false
        val store = EnterpriseAppliedStore(temporary.newFolder()) { if (failClientCommit) throw java.io.IOException("injected client failure") }
        val sessions = EnterpriseSessionController(store) { 1000L }
        val portals = PortalDocumentRegistry()
        val sourceRoot = temporary.newFolder()
        val realSource = LocalEnterpriseSource(
            { requireNotNull(javaClass.getResourceAsStream("/${LocalEnterpriseSource.EXAMPLE_ASSET}")) }, sessions,
            LocalEnrollmentAuthority(sourceRoot, { 1000L }),
            { requireNotNull(javaClass.getResourceAsStream("/${LocalEnterpriseSource.IDENTITY_ASSET}")) },
            LocalEnterpriseConfigurationStore(sourceRoot), { 1000L },
        )
        val source = io.mockk.spyk(realSource)
        val synchronization = EnterpriseSynchronizationService(sessions, source, scope)
        val exit = mockk<EnterpriseExitService> {
            every { failure } returns MutableStateFlow<EnterpriseExitFailure?>(null)
        }
        val service = EnterpriseApplicationService(sessions, source, synchronization, exit, portals,
            ApplicationRecoveryGate().apply { ready() }, scope, mockk(), mockk { io.mockk.coEvery { revokeViewports(any()) } returns Unit }, speech = mockk(relaxed = true))
        val documents = mutableListOf<PortalDocument>()
        val hosts = mutableListOf<BlockedHost>()

        suspend fun selection(): RealmSelection = requireNotNull(sessions.readPresentation().selection)

        suspend fun openBlockedPortal(): BlockedPortal {
            val host = BlockedHost().also(hosts::add)
            val document = PortalDocument.open(selection(), sessions, synchronization, scope, portals,
                nowMillis = { 1000L }, closeHost = host::close, onClosed = {}).also(documents::add)
            return BlockedPortal(document, host)
        }
    }
}
