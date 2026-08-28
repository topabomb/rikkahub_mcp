package me.rerere.ai.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ImageGenSize
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.OpenAIResponseMetadata
import me.rerere.ai.ui.OpenAIResponseSourceProfile
import me.rerere.ai.ui.OpenAIResponseWireFormat
import me.rerere.ai.ui.UIMessage

// 提供商实现
// 采用无状态设计，使用时除了需要传入需要的参数外，还需要传入provider setting作为参数
interface Provider<T : ProviderSetting> {
    /**
     * Whether this client implements [generateImage]. A true value does not prove that an
     * arbitrary compatible host exposes the endpoint; remote HTTP rejection is still a runtime error.
     */
    val supportsImageGeneration: Boolean
        get() = false

    suspend fun listModels(providerSetting: T): List<Model>

    suspend fun getBalance(providerSetting: T): String {
        error("Balance lookup is not supported")
    }

    /**
     * Maps the user's explicitly configured model capabilities and the selected Provider protocol
     * onto this request's container-level image projection.
     *
     * This is an internal derived result, not a second configuration surface: it expresses which
     * protocol containers (USER / ASSISTANT / Tool.output) can carry images, given the model's
     * [Model.inputModalities] and the Provider's wire contract. Endpoint profiles may select
     * known container-shape differences, but must not veto the model's explicitly configured
     * image-input capability. Model-name guessing and cached probe results are not inputs.
     * For a model configured with [Modality.IMAGE], [RequestMediaCapabilities.userImages]
     * must be [RequestImageSupport.STRUCTURED]; inspection and ordinary user input share that
     * Provider contract. Other containers may remain unsupported or opaque-only.
     *
     * Every Provider must implement this; a missing override silently drops the user's IMAGE
     * configuration, so the default is intentionally removed.
     */
    abstract fun requestMediaCapabilities(
        providerSetting: T,
        model: Model,
    ): RequestMediaCapabilities

    suspend fun generateText(
        providerSetting: T,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk

    suspend fun streamText(
        providerSetting: T,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk>

    suspend fun generateEmbedding(
        providerSetting: T,
        params: EmbeddingGenerationParams,
    ): EmbeddingGenerationResult {
        error("Embedding generation is not supported")
    }

    suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> {
        error("Image generation is not supported")
    }

    suspend fun editImage(
        providerSetting: ProviderSetting,
        params: ImageEditParams,
    ): Flow<ImageGenerationItem> {
        error("Image edit is not supported")
    }
}

@Serializable
enum class RequestImageSupport {
    NONE,
    STRUCTURED,
    OPAQUE_REPLAY_ONLY,
}

@Serializable
data class RequestMediaCapabilities(
    val userImages: RequestImageSupport = RequestImageSupport.NONE,
    val assistantImages: RequestImageSupport = RequestImageSupport.NONE,
    val toolOutputImages: RequestImageSupport = RequestImageSupport.NONE,
    val opaqueReplayWireFormat: OpenAIResponseWireFormat? = null,
    val opaqueReplaySourceProfile: OpenAIResponseSourceProfile? = null,
) {
    companion object {
        val NONE = RequestMediaCapabilities()
    }

    fun supportFor(role: MessageRole, insideToolOutput: Boolean): RequestImageSupport =
        when {
            insideToolOutput -> toolOutputImages
            role == MessageRole.ASSISTANT -> assistantImages
            else -> userImages
        }

    fun opaqueReplayEligible(metadata: OpenAIResponseMetadata?): Boolean {
        if (assistantImages != RequestImageSupport.OPAQUE_REPLAY_ONLY) return false
        val metadata = metadata ?: return false
        val expectedWire = opaqueReplayWireFormat ?: return false
        val expectedSource = opaqueReplaySourceProfile ?: return false
        if (metadata.wireFormat != expectedWire) return false
        if (metadata.outputItemGroups.none { it.isNotEmpty() }) return false
        return metadata.sourceProfile == null || metadata.sourceProfile == expectedSource
    }
}

@Serializable
data class TextGenerationParams(
    val model: Model,
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    val tools: List<Tool> = emptyList(),
    val reasoningLevel: ReasoningLevel = ReasoningLevel.OFF,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
    val mediaCapabilities: RequestMediaCapabilities = RequestMediaCapabilities.NONE,
    /**
     * Request-scoped Provider session identifier for sticky routing / caching.
     *
     * Derived from the conversation UUID by the turn owner; never enters Settings, Room, backup
     * or is generated from titles/user text. Only OpenRouter Chat Completions and Responses
     * builders write this as a top-level `session_id`; all other Providers ignore it.
     * Max 256 characters per OpenRouter contract.
     */
    val providerSessionId: String? = null,
)

@Serializable
data class ImageGenerationParams(
    val model: Model,
    val prompt: String,
    val numOfImages: Int = 1,
    val size: String = ImageGenSize.AUTO.value,
    val partialImages: Int = 2,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
)

@Serializable
data class ImageEditParams(
    val model: Model,
    val prompt: String,
    val images: List<String>,
    val numOfImages: Int = 1,
    val size: String = ImageGenSize.AUTO.value,
    val partialImages: Int = 2,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
)

@Serializable
data class EmbeddingGenerationParams(
    val model: Model,
    val input: List<String>,
    val dimensions: Int? = null,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
)

@Serializable
data class EmbeddingGenerationResult(
    val model: String,
    val embeddings: List<List<Float>>,
)

@Serializable
data class CustomHeader(
    val name: String,
    val value: String
)

@Serializable
data class CustomBody(
    val key: String,
    val value: JsonElement
)
