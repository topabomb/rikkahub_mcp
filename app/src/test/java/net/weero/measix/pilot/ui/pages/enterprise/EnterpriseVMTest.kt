package net.weero.measix.pilot.ui.pages.enterprise

import androidx.lifecycle.ViewModelStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.service.*
import org.junit.Assert.*
import org.junit.Test

class EnterpriseVMTest {
    @Test fun `platform enrollment conflict is actionable and does not expose a runtime class name`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val overview = MutableStateFlow(EnterpriseOverview(
                RealmSelection(RealmAccess.Personal, 1), EnterpriseSessionPhase.SIGNED_OUT,
                null, null, null, null, null, null, null, false,
            ))
            val confirmation = EnterpriseJoinConfirmation(kotlin.uuid.Uuid.random(), "https://platform.example")
            val service = mockk<EnterpriseApplicationService>()
            every { service.observe() } returns overview
            coEvery { service.join("payload") } returns confirmation
            coEvery { service.confirmJoin(confirmation) } throws PlatformHttpException(409,
                PlatformProblem("about:blank", "Installation is already bound to another user", 409,
                    "installation_user_conflict"),
                "Installation is already bound to another user")
            val vm = EnterpriseVM(service)
            store.put("enterprise", vm)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.error.collect {} }

            vm.join("payload")
            runCurrent()
            vm.confirmJoin()
            runCurrent()

            assertEquals(net.weero.measix.pilot.R.string.enterprise_installation_user_conflict, vm.error.value?.resource)
            assertEquals("HTTP 409: installation_user_conflict: Installation is already bound to another user",
                vm.error.value?.detail)

            val used = EnterpriseJoinConfirmation(kotlin.uuid.Uuid.random(), "https://platform.example")
            coEvery { service.join("used") } returns used
            coEvery { service.confirmJoin(used) } throws PlatformHttpException(409,
                PlatformProblem("about:blank", "Enrollment code already used", 409, "enrollment_already_used"),
                "Enrollment code already used")
            vm.join("used")
            runCurrent()
            vm.confirmJoin()
            runCurrent()
            assertEquals(net.weero.measix.pilot.R.string.enterprise_enrollment_already_used, vm.error.value?.resource)
            assertEquals("HTTP 409: enrollment_already_used: Enrollment code already used", vm.error.value?.detail)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun `portal failure retains actionable diagnostics and cannot overwrite a replacement request`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val packet = exampleEnterprisePackage()
            val access = RealmAccess.Enterprise(packet.identity.scope, "session")
            val selection = RealmSelection(access, 1)
            val overview = MutableStateFlow(EnterpriseOverview(selection, EnterpriseSessionPhase.READY,
                "Example", "Member", access, 1, 1000, null, null, false))
            val service = mockk<EnterpriseApplicationService>()
            every { service.observe() } returns overview
            val vm = EnterpriseVM(service)
            store.put("enterprise", vm)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.error.collect {} }
            runCurrent()
            vm.showPortal()
            val original = requireNotNull(vm.portal.value)
            val unavailable = net.weero.measix.pilot.service.portal.PortalHostUnavailable("vendor.webview 130", listOf("REQUIRED_FEATURE"))
            vm.portalFailed(original, unavailable)
            runCurrent()
            assertNull(vm.portal.value)
            assertEquals(net.weero.measix.pilot.R.string.enterprise_portal_unavailable, vm.error.value?.resource)
            assertEquals(listOf("vendor.webview 130", "REQUIRED_FEATURE"), vm.error.value?.arguments)
            vm.showPortal()
            val replacement = requireNotNull(vm.portal.value)
            vm.portalFailed(original, unavailable)
            runCurrent()
            assertEquals(replacement, vm.portal.value)
            assertNull(vm.error.value)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun `abnormal portal closure is visible while user closure is silent`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val packet = exampleEnterprisePackage()
            val access = RealmAccess.Enterprise(packet.identity.scope, "session")
            val selection = RealmSelection(access, 1)
            val overview = MutableStateFlow(EnterpriseOverview(selection, EnterpriseSessionPhase.READY,
                "Example", "Member", access, 1, 1000, null, null, false))
            val service = mockk<EnterpriseApplicationService>()
            every { service.observe() } returns overview
            val vm = EnterpriseVM(service)
            store.put("enterprise", vm)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.error.collect {} }
            runCurrent()

            vm.showPortal()
            val failed = requireNotNull(vm.portal.value)
            vm.portalClosed(failed, net.weero.measix.pilot.service.portal.PortalClosure(
                "document", net.weero.measix.pilot.service.portal.PortalCloseReason.HOST_FAILURE))
            runCurrent()
            assertNull(vm.portal.value)
            assertEquals(net.weero.measix.pilot.R.string.enterprise_portal_open_failed, vm.error.value?.resource)
            assertEquals(listOf("host_failure"), vm.error.value?.arguments)

            vm.showPortal()
            val dismissed = requireNotNull(vm.portal.value)
            vm.portalClosed(dismissed, net.weero.measix.pilot.service.portal.PortalClosure(
                "document", net.weero.measix.pilot.service.portal.PortalCloseReason.USER_REQUEST))
            runCurrent()
            assertNull(vm.portal.value)
            assertNull(vm.error.value)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun `space changes hide prior errors and reject delayed sync failures`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val packet = exampleEnterprisePackage()
            val access = RealmAccess.Enterprise(packet.identity.scope, "session")
            val selection = RealmSelection(access, 1)
            val overview = MutableStateFlow(EnterpriseOverview(selection, EnterpriseSessionPhase.READY,
                "Example", "Member", access, 1, 1000, null, null, false))
            val service = mockk<EnterpriseApplicationService>()
            every { service.observe() } returns overview
            val syncing = CompletableDeferred<Unit>()
            coEvery { service.synchronize(access) } coAnswers { syncing.await() }
            val vm = EnterpriseVM(service)
            store.put("enterprise", vm)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.error.collect {} }
            runCurrent()
            vm.scanFailed()
            runCurrent()
            assertNotNull(vm.error.value)
            overview.value = overview.value.copy(selection = RealmSelection(RealmAccess.Personal, 2))
            runCurrent()
            assertNull(vm.error.value)
            overview.value = overview.value.copy(selection = RealmSelection(access, 3))
            runCurrent()
            vm.synchronize()
            runCurrent()
            overview.value = overview.value.copy(selection = RealmSelection(RealmAccess.Personal, 4))
            runCurrent()
            syncing.completeExceptionally(IllegalStateException("late failure"))
            runCurrent()
            assertNull(vm.error.value)
            assertFalse(vm.busy.value)
            overview.value = overview.value.copy(selection = RealmSelection(access, 5))
            runCurrent()
            assertNull(vm.error.value)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

}
