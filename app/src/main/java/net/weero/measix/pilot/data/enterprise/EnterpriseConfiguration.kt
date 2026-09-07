package net.weero.measix.pilot.data.enterprise

import kotlinx.serialization.Serializable
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Modality
import me.rerere.common.configuration.ConfigurationReference
import me.rerere.common.configuration.EnterpriseAuthority
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.configuration.EnterprisePolicy
import net.weero.measix.pilot.data.configuration.GatewayEnablementPolicy

@Serializable
internal data class EnterpriseIdentity(
    val authority: EnterpriseAuthority,
    val enterpriseName: String,
    val userId: String,
    val userName: String,
) {
    val scope: ConfigurationScope.Enterprise get() = ConfigurationScope.Enterprise(authority, userId)
}

/** Public definitions contain no upstream address, header, or credential. */
@Serializable
internal data class EnterpriseConfiguration(
    val generation: Long,
    val policy: EnterprisePolicy,
    val models: List<EnterpriseModel>,
    val tts: List<EnterpriseSpeechResource>,
    val asr: List<EnterpriseSpeechResource>,
    val mcpServers: List<EnterpriseMcpResource>,
    val assistants: List<EnterpriseAssistant>,
    val memorySeeds: List<EnterpriseMemorySeed>,
    val starters: List<EnterpriseStarter>,
    val gateways: List<EnterpriseGateway>,
    val defaults: EnterpriseDefaults,
    val feed: List<EnterpriseFeedItem>,
    val portal: EnterprisePortal,
)

@Serializable
internal data class EnterpriseModel(
    val id: String,
    val name: String,
    val modelId: String,
    val enabled: Boolean = true,
    val type: ModelType = ModelType.CHAT,
    val inputModalities: List<Modality> = listOf(Modality.TEXT),
    val outputModalities: List<Modality> = listOf(Modality.TEXT),
    val abilities: List<ModelAbility> = emptyList(),
)

@Serializable
internal data class EnterpriseSpeechResource(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val modelId: String,
    val voice: String? = null,
)

@Serializable
internal data class EnterpriseMcpResource(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
)

/** Only enterprise-owned fields belong here; local usage choices are user preferences. */
@Serializable
internal data class EnterpriseAssistant(
    val id: String,
    val name: String,
    val description: String,
    val modelId: String,
    val systemPrompt: String,
    val mcpServerIds: List<String>,
    val memorySeedIds: List<String>,
    val enabled: Boolean = true,
    val allowAsSubAssistant: Boolean = false,
    val allowedSubAssistantIds: List<String> = emptyList(),
)

@Serializable
internal data class EnterpriseMemorySeed(val id: String, val content: String)

@Serializable
internal data class EnterpriseStarter(val id: String, val assistantId: String, val title: String, val prompt: String)

@Serializable
internal data class EnterpriseGateway(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val enablement: GatewayEnablementPolicy,
)

@Serializable
internal data class EnterpriseDefaults(
    val assistantId: String? = null,
    val chatModelId: String? = null,
    val fastModelId: String? = null,
    val titleModelId: String? = null,
    val imageGenerationModelId: String? = null,
    val attachmentInspectionModelId: String? = null,
    val suggestionModelId: String? = null,
    val compressModelId: String? = null,
    val ttsId: String? = null,
    val asrId: String? = null,
)

@Serializable
internal data class EnterpriseFeedItem(val id: String, val title: String, val content: String)

@Serializable
internal data class EnterprisePortal(val title: String, val html: String)

internal enum class EnterpriseResourceKind { MODEL, TTS, ASR, MCP, GATEWAY }

internal fun EnterpriseIdentity.reference(id: String): ConfigurationReference.Enterprise =
    ConfigurationReference.Enterprise(authority, id)

internal fun EnterpriseConfiguration.runtimeResources(): Map<String, EnterpriseResourceKind> = buildMap {
    models.forEach { put(it.id, EnterpriseResourceKind.MODEL) }
    tts.forEach { put(it.id, EnterpriseResourceKind.TTS) }
    asr.forEach { put(it.id, EnterpriseResourceKind.ASR) }
    mcpServers.forEach { put(it.id, EnterpriseResourceKind.MCP) }
    gateways.forEach { put(it.id, EnterpriseResourceKind.GATEWAY) }
}
