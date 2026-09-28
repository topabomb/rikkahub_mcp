package net.weero.measix.pilot.ui.pages.favorite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.weero.measix.pilot.service.FavoriteService
import net.weero.measix.pilot.service.FavoriteDirectoryState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FavoriteVMTest {
    @Test fun `failed directory keeps original cause visible and retry can restore an empty result`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val service = mockk<FavoriteService>()
            val failure = IllegalStateException("favorite query failed", java.io.IOException("database page unreadable"))
            every { service.observeNodeFavorites() } returns flowOf(FavoriteDirectoryState(failure = failure))
            val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = FavoriteVM(service) as T
            })[FavoriteVM::class.java]
            runCurrent()
            assertFalse(vm.directory.value.loading)
            assertTrue(vm.directory.value.items.isEmpty())
            assertTrue(requireNotNull(vm.directory.value.diagnostic).contains("IllegalStateException: favorite query failed"))
            assertTrue(requireNotNull(vm.directory.value.diagnostic).contains("Caused by: IOException: database page unreadable"))
            assertSame(failure, vm.directory.value.failure)
            every { service.observeNodeFavorites() } returns flowOf(FavoriteDirectoryState())
            vm.retry()
            runCurrent()
            assertNull(vm.directory.value.diagnostic)
            assertFalse(vm.directory.value.loading)
            assertTrue(vm.directory.value.items.isEmpty())
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
