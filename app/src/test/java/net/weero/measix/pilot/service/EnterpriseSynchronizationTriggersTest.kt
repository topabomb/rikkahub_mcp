package net.weero.measix.pilot.service

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.service.portal.PortalDocumentRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnterpriseSynchronizationTriggersTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `unreadable applied configuration shows a sync diagnostic without disabling the identity or auto syncing`() = runTest {
        val root = temporary.newFolder()
        val original = EnterpriseSessionController(enterpriseTestStore(root)).enrollFixture(exampleEnterprisePackage())
        java.io.File(root, "revisions/${original.manifest.applied!!.revision}/configuration.json").writeText("broken")
        val sessions = EnterpriseSessionController(enterpriseTestStore(root))
        sessions.recover()
        val fixture = Fixture(this, sessions)
        val service = fixture.create()
        runCurrent()
        val overview = service.observe().first()
        assertNull(overview.failure)
        assertTrue(overview.canEnterEnterprise)
        assertEquals(EnterpriseResetPath.CONNECTED, overview.resetPath)
        assertEquals(EnterpriseSynchronizationIssue.INVALID_CONFIGURATION, overview.synchronization?.failure?.issue)
        assertTrue(overview.synchronization!!.failure!!.diagnostic.contains("enterprise_revision_hash_mismatch"))
        assertNull(overview.configurationDetails)
        coVerify(exactly = 0) { fixture.sync.synchronizeForPresentation(any()) }
        coVerify(exactly = 0) { fixture.sync.synchronize(any()) }
    }

    @Test fun `restored applied session and realm switching never synchronize configuration`() = runTest {
        val sessions = EnterpriseSessionController(enterpriseTestStore(temporary.newFolder())) { 1000L }
        val available = sessions.enrollFixture(exampleEnterprisePackage())
        val access = requireNotNull(sessions.readPresentation().selection).access as RealmAccess.Enterprise
        val f = Fixture(this, sessions)
        coEvery { f.platform.recoverPlatformAccess() } returns access
        val service = f.create()
        runCurrent()
        coVerify(exactly = 1) { f.platform.recoverPlatformAccess() }
        coVerify(exactly = 0) { f.sync.synchronize(any()) }
        coVerify(exactly = 0) { f.sync.synchronizeForPresentation(any()) }

        service.switchRealm(RealmSwitchRequest(requireNotNull(sessions.readPresentation().selection), RealmAccess.Personal))
        service.switchRealm(RealmSwitchRequest(requireNotNull(sessions.readPresentation().selection), access))
        runCurrent()
        assertEquals(access, sessions.readPresentation().selection?.access)
        assertEquals(available.manifest.applied, (sessions.state.value as EnterpriseState.Available).manifest.applied)
        coVerify(exactly = 0) { f.sync.synchronize(any()) }
        coVerify(exactly = 0) { f.sync.synchronizeForPresentation(any()) }
    }

    @Test fun `startup completion of a saved enrollment initializes once but an existing pending configuration does not retry`() = runTest {
        val sessions = EnterpriseSessionController(enterpriseTestStore(temporary.newFolder())) { 1000L }
        val connection = platformCandidate(exampleEnterprisePackage()).execution.let { (it as EnterpriseExecution.Platform).connection }
        val sessionId = sessions.acceptPlatformEnrollment(sessions.beginPlatformEnrollment(), connection,
            PlatformWireCodec.decode(case("enrollment-response")))
        assertNotNull(sessions.pendingPlatformEnrollment())
        val f = Fixture(this, sessions)
        coEvery { f.platform.recoverPlatformAccess() } coAnswers {
            sessions.completePlatformBootstrap(sessionId, PlatformWireCodec.decode(case("bootstrap")))
        }
        val initialization = CompletableDeferred<RealmAccess.Enterprise>()
        coEvery { f.sync.synchronizeForPresentation(any()) } coAnswers {
            initialization.complete(firstArg())
            EnterpriseSynchronizationCommandResult.COMPLETED
        }
        f.create()
        val access = initialization.await()
        assertEquals(sessionId, access.sessionId)
        assertEquals(access.scope, requireNotNull((sessions.state.value as EnterpriseState.Available).manifest.session).identity.scope)
        assertNull(sessions.pendingPlatformEnrollment())
        assertEquals(EnterpriseSessionPhase.CONFIGURATION_PENDING, (sessions.state.value as EnterpriseState.Available).manifest.phase)
        coVerify(exactly = 1) { f.sync.synchronizeForPresentation(access) }

        coEvery { f.platform.recoverPlatformAccess() } returns access
        f.create()
        runCurrent()
        coVerify(exactly = 2) { f.platform.recoverPlatformAccess() }
        coVerify(exactly = 1) { f.sync.synchronizeForPresentation(access) }
        coVerify(exactly = 0) { f.sync.synchronize(any()) }
    }

    @Test fun `confirmed enrollment synchronizes once and its following realm switch does not synchronize again`() = runTest {
        val sessions = EnterpriseSessionController(enterpriseTestStore(temporary.newFolder())) { 1000L }
        sessions.enrollFixture(exampleEnterprisePackage())
        val access = requireNotNull(sessions.readPresentation().selection).access as RealmAccess.Enterprise
        sessions.switchRealm(RealmSwitchRequest(requireNotNull(sessions.readPresentation().selection), RealmAccess.Personal)) {}
        val f = Fixture(this, sessions)
        coEvery { f.platform.enroll(any(), any(), any()) } returns access
        val service = f.create()
        runCurrent()
        val confirmation = requireNotNull(service.join("""{"formatVersion":1,"kind":"PLATFORM_ENROLLMENT","platformUrl":"https://platform.test","code":"manual-test-code","expiresAt":"2099-01-01T00:00:00Z"}"""))
        assertEquals(EnterpriseSynchronizationCommandResult.COMPLETED, service.confirmJoin(confirmation))
        runCurrent()
        assertEquals(access, sessions.readPresentation().selection?.access)
        coVerify(exactly = 1) { f.platform.enroll(any(), any(), any()) }
        coVerify(exactly = 1) { f.sync.synchronizeForPresentation(access) }
        coVerify(exactly = 0) { f.sync.synchronize(any()) }
    }

    @Test fun `late initialization admission failure cannot publish an enrollment failure into a replacement session`() = runTest {
        val sessions = EnterpriseSessionController(enterpriseTestStore(temporary.newFolder())) { 1000L }
        val sessionId = createPending(sessions)
        val f = Fixture(this, sessions)
        coEvery { f.platform.recoverPlatformAccess() } coAnswers {
            sessions.completePlatformBootstrap(sessionId, PlatformWireCodec.decode(case("bootstrap")))
        }
        val initializationStarted = CompletableDeferred<RealmAccess.Enterprise>()
        val finishInitialization = CompletableDeferred<Unit>()
        coEvery { f.sync.synchronizeForPresentation(any()) } coAnswers {
            val original = firstArg<RealmAccess.Enterprise>()
            initializationStarted.complete(original)
            finishInitialization.await()
            sessions.withRealmAccess(original) { Unit }
            EnterpriseSynchronizationCommandResult.COMPLETED
        }
        val service = f.create()
        runCurrent()
        val original = initializationStarted.await()
        sessions.finishExit(sessions.beginInvalidation(original, EnterpriseExitReason.AUTHORIZATION_REVOKED))
        sessions.enrollFixture(exampleEnterprisePackage())
        val replacement = requireNotNull(sessions.readPresentation().selection).access
        assertNotEquals(original, replacement)
        finishInitialization.complete(Unit)
        runCurrent()
        val overview = service.observe().first()
        assertEquals(replacement, overview.access)
        assertNull(overview.enrollmentRecoveryFailure)
        assertNull(overview.synchronization?.failure)
        coVerify(exactly = 1) { f.sync.synchronizeForPresentation(original) }
    }

    @Test fun `late Bootstrap failure belongs to the saved enrollment and is hidden after its session is replaced`() = runTest {
        val sessions = EnterpriseSessionController(enterpriseTestStore(temporary.newFolder())) { 1000L }
        val sessionId = createPending(sessions)
        val f = Fixture(this, sessions)
        val bootstrapStarted = CompletableDeferred<Unit>()
        val finishBootstrap = CompletableDeferred<Unit>()
        coEvery { f.platform.recoverPlatformAccess() } coAnswers {
            bootstrapStarted.complete(Unit)
            finishBootstrap.await()
            throw IOException("original_pending_bootstrap_failure")
        }
        val service = f.create()
        runCurrent()
        bootstrapStarted.await()
        sessions.abandonPendingPlatformEnrollment(sessionId)
        sessions.enrollFixture(exampleEnterprisePackage())
        val replacement = requireNotNull(sessions.readPresentation().selection).access
        finishBootstrap.complete(Unit)
        runCurrent()
        val overview = service.observe().first()
        assertEquals(replacement, overview.access)
        assertNull(overview.enrollmentRecoveryFailure)
        coVerify(exactly = 0) { f.sync.synchronizeForPresentation(any()) }
    }

    @Test fun `failed Bootstrap retains its original pending enrollment diagnostic until explicit recovery`() = runTest {
        val sessions = EnterpriseSessionController(enterpriseTestStore(temporary.newFolder())) { 1000L }
        createPending(sessions)
        val f = Fixture(this, sessions)
        coEvery { f.platform.recoverPlatformAccess() } throws IOException("pending_bootstrap_diagnostic", IOException("source_detail"))
        val service = f.create()
        runCurrent()
        val overview = service.observe().first()
        assertTrue(overview.enrollmentRecoveryFailure.orEmpty().contains("pending_bootstrap_diagnostic"))
        assertTrue(overview.enrollmentRecoveryFailure.orEmpty().contains("source_detail"))
        assertNotNull(sessions.pendingPlatformEnrollment())
        coVerify(exactly = 1) { f.platform.recoverPlatformAccess() }
        coVerify(exactly = 0) { f.sync.synchronizeForPresentation(any()) }
    }

    private suspend fun createPending(sessions: EnterpriseSessionController): String {
        val connection = (platformCandidate(exampleEnterprisePackage()).execution as EnterpriseExecution.Platform).connection
        return sessions.acceptPlatformEnrollment(sessions.beginPlatformEnrollment(), connection,
            PlatformWireCodec.decode(case("enrollment-response")))
    }

    private fun case(name: String): String = requireNotNull(javaClass.getResourceAsStream("/contracts/platform/cases.json"))
        .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonArray }
        .first { it.jsonObject.getValue("name").jsonPrimitive.content == name }.jsonObject.getValue("value").toString()

    private class Fixture(private val test: TestScope, private val sessions: EnterpriseSessionController) {
        val sync = mockk<EnterpriseSynchronizationService>(relaxed = true)
        val platform = mockk<PlatformEnterpriseService>(relaxed = true)
        val exit = mockk<EnterpriseExitService>(relaxed = true)
        val dataReset = mockk<EnterpriseDataResetService>(relaxed = true)

        init {
            coEvery { platform.recoverPlatformAccess() } returns null
            coEvery { sync.synchronizeForPresentation(any()) } returns EnterpriseSynchronizationCommandResult.COMPLETED
            every { sync.status } returns MutableStateFlow<EnterpriseSynchronizationStatus?>(null)
            every { exit.failure } returns MutableStateFlow<EnterpriseExitFailure?>(null)
            every { exit.recoveryLogoutFailure } returns MutableStateFlow<String?>(null)
            every { dataReset.progress } returns MutableStateFlow<EnterpriseDataResetProgress?>(null)
            every { platform.pendingLogoutFailure } returns MutableStateFlow<String?>(null)
        }

        fun create() = EnterpriseApplicationService(
            sessions = sessions,
            synchronization = sync,
            exit = exit,
            dataReset = dataReset,
            portals = PortalDocumentRegistry(),
            recovery = ApplicationRecoveryGate().apply { ready() },
            scope = test.backgroundScope,
            media = mockk(relaxed = true),
            terminals = mockk(relaxed = true),
            speech = mockk(relaxed = true),
            platform = platform,
            remoteWorkspace = mockk(relaxed = true),
        )
    }
}
