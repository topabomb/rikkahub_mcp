package net.weero.measix.pilot.data.imggen

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting

@Serializable
data class ImageGenerationModelDescriptor(
    @SerialName("provider_type")
    val providerType: String,
    @SerialName("provider_name")
    val providerName: String,
    @SerialName("model_id")
    val modelId: String,
    @SerialName("model_name")
    val modelName: String,
) {
    fun toPromptJson(json: Json = DescriptorJson): String =
        json.encodeToString(serializer(), sanitized())

    fun toPromptObject(): String = buildJsonObject {
        val safe = sanitized()
        put("provider_type", safe.providerType)
        put("provider_name", safe.providerName)
        put("model_id", safe.modelId)
        put("model_name", safe.modelName)
    }.toString()

    fun sanitized(): ImageGenerationModelDescriptor = copy(
        providerType = sanitizeConfigString(providerType, MAX_PROVIDER_TYPE_CODE_POINTS),
        providerName = sanitizeConfigString(providerName, MAX_DISPLAY_NAME_CODE_POINTS),
        modelId = sanitizeConfigString(modelId, MAX_MODEL_ID_CODE_POINTS),
        modelName = sanitizeConfigString(modelName, MAX_DISPLAY_NAME_CODE_POINTS),
    )

    companion object {
        private val DescriptorJson = Json { encodeDefaults = true }

        fun from(
            model: Model,
            effectiveProvider: ProviderSetting,
        ): ImageGenerationModelDescriptor {
            val modelName = model.displayName.ifBlank { model.modelId }
            return ImageGenerationModelDescriptor(
                providerType = providerTypeName(effectiveProvider),
                providerName = effectiveProvider.name,
                modelId = model.modelId,
                modelName = modelName,
            ).sanitized()
        }
    }
}

internal const val MAX_PROVIDER_TYPE_CODE_POINTS = 32
internal const val MAX_MODEL_ID_CODE_POINTS = 128
internal const val MAX_DISPLAY_NAME_CODE_POINTS = 64

internal fun sanitizeConfigString(value: String, maxCodePoints: Int): String {
    val cleaned = buildString(value.length) {
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val type = Character.getType(codePoint)
            val isControl = type == Character.CONTROL.toInt() ||
                type == Character.FORMAT.toInt() ||
                type == Character.LINE_SEPARATOR.toInt() ||
                type == Character.PARAGRAPH_SEPARATOR.toInt()
            if (!isControl) appendCodePoint(codePoint)
            index += Character.charCount(codePoint)
        }
    }
    val codePoints = cleaned.codePoints().toArray()
    if (codePoints.size <= maxCodePoints) return cleaned
    return String(codePoints, 0, maxCodePoints)
}

internal fun providerTypeName(provider: ProviderSetting): String = when (provider) {
    is ProviderSetting.OpenAI -> "openai"
    is ProviderSetting.Google -> "google"
    is ProviderSetting.Claude -> "claude"
}
