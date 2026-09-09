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
        val store = EnterpriseAppliedStore(temporary.newFolder())
        val sessions = EnterpriseSessionController(store) { 1000L }
        val portals = PortalDocumentRegistry()
        val synchronization = mockk<EnterpriseSynchronizationService>()
        val exit = mockk<EnterpriseExitService> {
            every { failure } returns MutableStateFlow<EnterpriseExitFailure?>(null)
        }
        val service = EnterpriseApplicationService(sessions, mockk(), synchronization, exit, portals,
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
