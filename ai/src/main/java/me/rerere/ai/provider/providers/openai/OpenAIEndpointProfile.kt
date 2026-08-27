package me.rerere.ai.provider.providers.openai

import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.RequestImageSupport
import me.rerere.ai.provider.RequestMediaCapabilities
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.OpenAIResponseSourceProfile
import me.rerere.ai.ui.OpenAIResponseWireFormat
import okhttp3.HttpUrl.Companion.toHttpUrl

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
    MIMO,
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
        "api.xiaomimimo.com" -> OpenAIEndpointVendor.MIMO
        "token-plan-cn.xiaomimimo.com" -> OpenAIEndpointVendor.MIMO
        "integrate.api.nvidia.com" -> OpenAIEndpointVendor.NVIDIA
        "opencode.ai" -> OpenAIEndpointVendor.OPENCODE
        else -> OpenAIEndpointVendor.COMPATIBLE
    }
}

internal fun isOfficialOpenAIHost(host: String): Boolean =
    resolveOpenAIEndpointVendor(host) == OpenAIEndpointVendor.OPENAI

internal fun openAIRequestMediaCapabilities(
    providerSetting: ProviderSetting.OpenAI,
    model: Model,
): RequestMediaCapabilities {
    val userImages = if (Modality.IMAGE in model.inputModalities) {
        RequestImageSupport.STRUCTURED
    } else {
        RequestImageSupport.NONE
    }
    if (!providerSetting.useResponseApi) {
        return RequestMediaCapabilities(userImages = userImages)
    }
    val profile = resolveResponseEndpointProfile(providerSetting.baseUrl.toHttpUrl().host)
    return RequestMediaCapabilities(
        userImages = userImages,
        assistantImages = RequestImageSupport.OPAQUE_REPLAY_ONLY,
        toolOutputImages = if (userImages == RequestImageSupport.STRUCTURED && profile.supportsMultimodalFunctionOutput) {
            RequestImageSupport.STRUCTURED
        } else {
            RequestImageSupport.NONE
        },
        opaqueReplayWireFormat = profile.wireFormat,
        opaqueReplaySourceProfile = profile.sourceProfile,
    )
}

/**
 * Closed set of verified Responses wire profiles.
 *
 * [sourceProfile] is stricter than [wireFormat]: OpenAI, generic compatible gateways and Volcengine
 * Ark can use similar output-item shapes, but their opaque reasoning state is not interchangeable.
 */
internal enum class ResponseEndpointProfile(
    val wireFormat: OpenAIResponseWireFormat,
    val sourceProfile: OpenAIResponseSourceProfile,
    val supportsStore: Boolean,
    val supportsReasoningSummary: Boolean,
    val supportsEncryptedContent: Boolean,
    val usesReasoningTextContent: Boolean,
    val supportsMultimodalFunctionOutput: Boolean,
) {
    OPENAI(
        wireFormat = OpenAIResponseWireFormat.OPENAI,
        sourceProfile = OpenAIResponseSourceProfile.OPENAI,
        supportsStore = true,
        supportsReasoningSummary = true,
        supportsEncryptedContent = true,
        usesReasoningTextContent = false,
        supportsMultimodalFunctionOutput = true,
    ),
    OPENAI_COMPATIBLE(
        wireFormat = OpenAIResponseWireFormat.OPENAI,
        sourceProfile = OpenAIResponseSourceProfile.OPENAI_COMPATIBLE,
        supportsStore = true,
        supportsReasoningSummary = true,
        supportsEncryptedContent = true,
        usesReasoningTextContent = false,
        supportsMultimodalFunctionOutput = false,
    ),
    VOLC_ARK(
        wireFormat = OpenAIResponseWireFormat.OPENAI,
        sourceProfile = OpenAIResponseSourceProfile.VOLC_ARK,
        supportsStore = true,
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
        supportsStore = true,
        supportsReasoningSummary = false,
        supportsEncryptedContent = false,
        usesReasoningTextContent = true,
        // DeepSeek Responses accepts a string function_call_output.output.
        supportsMultimodalFunctionOutput = false,
    ),
    MIMO(
        wireFormat = OpenAIResponseWireFormat.OPENAI,
        sourceProfile = OpenAIResponseSourceProfile.MIMO,
        supportsStore = false,
        // MiMo Responses does not support reasoning summary.
        supportsReasoningSummary = false,
        // MiMo Responses does not support encrypted reasoning content or opaque item replay.
        supportsEncryptedContent = false,
        // MiMo Responses uses reasoning_text content for tool continuation replay.
        usesReasoningTextContent = true,
        // MiMo Responses accepts a string function_call_output.output.
        supportsMultimodalFunctionOutput = false,
    ),
}

internal fun resolveResponseEndpointProfile(host: String): ResponseEndpointProfile {
    return when (resolveOpenAIEndpointVendor(host)) {
        OpenAIEndpointVendor.OPENAI -> ResponseEndpointProfile.OPENAI
        OpenAIEndpointVendor.VOLC_ARK -> ResponseEndpointProfile.VOLC_ARK
        OpenAIEndpointVendor.DEEPSEEK -> ResponseEndpointProfile.DEEPSEEK
        OpenAIEndpointVendor.MIMO -> ResponseEndpointProfile.MIMO
        else -> ResponseEndpointProfile.OPENAI_COMPATIBLE
    }
}

/**
 * Maps app reasoning levels to DeepSeek Chat Completions effort values.
 *
 * Official Thinking Mode documents the Chat request field as `low` / `high` / `max`, and maps
 * compatibility aliases as `medium -> high` and `xhigh -> high`. XHIGH therefore stays on the
 * documented `high` alias. The separate MAX level is the only UI value that sends `max`.
 */
internal fun mapDeepSeekChatReasoningEffort(level: ReasoningLevel): String? {
    return when (level) {
        ReasoningLevel.OFF, ReasoningLevel.AUTO -> null
        ReasoningLevel.LOW -> "low"
        ReasoningLevel.MEDIUM, ReasoningLevel.HIGH, ReasoningLevel.XHIGH -> "high"
        ReasoningLevel.MAX -> "max"
    }
}

/**
 * Maps app reasoning levels to DeepSeek Responses effort values.
 *
 * Responses uses `none` as its documented thinking-off value. AUTO omits the field so the endpoint
 * can use its default. MAX is the only UI value that sends DeepSeek's separate `max` effort.
 */
internal fun mapDeepSeekResponsesReasoningEffort(level: ReasoningLevel): String? {
    return when (level) {
        ReasoningLevel.OFF -> "none"
        ReasoningLevel.AUTO -> null
        ReasoningLevel.LOW -> "low"
        ReasoningLevel.MEDIUM, ReasoningLevel.HIGH, ReasoningLevel.XHIGH -> "high"
        ReasoningLevel.MAX -> "max"
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
            ReasoningLevel.XHIGH, ReasoningLevel.MAX -> "xhigh"
            ReasoningLevel.AUTO -> null
        }

        "-codex" in normalizedModelId -> when {
            level == ReasoningLevel.OFF -> "low"
            level == ReasoningLevel.XHIGH && !isXHighCodex -> "high"
            level == ReasoningLevel.MAX && !isXHighCodex -> "high"
            level == ReasoningLevel.MAX && isXHighCodex -> "xhigh"
            else -> level.effort
        }

        ModelRegistry.GPT_5.match(modelId) -> when (level) {
            ReasoningLevel.OFF -> "minimal"
            ReasoningLevel.XHIGH, ReasoningLevel.MAX -> "high"
            else -> level.effort
        }

        OPENAI_GPT_5_1_STANDARD_PATTERN.matches(normalizedModelId) -> when (level) {
            ReasoningLevel.XHIGH, ReasoningLevel.MAX -> "high"
            else -> level.effort
        }

        OPENAI_GPT_5_XHIGH_STANDARD_PATTERN.matches(normalizedModelId) -> when (level) {
            ReasoningLevel.MAX -> "xhigh"
            else -> level.effort
        }

        ModelRegistry.OPENAI_O_MODELS.match(modelId) -> when (level) {
            ReasoningLevel.OFF -> "low"
            ReasoningLevel.XHIGH, ReasoningLevel.MAX -> "high"
            else -> level.effort
        }

        else -> when (level) {
            ReasoningLevel.OFF -> "low"
            ReasoningLevel.XHIGH, ReasoningLevel.MAX -> "high"
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

/**
 * Maps app reasoning levels to MiMo Chat Completions `thinking.type` values.
 *
 * MiMo uses `thinking.type=enabled|disabled` for Chat Completions. OFF maps to `disabled`;
 * all other levels map to `enabled`.
 */
internal fun mapMiMoChatThinkingType(level: ReasoningLevel): String {
    return if (level.isEnabled) "enabled" else "disabled"
}

/** True only when the request will actually enable MiMo reasoning on the wire. */
internal fun isMiMoThinkingEnabled(model: Model, level: ReasoningLevel): Boolean =
    model.abilities.contains(ModelAbility.REASONING) && level.isEnabled

/**
 * Maps app reasoning levels to MiMo Responses `reasoning.effort` values.
 *
 * MiMo Responses supports `none`, `low`, `medium`, `high`. OFF maps to `none`; AUTO omits the
 * field so the endpoint applies its default; XHIGH/MAX are capped to `high`.
 */
internal fun mapMiMoResponsesReasoningEffort(level: ReasoningLevel): String? {
    return when (level) {
        ReasoningLevel.OFF -> "none"
        ReasoningLevel.AUTO -> null
        ReasoningLevel.LOW -> "low"
        ReasoningLevel.MEDIUM -> "medium"
        ReasoningLevel.HIGH, ReasoningLevel.XHIGH, ReasoningLevel.MAX -> "high"
    }
}
