package net.weero.measix.pilot.service

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.data.enterprise.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnterpriseSynchronizationServiceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `version direction excludes empty declarations and preserves exact diagnostics`() {
        val cases = listOf(
            listOf(6L) to EnterpriseSynchronizationIssue.UPDATE_APP,
            listOf(2L, 3L) to EnterpriseSynchronizationIssue.UPDATE_PLATFORM,
            listOf(3L, 6L) to EnterpriseSynchronizationIssue.UNSUPPORTED_VERSION,
            emptyList<Long>() to EnterpriseSynchronizationIssue.UNSUPPORTED_VERSION,
        )
        for ((received, expected) in cases) {
            val failure = EnterpriseSynchronizationFailure.from(EnterpriseSnapshotCompatibilityException(received))
            assertEquals(received.toString(), expected, failure.issue)
            assertTrue(failure.diagnostic.contains("EnterpriseSnapshotCompatibilityException"))
            assertTrue(failure.diagnostic.contains("enterprise_configuration_version_unsupported"))
            assertTrue(failure.diagnostic.contains(received.toString()))
        }
        assertEquals(EnterpriseSynchronizationIssue.NETWORK, EnterpriseSynchronizationFailure.from(IOException("connection reset")).issue)
        val httpFailure = EnterpriseSynchronizationFailure.from(PlatformHttpException(409, null, "release is unavailable"))
        assertEquals(EnterpriseSynchronizationIssue.FAILED, httpFailure.issue)
        assertTrue(httpFailure.diagnostic.contains("HTTP 409"))
        assertTrue(httpFailure.diagnostic.contains("release is unavailable"))
        val failure = EnterpriseSynchronizationFailure.from(IllegalStateException("invalid snapshot payload", IOException("truncated body")))
        assertEquals(EnterpriseSynchronizationIssue.FAILED, failure.issue)
        assertTrue(failure.diagnostic.contains("IllegalStateException: invalid snapshot payload"))
        assertTrue(failure.diagnostic.contains("IOException: truncated body"))
    }

    @Test fun `failed shared sync retains original exception and successful retry clears its projection`() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        try {
            val access = f.enroll()
            val original = IllegalStateException("invalid managed content", IOException("source detail"))
            coEvery { f.platform.synchronize(access) } throws original
            val failure = runCatching { f.service.synchronize(access) }.exceptionOrNull()
            assertTrue(generateSequence(failure) { it.cause }.any { it === original })
            val status = requireNotNull(f.service.status.value)
            assertEquals(access, status.access)
            assertFalse(status.syncing)
            assertEquals(EnterpriseSynchronizationIssue.FAILED, status.failure?.issue)
            assertTrue(status.failure?.diagnostic.orEmpty().contains("source detail"))
            val rejected = runCatching { f.service.prepareExecution(access) }.exceptionOrNull()
            assertTrue(generateSequence(rejected) { it.cause }.any { it === original })
            coVerify(exactly = 0) { f.platform.prepareExecution(access, any()) }

            val snapshot = f.current()
            coEvery { f.platform.synchronize(access) } returns snapshot
            assertSame(snapshot, f.service.synchronize(access))
            assertEquals(EnterpriseSynchronizationStatus(access, syncing = false), f.service.status.value)
            coVerify(exactly = 3) { f.platform.synchronize(access) }
        } finally { f.scope.cancel() }
    }

    @Test fun `native command receipts cover every sync failure and repeated completion without exception whitelists`() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        try {
            val access = f.enroll()
            for (error in listOf(
                IOException("connection closed"),
                EnterpriseSnapshotContentException(IllegalArgumentException("invalid reference")),
                EnterpriseSnapshotCompatibilityException(listOf(6L)),
                IllegalStateException("unexpected storage failure"),
            )) {
                coEvery { f.platform.synchronize(access) } throws error
                repeat(2) {
                    assertEquals(EnterpriseSynchronizationCommandResult.FAILURE_PRESENTED,
                        f.service.synchronizeForPresentation(access))
                    assertEquals(EnterpriseSynchronizationFailure.from(error), f.service.status.value?.failure)
                }
                val executionFailure = runCatching { f.service.synchronize(access) }.exceptionOrNull()
                assertTrue(generateSequence(executionFailure) { it.cause }.any { it === error })
            }
            coEvery { f.platform.synchronize(access) } returns f.current()
            repeat(2) {
                assertEquals(EnterpriseSynchronizationCommandResult.COMPLETED,
                    f.service.synchronizeForPresentation(access))
                assertNull(f.service.status.value?.failure)
            }
        } finally { f.scope.cancel() }
    }

    @Test fun `same session waiters share one operation and cancelling a waiter keeps it running`() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        try {
            val access = f.enroll()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val snapshot = f.current()
            coEvery { f.platform.synchronize(access) } coAnswers {
                entered.complete(Unit)
                release.await()
                snapshot
            }
            val first = async { f.service.synchronize(access) }
            entered.await()
            val second = async { f.service.synchronize(access) }
            runCurrent()
            assertEquals(EnterpriseSynchronizationStatus(access, syncing = true), f.service.status.value)
            first.cancelAndJoin()
            assertFalse(second.isCompleted)
            assertTrue(requireNotNull(f.service.status.value).syncing)
            release.complete(Unit)
            assertSame(snapshot, second.await())
            coVerify(exactly = 1) { f.platform.synchronize(access) }
            assertEquals(EnterpriseSynchronizationStatus(access, syncing = false), f.service.status.value)
        } finally { f.scope.cancel() }
    }

    @Test fun `cancelling synchronization propagates cancellation and restores the preceding failure`() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        try {
            val access = f.enroll()
            coEvery { f.platform.synchronize(access) } throws EnterpriseSnapshotCompatibilityException(listOf(6L))
            assertTrue(runCatching { f.service.synchronize(access) }.exceptionOrNull() is EnterpriseSnapshotCompatibilityException)
            val previous = requireNotNull(f.service.status.value).failure
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            coEvery { f.platform.synchronize(access) } coAnswers {
                entered.complete(Unit)
                release.await()
                f.current()
            }
            val attempt = async { runCatching { f.service.synchronize(access) } }
            entered.await()
            assertEquals(EnterpriseSynchronizationStatus(access, syncing = true, failure = previous), f.service.status.value)
            f.service.cancelAndAwait(access)
            assertTrue(attempt.await().exceptionOrNull() is CancellationException)
            assertEquals(EnterpriseSynchronizationStatus(access, syncing = false, failure = previous), f.service.status.value)

            coEvery { f.platform.synchronize(access) } returns f.current()
            f.service.synchronize(access)
            assertNull(f.service.status.value?.failure)
            coVerify(exactly = 3) { f.platform.synchronize(access) }
        } finally { f.scope.cancel() }
    }

    @Test fun `late previous session failure cannot overwrite a replacement session success`() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        try {
            val originalAccess = f.enroll()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val original = IOException("late old session response")
            coEvery { f.platform.synchronize(originalAccess) } coAnswers {
                entered.complete(Unit)
                release.await()
                throw original
            }
            val old = async { runCatching { f.service.synchronize(originalAccess) } }
            entered.await()
            val nativeCommand = async { f.service.synchronizeForPresentation(originalAccess) }
            runCurrent()
            f.sessions.finishExit(f.sessions.beginInvalidation(originalAccess, EnterpriseExitReason.AUTHORIZATION_REVOKED))
            val currentAccess = f.enroll()
            assertNotEquals(originalAccess.sessionId, currentAccess.sessionId)
            coEvery { f.platform.synchronize(currentAccess) } returns f.current()
            f.service.synchronize(currentAccess)
            val currentStatus = EnterpriseSynchronizationStatus(currentAccess, syncing = false)
            assertEquals(currentStatus, f.service.status.value)
            release.complete(Unit)
            assertTrue(generateSequence(old.await().exceptionOrNull()) { it.cause }.any { it === original })
            assertEquals(EnterpriseSynchronizationCommandResult.SUPERSEDED, nativeCommand.await())
            assertEquals(currentStatus, f.service.status.value)
            assertEquals(currentAccess, f.sessions.readPresentation().selection?.access)
        } finally { f.scope.cancel() }
    }

    private inner class Fixture(dispatcher: CoroutineDispatcher) {
        val sessions = EnterpriseSessionController(enterpriseTestStore(temporary.newFolder())) { 1000L }
        val platform = mockk<PlatformEnterpriseService>()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val service = EnterpriseSynchronizationService(sessions, scope, platform)

        suspend fun enroll(): RealmAccess.Enterprise {
            sessions.enrollFixture(exampleEnterprisePackage())
            return requireNotNull(sessions.readPresentation().selection).access as RealmAccess.Enterprise
        }

        fun current() = sessions.state.value as EnterpriseState.Available
    }
}
