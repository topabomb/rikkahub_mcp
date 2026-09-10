package net.weero.measix.pilot.data.datastore

import me.rerere.common.configuration.ConfigurationReference
import me.rerere.ai.provider.ProviderSetting
import me.rerere.search.SearchServiceOptions
import net.weero.measix.pilot.data.ai.mcp.normalizeMcpDefinitions

internal fun List<net.weero.measix.pilot.data.model.Assistant>.withBuiltInAssistantDefinitions() =
    mergeDefaults(DEFAULT_ASSISTANTS, { it.id }) { it.copy() }

/** Restores built-in definitions and runtime attributes without rewriting stored choices or references. */
internal fun Settings.withBuiltInDefinitions(): Settings {
    val materializedProviders = providers.mergeDefaults(DEFAULT_PROVIDERS, ProviderSetting::id) { provider ->
        DEFAULT_PROVIDERS.find { it.id == provider.id }?.let { default ->
            provider.copyProvider(
                builtIn = default.builtIn,
                description = default.description,
                shortDescription = default.shortDescription,
            )
        } ?: provider
    }
    val materializedAssistants = assistants.withBuiltInAssistantDefinitions()
    val materializedTtsProviders = ttsProviders.mergeDefaults(DEFAULT_TTS_PROVIDERS, { it.id }) { it.copyProvider() }

    return copy(
        providers = materializedProviders,
        assistants = materializedAssistants,
        ttsProviders = materializedTtsProviders,
        mcpServers = mcpServers.normalizeMcpDefinitions(),
    )
}

/** Materializes the Settings projection without persisting its reference cleanup. */
internal fun Settings.materializeForRead(): Settings {
    val withDefaults = withBuiltInDefinitions()
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
    idOf: (T) -> ConfigurationReference,
    materialize: (T) -> T,
): List<T> = (ifEmpty { defaults } + defaults).distinctBy(idOf).map(materialize)
