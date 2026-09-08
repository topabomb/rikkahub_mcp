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
import net.weero.measix.pilot.data.datastore.toEffectiveSettingsSnapshot
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

    @Test fun `accepted exit survives caller cancellation and duplicate requests share cleanup`() = runTest {
        fixture { f ->
            f.sessions.enrollFixture(exampleEnterprisePackage())
            f.sessions.switchToPersonal()
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
            f.sessions.switchToPersonal()
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
        val sessions = EnterpriseSessionController(EnterpriseAppliedStore(root) { checkpoint ->
            if (failCommit && checkpoint == EnterpriseStorageCheckpoint.BEFORE_MANIFEST_COMMIT) {
                throw IOException("manifest unavailable")
            }
        }) { now }
        val gate = ApplicationRecoveryGate().apply { if (!virtualTime) ready() }
        val conversations = mockk<ConversationApplicationService>()
        val sync = mockk<EnterpriseSynchronizationService>()
        var cleanup: suspend (EnterpriseExitToken) -> Unit = {}
        val service: EnterpriseExitService
        val manifest get() = (sessions.state.value as EnterpriseState.Available).manifest
        init {
            coEvery { sync.cancelAndAwait(any()) } returns Unit
            coEvery { conversations.stopEnterpriseWork(any()) } coAnswers { cleanup(firstArg()) }
            coEvery { conversations.requireEnterpriseStopped(any()) } returns Unit
            service = EnterpriseExitService(sessions, sync, conversations, gate, scope, net.weero.measix.pilot.service.portal.PortalDocumentRegistry())
        }

        fun recovery(): ApplicationRecoveryCoordinator = ApplicationRecoveryCoordinator(
            appScope = scope,
            settingsStore = mockk<SettingsStore> {
                every { effectiveSettings } returns MutableStateFlow(Settings(init = false).toEffectiveSettingsSnapshot())
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
