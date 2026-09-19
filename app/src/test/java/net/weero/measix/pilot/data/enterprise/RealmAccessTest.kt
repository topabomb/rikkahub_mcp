package net.weero.measix.pilot.data.enterprise

import kotlinx.coroutines.launch
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

    private suspend fun expectDenied(operation: suspend () -> Unit) {
        try { operation(); fail("expected access rejection") }
        catch (_: EnterpriseConfigurationException) { }
    }
}
