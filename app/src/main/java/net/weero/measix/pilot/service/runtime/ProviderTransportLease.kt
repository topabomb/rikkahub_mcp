package net.weero.measix.pilot.service.runtime

import me.rerere.ai.provider.ClaudePromptCacheTtl
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import net.weero.measix.pilot.data.datastore.Settings
import kotlin.uuid.Uuid

/** Immutable provider options that may affect one Turn's request wire shape. */
internal sealed interface FrozenProviderWireShape {
    val id: Uuid
    val model: Model

    data class OpenAI(
        override val id: Uuid,
        override val model: Model,
        val enabled: Boolean,
        val name: String,
        val baseUrl: String,
        val chatCompletionsPath: String,
        val useResponseApi: Boolean,
        val includeHistoryReasoning: Boolean,
    ) : FrozenProviderWireShape

    data class Google(
        override val id: Uuid,
        override val model: Model,
        val enabled: Boolean,
        val name: String,
        val baseUrl: String,
        val vertexAI: Boolean,
        val useServiceAccount: Boolean,
        val location: String,
        val projectId: String,
    ) : FrozenProviderWireShape

    data class Claude(
        override val id: Uuid,
        override val model: Model,
        val enabled: Boolean,
        val name: String,
        val baseUrl: String,
        val promptCaching: Boolean,
        val promptCacheTtl: ClaudePromptCacheTtl,
    ) : FrozenProviderWireShape
}

/** Removes credentials and the provider catalog while retaining the selected model and wire options. */
internal fun freezeProviderWireShape(
    setting: ProviderSetting,
    model: Model,
): FrozenProviderWireShape = when (setting) {
    is ProviderSetting.OpenAI -> FrozenProviderWireShape.OpenAI(
        id = setting.id,
        model = model,
        enabled = setting.enabled,
        name = setting.name,
        baseUrl = setting.baseUrl,
        chatCompletionsPath = setting.chatCompletionsPath,
        useResponseApi = setting.useResponseApi,
        includeHistoryReasoning = setting.includeHistoryReasoning,
    )

    is ProviderSetting.Google -> FrozenProviderWireShape.Google(
        id = setting.id,
        model = model,
        enabled = setting.enabled,
        name = setting.name,
        baseUrl = setting.baseUrl,
        vertexAI = setting.vertexAI,
        useServiceAccount = setting.useServiceAccount,
        location = setting.location,
        projectId = setting.projectId,
    )

    is ProviderSetting.Claude -> FrozenProviderWireShape.Claude(
        id = setting.id,
        model = model,
        enabled = setting.enabled,
        name = setting.name,
        baseUrl = setting.baseUrl,
        promptCaching = setting.promptCaching,
        promptCacheTtl = setting.promptCacheTtl,
    )
}

/** Exact START-selected owner of refreshable transport credentials. */
internal sealed interface ProviderCredentialOwnerLocator {
    data class CatalogProvider(val providerId: Uuid) : ProviderCredentialOwnerLocator
    data class ModelOverwrite(
        val catalogProviderId: Uuid,
        val modelId: Uuid,
        val providerId: Uuid,
    ) : ProviderCredentialOwnerLocator
}

internal fun captureProviderCredentialOwner(
    settings: Settings,
    model: Model,
    selectedProvider: ProviderSetting,
): ProviderCredentialOwnerLocator {
    if (model.providerOverwrite === selectedProvider || model.providerOverwrite?.id == selectedProvider.id) {
        val containers = settings.providers.filter { provider -> provider.models.any { it === model } }
        check(containers.size == 1) { "selected overwrite model ${model.id} has no unique catalog container" }
        return ProviderCredentialOwnerLocator.ModelOverwrite(
            catalogProviderId = containers.single().id,
            modelId = model.id,
            providerId = selectedProvider.id,
        )
    }
    check(settings.providers.any { it.id == selectedProvider.id }) {
        "selected provider ${selectedProvider.id} has no Settings owner"
    }
    return ProviderCredentialOwnerLocator.CatalogProvider(selectedProvider.id)
}

/** Resolves only the exact owner captured at START; duplicate or moved model IDs fail closed. */
internal fun resolveProviderTransportOwner(
    settings: Settings,
    locator: ProviderCredentialOwnerLocator,
): ProviderSetting = when (locator) {
    is ProviderCredentialOwnerLocator.CatalogProvider ->
        settings.providers.singleOrNull { it.id == locator.providerId }
            ?: error("Provider credential owner ${locator.providerId} is unavailable or duplicated")

    is ProviderCredentialOwnerLocator.ModelOverwrite -> {
        val container = settings.providers.singleOrNull { it.id == locator.catalogProviderId }
            ?: error("Model overwrite catalog owner ${locator.catalogProviderId} is unavailable or duplicated")
        val matches = container.models
            .filter { it.id == locator.modelId && it.providerOverwrite?.id == locator.providerId }
        check(matches.size == 1) {
            "Model overwrite credential owner ${locator.modelId}/${locator.providerId} is unavailable or duplicated"
        }
        requireNotNull(matches.single().providerOverwrite)
    }
}

/** Materializes a request-local ProviderSetting; identity/type drift fails closed and only credentials stay live. */
internal fun mergeProviderTransportCredentials(
    frozen: FrozenProviderWireShape,
    live: ProviderSetting,
): ProviderSetting {
    check(frozen.id == live.id) {
        "provider transport lease no longer matches frozen provider ${frozen.id}"
    }
    return when (frozen) {
        is FrozenProviderWireShape.OpenAI -> {
            check(live is ProviderSetting.OpenAI) { "provider transport type changed for ${frozen.id}" }
            ProviderSetting.OpenAI(
                id = frozen.id,
                enabled = frozen.enabled,
                name = frozen.name,
                models = listOf(frozen.model),
                apiKey = live.apiKey,
                baseUrl = frozen.baseUrl,
                chatCompletionsPath = frozen.chatCompletionsPath,
                useResponseApi = frozen.useResponseApi,
                includeHistoryReasoning = frozen.includeHistoryReasoning,
            )
        }

        is FrozenProviderWireShape.Google -> {
            check(live is ProviderSetting.Google) { "provider transport type changed for ${frozen.id}" }
            ProviderSetting.Google(
                id = frozen.id,
                enabled = frozen.enabled,
                name = frozen.name,
                models = listOf(frozen.model),
                apiKey = live.apiKey,
                baseUrl = frozen.baseUrl,
                vertexAI = frozen.vertexAI,
                useServiceAccount = frozen.useServiceAccount,
                privateKey = live.privateKey,
                serviceAccountEmail = live.serviceAccountEmail,
                location = frozen.location,
                projectId = frozen.projectId,
            )
        }

        is FrozenProviderWireShape.Claude -> {
            check(live is ProviderSetting.Claude) { "provider transport type changed for ${frozen.id}" }
            ProviderSetting.Claude(
                id = frozen.id,
                enabled = frozen.enabled,
                name = frozen.name,
                models = listOf(frozen.model),
                apiKey = live.apiKey,
                baseUrl = frozen.baseUrl,
                promptCaching = frozen.promptCaching,
                promptCacheTtl = frozen.promptCacheTtl,
            )
        }
    }
}
