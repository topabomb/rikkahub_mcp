package net.weero.measix.pilot.data.datastore

import me.rerere.ai.provider.ProviderSetting
import me.rerere.search.SearchServiceOptions
import kotlin.uuid.Uuid

/**
 * 把 DataStore 中的持久化快照物化为应用实际消费的 Settings。
 *
 * 这里有意与 [Settings.normalizeForPersistence] 分离：内置项补齐、运行时属性恢复、去重和失效引用
 * 清理属于读取模型，不应静默改写磁盘。写入成功后的内存发布也必须走同一函数，避免手工发布值与
 * 随后的 DataStore 回读值不一致。
 */
internal fun Settings.materializeForRead(): Settings {
    val materializedProviders = providers.mergeDefaults(DEFAULT_PROVIDERS, ProviderSetting::id) { provider ->
        DEFAULT_PROVIDERS.find { it.id == provider.id }?.let { default ->
            provider.copyProvider(
                builtIn = default.builtIn,
                description = default.description,
                shortDescription = default.shortDescription,
            )
        } ?: provider
    }
    val materializedAssistants = assistants.mergeDefaults(DEFAULT_ASSISTANTS, { it.id }) { it.copy() }
    val materializedTtsProviders = ttsProviders.mergeDefaults(DEFAULT_TTS_PROVIDERS, { it.id }) { it.copyProvider() }

    val withDefaults = copy(
        providers = materializedProviders,
        assistants = materializedAssistants,
        ttsProviders = materializedTtsProviders,
    )

    val validMcpServerIds = withDefaults.mcpServers.mapTo(HashSet()) { it.id }
    val validModeInjectionIds = withDefaults.modeInjections.mapTo(HashSet()) { it.id }
    val validQuickMessageIds = withDefaults.quickMessages.mapTo(HashSet()) { it.id }
    val modelIds = withDefaults.providers
        .asSequence()
        .flatMap { it.models.asSequence() }
        .mapTo(HashSet()) { it.id }
    val distinctAsrProviders = withDefaults.asrProviders.distinctBy { it.id }

    return withDefaults.copy(
        providers = withDefaults.providers.distinctBy { it.id }.map { provider ->
            when (provider) {
                is ProviderSetting.OpenAI -> provider.copy(models = provider.models.distinctBy { it.id })
                is ProviderSetting.Google -> provider.copy(models = provider.models.distinctBy { it.id })
                is ProviderSetting.Claude -> provider.copy(models = provider.models.distinctBy { it.id })
            }
        },
        assistants = withDefaults.assistants.distinctBy { it.id }.map { assistant ->
            assistant.copy(
                mcpServers = assistant.mcpServers.filterTo(LinkedHashSet()) { it in validMcpServerIds },
                modeInjectionIds = assistant.modeInjectionIds.filterTo(LinkedHashSet()) {
                    it in validModeInjectionIds
                },
                quickMessageIds = assistant.quickMessageIds.filterTo(LinkedHashSet()) {
                    it in validQuickMessageIds
                },
            )
        },
        ttsProviders = withDefaults.ttsProviders.distinctBy { it.id },
        asrProviders = distinctAsrProviders,
        selectedASRProviderId = withDefaults.selectedASRProviderId
            ?.takeIf { id -> distinctAsrProviders.any { provider -> provider.id == id } }
            ?: distinctAsrProviders.firstOrNull()?.id,
        favoriteModels = withDefaults.favoriteModels.filter { it in modelIds },
        searchServices = withDefaults.searchServices.ifEmpty { listOf(SearchServiceOptions.DEFAULT) }.distinctBy { it.id },
        modeInjections = withDefaults.modeInjections.distinctBy { it.id },
        quickMessages = withDefaults.quickMessages.distinctBy { it.id },
    )
}

private fun <T> List<T>.mergeDefaults(
    defaults: List<T>,
    idOf: (T) -> Uuid,
    materialize: (T) -> T,
): List<T> = (ifEmpty { defaults } + defaults).distinctBy(idOf).map(materialize)
