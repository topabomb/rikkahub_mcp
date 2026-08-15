package net.weero.measix.pilot.data.imggen

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.findModelById
import net.weero.measix.pilot.data.datastore.findProvider

sealed class ImageGenerationSelection {
    data class Available(
        val model: Model,
        val sourceProvider: ProviderSetting,
        val effectiveProvider: ProviderSetting,
        val provider: Provider<*>,
        val descriptor: ImageGenerationModelDescriptor,
    ) : ImageGenerationSelection()

    data class Unavailable(val reason: String) : ImageGenerationSelection()
}

class ImageGenerationSelectionResolver(
    private val providerManager: ProviderManager,
) {
    fun resolve(settings: Settings): ImageGenerationSelection {
        val model = settings.findModelById(settings.imageGenerationModelId)
            ?: return ImageGenerationSelection.Unavailable("image_model_unavailable")
        if (model.type != ModelType.IMAGE) {
            return ImageGenerationSelection.Unavailable("image_model_unavailable")
        }
        val sourceProvider = model.findProvider(settings.providers, checkOverwrite = false)
            ?: return ImageGenerationSelection.Unavailable("image_model_unavailable")
        if (!sourceProvider.enabled) {
            return ImageGenerationSelection.Unavailable("image_model_unavailable")
        }
        val effectiveProvider = model.findProvider(settings.providers, checkOverwrite = true)
            ?: return ImageGenerationSelection.Unavailable("image_model_unavailable")
        val provider = runCatching { providerManager.getProviderByType(effectiveProvider) }.getOrNull()
            ?: return ImageGenerationSelection.Unavailable("image_model_unavailable")
        if (!provider.supportsImageGeneration) {
            return ImageGenerationSelection.Unavailable("image_model_unavailable")
        }
        return ImageGenerationSelection.Available(
            model = model,
            sourceProvider = sourceProvider,
            effectiveProvider = effectiveProvider,
            provider = provider,
            descriptor = ImageGenerationModelDescriptor.from(model, effectiveProvider),
        )
    }

    fun isAvailable(settings: Settings): Boolean = resolve(settings) is ImageGenerationSelection.Available

    fun supportsImageGeneration(provider: ProviderSetting): Boolean {
        val impl = runCatching { providerManager.getProviderByType(provider) }.getOrNull()
        return impl?.supportsImageGeneration == true
    }
}
