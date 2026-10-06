package net.weero.measix.pilot.data.enterprise

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Modality

/** Local stored display metadata may be discarded without losing conversation
 * content or changing stored bytes/hashes. Never used for network decoding. */
internal object StoredStarterSerializer : JsonTransformingSerializer<EnterpriseStarter>(EnterpriseStarter.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonObject) JsonObject(element.filterKeys { it != "description" }) else element
}

/** Stable validation failures cross the application boundary without exposing credential-bearing wire input. */
internal open class EnterpriseConfigurationException(val reason: String, detail: String? = null) :
    IllegalArgumentException(if (detail == null) reason else "$reason: $detail")

internal object EnterpriseConfigurationCodec {
    const val MAX_BYTES = 4 * 1024 * 1024
    internal val json = Json { encodeDefaults = true }

    fun validateIdentity(identity: EnterpriseIdentity) {
        check(!me.rerere.common.configuration.RetiredLocalEnterpriseIdentity.isReserved(identity.authority.deploymentId),
            "retired_local_enterprise_identity")
        listOf(identity.authority.deploymentId, identity.userId).forEach {
            check(it.codePointCount(0, it.length) in 1..128, "invalid_enterprise_identity_length")
        }
        check(identity.enterpriseName.isNotBlank() && identity.enterpriseName.length <= 256, "invalid_enterprise_name")
        check(identity.userName.isNotBlank() && identity.userName.length <= 256, "invalid_enterprise_user_name")
        try { identity.scope } catch (_: IllegalArgumentException) { fail("invalid_enterprise_user_id") }
    }

    fun validateConfiguration(identity: EnterpriseIdentity, config: EnterpriseConfiguration) {
        validateIdentity(identity)
        check(config.generation > 0, "invalid_enterprise_generation")
        check(config.gateways.size <= 1, "multiple_enterprise_gateways")
        check(config.mcpServers.all { it.id.startsWith("mcp_") }, "invalid_enterprise_mcp_id")
        config.gateways.forEach {
            check(it.id.startsWith("twg_"), "invalid_enterprise_gateway_id")
            try { it.surface }
            catch (_: IllegalArgumentException) { fail("invalid_gateway_surface") }
        }
        val ids = config.models.map { it.id } + config.imageGenerators.map { it.id } + config.tts.map { it.id } + config.asr.map { it.id } +
            config.mcpServers.map { it.id } + config.gateways.map { it.id } + config.assistants.map { it.id } +
            config.memorySeeds.map { it.id } + config.starters.map { it.id }
        val expectedCount = config.models.size + config.imageGenerators.size + config.tts.size + config.asr.size + config.mcpServers.size +
            config.gateways.size + config.assistants.size + config.memorySeeds.size + config.starters.size
        check(ids.size == expectedCount && ids.distinct().size == ids.size, "duplicate_enterprise_resource_id")
        ids.forEach { id ->
            try { identity.reference(id) } catch (_: IllegalArgumentException) { fail("invalid_enterprise_resource_id") }
        }
        val models = config.models.associateBy { it.id }
        val assistants = config.assistants.associateBy { it.id }
        val mcp = config.mcpServers.associateBy { it.id }
        val seeds = config.memorySeeds.associateBy { it.id }
        config.models.forEach {
            check(it.name.isNotBlank() && it.modelId.isNotBlank(), "invalid_enterprise_model")
            check(it.inputModalities.isNotEmpty() && it.outputModalities.isNotEmpty(), "invalid_model_modalities")
        }
        config.imageGenerators.forEach {
            it.validate()
        }
        config.tts.forEach(EnterpriseTtsResource::validate)
        config.asr.forEach(EnterpriseAsrResource::validate)
        (config.mcpServers.map { it.name } + config.gateways.map { it.name }).forEach {
            check(it.isNotBlank(), "invalid_enterprise_tool_resource")
        }
        config.mcpServers.forEach { server ->
            check(when (server.toolAccessMode) {
                PlatformMcpDefinitionToolAccessMode.ALL -> server.allowedTools.isEmpty()
                PlatformMcpDefinitionToolAccessMode.ALLOWLIST -> server.allowedTools.isNotEmpty()
            }, "invalid_mcp_tool_access_mode")
            check(server.allowedTools.map { it.name }.distinct().size == server.allowedTools.size,
                "duplicate_mcp_tool_grant")
        }
        config.assistants.forEach { assistant ->
            check(assistant.name.isNotBlank(), "invalid_enterprise_assistant")
            val model = models[assistant.modelId]
            check(model != null && model.type == ModelType.CHAT && (!assistant.enabled || model.enabled), "invalid_assistant_model_reference")
            check(assistant.mcpBindings.map { it.mcpServerId }.distinct().size == assistant.mcpBindings.size &&
                assistant.mcpBindings.all { mcp[it.mcpServerId]?.let { server -> !assistant.enabled || server.enabled } == true },
                "invalid_assistant_mcp_reference")
            assistant.mcpBindings.forEach { binding ->
                check(when (binding.toolSelection) {
                    PlatformAssistantMcpBindingToolSelection.ALL -> binding.toolNames.isEmpty()
                    PlatformAssistantMcpBindingToolSelection.ALLOWLIST -> binding.toolNames.isNotEmpty()
                }, "invalid_assistant_mcp_tool_selection")
                val server = mcp.getValue(binding.mcpServerId)
                check(server.toolAccessMode == PlatformMcpDefinitionToolAccessMode.ALL ||
                    binding.toolNames.all { name -> server.allowedTools.any { it.name == name } },
                    "assistant_mcp_tool_exceeds_server_grant")
            }
            check(assistant.memorySeedIds.distinct().size == assistant.memorySeedIds.size &&
                assistant.memorySeedIds.all { it in seeds }, "invalid_assistant_seed_reference")
            check(assistant.allowedSubAssistantIds.distinct().size == assistant.allowedSubAssistantIds.size &&
                assistant.allowedSubAssistantIds.all { id ->
                    id != assistant.id && assistants[id]?.let { child -> child.allowAsSubAssistant && (!assistant.enabled || child.enabled) } == true
                }, "invalid_sub_assistant_reference")
        }
        config.starters.forEach {
            check(assistants[it.assistantId]?.let { assistant -> !it.enabled || assistant.enabled } == true &&
                it.title.isNotBlank() && it.prompt.isNotBlank(), "invalid_enterprise_starter")
        }
        val defaults = config.defaults
        defaults.assistantId?.let { check(assistants[it]?.enabled == true, "invalid_default_assistant") }
        listOfNotNull(defaults.chatModelId, defaults.fastModelId, defaults.titleModelId, defaults.attachmentInspectionModelId,
            defaults.suggestionModelId, defaults.compressModelId).forEach {
            check(models[it]?.let { model -> model.enabled && model.type == ModelType.CHAT } == true, "invalid_default_chat_model")
        }
        defaults.attachmentInspectionModelId?.let {
            check(Modality.IMAGE in requireNotNull(models[it]).inputModalities, "invalid_default_attachment_inspection_model_modality")
        }
        defaults.imageGenerationModelId?.let {
            check(config.imageGenerators.any { resource -> resource.id == it && resource.enabled }, "invalid_default_image_model")
        }
        defaults.ttsId?.let { check(config.tts.any { resource -> resource.id == it && resource.enabled }, "invalid_default_tts") }
        defaults.asrId?.let { check(config.asr.any { resource -> resource.id == it && resource.enabled }, "invalid_default_asr") }
    }

    private fun check(condition: Boolean, reason: String) { if (!condition) fail(reason) }
    private fun fail(reason: String): Nothing = throw EnterpriseConfigurationException(reason)
}
