package net.weero.measix.pilot.service

import java.io.File
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.data.configuration.ConfigurationScope
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
    private var now = Instant.parse("2029-01-01T00:00:00Z").toEpochMilli()

    @Test
    fun `source publication persists independently and sync preserves session and personal selection`() = runTest {
        val h = harness()
        try {
            val first = h.source.enrollExample()
            val original = h.source.candidate(packet().identity.scope)!!
            val changed = h.source.setPolicy(packet().identity.scope, original.revision, original.packet.configuration.policy.copy(allowLocalProviders = false))
            assertEquals(first, h.sessions.state.value)
            rejected("local_enterprise_configuration_changed") {
                h.source.setPolicy(packet().identity.scope, original.revision, original.packet.configuration.policy)
            }
            assertEquals(changed, source(h.sessions, h.sourceRoot).candidate(packet().identity.scope))
            val access = access(first)
            h.sessions.selectPersonalFixture()
            now += 1000
            val synchronized = h.sync.synchronize(access)
            assertEquals(changed.packet.configuration, synchronized.configuration)
            assertEquals(first.manifest.session, synchronized.manifest.session)
            assertEquals(ConfigurationScope.Personal, synchronized.manifest.selectedScope)
            assertEquals(now, synchronized.manifest.lastConfigurationSyncMillis)
            now += 1000
            val checked = h.sync.synchronize(access)
            assertEquals(synchronized.manifest.applied, checked.manifest.applied)
            assertEquals(now, checked.manifest.lastConfigurationSyncMillis)
            assertEquals(checked, EnterpriseSessionController(EnterpriseAppliedStore(h.clientRoot)) { now }.recover())
            h.sessions.finishExit(h.sessions.beginExit(requireNotNull(h.sessions.captureExitRequest())))
            assertEquals(changed.packet.configuration, source(h.sessions, h.sourceRoot).enrollExample().configuration)
        } finally { h.work.cancel() }
    }

    @Test
    fun `client failure retains source candidate and prior successful sync until explicit retry`() = runTest {
        var failClient = false
        val h = harness(clientCheckpoint = { if (failClient && it == EnterpriseStorageCheckpoint.BEFORE_MANIFEST_COMMIT) error("disk failure") })
        try {
            val first = h.source.enrollExample()
            val candidate = packet().copy(configuration = packet().configuration.copy(generation = 2))
            now += 1000
            failClient = true
            val imported = h.source.importPackage(EnterprisePackageCodec.encode(candidate).inputStream())
            assertNull(imported.applied)
            assertNotNull(imported.failureReason)
            assertEquals(candidate, h.source.candidate(candidate.identity.scope)!!.packet)
            assertEquals(first, h.sessions.state.value)
            assertEquals(first.manifest, EnterpriseAppliedStore(h.clientRoot).readManifest())
            failClient = false
            val result = h.sync.synchronize(access(first))
            assertEquals(2L, result.manifest.applied!!.generation)
            assertEquals(first.manifest.session, result.manifest.session)
            assertEquals(now, result.manifest.lastConfigurationSyncMillis)
        } finally { h.work.cancel() }
    }

    @Test
    fun `concurrent sync shares a commit and cancellation only detaches its waiter`() = runTest {
        var pauseSource = false
        var commits = 0
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val h = harness(
            sourceCheckpoint = { if (pauseSource) { entered.complete(Unit); check(release.await(10, TimeUnit.SECONDS)) } },
            clientCheckpoint = { if (it == EnterpriseStorageCheckpoint.MANIFEST_WRITTEN) commits++ },
        )
        try {
            val first = h.source.enrollExample()
            val candidate = h.source.candidate(packet().identity.scope)!!
            pauseSource = true
            val publisher = async(Dispatchers.Default) {
                h.source.setPolicy(packet().identity.scope, candidate.revision, candidate.packet.configuration.policy.copy(allowLocalMcp = false))
            }
            entered.await()
            val one = async { h.sync.synchronize(access(first)) }
            val two = async { h.sync.synchronize(access(first)) }
            runCurrent()
            one.cancel()
            val before = commits
            release.countDown()
            publisher.await()
            assertFalse(two.await().configuration!!.policy.allowLocalMcp)
            assertEquals(before + 1, commits)
            assertTrue(one.isCancelled)
        } finally { release.countDown(); h.work.cancel() }
    }

    @Test
    fun `exit while reading source cannot apply late results or renew the revoked session`() = runTest {
        var pauseSource = false
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val h = harness(sourceCheckpoint = { if (pauseSource) { entered.complete(Unit); check(release.await(10, TimeUnit.SECONDS)) } })
        try {
            val first = h.source.enrollExample()
            val candidate = h.source.candidate(packet().identity.scope)!!
            pauseSource = true
            val publisher = async(Dispatchers.Default) {
                h.source.setPolicy(packet().identity.scope, candidate.revision, candidate.packet.configuration.policy.copy(allowLocalMcp = false))
            }
            entered.await()
            val synchronization = async { runCatching { h.sync.synchronize(access(first)) } }
            runCurrent()
            h.sessions.finishExit(h.sessions.beginExit(requireNotNull(h.sessions.captureExitRequest())))
            release.countDown()
            publisher.await()
            val failure = synchronization.await().exceptionOrNull() as EnterpriseConfigurationException
            assertEquals("enterprise_data_access_unavailable", failure.reason)
            val signedOut = h.sessions.state.value as EnterpriseState.Available
            assertEquals(EnterpriseSessionPhase.SIGNED_OUT, signedOut.manifest.phase)
            assertNull(signedOut.manifest.lastConfigurationSyncMillis)
            pauseSource = false
            val renewed = h.source.enrollExample()
            rejected("enterprise_data_access_unavailable") { h.sync.synchronize(access(first)) }
            assertEquals(renewed, h.sessions.state.value)
        } finally { release.countDown(); h.work.cancel() }
    }

    @Test
    fun `expiry during staging rejects publication and cancellation after commit preserves it`() = runTest {
        for (expire in listOf(true, false)) {
            var pause = false
            var expiry = Long.MAX_VALUE
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val h = harness(clientCheckpoint = { point ->
                if (pause && expire && point == EnterpriseStorageCheckpoint.BINDINGS_STAGED) now = expiry
                if (pause && !expire && point == EnterpriseStorageCheckpoint.MANIFEST_WRITTEN) {
                    entered.complete(Unit); check(release.await(10, TimeUnit.SECONDS))
                }
            })
            try {
                val first = h.source.enrollExample()
                expiry = first.manifest.session!!.expiresAtMillis
                val old = h.source.candidate(packet().identity.scope)!!
                h.source.setPolicy(packet().identity.scope, old.revision, old.packet.configuration.policy.copy(allowLocalMcp = false))
                pause = true
                if (expire) {
                    rejected("enterprise_data_access_unavailable") { h.sync.synchronize(access(first)) }
                    assertEquals(first.manifest, EnterpriseAppliedStore(h.clientRoot).readManifest())
                } else {
                    val waiter = launch { h.sync.synchronize(access(first)) }
                    entered.await()
                    h.work.cancel()
                    release.countDown()
                    waiter.join()
                    val durable = EnterpriseAppliedStore(h.clientRoot).readManifest()
                    assertEquals(2L, durable.applied!!.generation)
                    assertEquals(durable, (h.sessions.state.value as EnterpriseState.Available).manifest)
                }
            } finally { release.countDown(); h.work.cancel() }
        }
    }

    private data class Harness(val clientRoot: File, val sourceRoot: File, val sessions: EnterpriseSessionController,
        val source: LocalEnterpriseSource, val sync: EnterpriseSynchronizationService, val work: CoroutineScope)

    private fun TestScope.harness(sourceCheckpoint: () -> Unit = {}, clientCheckpoint: (EnterpriseStorageCheckpoint) -> Unit = {}): Harness {
        val clientRoot = temporary.newFolder()
        val sourceRoot = temporary.newFolder()
        val sessions = EnterpriseSessionController(EnterpriseAppliedStore(clientRoot, clientCheckpoint)) { now }
        val source = source(sessions, sourceRoot, sourceCheckpoint)
        val work = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        return Harness(clientRoot, sourceRoot, sessions, source, EnterpriseSynchronizationService(sessions, source, work), work)
    }

    private fun source(sessions: EnterpriseSessionController, root: File, checkpoint: () -> Unit = {}) = LocalEnterpriseSource(
        { bytes().inputStream() }, sessions, LocalEnrollmentAuthority(root, { now }),
        { requireNotNull(javaClass.getResourceAsStream("/${LocalEnterpriseSource.IDENTITY_ASSET}")) },
        LocalEnterpriseConfigurationStore(root, checkpoint), { now },
    )
    private fun bytes() = requireNotNull(javaClass.getResourceAsStream("/${LocalEnterpriseSource.EXAMPLE_ASSET}")).use { it.readBytes() }
    private fun packet() = EnterprisePackageCodec.decode(bytes())
    private fun access(state: EnterpriseState.Available) = RealmAccess.Enterprise(state.manifest.session!!.identity.scope, state.manifest.session.id)
    private suspend fun rejected(reason: String, block: suspend () -> Any?) {
        try { block(); fail("expected $reason") } catch (error: EnterpriseConfigurationException) { assertEquals(reason, error.reason) }
    }
}
