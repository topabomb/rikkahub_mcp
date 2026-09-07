package net.weero.measix.pilot.data.configuration

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.common.configuration.EnterpriseAuthority

/** Durable data and usage preferences belong to a principal, not an assistant definition. */
@Serializable
sealed interface ConfigurationScope {
    @Serializable
    @SerialName("personal")
    data object Personal : ConfigurationScope

    @Serializable
    @SerialName("enterprise")
    data class Enterprise(
        val authority: EnterpriseAuthority,
        val userId: String,
    ) : ConfigurationScope {
        init {
            require(userId.isNotBlank() && userId.length <= 256) { "invalid_enterprise_user_id" }
        }
    }
}
