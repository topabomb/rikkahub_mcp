package me.rerere.ai.provider.providers.openai

import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.OpenAIResponseSourceProfile
import me.rerere.ai.ui.OpenAIResponseWireFormat

/**
 * OpenAI-compatible endpoint identity derived from the actual request host.
 *
 * This is internal protocol state, not a user-selectable provider dialect. Unknown gateways remain
 * [COMPATIBLE] and are never reclassified from their model id.
 */
internal enum class OpenAIEndpointVendor {
    OPENAI,
    OPENROUTER,
    DASHSCOPE,
    VOLC_ARK,
    MISTRAL,
    INTERN,
    SILICONFLOW,
    BIGMODEL,
    MOONSHOT,
    DEEPSEEK,
    NVIDIA,
    OPENCODE,
    COMPATIBLE,
}

internal fun resolveOpenAIEndpointVendor(host: String): OpenAIEndpointVendor {
    return when (host.lowercase()) {
        "api.openai.com" -> OpenAIEndpointVendor.OPENAI
        "openrouter.ai" -> OpenAIEndpointVendor.OPENROUTER
        "dashscope.aliyuncs.com" -> OpenAIEndpointVendor.DASHSCOPE
        "ark.cn-beijing.volces.com" -> OpenAIEndpointVendor.VOLC_ARK
        "api.mistral.ai" -> OpenAIEndpointVendor.MISTRAL
        "chat.intern-ai.org.cn" -> OpenAIEndpointVendor.INTERN
        "api.siliconflow.cn" -> OpenAIEndpointVendor.SILICONFLOW
        "open.bigmodel.cn" -> OpenAIEndpointVendor.BIGMODEL
        "api.moonshot.cn" -> OpenAIEndpointVendor.MOONSHOT
        "api.deepseek.com" -> OpenAIEndpointVendor.DEEPSEEK
        "integrate.api.nvidia.com" -> OpenAIEndpointVendor.NVIDIA
        "opencode.ai" -> OpenAIEndpointVendor.OPENCODE
        else -> OpenAIEndpointVendor.COMPATIBLE
    }
}

internal fun isOfficialOpenAIHost(host: String): Boolean =
    resolveOpenAIEndpointVendor(host) == OpenAIEndpointVendor.OPENAI

/**
 * Closed set of verified Responses wire profiles.
 *
 * [sourceProfile] is stricter than [wireFormat]: OpenAI, generic compatible gateways and Volcengine
 * Ark can use similar output-item shapes, but their opaque reasoning state is not interchangeable.
 */
internal enum class ResponseEndpointProfile(
    val wireFormat: OpenAIResponseWireFormat,
    val sourceProfile: OpenAIResponseSourceProfile,
    val supportsReasoningSummary: Boolean,
    val supportsEncryptedContent: Boolean,
    val usesReasoningTextContent: Boolean,
    val supportsMultimodalFunctionOutput: Boolean,
) {
    OPENAI(
        wireFormat = OpenAIResponseWireFormat.OPENAI,
        sourceProfile = OpenAIResponseSourceProfile.OPENAI,
        supportsReasoningSummary = true,
        supportsEncryptedContent = true,
        usesReasoningTextContent = false,
        supportsMultimodalFunctionOutput = true,
    ),
    OPENAI_COMPATIBLE(
        wireFormat = OpenAIResponseWireFormat.OPENAI,
        sourceProfile = OpenAIResponseSourceProfile.OPENAI_COMPATIBLE,
        supportsReasoningSummary = true,
        supportsEncryptedContent = true,
        usesReasoningTextContent = false,
        supportsMultimodalFunctionOutput = true,
    ),
    VOLC_ARK(
        wireFormat = OpenAIResponseWireFormat.OPENAI,
        sourceProfile = OpenAIResponseSourceProfile.VOLC_ARK,
        supportsReasoningSummary = false,
        // Ark defaults to a thinking summary, while stateless tool continuation can manually replay
        // the complete encrypted_content requested through include.
        supportsEncryptedContent = true,
        usesReasoningTextContent = false,
        // Ark documents function_call_output.output as a string.
        supportsMultimodalFunctionOutput = false,
    ),
    DEEPSEEK(
        wireFormat = OpenAIResponseWireFormat.DEEPSEEK,
        sourceProfile = OpenAIResponseSourceProfile.DEEPSEEK,
        supportsReasoningSummary = false,
        supportsEncryptedContent = false,
        usesReasoningTextContent = true,
        // DeepSeek Responses accepts a string function_call_output.output.
        supportsMultimodalFunctionOutput = false,
    ),
}

internal fun resolveResponseEndpointProfile(host: String): ResponseEndpointProfile {
    return when (resolveOpenAIEndpointVendor(host)) {
        OpenAIEndpointVendor.OPENAI -> ResponseEndpointProfile.OPENAI
        OpenAIEndpointVendor.VOLC_ARK -> ResponseEndpointProfile.VOLC_ARK
        OpenAIEndpointVendor.DEEPSEEK -> ResponseEndpointProfile.DEEPSEEK
        else -> ResponseEndpointProfile.OPENAI_COMPATIBLE
    }
}

/** Maps app reasoning levels to DeepSeek Chat Completions effort values. */
internal fun mapDeepSeekChatReasoningEffort(level: ReasoningLevel): String? {
    return when (level) {
        ReasoningLevel.OFF, ReasoningLevel.AUTO -> null
        ReasoningLevel.LOW -> "low"
        ReasoningLevel.MEDIUM, ReasoningLevel.HIGH, ReasoningLevel.XHIGH -> "high"
    }
}

/**
 * Maps app reasoning levels to DeepSeek Responses effort values.
 *
 * Responses uses `none` as its documented thinking-off value. AUTO omits the field so the endpoint
 * can use its default; the app does not expose DeepSeek's separate `max` level.
 */
internal fun mapDeepSeekResponsesReasoningEffort(level: ReasoningLevel): String? {
    return when (level) {
        ReasoningLevel.OFF -> "none"
        ReasoningLevel.AUTO -> null
        ReasoningLevel.LOW -> "low"
        ReasoningLevel.MEDIUM, ReasoningLevel.HIGH, ReasoningLevel.XHIGH -> "high"
    }
}

/**
 * Maps the app-wide reasoning levels into the effort range documented by each official OpenAI model variant.
 *
 * Message-role and temperature behavior can be shared by the broad GPT-5 family, but effort values cannot:
 * base GPT-5, point releases, Pro and Codex variants expose different ranges. AUTO is represented by omitting
 * the parameter so that the endpoint can apply its model-specific default.
 */
internal fun mapOfficialOpenAIReasoningEffort(
    modelId: String,
    level: ReasoningLevel,
): String? {
    if (level == ReasoningLevel.AUTO) return null

    val normalizedModelId = modelId.lowercase()
    val isPointPro = OPENAI_GPT_5_POINT_PRO_PATTERN.matches(normalizedModelId)
    val isXHighCodex = OPENAI_GPT_5_XHIGH_CODEX_PATTERN.matches(normalizedModelId)
    return when {
        "-chat" in normalizedModelId -> null

        OPENAI_GPT_5_BASE_PRO_PATTERN.matches(normalizedModelId) -> "high"

        isPointPro -> when (level) {
            ReasoningLevel.OFF, ReasoningLevel.LOW, ReasoningLevel.MEDIUM -> "medium"
            ReasoningLevel.HIGH -> "high"
            ReasoningLevel.XHIGH -> "xhigh"
            ReasoningLevel.AUTO -> null
        }

        "-codex" in normalizedModelId -> when {
            level == ReasoningLevel.OFF -> "low"
            level == ReasoningLevel.XHIGH && !isXHighCodex -> "high"
            else -> level.effort
        }

        ModelRegistry.GPT_5.match(modelId) -> when (level) {
            ReasoningLevel.OFF -> "minimal"
            ReasoningLevel.XHIGH -> "high"
            else -> level.effort
        }

        OPENAI_GPT_5_1_STANDARD_PATTERN.matches(normalizedModelId) -> when (level) {
            ReasoningLevel.XHIGH -> "high"
            else -> level.effort
        }

        OPENAI_GPT_5_XHIGH_STANDARD_PATTERN.matches(normalizedModelId) -> level.effort

        ModelRegistry.OPENAI_O_MODELS.match(modelId) -> when (level) {
            ReasoningLevel.OFF -> "low"
            ReasoningLevel.XHIGH -> "high"
            else -> level.effort
        }

        else -> when (level) {
            ReasoningLevel.OFF -> "low"
            ReasoningLevel.XHIGH -> "high"
            else -> level.effort
        }
    }
}

private val OPENAI_GPT_5_POINT_PRO_PATTERN =
    Regex("^gpt-5\\.(?:2|4|5)-pro(?:-|$).*")

private val OPENAI_GPT_5_BASE_PRO_PATTERN =
    Regex("^gpt-5-pro(?:-|$).*")

private val OPENAI_GPT_5_XHIGH_CODEX_PATTERN =
    Regex("^gpt-5\\.(?:2|3)-codex(?:-|$).*")

private val OPENAI_GPT_5_1_STANDARD_PATTERN =
    Regex("^gpt-5\\.1(?:-|$).*")

private val OPENAI_GPT_5_XHIGH_STANDARD_PATTERN =
    Regex("^gpt-5\\.(?:2|4|5|6)(?:-|$).*")
