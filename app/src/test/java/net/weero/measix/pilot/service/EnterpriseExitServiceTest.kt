package net.weero.measix.pilot.service

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.imggen.GeneratedMediaStore
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.service.turn.TurnRecovery
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnterpriseExitServiceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `example removal keeps closing through data failure and resumes before Ready`() = runTest {
        fixture { f ->
            val packet = exampleEnterprisePackage()
            f.sessions.enrollFixture(packet)
            f.sessions.selectPersonalFixture()
            val request = requireNotNull(f.service.captureRequest())
            coEvery { f.files.clearEnterpriseData(any()) } throws IOException("payload pending")
            try { f.service.clearExampleData(request, packet.identity.scope); fail("Expected cleanup failure") }
            catch (_: IOException) { }
            val token = requireNotNull(f.sessions.pendingExit())
            assertEquals(EnterpriseExitReason.CLEAR_EXAMPLE_DATA, token.reason)
            assertNotNull(f.manifest.session)
            assertTrue(f.manifest.feeds.isNotEmpty())
            coVerify(exactly = 0) { f.catalogs.clearEnterpriseScope(any()) }
            assertEquals(token, EnterpriseSessionController(EnterpriseAppliedStore(f.root)) { f.now }.let { it.recover(); it.pendingExit() })
            coEvery { f.files.clearEnterpriseData(token) } returns Unit
            f.gate.loading()
            f.service.completeDuringRecovery()
            assertEquals(ApplicationRecoveryState.Loading, f.gate.state.value)
            assertEquals(EnterpriseSessionPhase.SIGNED_OUT, f.manifest.phase)
            assertNull(f.manifest.session)
            assertNull(f.manifest.lastIdentity)
            assertTrue(f.manifest.feeds.isEmpty())
            coVerify(exactly = 2) { f.settings.clearEnterprisePreferences(packet.identity.scope) }
            coVerify(exactly = 2) { f.memories.clearEnterpriseScope(packet.identity.scope) }
            coVerify(exactly = 1) { f.catalogs.clearEnterpriseScope(packet.identity.scope) }
        }
    }

    @Test fun `normal exit cannot satisfy a concurrent explicit data removal`() = runTest {
        fixture { f ->
            val packet = exampleEnterprisePackage()
            f.sessions.enrollFixture(packet)
            val request = requireNotNull(f.service.captureRequest())
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.cleanup = { entered.complete(Unit); release.await() }
            val normal = async { f.service.exit(request) }
            try {
                entered.await()
                try { f.service.clearExampleData(request, packet.identity.scope); fail("Normal exit retains data") }
                catch (error: EnterpriseConfigurationException) { assertEquals("enterprise_exit_in_progress", error.reason) }
                release.complete(Unit)
                normal.await()
                assertTrue(f.manifest.feeds.isNotEmpty())
                coVerify(exactly = 0) { f.conversations.clearEnterpriseData(any()) }
                coVerify(exactly = 0) { f.files.clearEnterpriseData(any()) }
            } finally { release.complete(Unit); normal.cancelAndJoin() }
        }
    }

    @Test fun `retired Feed payload failure retains removal token until pruning succeeds`() = runTest {
        fixture { f ->
            val packet = exampleEnterprisePackage()
            f.sessions.enrollFixture(packet)
            val feed = f.manifest.feeds.single()
            val request = requireNotNull(f.service.captureRequest())
            f.failPrune = true
            try { f.service.clearExampleData(request, packet.identity.scope); fail("Expected prune failure") }
            catch (_: IOException) { }
            val token = requireNotNull(f.sessions.pendingExit())
            assertEquals(EnterpriseExitReason.CLEAR_EXAMPLE_DATA, token.reason)
            assertTrue(f.manifest.feeds.isEmpty())
            assertTrue(File(f.root, "feed-revisions/${feed.revision}/feed.json").isFile)
            f.failPrune = false
            f.service.retry(token)
            assertEquals(EnterpriseSessionPhase.SIGNED_OUT, f.manifest.phase)
            assertFalse(File(f.root, "feed-revisions/${feed.revision}").exists())
        }
    }

    @Test fun `example removal rejects another principal and stale selection but accepts pending personal session`() = runTest {
        fixture { f ->
            val packet = exampleEnterprisePackage()
            f.sessions.enrollFixture(packet)
            val old = requireNotNull(f.service.captureRequest())
            val before = f.manifest
            try { f.service.clearExampleData(old, packet.identity.scope.copy(userId = "another")); fail("Wrong principal") }
            catch (error: EnterpriseConfigurationException) { assertEquals("bundled_example_session_required", error.reason) }
            assertEquals(before, f.manifest)
            f.sessions.selectPersonalFixture()
            f.sessions.selectEnterpriseFixture()
            try { f.service.clearExampleData(old, packet.identity.scope); fail("Stale selection") }
            catch (error: EnterpriseConfigurationException) { assertEquals("enterprise_selection_revoked", error.reason) }
            val pending = f.sessions.enrollLocal(packet.identity, { packet.identity }, { null })
            assertEquals(EnterpriseSessionPhase.CONFIGURATION_PENDING, pending.manifest.phase)
            val request = requireNotNull(f.service.captureRequest())
            assertEquals(RealmAccess.Personal, request.selection.access)
            f.service.clearExampleData(request, packet.identity.scope)
            assertEquals(EnterpriseSessionPhase.SIGNED_OUT, f.manifest.phase)
            assertNull(f.manifest.lastIdentity)
        }
    }

    @Test fun `accepted exit survives caller cancellation and duplicate requests share cleanup`() = runTest {
        fixture { f ->
            f.sessions.enrollFixture(exampleEnterprisePackage())
            f.sessions.selectPersonalFixture()
            val request = requireNotNull(f.service.captureRequest())
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.cleanup = { entered.complete(Unit); release.await() }
            val first = launch { f.service.exit(request) }
            entered.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { f.service.exit(request) }
            first.cancelAndJoin()
            assertEquals(EnterpriseSessionPhase.CLOSING, f.manifest.phase)
            assertEquals(request.access.sessionId, f.manifest.session?.id)
            release.complete(Unit)
            second.await()
            assertEquals(EnterpriseSessionPhase.SIGNED_OUT, f.manifest.phase)
            coVerify(exactly = 1) { f.conversations.stopEnterpriseWork(any()) }
        }
    }

    @Test fun `failure keeps original closing intent until explicit retry`() = runTest {
        fixture { f ->
            f.sessions.enrollFixture(exampleEnterprisePackage())
            val request = requireNotNull(f.service.captureRequest())
            f.cleanup = { throw IOException("terminal commit failed") }
            try { f.service.exit(request); fail("Expected cleanup failure") } catch (_: IOException) { }
            runCurrent()
            val failure = f.service.failure.value as EnterpriseExitFailure.Closing
            assertEquals(EnterpriseSessionPhase.CLOSING, f.manifest.phase)
            assertEquals(EnterpriseExitReason.USER_REQUEST, failure.token.reason)
            coVerify(exactly = 1) { f.conversations.stopEnterpriseWork(any()) }
            f.cleanup = {}
            f.service.retry(failure.token)
            assertEquals(EnterpriseSessionPhase.SIGNED_OUT, f.manifest.phase)
            assertNull(f.service.failure.value)
        }
    }

    @Test fun `expiry closes original enterprise even while personal space is selected`() = runTest {
        fixture(virtualTime = true) { f ->
            f.sessions.enrollFixture(exampleEnterprisePackage())
            f.sessions.selectPersonalFixture()
            val session = requireNotNull(f.manifest.session)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.cleanup = { entered.complete(Unit); release.await() }
            f.gate.ready()
            runCurrent()
            val remaining = session.expiresAtMillis - f.now
            f.now = session.expiresAtMillis
            advanceTimeBy(remaining)
            runCurrent()
            entered.await()
            assertEquals(EnterpriseSessionPhase.CLOSING, f.manifest.phase)
            assertEquals(EnterpriseExitReason.AUTHORIZATION_EXPIRED, f.manifest.exitReason)
            release.complete(Unit)
            f.service.retry(requireNotNull(f.sessions.pendingExit()))
            assertEquals(EnterpriseSessionPhase.REAUTH_REQUIRED, f.manifest.phase)
        }
    }

    @Test fun `synchronization cleanup failure still awaits conversation cleanup before reporting a retryable exit`() = runTest {
        fixture(virtualTime = true) { f ->
            f.sessions.enrollFixture(exampleEnterprisePackage())
            f.gate.ready()
            val request = requireNotNull(f.service.captureRequest())
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val expected = IOException("Synchronization cleanup failed")
            var conversationCancelled = false
            coEvery { f.sync.cancelAndAwait(request.access) } throws expected
            f.cleanup = {
                entered.complete(Unit)
                try { release.await() }
                catch (cancelled: CancellationException) { conversationCancelled = true; throw cancelled }
            }
            val pending = async {
                try { f.service.exit(request); null } catch (error: IOException) { error }
            }
            try {
                entered.await()
                runCurrent()
                assertFalse(pending.isCompleted)
                assertFalse(conversationCancelled)
                assertEquals(EnterpriseSessionPhase.CLOSING, f.manifest.phase)
                release.complete(Unit)
                assertSame(expected, generateSequence<Throwable>(requireNotNull(pending.await())) { it.cause }.last())
                val failure = f.service.failure.value as EnterpriseExitFailure.Closing
                coVerify(exactly = 1) { f.conversations.stopEnterpriseWork(failure.token) }
                coEvery { f.sync.cancelAndAwait(request.access) } returns Unit
                f.cleanup = {}
                f.service.retry(failure)
                assertEquals(EnterpriseSessionPhase.SIGNED_OUT, f.manifest.phase)
            } finally { release.complete(Unit); pending.cancelAndJoin() }
        }
    }

    @Test fun `old personal confirmation cannot exit a replacement pending session`() = runTest {
        fixture { f ->
            val identity = exampleEnterprisePackage().identity
            f.sessions.enrollLocal(identity, { identity }, { null })
            val old = requireNotNull(f.service.captureRequest())
            f.sessions.enrollLocal(identity, { identity }, { null })
            val replacement = f.manifest
            assertEquals(old.selection.revision, f.sessions.selectionRevision.value)
            try { f.service.exit(old); fail("Expected stale confirmation") }
            catch (error: EnterpriseConfigurationException) { assertEquals("stale_enterprise_exit", error.reason) }
            assertEquals(replacement, f.manifest)
            coVerify(exactly = 0) { f.conversations.stopEnterpriseWork(any()) }
        }
    }

    @Test fun `expiry admission failure is observable and retries the original session`() = runTest {
        fixture(virtualTime = true) { f ->
            f.sessions.enrollFixture(exampleEnterprisePackage())
            val access = f.sessions.captureSelectedRealmAccess() as RealmAccess.Enterprise
            f.gate.ready()
            runCurrent()
            val remaining = requireNotNull(f.manifest.session).expiresAtMillis - f.now
            f.failCommit = true
            f.now += remaining
            advanceTimeBy(remaining)
            val failure = f.service.failure.filterNotNull().first() as EnterpriseExitFailure.Invalidation
            assertEquals(access, failure.access)
            assertEquals(EnterpriseExitReason.AUTHORIZATION_EXPIRED, failure.exitReason)
            assertEquals(EnterpriseSessionPhase.READY, f.manifest.phase)
            assertNull(f.sessions.pendingExit())
            assertFalse(f.sessions.observeRealmAccess(access).first())
            coVerify(exactly = 0) { f.conversations.stopEnterpriseWork(any()) }
            f.failCommit = false
            f.service.retry(failure)
            assertEquals(EnterpriseSessionPhase.REAUTH_REQUIRED, f.manifest.phase)
            assertNull(f.service.failure.value)
        }
    }

    @Test fun `unverifiable enterprise manifest does not block personal startup recovery`() = runTest {
        fixture { f ->
            f.gate.loading()
            File(f.root, "manifest.json").writeText("corrupt")
            f.recovery().recoverNow()
            assertTrue(f.sessions.state.value is EnterpriseState.Failed)
            assertEquals(ApplicationRecoveryState.Ready, f.gate.state.value)
            coVerify(exactly = 0) { f.conversations.requireEnterpriseStopped(any()) }
        }
    }

    @Test fun `recovery completes a validated closing token without awaiting its own gate`() = runTest {
        fixture { f ->
            f.gate.loading()
            f.sessions.enrollFixture(exampleEnterprisePackage())
            val access = f.sessions.captureSelectedRealmAccess() as RealmAccess.Enterprise
            f.sessions.beginInvalidation(access, EnterpriseExitReason.AUTHORIZATION_REVOKED)
            f.service.completeDuringRecovery()
            assertEquals(ApplicationRecoveryState.Loading, f.gate.state.value)
            assertEquals(EnterpriseSessionPhase.REAUTH_REQUIRED, f.manifest.phase)
            coVerify(exactly = 1) { f.conversations.requireEnterpriseStopped(any()) }
            coVerify(exactly = 0) { f.conversations.stopEnterpriseWork(any()) }
        }
    }

    private suspend fun TestScope.fixture(virtualTime: Boolean = false, block: suspend (Fixture) -> Unit) {
        val f = Fixture(this, virtualTime)
        try { block(f) }
        finally { f.scope.cancel(); f.scope.coroutineContext[Job]?.join() }
    }

    private inner class Fixture(private val test: TestScope, virtualTime: Boolean) {
        var now = 1000L
        val scope = CoroutineScope(test.backgroundScope.coroutineContext +
            (if (virtualTime) StandardTestDispatcher(test.testScheduler) else Dispatchers.Default) +
            SupervisorJob(test.backgroundScope.coroutineContext[Job]))
        val root = temporary.newFolder()
        var failCommit = false
        var failPrune = false
        val sessions = EnterpriseSessionController(EnterpriseAppliedStore(root) { checkpoint ->
            if (failPrune && checkpoint == EnterpriseStorageCheckpoint.BEFORE_REVISION_PRUNE) throw IOException("prune unavailable")
            if (failCommit && checkpoint == EnterpriseStorageCheckpoint.BEFORE_MANIFEST_COMMIT) {
                throw IOException("manifest unavailable")
            }
        }) { now }
        val gate = ApplicationRecoveryGate().apply { if (!virtualTime) ready() }
        val conversations = mockk<ConversationApplicationService>()
        val sync = mockk<EnterpriseSynchronizationService>()
        val settings = mockk<SettingsStore>()
        val memories = mockk<net.weero.measix.pilot.data.repository.MemoryRepository>()
        val catalogs = mockk<net.weero.measix.pilot.data.ai.mcp.McpCatalogStore>()
        val files = mockk<FileManagementApplicationService>()
        var cleanup: suspend (EnterpriseExitToken) -> Unit = {}
        val service: EnterpriseExitService
        val manifest get() = (sessions.state.value as EnterpriseState.Available).manifest
        init {
            coEvery { sync.cancelAndAwait(any()) } returns Unit
            coEvery { conversations.stopEnterpriseWork(any()) } coAnswers { cleanup(firstArg()) }
            coEvery { conversations.requireEnterpriseStopped(any()) } returns Unit
            coEvery { conversations.clearEnterpriseData(any()) } returns Unit
            coEvery { settings.clearEnterprisePreferences(any()) } returns Unit
            coEvery { memories.clearEnterpriseScope(any()) } returns Unit
            coEvery { catalogs.clearEnterpriseScope(any()) } returns Unit
            coEvery { files.clearEnterpriseData(any()) } returns Unit
            service = EnterpriseExitService(sessions, sync, conversations, gate, scope, net.weero.measix.pilot.service.portal.PortalDocumentRegistry(), mockk(relaxed = true), mockk { io.mockk.coEvery { closeRealm(any()) } returns Unit }, mcp = mockk { io.mockk.coEvery { closeRealm(any()) } returns Unit }, speech = mockk(relaxed = true), settings = settings, memories = memories, catalogs = catalogs, files = files)
        }

        fun recovery(): ApplicationRecoveryCoordinator = ApplicationRecoveryCoordinator(
            appScope = scope,
            settingsStore = mockk<SettingsStore> {
                every { userSettings } returns MutableStateFlow(Settings(init = false))
            },
            artifactStore = mockk<ArtifactStore> {
                coEvery { reconcileStartup() } returns Unit
                coEvery { ensureReferenceProjection() } returns Unit
            },
            generatedMediaStore = mockk<GeneratedMediaStore> { coEvery { reconcile(any()) } returns Unit },
            conversationRepository = mockk<ConversationRepository> { coEvery { ensureSearchProjection() } returns Unit },
            turnRecovery = mockk<TurnRecovery> {
                coEvery { recoverInterruptedRuns() } returns Unit
                coEvery { recoverInterruptedTurns() } returns Unit
            },
            assistantManagementService = lazy {
                mockk<AssistantManagementService> { coEvery { performPendingDeletionCleanupDuringRecovery() } returns Unit }
            },
            gate = gate,
            recoverEnterpriseConfiguration = { sessions.recover() },
            completePendingEnterpriseExit = { service.completeDuringRecovery() },
            recoveryDispatcher = StandardTestDispatcher(test.testScheduler),
            startImmediately = false,
        )
    }
}
