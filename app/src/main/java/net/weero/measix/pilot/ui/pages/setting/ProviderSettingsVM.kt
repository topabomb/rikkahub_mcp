package net.weero.measix.pilot.ui.pages.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import net.weero.measix.pilot.service.ProviderSettingsApplicationService
import net.weero.measix.pilot.service.ProviderToolProbeResult
import net.weero.measix.pilot.utils.UiState
import kotlin.uuid.Uuid

class ProviderSettingsVM(
    private val providerId: Uuid,
    private val service: ProviderSettingsApplicationService,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ProviderSettingsUiState())
    val state: StateFlow<ProviderSettingsUiState> = mutableState.asStateFlow()

    private var catalogJob: Job? = null
    private var catalogRun = 0L
    private var connectionJob: Job? = null
    private var connectionRun = 0L
    private var connectionProvider: ProviderSetting? = null

    init {
        viewModelScope.launch {
            service.observeProvider(providerId).collectLatest { provider ->
                mutableState.value = mutableState.value.copy(provider = provider)
            }
        }
    }

    fun loadModelCatalog() {
        val provider = mutableState.value.provider ?: return
        catalogJob?.cancel()
        val run = ++catalogRun
        mutableState.value = mutableState.value.copy(modelCatalog = UiState.Loading)
        catalogJob = viewModelScope.launch {
            try {
                val models = service.listModels(provider)
                if (run == catalogRun) {
                    mutableState.value = mutableState.value.copy(modelCatalog = UiState.Success(models))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (run == catalogRun) {
                    mutableState.value = mutableState.value.copy(modelCatalog = UiState.Error(error))
                }
            }
        }
    }

    fun openConnectionTest(provider: ProviderSetting) {
        connectionJob?.cancel()
        connectionRun++
        connectionProvider = provider
        mutableState.value = mutableState.value.copy(
            showConnectionTest = true,
            selectedConnectionModelId = provider.models.firstOrNull { it.type == me.rerere.ai.provider.ModelType.CHAT }?.id,
            connectionTest = ProviderConnectionTestState(),
        )
    }

    fun selectConnectionModel(id: Uuid?) {
        connectionJob?.cancel()
        connectionRun++
        mutableState.value = mutableState.value.copy(
            selectedConnectionModelId = id,
            connectionTest = ProviderConnectionTestState(),
        )
    }

    fun dismissConnectionTest() {
        connectionJob?.cancel()
        connectionRun++
        connectionProvider = null
        mutableState.value = mutableState.value.copy(
            showConnectionTest = false,
            connectionTest = ProviderConnectionTestState(),
        )
    }

    fun runConnectionTest() {
        val provider = connectionProvider ?: return
        val modelId = mutableState.value.selectedConnectionModelId ?: return
        val model = provider.models.firstOrNull { it.id == modelId } ?: return
        connectionJob?.cancel()
        val run = ++connectionRun
        mutableState.value = mutableState.value.copy(
            connectionTest = ProviderConnectionTestState(
                nonStreaming = UiState.Loading,
                streaming = UiState.Loading,
                toolCall = UiState.Loading,
            )
        )
        connectionJob = viewModelScope.launch {
            supervisorScope {
                launchProbe(run, ProviderConnectionProbe.NON_STREAMING) {
                    service.testNonStreaming(provider, model)
                }
                launchProbe(run, ProviderConnectionProbe.STREAMING) {
                    service.testStreaming(provider, model) { text -> appendStreamingText(run, text) }
                    ""
                }
                launchProbe(run, ProviderConnectionProbe.TOOL_CALL) {
                    service.testToolCall(provider, model)
                }
            }
        }
    }

    fun applyRegistryCapabilities(model: Model): Model = service.applyRegistryCapabilities(model)

    fun saveConfiguration(provider: ProviderSetting, onSuccess: () -> Unit) = launchMutation(onSuccess) {
        service.saveConfiguration(providerId, provider)
    }

    fun deleteProvider(onSuccess: () -> Unit) = launchMutation(onSuccess) { service.deleteProvider(providerId) }

    fun addModel(model: Model) = launchMutation { service.addModel(providerId, model) }

    fun removeModel(modelId: Uuid) = launchMutation { service.removeModel(providerId, modelId) }

    fun editModel(model: Model) = launchMutation { service.editModel(providerId, model) }

    fun addModels(models: List<Model>) = launchMutation { service.addModels(providerId, models) }

    fun removeModelsByModelIds(modelIds: Set<String>) = launchMutation {
        service.removeModelsByModelIds(providerId, modelIds)
    }

    fun moveModel(fromModelId: Uuid, toModelId: Uuid) = launchMutation {
        service.moveModel(providerId, fromModelId, toModelId)
    }

    fun consumeMutationFailure() {
        mutableState.value = mutableState.value.copy(mutationFailed = false)
    }

    private fun launchMutation(onSuccess: () -> Unit = {}, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                mutableState.value = mutableState.value.copy(mutationFailed = false)
                block()
                onSuccess()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(mutationFailed = true)
            }
        }
    }

    private fun <T> kotlinx.coroutines.CoroutineScope.launchProbe(
        run: Long,
        probe: ProviderConnectionProbe,
        block: suspend () -> T,
    ) = launch {
        try {
            publishProbe(run, probe, UiState.Success(block()))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            publishProbe(run, probe, UiState.Error(error))
        }
    }

    private fun appendStreamingText(run: Long, text: String) {
        if (run != connectionRun) return
        val current = mutableState.value.connectionTest
        mutableState.value = mutableState.value.copy(
            connectionTest = current.copy(streamingText = current.streamingText + text)
        )
    }

    private fun publishProbe(run: Long, probe: ProviderConnectionProbe, result: UiState<*>) {
        if (run != connectionRun) return
        val current = mutableState.value.connectionTest
        @Suppress("UNCHECKED_CAST")
        val updated = when (probe) {
            ProviderConnectionProbe.NON_STREAMING -> current.copy(nonStreaming = result as UiState<String>)
            ProviderConnectionProbe.STREAMING -> current.copy(streaming = result as UiState<String>)
            ProviderConnectionProbe.TOOL_CALL -> current.copy(toolCall = result as UiState<ProviderToolProbeResult>)
        }
        mutableState.value = mutableState.value.copy(connectionTest = updated)
    }
}

data class ProviderSettingsUiState(
    val provider: ProviderSetting? = null,
    val modelCatalog: UiState<List<Model>> = UiState.Idle,
    val showConnectionTest: Boolean = false,
    val selectedConnectionModelId: Uuid? = null,
    val connectionTest: ProviderConnectionTestState = ProviderConnectionTestState(),
    val mutationFailed: Boolean = false,
)

data class ProviderConnectionTestState(
    val nonStreaming: UiState<String> = UiState.Idle,
    val streaming: UiState<String> = UiState.Idle,
    val streamingText: String = "",
    val toolCall: UiState<ProviderToolProbeResult> = UiState.Idle,
)

private enum class ProviderConnectionProbe {
    NON_STREAMING,
    STREAMING,
    TOOL_CALL,
}
