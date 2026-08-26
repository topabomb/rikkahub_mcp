package net.weero.measix.pilot.ui.components.ai

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.rerere.ai.provider.ProviderSetting
import net.weero.measix.pilot.service.ProviderSettingsApplicationService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderBalanceVMTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `one provider draft has one owned request and typed result`() = runTest(dispatcher) {
        val service = mockk<ProviderSettingsApplicationService>()
        val provider = ProviderSetting.OpenAI(baseUrl = "https://example.test/v1", apiKey = "draft-key")
        coEvery { service.getBalance(provider) } returns "12.50"
        val vm = ProviderBalanceVM(service)

        vm.request(provider)
        vm.request(provider)
        assertEquals(ProviderBalanceUiState.Loading, vm.balances.value[provider.id])
        advanceUntilIdle()

        assertEquals(ProviderBalanceUiState.Available("12.50"), vm.balances.value[provider.id])
        coVerify(exactly = 1) { service.getBalance(provider) }
    }

    @Test
    fun `unavailable result is typed and can be retried`() = runTest(dispatcher) {
        val service = mockk<ProviderSettingsApplicationService>()
        val provider = ProviderSetting.OpenAI(baseUrl = "https://example.test/v1")
        coEvery { service.getBalance(provider) } throws IllegalStateException("raw diagnostic") andThen "8.00"
        val vm = ProviderBalanceVM(service)

        vm.request(provider)
        advanceUntilIdle()
        assertEquals(ProviderBalanceUiState.Unavailable, vm.balances.value[provider.id])

        vm.request(provider)
        advanceUntilIdle()
        assertEquals(ProviderBalanceUiState.Available("8.00"), vm.balances.value[provider.id])
        coVerify(exactly = 2) { service.getBalance(provider) }
    }

    @Test
    fun `new draft revision cancels the previous request and only latest result is published`() = runTest(dispatcher) {
        val service = mockk<ProviderSettingsApplicationService>()
        val firstStarted = CompletableDeferred<Unit>()
        val firstCancelled = CompletableDeferred<Unit>()
        val original = ProviderSetting.OpenAI(baseUrl = "https://old.test/v1", apiKey = "old-key")
        val revised = original.copy(baseUrl = "https://new.test/v1", apiKey = "new-key")
        coEvery { service.getBalance(original) } coAnswers {
            firstStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                firstCancelled.complete(Unit)
            }
        }
        coEvery { service.getBalance(revised) } returns "21.00"
        val vm = ProviderBalanceVM(service)

        vm.request(original)
        dispatcher.scheduler.runCurrent()
        firstStarted.await()
        vm.request(revised)
        advanceUntilIdle()

        firstCancelled.await()
        assertEquals(setOf(original.id), vm.balances.value.keys)
        assertEquals(ProviderBalanceUiState.Available("21.00"), vm.balances.value[original.id])
        coVerify(exactly = 1) { service.getBalance(original) }
        coVerify(exactly = 1) { service.getBalance(revised) }
    }
}
