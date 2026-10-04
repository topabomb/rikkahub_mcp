package net.weero.measix.pilot.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.common.configuration.ConfigurationReference
import kotlin.uuid.Uuid

/** Immutable request content. A reference is a locator, never permission to read its payload. */
@Serializable
internal sealed interface ConversationContextBody {
    @Serializable
    @SerialName("inline")
    data class Inline(val text: String) : ConversationContextBody

    @Serializable
    @SerialName("artifact")
    data class Artifact(val artifactId: Long, val relativePath: String) : ConversationContextBody {
        init { require(artifactId > 0) { "invalid_context_artifact_identity" } }
        init {
            require(relativePath.isNotBlank() && !relativePath.startsWith('/') &&
                '\\' !in relativePath && ':' !in relativePath &&
                relativePath.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
                "invalid_context_artifact_reference"
            }
        }
    }

    @Serializable
    @SerialName("opening")
    data object Opening : ConversationContextBody

    @Serializable
    @SerialName("message_reference")
    data class MessageReference(val message: ContextMessageLocator) : ConversationContextBody
}

/** Realm is inherited from the owning conversation; memory IDs alone do not identify a namespace. */
@Serializable
internal data class DisclosureNamespace(
    val memoryOwner: String?,
    val caller: ConfigurationReference,
) {
    init {
        require(memoryOwner == null || memoryOwner == "__global__" ||
            ConfigurationReference.parse(memoryOwner).toString() == memoryOwner) {
            "invalid_disclosure_memory_owner"
        }
    }
}

@Serializable
internal data class ContextMessageLocator(val nodeId: Uuid, val messageId: Uuid)

/** The producer and original inputs remain distinct from the rendered body. */
@Serializable
internal sealed interface ConversationContextSource {
    @Serializable
    @SerialName("disclosure")
    data class Disclosure(
        /** Null is an explicit unknown in supported historical rows, not the current namespace. */
        val namespace: DisclosureNamespace?,
        val reasons: Map<DisclosureSection, ContextAdmissionReason> = emptyMap(),
        /** Null means historical change details are unknown; it must never be inferred from current settings. */
        val changes: Map<DisclosureSection, DisclosureSectionChange>? = null,
    ) : ConversationContextSource {
        init {
            changes?.let { entries ->
                require(entries.keys == reasons.filterValues { it == ContextAdmissionReason.EXTERNAL_STATE }.keys) {
                    "disclosure_change_reason_mismatch"
                }
                entries.forEach { (section, change) -> change.validateSection(section) }
            }
        }
    }

    @Serializable
    @SerialName("system")
    data class System(val contributions: List<SystemContextContribution>) : ConversationContextSource

    @Serializable
    @SerialName("prompt_rule")
    data class PromptRule(
        val definition: PromptInjection.ModeInjection,
        val variables: Map<String, String>,
        val rendererVersion: Int = 1,
    ) : ConversationContextSource {
        init { require(rendererVersion == 1) { "unsupported_prompt_rule_renderer" } }
    }

    @Serializable
    @SerialName("message_time")
    data class MessageTime(
        val message: ContextMessageLocator,
        val messageTime: String,
        val previous: ContextMessageLocator?,
        val previousTime: String?,
        val zoneId: String,
        val rendererVersion: Int = 1,
    ) : ConversationContextSource {
        init {
            require((previous == null) == (previousTime == null)) { "invalid_time_context_predecessor" }
            require(rendererVersion == 1) { "unsupported_time_context_renderer" }
        }
    }

    @Serializable
    @SerialName("attachment")
    data class Attachment(
        val message: ContextMessageLocator,
        val partIndex: Int,
        val name: String,
        val input: AttachmentContextInput,
        val originalReference: String?,
        val toolOutputPath: List<Int> = emptyList(),
    ) : ConversationContextSource {
        init { require(partIndex >= 0 && toolOutputPath.all { it >= 0 }) { "invalid_attachment_context_part" } }
    }

    @Serializable
    @SerialName("starter")
    data object Starter : ConversationContextSource

    @Serializable
    @SerialName("preset")
    data class Preset(val assistant: ConfigurationReference, val index: Int) : ConversationContextSource {
        init { require(index >= 0) { "invalid_preset_context_index" } }
    }

    @Serializable
    @SerialName("history_summary")
    data class HistorySummary(
        val model: ConfigurationReference?,
        val prompt: String,
        val additionalPrompt: String = "",
        val targetTokens: Int? = null,
    ) : ConversationContextSource
}

@Serializable
internal enum class AttachmentContextInput { DOCUMENT_TEXT, NATIVE, REFERENCE_ONLY, UNAVAILABLE }

@Serializable
internal enum class SystemContextKind { DOMAIN, APPLICATION, TOOL, WORKSPACE, PROMPT_RULE }

@Serializable
internal data class SystemContextContribution(
    val kind: SystemContextKind,
    val name: String,
    val template: String,
    val variables: Map<String, String> = emptyMap(),
    val reference: ConfigurationReference? = null,
    val promptRule: PromptInjection.ModeInjection? = null,
)

/** Payload version and model envelope format are deliberately independent. */
@Serializable
internal data class ConversationContextPayload(
    val version: Int = 1,
    val source: ConversationContextSource,
    val body: ConversationContextBody,
) {
    init {
        require(version in 1..2) { "unsupported_conversation_context_payload: $version" }
        require((body == ConversationContextBody.Opening) == (source == ConversationContextSource.Starter)) {
            "context_opening_source_mismatch"
        }
        require(body !is ConversationContextBody.MessageReference || source is ConversationContextSource.Preset ||
            source is ConversationContextSource.HistorySummary) { "context_message_reference_source_mismatch" }
    }
}

/** Only actual changes to application contributions are recorded at subsequent request boundaries. */
@Serializable
internal sealed interface ContextPlacement {
    @Serializable
    @SerialName("system")
    data object System : ContextPlacement

    @Serializable
    @SerialName("before_message")
    data class BeforeMessage(val message: ContextMessageLocator) : ContextPlacement

    @Serializable
    @SerialName("message_part")
    data class MessagePart(
        val message: ContextMessageLocator,
        val partIndex: Int,
        val toolOutputPath: List<Int> = emptyList(),
    ) : ContextPlacement {
        init { require(partIndex >= 0 && toolOutputPath.all { it >= 0 }) { "invalid_context_part_index" } }
    }

    @Serializable
    @SerialName("before_step")
    data class BeforeStep(val stepId: Uuid) : ContextPlacement

    /** Explicitly closes the inherited contribution at this boundary; it has no model body. */
    @Serializable
    @SerialName("omitted")
    data object Omitted : ContextPlacement

    /** Durable preset/summary attribution; the original message itself supplies the wire content. */
    @Serializable
    @SerialName("message_origin")
    data object MessageOrigin : ContextPlacement
}

@Serializable
internal data class ConversationContextUse(
    val entryId: Uuid,
    val role: MessageRole,
    val placement: ContextPlacement,
)

/** One immutable selection per Turn. Empty rules mean disabled, never inheritance from an older Turn. */
@Serializable
internal data class TurnContextSelection(
    val version: Int = 1,
    val systemEntryId: Uuid,
    val ruleEntryIds: List<Uuid>,
    val timeReminderEnabled: Boolean,
    val timeZoneId: String,
    val disclosureNamespace: DisclosureNamespace? = null,
    val builtinTools: Map<String, DisclosureBuiltinTool> = emptyMap(),
) {
    init {
        require(version == 1) { "unsupported_turn_context_selection: $version" }
        require(ruleEntryIds.distinct().size == ruleEntryIds.size) { "duplicate_selected_context_rule" }
    }
}

internal val ConversationContextSource.kind: String
    get() = when (this) {
        is ConversationContextSource.Disclosure -> "disclosure"
        is ConversationContextSource.System -> "system"
        is ConversationContextSource.PromptRule -> "prompt_rule"
        is ConversationContextSource.MessageTime -> "message_time"
        is ConversationContextSource.Attachment -> "attachment"
        ConversationContextSource.Starter -> "starter"
        is ConversationContextSource.Preset -> "preset"
        is ConversationContextSource.HistorySummary -> "history_summary"
    }

internal object ConversationContextCodec {
    private val json = Json { encodeDefaults = true }

    fun encode(payload: ConversationContextPayload): String =
        json.encodeToString(ConversationContextPayload.serializer(), payload)

    fun decode(value: String): ConversationContextPayload =
        json.decodeFromString(ConversationContextPayload.serializer(), value)

    fun encodeSelection(value: TurnContextSelection): String =
        json.encodeToString(TurnContextSelection.serializer(), value)

    fun decodeSelection(value: String): TurnContextSelection =
        json.decodeFromString(TurnContextSelection.serializer(), value)

    fun encodePlacement(value: ContextPlacement): String =
        json.encodeToString(ContextPlacement.serializer(), value)

    fun decodePlacement(value: String): ContextPlacement =
        json.decodeFromString(ContextPlacement.serializer(), value)
}
