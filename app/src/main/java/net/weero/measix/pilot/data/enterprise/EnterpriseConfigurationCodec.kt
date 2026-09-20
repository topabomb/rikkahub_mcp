package net.weero.measix.pilot.data.enterprise

import kotlinx.serialization.json.Json
import me.rerere.ai.provider.ModelType

/** Stable validation failures cross the application boundary without exposing credential-bearing wire input. */
internal class EnterpriseConfigurationException(val reason: String) : IllegalArgumentException(reason)

internal object EnterpriseConfigurationCodec {
    const val MAX_BYTES = 4 * 1024 * 1024
    internal val json = Json { encodeDefaults = true }

    fun validateIdentity(identity: EnterpriseIdentity) {
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
        val ids = config.models.map { it.id } + config.tts.map { it.id } + config.asr.map { it.id } +
            config.mcpServers.map { it.id } + config.gateways.map { it.id } + config.assistants.map { it.id } +
            config.memorySeeds.map { it.id } + config.starters.map { it.id }
        val expectedCount = config.models.size + config.tts.size + config.asr.size + config.mcpServers.size +
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
        config.tts.forEach(EnterpriseTtsResource::validate)
        config.asr.forEach(EnterpriseAsrResource::validate)
        (config.mcpServers.map { it.name } + config.gateways.map { it.name }).forEach {
            check(it.isNotBlank(), "invalid_enterprise_tool_resource")
        }
        config.assistants.forEach { assistant ->
            check(assistant.name.isNotBlank(), "invalid_enterprise_assistant")
            val model = models[assistant.modelId]
            check(model != null && model.type == ModelType.CHAT && (!assistant.enabled || model.enabled), "invalid_assistant_model_reference")
            check(assistant.mcpServerIds.distinct().size == assistant.mcpServerIds.size &&
                assistant.mcpServerIds.all { mcp[it]?.let { server -> !assistant.enabled || server.enabled } == true }, "invalid_assistant_mcp_reference")
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
        defaults.imageGenerationModelId?.let {
            check(models[it]?.let { model -> model.enabled && model.type == ModelType.IMAGE } == true, "invalid_default_image_model")
        }
        defaults.ttsId?.let { check(config.tts.any { resource -> resource.id == it && resource.enabled }, "invalid_default_tts") }
        defaults.asrId?.let { check(config.asr.any { resource -> resource.id == it && resource.enabled }, "invalid_default_asr") }
    }

    private fun check(condition: Boolean, reason: String) { if (!condition) fail(reason) }
    private fun fail(reason: String): Nothing = throw EnterpriseConfigurationException(reason)
}
