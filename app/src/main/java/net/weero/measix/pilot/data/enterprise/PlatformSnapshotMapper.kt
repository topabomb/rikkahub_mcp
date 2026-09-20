package net.weero.measix.pilot.data.enterprise

import java.security.MessageDigest
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Modality
import net.weero.measix.pilot.data.configuration.EnterprisePolicy

internal object PlatformSnapshotMapper {
    fun map(connection: PlatformConnection, identity: EnterpriseIdentity, snapshot: PlatformManagedSnapshot): EnterpriseCandidate {
        require(identity.authority == connection.authority && snapshot.deploymentId == connection.discovery.deploymentId) {
            "platform_snapshot_authority_mismatch"
        }
        val providers = snapshot.providers.unique { it.providerId }
        val models = snapshot.models.unique { it.modelId }
        val imageGenerators = snapshot.imageGenerators.orEmpty().unique { it.imageId }
        val tts = snapshot.tts.unique { it.ttsId }
        val asr = snapshot.asr.unique { it.asrId }
        val mcp = snapshot.mcp.unique { it.mcpServerId }
        val assistants = snapshot.assistants.unique { it.assistantDefinitionId }
        snapshot.starters.unique { it.starterId }
        snapshot.models.forEach { model ->
            require(model.providerId in providers) { "unknown_platform_model_provider" }
            require(model.displayName.isNotBlank() && model.upstreamModelKey.isNotBlank()) { "invalid_platform_model" }
        }
        snapshot.providers.forEach { require(it.displayName.isNotBlank()) { "invalid_platform_provider_name" } }
        snapshot.imageGenerators.orEmpty().forEach { image ->
            val validPath = when (image.clientProtocol) {
                PlatformImageGenerationDefinitionClientProtocol.OPENAI_IMAGES_GENERATIONS ->
                    image.runtimePath.endsWith("/images/generations")
                PlatformImageGenerationDefinitionClientProtocol.DASHSCOPE_MULTIMODAL_GENERATION ->
                    image.runtimePath == "/api/v1/services/aigc/multimodal-generation/generation"
            }
            require(validPath) { "invalid_platform_image_generation_path" }
        }
        snapshot.mcp.forEach { require(it.displayName.isNotBlank()) { "invalid_platform_mcp_name" } }
        snapshot.assistants.forEach { assistant ->
            require(assistant.displayName.isNotBlank() && assistant.systemPrompt.isNotBlank()) { "invalid_platform_assistant" }
            require(models[assistant.modelId]?.enabled == true) { "invalid_platform_assistant_model" }
            require(assistant.mcpServerIds.all { mcp[it]?.enabled == true }) { "invalid_platform_assistant_mcp" }
            require(assistant.memorySeed.all { it.isNotBlank() }) { "empty_platform_memory_seed" }
        }
        snapshot.starters.forEach {
            require(assistants[it.assistantDefinitionId]?.enabled == true && it.title.isNotBlank() && it.prompt.isNotBlank()) {
                "invalid_platform_starter"
            }
        }
        val policy = snapshot.policy
        policy.defaultModelId?.let { require(models[it]?.enabled == true) { "invalid_platform_default_model" } }
        policy.defaultImageGenerationId?.let {
            require(imageGenerators[it]?.enabled == true) { "invalid_platform_default_image_generation" }
        }
        policy.defaultTtsId?.let { require(tts[it]?.enabled == true) { "invalid_platform_default_tts" } }
        policy.defaultAsrId?.let { require(asr[it]?.enabled == true) { "invalid_platform_default_asr" } }
        policy.defaultAssistantId?.let { require(assistants[it]?.enabled == true) { "invalid_platform_default_assistant" } }
        val routes = buildMap {
            snapshot.models.forEach { put(it.modelId, it.runtimePath) }
            snapshot.imageGenerators.orEmpty().forEach { put(it.imageId, it.runtimePath) }
            snapshot.tts.forEach {
                if (it.clientProtocol == PlatformTtsDefinitionClientProtocol.SYSTEM_TTS) {
                    require(it.runtimePath == null) { "system_tts_runtime_path" }
                } else put(it.ttsId, requireNotNull(it.runtimePath) { "missing_tts_runtime_path" })
            }
            snapshot.asr.forEach { put(it.asrId, it.runtimePath) }
            snapshot.mcp.forEach { put(it.mcpServerId, it.runtimePath) }
        }
        routes.values.forEach(::requirePlatformPath)
        fun seedId(assistantId: String, index: Int): String {
            val input = "${snapshot.deploymentId}/$assistantId/${snapshot.managedGeneration}/$index"
            return "mem_" + MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
        val configuration = EnterpriseConfiguration(
            generation = snapshot.managedGeneration,
            policy = EnterprisePolicy(policy.allowLocalProviders, policy.allowLocalTts, policy.allowLocalAsr,
                policy.allowLocalMcp, policy.allowLocalAssistants),
            providers = snapshot.providers.map { EnterpriseProvider(it.providerId, it.displayName, it.clientProtocol, it.enabled) },
            models = snapshot.models.map { model -> EnterpriseModel(
                id = model.modelId, name = model.displayName, modelId = model.upstreamModelKey,
                enabled = model.enabled, providerId = model.providerId,
                inputModalities = model.inputModalities.map { when (it) {
                    PlatformModelDefinitionInputModalitiesItem.TEXT -> Modality.TEXT
                    PlatformModelDefinitionInputModalitiesItem.IMAGE -> Modality.IMAGE
                } },
                outputModalities = listOf(Modality.TEXT),
                abilities = model.capabilities.map { when (it) {
                    PlatformModelDefinitionCapabilitiesItem.TOOL -> ModelAbility.TOOL
                    PlatformModelDefinitionCapabilitiesItem.REASONING -> ModelAbility.REASONING
                } },
            ) },
            imageGenerators = snapshot.imageGenerators.orEmpty().map { value -> EnterpriseImageGenerationResource(
                id = value.imageId,
                name = value.displayName,
                modelId = value.upstreamModelKey,
                enabled = value.enabled,
                protocol = when (value.clientProtocol) {
                    PlatformImageGenerationDefinitionClientProtocol.OPENAI_IMAGES_GENERATIONS ->
                        me.rerere.ai.provider.images.ImageGenerationClientProtocol.OPENAI_IMAGES_GENERATIONS
                    PlatformImageGenerationDefinitionClientProtocol.DASHSCOPE_MULTIMODAL_GENERATION ->
                        me.rerere.ai.provider.images.ImageGenerationClientProtocol.DASHSCOPE_MULTIMODAL_GENERATION
                },
                maxImagesPerRequest = value.maxImagesPerRequest.checkedInt(),
                allowedSizes = value.allowedSizes,
            ).also(EnterpriseImageGenerationResource::validate) },
            tts = snapshot.tts.map { value -> EnterpriseTtsResource(
                id = value.ttsId, name = value.displayName, enabled = value.enabled,
                protocol = when (value.clientProtocol) {
                    PlatformTtsDefinitionClientProtocol.OPENAI_AUDIO_SPEECH -> EnterpriseTtsProtocol.OPENAI
                    PlatformTtsDefinitionClientProtocol.GEMINI_GENERATE_CONTENT_TTS -> EnterpriseTtsProtocol.GEMINI
                    PlatformTtsDefinitionClientProtocol.MIMO_CHAT_COMPLETIONS_TTS -> EnterpriseTtsProtocol.MIMO
                    PlatformTtsDefinitionClientProtocol.SYSTEM_TTS -> EnterpriseTtsProtocol.SYSTEM
                },
                modelId = value.upstreamModelKey, voice = value.voice, voiceDesignPrompt = value.voiceDesignPrompt,
                speechRate = value.speechRate, pitch = value.pitch,
            ).also(EnterpriseTtsResource::validate) },
            asr = snapshot.asr.map { value -> EnterpriseAsrResource(
                id = value.asrId, name = value.displayName, enabled = value.enabled,
                modelId = value.upstreamModelKey, language = value.language,
                protocol = when (value.clientProtocol) {
                    PlatformAsrDefinitionClientProtocol.OPENAI_AUDIO_TRANSCRIPTIONS -> EnterpriseAsrProtocol.OPENAI_HTTP
                    PlatformAsrDefinitionClientProtocol.DASHSCOPE_HTTP_ASR -> EnterpriseAsrProtocol.DASHSCOPE_HTTP
                    PlatformAsrDefinitionClientProtocol.OPENAI_REALTIME_TRANSCRIPTION -> EnterpriseAsrProtocol.OPENAI_REALTIME
                    PlatformAsrDefinitionClientProtocol.DASHSCOPE_REALTIME_ASR -> EnterpriseAsrProtocol.DASHSCOPE
                },
                sampleRate = value.sampleRate?.checkedInt(), vadThreshold = value.vadThreshold,
                silenceDurationMs = value.silenceDurationMs?.checkedInt(), prefixPaddingMs = value.prefixPaddingMs?.checkedInt(), prompt = value.prompt,
            ).also(EnterpriseAsrResource::validate) },
            mcpServers = snapshot.mcp.map { EnterpriseMcpResource(it.mcpServerId, it.displayName, it.enabled, it.authOwnership) },
            assistants = snapshot.assistants.map { value -> EnterpriseAssistant(
                value.assistantDefinitionId, value.displayName, value.description.orEmpty(), value.modelId, value.systemPrompt,
                value.mcpServerIds.distinct(), value.memorySeed.indices.map { seedId(value.assistantDefinitionId, it) }, value.enabled,
            ) },
            memorySeeds = snapshot.assistants.flatMap { assistant -> assistant.memorySeed.mapIndexed { index, text ->
                EnterpriseMemorySeed(seedId(assistant.assistantDefinitionId, index), text)
            } },
            starters = snapshot.starters.map { EnterpriseStarter(it.starterId, it.assistantDefinitionId, it.title, it.prompt,
                it.description, it.sortOrder.checkedInt(), it.enabled) },
            gateways = emptyList(),
            defaults = EnterpriseDefaults(assistantId = policy.defaultAssistantId, chatModelId = policy.defaultModelId,
                imageGenerationModelId = policy.defaultImageGenerationId,
                ttsId = policy.defaultTtsId, asrId = policy.defaultAsrId),
        )
        require(routes.keys == configuration.runtimeResources().keys) { "platform_runtime_resource_set_mismatch" }
        return EnterpriseCandidate(identity, configuration,
            EnterpriseExecution.Platform(connection, snapshot.releaseId, snapshot.snapshotHash, routes)).also(EnterpriseCandidate::validate)
    }

    private fun Long.checkedInt(): Int {
        require(this in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "platform_integer_out_of_range" }
        return toInt()
    }

    private fun <T> List<T>.unique(id: (T) -> String): Map<String, T> = associateBy(id).also {
        require(it.size == size) { "duplicate_platform_resource" }
    }
}
