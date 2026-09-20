package me.rerere.ai.provider.images

import kotlinx.serialization.Serializable

/** Typed managed image wire; personal providers continue to use their own settings. */
@Serializable
enum class ImageGenerationClientProtocol {
    OPENAI_IMAGES_GENERATIONS,
    DASHSCOPE_MULTIMODAL_GENERATION,
}
