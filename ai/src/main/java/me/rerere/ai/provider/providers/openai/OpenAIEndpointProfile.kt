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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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
    // Model image input capability comes only from the user's explicit Model.inputModalities.
    // The endpoint host is used only to select the wire profile (reasoning/function-output shape),
    // never to veto a model that the user configured as IMAGE-capable. A compatible gateway that
    // actually rejects images will surface a real Provider error at request time.
    val userImages = if (Modality.IMAGE in model.inputModalities) {
        RequestImageSupport.STRUCTURED
    } else {
        RequestImageSupport.NONE
    }
    if (!providerSetting.useResponseApi) {
        // Chat Completions can carry images in the USER role only; assistant and tool-output
        // containers are not native image containers on this wire.
        return RequestMediaCapabilities(
            userImages = userImages,
            assistantImages = RequestImageSupport.NONE,
            toolOutputImages = RequestImageSupport.NONE,
        )
    }
    val profile = providerSetting.baseUrl.toHttpUrlOrNull()
        ?.host
        ?.let(::resolveResponseEndpointProfile)
        ?: ResponseEndpointProfile.OPENAI_COMPATIBLE
    return RequestMediaCapabilities(
        userImages = userImages,
        assistantImages = RequestImageSupport.OPAQUE_REPLAY_ONLY,
        toolOutputImages = if (userImages == RequestImageSupport.STRUCTURED && profile.supportsMultimodalFunctionOutput) {
            RequestImageSupport.STRUCTURED
        } else {
            RequestImageSupport.NONE
        },
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
        // Generic OpenAI-compatible Responses gateways are assumed to follow the standard
        // function_call_output contract, which allows image content arrays. Known non-standard
        // implementations (Volc Ark, DeepSeek, MiMo) keep this false below.
        supportsMultimodalFunctionOutput = true,
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

/**
 * Scope of visible reasoning text replayed in Chat Completions history.
 *
 * Visible reasoning is the human-readable `reasoning_content` string persisted in
 * [UIMessagePart.Reasoning]. Its replay scope is orthogonal to opaque continuation state:
 * DeepSeek V4 mandates all complete assistant envelopes when the request carries tools,
 * MiMo only mandates tool-bound envelopes, and ordinary compatible gateways follow the
 * user's `includeHistoryReasoning` preference.
 */
internal enum class VisibleReasoningReplay {
    NONE,
    TOOL_ASSISTANT_ENVELOPES,
    ALL_ASSISTANT_ENVELOPES,
}

/**
 * Scope of source-isolated opaque reasoning details replayed in Chat Completions history.
 *
 * Only OpenRouter `reasoning_details` are opaque continuation state on this wire; they must
 * never be replayed to a different host, and when details exist the visible text is not
 * downgraded to `reasoning_content` for the same envelope.
 */
internal enum class OpaqueReasoningReplay {
    NONE,
    OPENROUTER_SOURCE_MATCHED,
}

/**
 * Replay behavior for a non-success terminal assistant envelope.
 *
 * This is deliberately independent from visible reasoning replay. DeepSeek V4 tool requests
 * require a complete provider-step prefix, while ordinary compatible and OpenRouter requests may
 * still keep request-only partial text even when they also replay visible reasoning.
 */
internal enum class TerminalAssistantReplay {
    COMPATIBLE_PARTIAL,
    COMPLETE_STEP_PREFIX,
}

/**
 * Chat-line reasoning replay policy resolved from the final request shape.
 *
 * The three dimensions are independent: a DeepSeek V4 request with tools mandates all visible
 * reasoning envelopes but no opaque details; an OpenRouter request always checks source-matched
 * details but only sends visible text when the user opted in. This separation prevents leaking
 * one provider's continuation state through another's serializer.
 */
internal data class ChatReasoningReplayPolicy(
    val visible: VisibleReasoningReplay,
    val opaque: OpaqueReasoningReplay,
    val terminalAssistant: TerminalAssistantReplay,
)

/**
 * Unique resolver for Chat Completions reasoning replay policy.
 *
 * Inputs are the endpoint vendor (derived from the actual request host), the model id
 * (checked against registered model families, not loose string contains), whether the final
 * request body carries top-level `tools`, and the user's `includeHistoryReasoning` preference.
 * The serializer consumes the resulting policy without re-computing conditions.
 *
 * B.AI and other compatible gateways hosting `deepseek-v4-flash` are identified solely through
 * [ModelRegistry.DEEPSEEK_V4]; the host itself provides no model-protocol evidence and must not
 * be special-cased.
 */
internal fun resolveChatReasoningReplayPolicy(
    endpointVendor: OpenAIEndpointVendor,
    modelId: String,
    requestHasTools: Boolean,
    includeHistoryReasoning: Boolean,
): ChatReasoningReplayPolicy {
    // DeepSeek V4 model-family contract is only matched on the official DeepSeek endpoint or
    // unknown compatible gateways. OpenRouter and MiMo host their own protocol dialects and
    // must not inherit DeepSeek V4 mandatory replay rules even if they serve a matching model.
    val isDeepSeekV4 = endpointVendor == OpenAIEndpointVendor.DEEPSEEK ||
            (endpointVendor == OpenAIEndpointVendor.COMPATIBLE &&
                    ModelRegistry.DEEPSEEK_V4.match(modelId))
    val isMiMo = endpointVendor == OpenAIEndpointVendor.MIMO
    val isOpenRouter = endpointVendor == OpenAIEndpointVendor.OPENROUTER

    val visible = when {
        endpointVendor == OpenAIEndpointVendor.OPENAI -> VisibleReasoningReplay.NONE

        isDeepSeekV4 && requestHasTools -> VisibleReasoningReplay.ALL_ASSISTANT_ENVELOPES
        isDeepSeekV4 && !requestHasTools && includeHistoryReasoning -> VisibleReasoningReplay.ALL_ASSISTANT_ENVELOPES

        isMiMo && requestHasTools -> VisibleReasoningReplay.TOOL_ASSISTANT_ENVELOPES
        isMiMo && !requestHasTools && includeHistoryReasoning -> VisibleReasoningReplay.ALL_ASSISTANT_ENVELOPES

        isOpenRouter && includeHistoryReasoning -> VisibleReasoningReplay.ALL_ASSISTANT_ENVELOPES

        !isDeepSeekV4 && !isMiMo && !isOpenRouter && includeHistoryReasoning ->
            VisibleReasoningReplay.ALL_ASSISTANT_ENVELOPES

        else -> VisibleReasoningReplay.NONE
    }

    val opaque = if (isOpenRouter) {
        OpaqueReasoningReplay.OPENROUTER_SOURCE_MATCHED
    } else {
        OpaqueReasoningReplay.NONE
    }

    val terminalAssistant = if (isDeepSeekV4 && requestHasTools) {
        TerminalAssistantReplay.COMPLETE_STEP_PREFIX
    } else {
        TerminalAssistantReplay.COMPATIBLE_PARTIAL
    }

    return ChatReasoningReplayPolicy(
        visible = visible,
        opaque = opaque,
        terminalAssistant = terminalAssistant,
    )
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
