package net.weero.measix.pilot.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.enterprise.EnterpriseStarter
import net.weero.measix.pilot.data.enterprise.StoredStarterSerializer

/** Published task content instantiated once; no session, execution binding, or credentials belong here. */
@Serializable
internal data class ConversationOpening(
    val format: Int = 1,
    val assistant: ConfigurationReference.Enterprise,
    val releaseId: String,
    val generation: Long,
    val snapshotHash: String,
    @Serializable(with = StoredStarterSerializer::class) val definition: EnterpriseStarter,
) {
    init {
        require(format == 1) { "unsupported_conversation_opening_format: $format" }
        require(releaseId.isNotBlank() && snapshotHash.isNotBlank() && generation >= 0) {
            "invalid_conversation_opening_source"
        }
        requireNotNull(definition.openingSnapshot) { "conversation_opening_not_supplied" }
        require(assistant.id == definition.assistantId) { "conversation_opening_assistant_mismatch" }
    }
}

internal object ConversationOpeningCodec {
    private val json = Json { encodeDefaults = true }
    fun encode(value: ConversationOpening): String = json.encodeToString(ConversationOpening.serializer(), value)
    fun decode(value: String): ConversationOpening = json.decodeFromString(ConversationOpening.serializer(), value)
}
