package net.weero.measix.pilot.data.configuration

import java.util.Base64
import me.rerere.common.configuration.EnterpriseAuthority

/** Canonical database identity; display names and the currently selected space are not part of it. */
internal fun ConfigurationScope.storageKey(): String = when (this) {
    ConfigurationScope.Personal -> PERSONAL_SCOPE_KEY
    is ConfigurationScope.Enterprise -> {
        val bytes = userId.toByteArray(Charsets.UTF_8)
        require(bytes.toString(Charsets.UTF_8) == userId) { "invalid_enterprise_user_encoding" }
        "enterprise~${authority.sourceNamespace}~${authority.deploymentId}~" +
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

internal const val PERSONAL_SCOPE_KEY = "personal"

internal fun configurationScopeFromStorageKey(value: String): ConfigurationScope {
    if (value == PERSONAL_SCOPE_KEY) return ConfigurationScope.Personal
    val parts = value.split('~')
    require(parts.size == 4 && parts[0] == "enterprise") { "invalid_configuration_scope_key" }
    val scope = ConfigurationScope.Enterprise(
        authority = EnterpriseAuthority(parts[1], parts[2]),
        userId = Base64.getUrlDecoder().decode(parts[3]).toString(Charsets.UTF_8),
    )
    require(scope.storageKey() == value) { "noncanonical_configuration_scope_key" }
    return scope
}
