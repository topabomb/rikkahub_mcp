package net.weero.measix.pilot.service

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.map
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.utils.SimpleCache
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

/**
 * Provider 设置功能访问 Provider SDK 的唯一应用边界。
 *
 * UI 与 ViewModel 只表达“读取模型目录、读取余额、验证连接”等意图，不持有 Provider 容器，
 * 也不解释 Provider 返回的线协议对象。该服务不持有页面运行态；取消与过期结果隔离由页面
 * ViewModel 负责，协程取消会原样传播到 Provider SDK。
 */
class ProviderSettingsApplicationService(
    private val providerManager: ProviderManager,
    private val settingsStore: SettingsStore,
) {
    private val balanceCache = SimpleCache.builder<ProviderBalanceCacheKey, String>()
        .expireAfterWrite(2, TimeUnit.MINUTES)
        .build()

    fun observeProvider(id: Uuid) = settingsStore.effectiveSettings.map { it.settings }.map { settings ->
        settings.providers.firstOrNull { it.id == id }
    }

    suspend fun saveConfiguration(id: Uuid, edited: ProviderSetting) = updateProvider(id) { latest ->
        if (edited.id != id) return@updateProvider latest
        edited.copyProvider(
            models = latest.models,
            builtIn = latest.builtIn,
            description = latest.description,
            shortDescription = latest.shortDescription,
        )
    }

    suspend fun deleteProvider(id: Uuid) {
        settingsStore.updateLocal { current ->
            current.copy(providers = current.providers.filterNot { it.id == id })
        }
    }

    suspend fun addModel(id: Uuid, model: Model) = updateProvider(id) { it.addModel(model) }

    suspend fun removeModel(id: Uuid, modelId: Uuid) = updateProvider(id) { latest ->
        latest.models.firstOrNull { it.id == modelId }?.let(latest::delModel) ?: latest
    }

    suspend fun editModel(id: Uuid, model: Model) = updateProvider(id) { it.editModel(model) }

    suspend fun addModels(id: Uuid, models: List<Model>) = updateProvider(id) { latest ->
        val additions = models.filter { model -> latest.models.none { it.modelId == model.modelId } }
        latest.copyProvider(models = latest.models + additions)
    }

    suspend fun removeModelsByModelIds(id: Uuid, modelIds: Set<String>) = updateProvider(id) { latest ->
        latest.copyProvider(models = latest.models.filterNot { it.modelId in modelIds })
    }

    suspend fun moveModel(id: Uuid, fromModelId: Uuid, toModelId: Uuid) = updateProvider(id) { latest ->
        val fromIndex = latest.models.indexOfFirst { it.id == fromModelId }
        val toIndex = latest.models.indexOfFirst { it.id == toModelId }
        if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) latest
        else latest.moveMove(fromIndex, toIndex)
    }

    suspend fun listModels(setting: ProviderSetting): List<Model> =
        providerManager.getProviderByType(setting)
            .listModels(setting)
            .map(::applyRegistryCapabilities)
            .sortedBy(Model::modelId)

    suspend fun getBalance(setting: ProviderSetting.OpenAI): String {
        val key = ProviderBalanceCacheKey(
            providerId = setting.id,
            requestFingerprint = setting.balanceRequestFingerprint(),
        )
        balanceCache.getIfPresent(key)?.let { return it }
        return providerManager.getProviderByType(setting)
            .getBalance(setting)
            .also { balanceCache.put(key, it) }
    }

    fun applyRegistryCapabilities(model: Model): Model = model.copy(
        inputModalities = ModelRegistry.MODEL_INPUT_MODALITIES.getData(model.modelId),
        outputModalities = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(model.modelId),
        abilities = ModelRegistry.MODEL_ABILITIES.getData(model.modelId),
    )

    suspend fun testNonStreaming(setting: ProviderSetting, model: Model): String {
        val response = providerManager.getProviderByType(setting).generateText(
            providerSetting = setting,
            messages = TEST_MESSAGES,
            params = params(model),
        )
        return response.choices.firstOrNull()?.message.textContent()
    }

    suspend fun testStreaming(
        setting: ProviderSetting,
        model: Model,
        onText: (String) -> Unit,
    ) {
        providerManager.getProviderByType(setting).streamText(
            providerSetting = setting,
            messages = TEST_MESSAGES,
            params = params(model),
        ).collect { chunk ->
            chunk.choices.firstOrNull()?.delta?.parts
                ?.filterIsInstance<UIMessagePart.Text>()
                ?.forEach { onText(it.text) }
        }
    }

    suspend fun testToolCall(setting: ProviderSetting, model: Model): ProviderToolProbeResult {
        val testTool = Tool(
            name = "get_current_time",
            description = "Get the current date and time.",
            execute = { emptyList() },
        )
        val response = providerManager.getProviderByType(setting).generateText(
            providerSetting = setting,
            messages = listOf(
                UIMessage.system("You are a helpful assistant"),
                UIMessage.user("Use the get_current_time tool."),
            ),
            params = params(model).copy(tools = listOf(testTool)),
        )
        val message = response.choices.firstOrNull()?.message
        val call = message?.parts?.filterIsInstance<UIMessagePart.Tool>()?.firstOrNull()
        return if (call != null) {
            ProviderToolProbeResult.Called(call.toolName, call.input)
        } else {
            ProviderToolProbeResult.NotCalled(message.textContent())
        }
    }

    private fun params(model: Model) = TextGenerationParams(
        model = model,
        customHeaders = model.customHeaders,
        customBody = model.customBodies,
    )

    private fun UIMessage?.textContent(): String = this?.parts
        ?.filterIsInstance<UIMessagePart.Text>()
        ?.joinToString("") { it.text }
        .orEmpty()

    private suspend fun updateProvider(id: Uuid, transform: (ProviderSetting) -> ProviderSetting) {
        settingsStore.updateLocal { current ->
            current.copy(
                providers = current.providers.map { provider ->
                    if (provider.id == id) transform(provider) else provider
                }
            )
        }
    }

    private companion object {
        val TEST_MESSAGES = listOf(
            UIMessage.system("You are a helpful assistant"),
            UIMessage.user("hello"),
        )
    }

    private data class ProviderBalanceCacheKey(
        val providerId: Uuid,
        val requestFingerprint: String,
    )
}

internal fun ProviderSetting.OpenAI.balanceRequestFingerprint(): String {
    val requestMaterial = listOf(
        baseUrl,
        apiKey,
        balanceOption.apiPath,
        balanceOption.resultPath,
    ).joinToString(separator = "\u0000")
    return MessageDigest.getInstance("SHA-256")
        .digest(requestMaterial.toByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }
}

sealed interface ProviderToolProbeResult {
    data class Called(val toolName: String, val input: String) : ProviderToolProbeResult
    data class NotCalled(val responseText: String) : ProviderToolProbeResult
}
