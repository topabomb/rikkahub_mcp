package net.weero.measix.pilot.service

import androidx.lifecycle.ViewModelStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.rerere.common.configuration.EnterpriseAuthority
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.ui.pages.stats.StatsVM
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScopedStatisticsPresentationTest {
    @Test fun `switch cancels old load and unavailable or failed access clears counts`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val enterprise = RealmAccess.Enterprise(ConfigurationScope.Enterprise(
                EnterpriseAuthority("local:example", "deployment"), "alice"), "session")
            val access = MutableStateFlow<RealmAccess?>(RealmAccess.Personal)
            val query = mockk<ConversationQueryService>()
            every { query.observeCurrentAccess() } returns access
            val statistics = mockk<StatsQueryService>()
            var cancelled = false
            coEvery { statistics.load(RealmAccess.Personal, any()) } coAnswers {
                try { awaitCancellation() } finally { cancelled = true }
            }
            coEvery { statistics.load(enterprise, any()) } returns StatsSnapshot(
                7, 11, 101, 22, 33, 0, 0, emptyMap(), 9,
            )
            val vm = StatsVM(statistics, query)
            store.put("stats", vm)
            runCurrent()
            assertTrue(vm.stats.value.isLoading)
            access.value = enterprise
            runCurrent()
            assertTrue(cancelled)
            assertEquals(7, vm.stats.value.totalConversations)
            access.value = null
            runCurrent()
            assertTrue(vm.stats.value.hasFailed)
            assertEquals(0, vm.stats.value.totalConversations)
            coEvery { statistics.load(enterprise, any()) } throws IllegalStateException("query failed")
            access.value = enterprise
            runCurrent()
            assertTrue(vm.stats.value.hasFailed)
            assertFalse(vm.stats.value.isLoading)
            assertEquals(0, vm.stats.value.totalConversations)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
