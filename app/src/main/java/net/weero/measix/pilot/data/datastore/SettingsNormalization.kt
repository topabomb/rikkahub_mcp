package net.weero.measix.pilot.data.datastore

import me.rerere.ai.provider.ProviderSetting

/**
 * 把 DataStore 中的持久化快照物化为应用实际消费的 Settings。
 *
 * 这里有意与 [Settings.normalizeForPersistence] 分离：内置项补齐、运行时属性恢复、去重和失效引用
 * 清理属于读取模型，不应静默改写磁盘。写入成功后的内存发布也必须走同一函数，避免手工发布值与
 * 随后的 DataStore 回读值不一致。
 */
internal fun Settings.materializeForRead(): Settings {
    var materializedProviders = providers.ifEmpty { DEFAULT_PROVIDERS }.toMutableList()
    DEFAULT_PROVIDERS.forEach { defaultProvider ->
        if (materializedProviders.none { it.id == defaultProvider.id }) {
            materializedProviders.add(defaultProvider.copyProvider())
        }
    }
    materializedProviders = materializedProviders.map { provider ->
        val defaultProvider = DEFAULT_PROVIDERS.find { it.id == provider.id }
        if (defaultProvider != null) {
            provider.copyProvider(
                builtIn = defaultProvider.builtIn,
                description = defaultProvider.description,
                shortDescription = defaultProvider.shortDescription,
            )
        } else {
            provider
        }
    }.toMutableList()

    val materializedAssistants = assistants.ifEmpty { DEFAULT_ASSISTANTS }.toMutableList()
    DEFAULT_ASSISTANTS.forEach { defaultAssistant ->
        if (materializedAssistants.none { it.id == defaultAssistant.id }) {
            materializedAssistants.add(defaultAssistant.copy())
        }
    }

    val materializedTtsProviders = ttsProviders.ifEmpty { DEFAULT_TTS_PROVIDERS }.toMutableList()
    DEFAULT_TTS_PROVIDERS.forEach { defaultTtsProvider ->
        if (materializedTtsProviders.none { provider -> provider.id == defaultTtsProvider.id }) {
            materializedTtsProviders.add(defaultTtsProvider.copyProvider())
        }
    }

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
        modeInjections = withDefaults.modeInjections.distinctBy { it.id },
        quickMessages = withDefaults.quickMessages.distinctBy { it.id },
    )
}
