package me.rerere.common.configuration

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.uuid.Uuid

@Serializable
data class EnterpriseAuthority(
    val sourceNamespace: String,
    val deploymentId: String,
) {
    init {
        require(sourceNamespace.matches(Regex("(local|platform):[A-Za-z0-9._-]{1,128}"))) {
            "invalid_source_namespace"
        }
        require(deploymentId.matches(Regex("[A-Za-z0-9._-]{1,256}"))) { "invalid_deployment_id" }
    }

    val isLocal: Boolean get() = sourceNamespace.startsWith("local:")
}

/** User UUIDs and authority-qualified enterprise IDs share a lossless scalar wire format. */
@Serializable(with = ConfigurationReferenceSerializer::class)
sealed class ConfigurationReference {
    data class User(val id: Uuid) : ConfigurationReference()

    @Serializable(with = EnterpriseConfigurationReferenceSerializer::class)
    data class Enterprise(
        val authority: EnterpriseAuthority,
        val id: String,
    ) : ConfigurationReference() {
        init {
            require(id.matches(Regex("[a-z][a-z0-9]*_[A-Za-z0-9._-]{1,256}"))) {
                "invalid_enterprise_resource_id"
            }
        }
    }

    final override fun toString(): String = when (this) {
        is User -> id.toString()
        is Enterprise -> "managed~${authority.sourceNamespace.replace(':', '~')}~${authority.deploymentId}~$id"
    }

    companion object {
        fun random(): ConfigurationReference = User(Uuid.random())

        fun parse(value: String): ConfigurationReference {
            if (!value.startsWith("managed~")) return User(Uuid.parse(value))
            val parts = value.split('~')
            require(parts.size == 5) { "invalid_enterprise_reference" }
            return Enterprise(
                EnterpriseAuthority("${parts[1]}:${parts[2]}", parts[3]),
                parts[4],
            )
        }
    }
}

object ConfigurationReferenceSerializer : KSerializer<ConfigurationReference> {
    override val descriptor = PrimitiveSerialDescriptor("ConfigurationReference", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ConfigurationReference) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): ConfigurationReference = ConfigurationReference.parse(decoder.decodeString())
}

object EnterpriseConfigurationReferenceSerializer : KSerializer<ConfigurationReference.Enterprise> {
    override val descriptor = PrimitiveSerialDescriptor("EnterpriseConfigurationReference", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ConfigurationReference.Enterprise) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): ConfigurationReference.Enterprise =
        ConfigurationReference.parse(decoder.decodeString()) as? ConfigurationReference.Enterprise
            ?: throw kotlinx.serialization.SerializationException("enterprise_reference_required")
}
