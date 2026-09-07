package net.weero.measix.pilot.data.enterprise

import java.io.InputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.ModelType

/** Local transport configuration is deliberately separate from public managed definitions. */
@Serializable
internal data class EnterpriseRuntimeBinding(
    val resourceId: String,
    val protocol: EnterpriseRuntimeProtocol,
    val endpoint: String? = null,
    val credential: String? = null,
    val headers: Map<String, String> = emptyMap(),
) {
    override fun toString(): String = "EnterpriseRuntimeBinding(resourceId=$resourceId, protocol=$protocol)"
}

@Serializable
internal enum class EnterpriseRuntimeProtocol {
    EXAMPLE,
    OPENAI_CHAT,
    OPENAI_RESPONSES,
    GOOGLE_GENERATE,
    CLAUDE_MESSAGES,
    OPENAI_TTS,
    GEMINI_TTS,
    MIMO_TTS,
    OPENAI_HTTP_ASR,
    MCP_STREAMABLE_HTTP,
    MCP_SSE,
}

/** This local import format is not the production platform wire protocol. */
@Serializable
internal data class EnterprisePackage(
    val formatVersion: Int,
    val identity: EnterpriseIdentity,
    val configuration: EnterpriseConfiguration,
    val runtimeBindings: List<EnterpriseRuntimeBinding>,
    val feedSeed: EnterpriseFeedSeed? = null,
) {
    override fun toString(): String = "EnterprisePackage(formatVersion=$formatVersion, generation=${configuration.generation})"
}

/** Never attach codec exceptions: their messages can contain credential-bearing input. */
internal class EnterpriseConfigurationException(val reason: String) : IllegalArgumentException(reason)

internal object EnterprisePackageCodec {
    const val FORMAT_VERSION = 2
    const val MAX_BYTES = 4 * 1024 * 1024
    internal val json = Json { encodeDefaults = true }

    fun decode(input: InputStream): EnterprisePackage = decode(readBytes(input))

    internal fun readBytes(input: InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > MAX_BYTES) fail("enterprise_package_too_large")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    fun decode(bytes: ByteArray): EnterprisePackage {
        if (bytes.size > MAX_BYTES) fail("enterprise_package_too_large")
        return try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            json.decodeFromString<EnterprisePackage>(text).also(::validate)
        } catch (error: EnterpriseConfigurationException) {
            throw error
        } catch (_: IllegalArgumentException) {
            fail("invalid_enterprise_package")
        } catch (_: java.nio.charset.CharacterCodingException) {
            fail("invalid_enterprise_package_encoding")
        }
    }

    fun encode(value: EnterprisePackage): ByteArray {
        validate(value)
        val bytes = json.encodeToString(value).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) fail("enterprise_package_too_large")
        return bytes
    }

    fun validateIdentity(identity: EnterpriseIdentity) {
        check(identity.authority.isLocal, "local_package_requires_local_authority")
        check(identity.enterpriseName.isNotBlank() && identity.enterpriseName.length <= 256, "invalid_enterprise_name")
        check(identity.userName.isNotBlank() && identity.userName.length <= 256, "invalid_enterprise_user_name")
        try { identity.scope } catch (_: IllegalArgumentException) { fail("invalid_enterprise_user_id") }
    }

    fun validate(value: EnterprisePackage) {
        check(value.formatVersion == FORMAT_VERSION, "unsupported_enterprise_package_version")
        val identity = value.identity
        validateIdentity(identity)
        val config = value.configuration
        check(config.generation > 0, "invalid_enterprise_generation")
        val ids = config.runtimeResources().keys.toList() + config.assistants.map { it.id } +
            config.memorySeeds.map { it.id } + config.starters.map { it.id }
        val expectedCount = config.models.size + config.tts.size + config.asr.size + config.mcpServers.size +
            config.gateways.size + config.assistants.size + config.memorySeeds.size + config.starters.size
        check(ids.size == expectedCount && ids.distinct().size == ids.size, "duplicate_enterprise_resource_id")
        ids.forEach { id ->
            try { identity.reference(id) } catch (_: IllegalArgumentException) { fail("invalid_enterprise_resource_id") }
        }
        check(value.runtimeBindings.map { it.resourceId }.distinct().size == value.runtimeBindings.size, "duplicate_runtime_binding")
        val resources = config.runtimeResources()
        check(resources.keys == value.runtimeBindings.map { it.resourceId }.toSet(), "runtime_binding_set_mismatch")
        value.runtimeBindings.forEach { binding -> validateBinding(binding, resources.getValue(binding.resourceId)) }

        val models = config.models.associateBy { it.id }
        val assistants = config.assistants.associateBy { it.id }
        val mcp = config.mcpServers.associateBy { it.id }
        val seeds = config.memorySeeds.associateBy { it.id }
        config.models.forEach {
            check(it.name.isNotBlank() && it.modelId.isNotBlank(), "invalid_enterprise_model")
            check(it.inputModalities.isNotEmpty() && it.outputModalities.isNotEmpty(), "invalid_model_modalities")
        }
        (config.tts + config.asr).forEach {
            check(it.name.isNotBlank() && it.modelId.isNotBlank(), "invalid_enterprise_speech_resource")
        }
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
            check(assistants[it.assistantId]?.enabled == true && it.title.isNotBlank() && it.prompt.isNotBlank(), "invalid_enterprise_starter")
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
        value.feedSeed?.let { seed ->
            try { EnterpriseFeed.initialize(seed) }
            catch (_: EnterpriseFeedException) { fail("invalid_enterprise_feed") }
        }
    }

    private fun validateBinding(binding: EnterpriseRuntimeBinding, kind: EnterpriseResourceKind) {
        if (binding.protocol == EnterpriseRuntimeProtocol.EXAMPLE) {
            check(binding.endpoint == null && binding.credential == null && binding.headers.isEmpty(), "example_binding_contains_connection_details")
            return
        }
        val allowed = when (kind) {
            EnterpriseResourceKind.MODEL -> setOf(EnterpriseRuntimeProtocol.OPENAI_CHAT, EnterpriseRuntimeProtocol.OPENAI_RESPONSES,
                EnterpriseRuntimeProtocol.GOOGLE_GENERATE, EnterpriseRuntimeProtocol.CLAUDE_MESSAGES)
            EnterpriseResourceKind.TTS -> setOf(EnterpriseRuntimeProtocol.OPENAI_TTS, EnterpriseRuntimeProtocol.GEMINI_TTS, EnterpriseRuntimeProtocol.MIMO_TTS)
            EnterpriseResourceKind.ASR -> setOf(EnterpriseRuntimeProtocol.OPENAI_HTTP_ASR)
            EnterpriseResourceKind.MCP, EnterpriseResourceKind.GATEWAY -> setOf(EnterpriseRuntimeProtocol.MCP_STREAMABLE_HTTP, EnterpriseRuntimeProtocol.MCP_SSE)
        }
        check(binding.protocol in allowed, "runtime_protocol_kind_mismatch")
        val uri = try { URI(requireNotNull(binding.endpoint)) } catch (_: Exception) { fail("invalid_runtime_endpoint") }
        check(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null, "invalid_runtime_endpoint")
        check(binding.credential?.let { '\r' !in it && '\n' !in it } != false, "invalid_runtime_credential")
        check(binding.headers.all { (key, value) -> key.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) && '\r' !in value && '\n' !in value }, "invalid_runtime_headers")
        check(binding.headers.keys.map { it.lowercase(java.util.Locale.ROOT) }.distinct().size == binding.headers.size, "duplicate_runtime_header")
    }

    private fun check(condition: Boolean, reason: String) { if (!condition) fail(reason) }
    private fun fail(reason: String): Nothing = throw EnterpriseConfigurationException(reason)
}
