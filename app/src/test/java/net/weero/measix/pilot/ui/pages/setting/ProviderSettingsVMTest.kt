package net.weero.measix.pilot.ui.pages.setting

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import net.weero.measix.pilot.service.ProviderSettingsApplicationService
import net.weero.measix.pilot.service.ProviderToolProbeResult
import net.weero.measix.pilot.utils.UiState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderSettingsVMTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `all probes publish typed results for one run`() = runTest(dispatcher) {
        val service = mockk<ProviderSettingsApplicationService>()
        val model = Model(modelId = "test")
        val provider = ProviderSetting.OpenAI(models = listOf(model))
        every { service.observeProvider(provider.id) } returns flowOf(provider)
        coEvery { service.testNonStreaming(provider, model) } returns "ok"
        coEvery { service.testStreaming(provider, model, any()) } answers {
            arg<(String) -> Unit>(2).invoke("stream")
        }
        coEvery { service.testToolCall(provider, model) } returns ProviderToolProbeResult.Called("clock", "{}")
        val vm = ProviderSettingsVM(provider.id, service)

        vm.openConnectionTest(provider)
        vm.runConnectionTest()
        advanceUntilIdle()

        val result = vm.state.value.connectionTest
        assertEquals("ok", (result.nonStreaming as UiState.Success).data)
        assertEquals("stream", result.streamingText)
        assertTrue(result.streaming is UiState.Success)
        assertTrue((result.toolCall as UiState.Success).data is ProviderToolProbeResult.Called)
    }

    @Test
    fun `dismiss cancels the owner and stale completion cannot republish`() = runTest(dispatcher) {
        val service = mockk<ProviderSettingsApplicationService>()
        val model = Model(modelId = "test")
        val provider = ProviderSetting.OpenAI(models = listOf(model))
        every { service.observeProvider(provider.id) } returns flowOf(provider)
        val blocked = CompletableDeferred<String>()
        coEvery { service.testNonStreaming(provider, model) } coAnswers { blocked.await() }
        coEvery { service.testStreaming(provider, model, any()) } coAnswers {
            blocked.await()
        }
        coEvery { service.testToolCall(provider, model) } coAnswers {
            blocked.await()
            ProviderToolProbeResult.NotCalled("")
        }
        val vm = ProviderSettingsVM(provider.id, service)

        vm.openConnectionTest(provider)
        vm.runConnectionTest()
        runCurrent()
        vm.dismissConnectionTest()
        blocked.complete("late")
        advanceUntilIdle()

        assertFalse(vm.state.value.showConnectionTest)
        assertEquals(ProviderConnectionTestState(), vm.state.value.connectionTest)
    }

    @Test
    fun `one failed probe does not cancel successful siblings`() = runTest(dispatcher) {
        val service = mockk<ProviderSettingsApplicationService>()
        val model = Model(modelId = "test")
        val provider = ProviderSetting.OpenAI(models = listOf(model))
        every { service.observeProvider(provider.id) } returns flowOf(provider)
        coEvery { service.testNonStreaming(provider, model) } throws IllegalStateException("offline")
        coEvery { service.testStreaming(provider, model, any()) } answers {
            arg<(String) -> Unit>(2).invoke("stream")
        }
        coEvery { service.testToolCall(provider, model) } returns ProviderToolProbeResult.NotCalled("plain")
        val vm = ProviderSettingsVM(provider.id, service)

        vm.openConnectionTest(provider)
        vm.runConnectionTest()
        advanceUntilIdle()

        val result = vm.state.value.connectionTest
        assertTrue(result.nonStreaming is UiState.Error)
        assertTrue(result.streaming is UiState.Success)
        assertEquals("stream", result.streamingText)
        assertTrue(result.toolCall is UiState.Success)
    }

    @Test
    fun `stream callback from previous run cannot append to the current run`() = runTest(dispatcher) {
        val service = mockk<ProviderSettingsApplicationService>()
        val model = Model(modelId = "test")
        val provider = ProviderSetting.OpenAI(models = listOf(model))
        every { service.observeProvider(provider.id) } returns flowOf(provider)
        val callbacks = mutableListOf<(String) -> Unit>()
        coEvery { service.testNonStreaming(provider, model) } returns "ok"
        coEvery { service.testStreaming(provider, model, any()) } answers {
            callbacks += arg<(String) -> Unit>(2)
        }
        coEvery { service.testToolCall(provider, model) } returns ProviderToolProbeResult.NotCalled("")
        val vm = ProviderSettingsVM(provider.id, service)

        vm.openConnectionTest(provider)
        vm.runConnectionTest()
        advanceUntilIdle()
        vm.runConnectionTest()
        advanceUntilIdle()
        callbacks.first().invoke("stale")
        callbacks.last().invoke("current")

        assertEquals("current", vm.state.value.connectionTest.streamingText)
    }
}
