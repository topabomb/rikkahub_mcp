package net.weero.measix.pilot.data.enterprise

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
    fun `captured identity survives switching and offline but not exit and same principal reenrollment`() = runTest {
        val controller = EnterpriseSessionController(EnterpriseAppliedStore(temporary.newFolder()))
        val packet = exampleEnterprisePackage()
        controller.enrollFixture(packet)
        val access = controller.captureRealmAccess(packet.identity.scope)
        val allowed = mutableListOf<Boolean>()
        backgroundScope.launch(kotlinx.coroutines.Dispatchers.Unconfined) { controller.observeRealmAccess(access).collect { allowed += it } }
        runCurrent()
        controller.switchToPersonal()
        controller.setOffline(true)
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
    fun `idle subscriptions expire without a write and an old deadline cannot revoke a renewed session`() = runTest {
        val controller = EnterpriseSessionController(EnterpriseAppliedStore(temporary.newFolder())) { 1_900_000_000_000L + testScheduler.currentTime }
        val packet = exampleEnterprisePackage()
        controller.enrollFixture(packet)
        val first = controller.captureRealmAccess(packet.identity.scope)
        val old = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.observeRealmAccess(first).collect { old += it } }
        val lifetime = (controller.state.value as EnterpriseState.Available).manifest.session!!.expiresAtMillis - 1_900_000_000_000L
        advanceTimeBy(lifetime / 2)
        controller.finishExit(controller.beginExit(requireNotNull(controller.captureExitRequest())))
        controller.enrollFixture(packet)
        val fresh = controller.captureRealmAccess(packet.identity.scope)
        val live = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.observeRealmAccess(fresh).collect { live += it } }
        advanceTimeBy(lifetime / 2 + 1)
        runCurrent()
        assertEquals(listOf(true, false), old)
        assertEquals(listOf(true), live)
        assertTrue(controller.withRealmAccess(fresh) { true })
        advanceTimeBy(lifetime / 2)
        runCurrent()
        assertEquals(listOf(true, false), live)
        expectDenied { controller.withRealmAccess(fresh) { fail("expired access executed") } }
    }

    @Test
    fun `verified pending identity can access its data but another principal cannot`() = runTest {
        val controller = EnterpriseSessionController(EnterpriseAppliedStore(temporary.newFolder()))
        val identity = exampleEnterprisePackage().identity
        controller.enrollLocal(identity, redeem = { identity }, configuration = { null })
        val access = controller.captureRealmAccess(identity.scope)
        assertTrue(controller.withRealmAccess(access) { true })
        expectDenied { controller.captureRealmAccess(identity.scope.copy(userId = "another-user")) }
        controller.beginInvalidation(access as RealmAccess.Enterprise, EnterpriseExitReason.AUTHORIZATION_REVOKED)
        expectDenied { controller.withRealmAccess(access) { fail("revoked access executed") } }
        assertEquals(RealmAccess.Personal, controller.captureRealmAccess(ConfigurationScope.Personal))
        assertTrue(controller.withRealmAccess(RealmAccess.Personal) { true })
    }

    private suspend fun expectDenied(operation: suspend () -> Unit) {
        try { operation(); fail("expected access rejection") }
        catch (_: EnterpriseConfigurationException) { }
    }
}
