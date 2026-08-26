package net.weero.measix.pilot.ui.pages.debug

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsLockedException
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.toEffectiveSettingsSnapshot
import net.weero.measix.pilot.service.ConversationApplicationService
import net.weero.measix.pilot.service.ConversationQueryService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DebugVMTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `managed lock is reported instead of escaping the debug action`() = runTest(dispatcher) {
        val settingsStore = mockk<SettingsStore>()
        val query = mockk<ConversationQueryService>()
        coEvery { query.count() } returns 0
        every { settingsStore.effectiveSettings } returns MutableStateFlow(Settings().toEffectiveSettingsSnapshot())
        coEvery { settingsStore.updateLocal(any()) } throws SettingsLockedException("defaults/chatModelId", "Managed")
        val vm = DebugVM(settingsStore, query, mockk<ConversationApplicationService>())
        val reported = async { vm.lockedChanges.first() }
        runCurrent()

        vm.updateSettings { it }
        runCurrent()

        assertEquals("Managed", reported.await().reason)
        coVerify(exactly = 1) { settingsStore.updateLocal(any()) }
    }
}
