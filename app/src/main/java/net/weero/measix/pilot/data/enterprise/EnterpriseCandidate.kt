package net.weero.measix.pilot.data.enterprise

import kotlinx.serialization.Serializable
import me.rerere.ai.provider.ChatTransportCapabilities

/** Validated internal publication input; neither source impersonates the other's wire format. */
internal data class EnterpriseCandidate(
    val identity: EnterpriseIdentity,
    val configuration: EnterpriseConfiguration,
    val execution: EnterpriseExecution,
    val feedSeed: EnterpriseFeedSeed? = null,
)

@Serializable
internal sealed interface EnterpriseExecution {
    @Serializable
    data class Local(val bindings: List<EnterpriseRuntimeBinding>) : EnterpriseExecution

    @Serializable
    data class Platform(
        val connection: PlatformConnection,
        val releaseId: String,
        val snapshotHash: String,
        val runtimePaths: Map<String, String>,
    ) : EnterpriseExecution
}

internal fun EnterprisePackage.toCandidate(): EnterpriseCandidate {
    EnterprisePackageCodec.validate(this)
    return EnterpriseCandidate(identity, configuration, EnterpriseExecution.Local(runtimeBindings), feedSeed)
}

internal fun EnterpriseCandidate.validate() {
    when (val source = execution) {
        is EnterpriseExecution.Local -> EnterprisePackageCodec.validate(EnterprisePackage(
            EnterprisePackageCodec.FORMAT_VERSION, identity, configuration, source.bindings, feedSeed,
        ))
        is EnterpriseExecution.Platform -> {
            EnterprisePackageCodec.validateConfiguration(identity, configuration)
            require(identity.authority == source.connection.authority && feedSeed == null) { "platform_candidate_identity_mismatch" }
            require(source.releaseId.matches(Regex("rel_[0-9a-f-]{36}")) && source.snapshotHash.matches(Regex("sha256:[0-9a-f]{64}"))) {
                "invalid_platform_release"
            }
            require(source.runtimePaths.keys == configuration.runtimeResources().keys) { "platform_runtime_resource_set_mismatch" }
            source.runtimePaths.values.forEach(::requirePlatformPath)
            require(configuration.providers.map { it.id }.distinct().size == configuration.providers.size) { "duplicate_platform_provider" }
            require(configuration.models.all { model -> configuration.providers.any { it.id == model.providerId } }) { "unknown_platform_model_provider" }
            require(configuration.mcpServers.all { it.authOwnership != null }) { "missing_platform_mcp_auth_ownership" }
            require(configuration.gateways.isEmpty() && configuration.assistants.all { !it.allowAsSubAssistant && it.allowedSubAssistantIds.isEmpty() }) {
                "unsupported_platform_definition"
            }
        }
    }
}

internal fun EnterpriseCandidate.modelCapabilities(): Map<String, ChatTransportCapabilities> = configuration.models.associate { model ->
    val capability = when (val source = execution) {
        is EnterpriseExecution.Local -> when (source.bindings.single { it.resourceId == model.id }.protocol) {
            EnterpriseRuntimeProtocol.GOOGLE_GENERATE -> ChatTransportCapabilities.GOOGLE
            EnterpriseRuntimeProtocol.OPENAI_RESPONSES -> ChatTransportCapabilities.RESPONSES
            else -> ChatTransportCapabilities.BASIC
        }
        is EnterpriseExecution.Platform -> when (configuration.providers.single { it.id == model.providerId }.protocol) {
            PlatformProviderDefinitionClientProtocol.GOOGLE_GENERATE_CONTENT -> ChatTransportCapabilities.GOOGLE
            PlatformProviderDefinitionClientProtocol.OPENAI_RESPONSES -> ChatTransportCapabilities.RESPONSES
            else -> ChatTransportCapabilities.BASIC
        }
    }
    model.id to capability
}
