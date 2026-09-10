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
    @Test fun `closing an editor invalidates delayed refresh success failure and edit notice`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val packet = exampleEnterprisePackage()
            val selection = RealmSelection(RealmAccess.Enterprise(packet.identity.scope, "session"), 1)
            val overview = MutableStateFlow(EnterpriseOverview(selection, EnterpriseSessionPhase.READY,
                "Example", "Member", selection.access as RealmAccess.Enterprise, true, 1, 1000, null, null, false))
            val original = LocalEnterpriseConfigurationUiModel(selection, "revision", 1,
                packet.configuration.policy, packet.configuration.models, packet.configuration.gateways)
            val service = mockk<EnterpriseApplicationService>()
            every { service.observe() } returns overview
            var reading = CompletableDeferred<LocalEnterpriseConfigurationUiModel>()
            coEvery { service.localConfiguration(selection) } coAnswers { reading.await() }
            val vm = EnterpriseVM(service)
            store.put("enterprise", vm)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.overview.collect {} }
            runCurrent()
            reading.complete(original)
            vm.showLocalConfiguration()
            runCurrent()
            assertEquals(original, vm.localConfiguration.value)

            reading = CompletableDeferred()
            vm.showLocalConfiguration()
            runCurrent()
            vm.dismissLocalConfiguration()
            reading.complete(original)
            runCurrent()
            assertNull(vm.localConfiguration.value)

            reading = CompletableDeferred()
            vm.showLocalConfiguration()
            runCurrent()
            vm.dismissLocalConfiguration()
            reading.completeExceptionally(EnterpriseConfigurationException("local_enterprise_configuration_changed"))
            runCurrent()
            assertNull(vm.error.value)
            assertNull(vm.localConfiguration.value)

            reading = CompletableDeferred<LocalEnterpriseConfigurationUiModel>().apply { complete(original) }
            vm.showLocalConfiguration()
            runCurrent()
            val editing = CompletableDeferred<LocalEnterpriseConfigurationEditResult>()
            val change = LocalEnterpriseConfigurationChange.Policy(original.policy.copy(allowLocalMcp = false))
            coEvery { service.changeLocalConfiguration(original, change) } coAnswers { editing.await() }
            vm.changeLocalConfiguration(original, change)
            runCurrent()
            vm.dismissLocalConfiguration()
            editing.complete(LocalEnterpriseConfigurationEditResult(original.copy(revision = "next", generation = 2), true))
            runCurrent()
            assertNull(vm.localConfiguration.value)
            assertNull(vm.notice.value)
            assertFalse(vm.busy.value)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
