package net.weero.measix.pilot.data.enterprise

import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RealmAccessTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `old access deadline waits for a renewal publication before denying the session`() = runTest {
        val now = java.util.concurrent.atomic.AtomicLong(1000L)
        val blockCommit = java.util.concurrent.atomic.AtomicBoolean(false)
        val entered = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val controller = EnterpriseSessionController(enterpriseTestStore(temporary.newFolder()) { checkpoint ->
            if (blockCommit.get() && checkpoint == EnterpriseStorageCheckpoint.BEFORE_MANIFEST_COMMIT) {
                entered.complete(Unit)
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "renewal commit did not resume" }
            }
        }) { now.get() }
        controller.enrollFixture(exampleEnterprisePackage(), java.time.Instant.ofEpochMilli(3000L))
        val access = controller.captureSelectedRealmAccess() as RealmAccess.Enterprise
        val allowed = mutableListOf<Boolean>()
        val observed = CompletableDeferred<Unit>()
        backgroundScope.launch {
            controller.observeRealmAccess(access).collect { allowed += it; observed.complete(Unit) }
        }
        observed.await()
        val refresh = controller.beginPlatformRefresh(access.sessionId)
        runCurrent()
        blockCommit.set(true)
        val renewal = async(Dispatchers.Default) {
            controller.acceptPlatformRefresh(refresh, PlatformRefreshResponse("new-access", "2099-01-01T00:00:00Z",
                "new-refresh", "2099-01-08T00:00:00Z", "1970-01-01T00:00:10Z"))
        }
        try {
            entered.await()
            now.set(3000L)
            advanceTimeBy(2000L)
            runCurrent()
            assertEquals(listOf(true), allowed)
        } finally {
            release.countDown()
            renewal.await()
        }
        runCurrent()
        assertEquals(listOf(true), allowed)
        assertNull(controller.expireIfCurrent(access))
        assertTrue(controller.withRealmAccess(access) { true })

        now.set(10_000L)
        advanceTimeBy(7000L)
        runCurrent()
        assertEquals(listOf(true, false), allowed)
    }

    @Test
    fun `local expiry respects renewal while a remote session expiry remains terminal`() = runTest {
        var now = 1000L
        val controller = EnterpriseSessionController(enterpriseTestStore(temporary.newFolder())) { now }
        controller.enrollFixture(exampleEnterprisePackage(), java.time.Instant.ofEpochMilli(3000L))
        val access = controller.captureSelectedRealmAccess() as RealmAccess.Enterprise
        val refresh = controller.beginPlatformRefresh(access.sessionId)
        controller.acceptPlatformRefresh(refresh, PlatformRefreshResponse("new-access", "2099-01-01T00:00:00Z",
            "new-refresh", "2099-01-08T00:00:00Z", "1970-01-01T00:00:10Z"))
        now = 3000L
        assertNull(controller.expireIfCurrent(access))
        assertTrue(controller.withRealmAccess(access) { true })
        assertEquals(EnterpriseExitReason.AUTHORIZATION_EXPIRED,
            controller.beginInvalidation(access, EnterpriseExitReason.AUTHORIZATION_EXPIRED).reason)
        expectDenied { controller.withRealmAccess(access) { fail("remote expiry ignored") } }
    }

    @Test
    fun `captured identity survives switching but not exit and same principal reenrollment`() = runTest {
        val controller = EnterpriseSessionController(net.weero.measix.pilot.data.enterprise.enterpriseTestStore(temporary.newFolder()))
        val packet = exampleEnterprisePackage()
        controller.enrollFixture(packet)
        val access = controller.captureRealmAccess(packet.identity.scope)
        val allowed = mutableListOf<Boolean>()
        backgroundScope.launch(kotlinx.coroutines.Dispatchers.Unconfined) { controller.observeRealmAccess(access).collect { allowed += it } }
        runCurrent()
        controller.selectPersonalFixture()
        assertEquals("original", controller.withRealmAccess(access) { "original" })
        controller.finishExit(controller.beginExit(requireNotNull(controller.captureExitRequest())))
        controller.enrollFixture(packet)
        runCurrent()
        assertEquals(listOf(true, false), allowed)
        expectDenied { controller.withRealmAccess(access) { fail("stale access executed") } }
        val fresh = controller.captureRealmAccess(packet.identity.scope)
        assertNotEquals(access, fresh)
        assertTrue(controller.withRealmAccess(fresh) { true })
    }

    @Test
    fun `verified pending identity can access its data but another principal cannot`() = runTest {
        val controller = EnterpriseSessionController(net.weero.measix.pilot.data.enterprise.enterpriseTestStore(temporary.newFolder()))
        val packet = exampleEnterprisePackage()
        val identity = packet.identity
        controller.enrollFixture(packet)
        val access = controller.captureRealmAccess(identity.scope)
        assertTrue(controller.withRealmAccess(access) { true })
        expectDenied { controller.captureRealmAccess(identity.scope.copy(userId = "another-user")) }
        controller.beginInvalidation(access as RealmAccess.Enterprise, EnterpriseExitReason.AUTHORIZATION_REVOKED)
        expectDenied { controller.withRealmAccess(access) { fail("revoked access executed") } }
        assertEquals(RealmAccess.Personal, controller.captureRealmAccess(ConfigurationScope.Personal))
        assertTrue(controller.withRealmAccess(RealmAccess.Personal) { true })
    }

    @Test
    fun `deleted identity permanently leaves enterprise while preserving personal access and reason`() = runTest {
        val root = temporary.newFolder()
        val controller = EnterpriseSessionController(net.weero.measix.pilot.data.enterprise.enterpriseTestStore(root))
        val packet = exampleEnterprisePackage()
        controller.enrollFixture(packet)
        val access = controller.captureRealmAccess(packet.identity.scope) as RealmAccess.Enterprise

        val token = controller.beginInvalidation(access, EnterpriseExitReason.IDENTITY_DELETED)
        controller.finishExit(token)

        val presentation = controller.readPresentation()
        val manifest = (presentation.state as EnterpriseState.Available).manifest
        assertEquals(RealmAccess.Personal, requireNotNull(presentation.selection).access)
        assertEquals(EnterpriseSessionPhase.SIGNED_OUT, manifest.phase)
        assertEquals(EnterpriseExitReason.IDENTITY_DELETED, manifest.exitReason)
        assertNull(manifest.session)
        assertNull(manifest.lastIdentity)
        assertTrue(controller.withRealmAccess(RealmAccess.Personal) { true })

        val restarted = EnterpriseSessionController(net.weero.measix.pilot.data.enterprise.enterpriseTestStore(root))
        restarted.recover()
        val recovered = (restarted.readPresentation().state as EnterpriseState.Available).manifest
        assertEquals(EnterpriseExitReason.IDENTITY_DELETED, recovered.exitReason)
        assertEquals(EnterpriseSessionPhase.SIGNED_OUT, recovered.phase)
    }

    @Test
    fun `deleted principal can enroll again only as a fresh Core principal`() = runTest {
        val root = temporary.newFolder()
        val controller = EnterpriseSessionController(enterpriseTestStore(root))
        val original = exampleEnterprisePackage()
        controller.enrollFixture(original)
        val retiredAccess = controller.captureRealmAccess(original.identity.scope) as RealmAccess.Enterprise
        controller.finishExit(controller.beginInvalidation(retiredAccess, EnterpriseExitReason.IDENTITY_DELETED))

        val recreated = original.copy(
            identity = original.identity.copy(
                userId = "usr_00000000-0000-4000-8000-000000000099",
                userName = "Recreated member",
            ),
        )
        controller.enrollFixture(recreated)

        val manifest = (controller.readPresentation().state as EnterpriseState.Available).manifest
        assertEquals(EnterpriseSessionPhase.READY, manifest.phase)
        assertNull(manifest.exitReason)
        assertEquals(recreated.identity, manifest.session?.identity)
        assertEquals(recreated.identity, manifest.lastIdentity)
        expectDenied { controller.withRealmAccess(retiredAccess) { fail("retired access executed") } }

        val restarted = EnterpriseSessionController(enterpriseTestStore(root))
        restarted.recover()
        val recovered = (restarted.readPresentation().state as EnterpriseState.Available).manifest
        assertEquals(EnterpriseSessionPhase.READY, recovered.phase)
        assertNull(recovered.exitReason)
        assertEquals(recreated.identity, recovered.session?.identity)
    }

    @Test
    fun `identity deletion durably upgrades a weaker closing reason across restart`() = runTest {
        val root = temporary.newFolder()
        val original = EnterpriseSessionController(net.weero.measix.pilot.data.enterprise.enterpriseTestStore(root))
        val packet = exampleEnterprisePackage()
        original.enrollFixture(packet)
        val access = original.captureRealmAccess(packet.identity.scope) as RealmAccess.Enterprise
        original.beginInvalidation(access, EnterpriseExitReason.AUTHORIZATION_REVOKED)

        val upgraded = original.beginInvalidation(access, EnterpriseExitReason.IDENTITY_DELETED)
        assertEquals(EnterpriseExitReason.IDENTITY_DELETED, upgraded.reason)

        val restarted = EnterpriseSessionController(net.weero.measix.pilot.data.enterprise.enterpriseTestStore(root))
        restarted.recover()
        assertEquals(EnterpriseExitReason.IDENTITY_DELETED, restarted.pendingExit()?.reason)
        restarted.finishExit(requireNotNull(restarted.pendingExit()))
        val finished = (restarted.readPresentation().state as EnterpriseState.Available).manifest
        assertEquals(EnterpriseSessionPhase.SIGNED_OUT, finished.phase)
        assertNull(finished.lastIdentity)
    }

    @Test
    fun `identity deletion durably upgrades a local reset closing reason across restart`() = runTest {
        val root = temporary.newFolder()
        val original = EnterpriseSessionController(net.weero.measix.pilot.data.enterprise.enterpriseTestStore(root))
        val packet = exampleEnterprisePackage()
        original.enrollFixture(packet)
        val access = original.captureRealmAccess(packet.identity.scope) as RealmAccess.Enterprise
        original.beginLocalDataReset(requireNotNull(original.captureExitRequest()))

        val upgraded = original.beginInvalidation(access, EnterpriseExitReason.IDENTITY_DELETED)
        assertEquals(EnterpriseExitReason.IDENTITY_DELETED, upgraded.reason)

        val restarted = EnterpriseSessionController(net.weero.measix.pilot.data.enterprise.enterpriseTestStore(root))
        restarted.recover()
        assertEquals(EnterpriseExitReason.IDENTITY_DELETED, restarted.pendingExit()?.reason)
        restarted.finishExit(requireNotNull(restarted.pendingExit()))
        val finished = (restarted.readPresentation().state as EnterpriseState.Available).manifest
        assertEquals(EnterpriseExitReason.IDENTITY_DELETED, finished.exitReason)
        assertNull(finished.lastIdentity)
    }

    private suspend fun expectDenied(operation: suspend () -> Unit) {
        try { operation(); fail("expected access rejection") }
        catch (_: EnterpriseConfigurationException) { }
    }
}
