package net.weero.measix.pilot.data.configuration

import me.rerere.common.configuration.ConfigurationReference
import kotlinx.serialization.Serializable

/** Missing fields are invalid, rather than an implicit authorization or restriction. */
@Serializable
data class EnterprisePolicy(
    val allowLocalProviders: Boolean,
    val allowLocalTts: Boolean,
    val allowLocalAsr: Boolean,
    val allowLocalMcp: Boolean,
    val allowLocalAssistants: Boolean,
) {
    fun allowsUserConfiguration(category: ConfigurationCategory): Boolean = when (category) {
        ConfigurationCategory.PROVIDER, ConfigurationCategory.MODEL -> allowLocalProviders
        ConfigurationCategory.TTS -> allowLocalTts
        ConfigurationCategory.ASR -> allowLocalAsr
        ConfigurationCategory.MCP -> allowLocalMcp
        ConfigurationCategory.ASSISTANT -> allowLocalAssistants
        ConfigurationCategory.SEARCH,
        ConfigurationCategory.SKILL,
        ConfigurationCategory.PROMPT_INJECTION,
        ConfigurationCategory.QUICK_MESSAGE,
        ConfigurationCategory.LOCAL_TOOL,
        ConfigurationCategory.WORKSPACE,
        -> true
        ConfigurationCategory.GATEWAY,
        ConfigurationCategory.MEMORY_SEED,
        ConfigurationCategory.STARTER,
        -> false // These are enterprise definitions, not user resource categories.
    }
}

enum class ConfigurationCategory {
    PROVIDER,
    MODEL,
    TTS,
    ASR,
    MCP,
    ASSISTANT,
    SEARCH,
    SKILL,
    PROMPT_INJECTION,
    QUICK_MESSAGE,
    LOCAL_TOOL,
    WORKSPACE,
    GATEWAY,
    MEMORY_SEED,
    STARTER,
}

enum class ConfigurationUnavailableReason {
    ENTERPRISE_CONFIGURATION_NOT_READY,
    USER_CATEGORY_NOT_ALLOWED,
    DIFFERENT_ENTERPRISE,
    ENTERPRISE_RESOURCE_IN_PERSONAL_SCOPE,
    RESOURCE_DISABLED,
    REFERENCE_MISSING,
}

/** Definition management and admission to a realm are intentionally separate decisions. */
data class ConfigurationAccess(
    val canEditDefinition: Boolean,
    val unavailableReason: ConfigurationUnavailableReason? = null,
    val requiredEnabled: Boolean = false,
) {
    val canSelect: Boolean get() = unavailableReason == null
    val canExecute: Boolean get() = unavailableReason == null
}

/** One policy decision shared by selectors and execution admission. */
internal fun configurationAccess(
    scope: ConfigurationScope,
    reference: ConfigurationReference,
    category: ConfigurationCategory,
    enabled: Boolean,
    policy: EnterprisePolicy?,
): ConfigurationAccess {
    val userOwned = reference is ConfigurationReference.User
    val reason = when {
        reference is ConfigurationReference.Enterprise && scope is ConfigurationScope.Personal ->
            ConfigurationUnavailableReason.ENTERPRISE_RESOURCE_IN_PERSONAL_SCOPE
        reference is ConfigurationReference.Enterprise && scope is ConfigurationScope.Enterprise &&
            reference.authority != scope.authority -> ConfigurationUnavailableReason.DIFFERENT_ENTERPRISE
        scope is ConfigurationScope.Enterprise && policy == null ->
            ConfigurationUnavailableReason.ENTERPRISE_CONFIGURATION_NOT_READY
        scope is ConfigurationScope.Enterprise && userOwned &&
            !requireNotNull(policy).allowsUserConfiguration(category) ->
            ConfigurationUnavailableReason.USER_CATEGORY_NOT_ALLOWED
        !enabled -> ConfigurationUnavailableReason.RESOURCE_DISABLED
        else -> null
    }
    return ConfigurationAccess(
        canEditDefinition = userOwned,
        unavailableReason = reason,
        requiredEnabled = reference is ConfigurationReference.Enterprise &&
            scope is ConfigurationScope.Enterprise && reference.authority == scope.authority &&
            category == ConfigurationCategory.MCP && enabled,
    )
}

@Serializable
enum class GatewayEnablementPolicy {
    REQUIRED,
    USER_CONTROLLABLE_DEFAULT_ON,
}

/** The authority partitions identical gateway IDs from unrelated local or platform sources. */
@Serializable
data class GatewayPreference(
    val gateway: ConfigurationReference.Enterprise,
    val enabled: Boolean,
)

internal fun effectiveGatewayEnabled(
    policy: GatewayEnablementPolicy,
    preference: GatewayPreference?,
    gateway: ConfigurationReference.Enterprise,
): Boolean {
    require(preference == null || preference.gateway == gateway) { "gateway_preference_scope_mismatch" }
    return policy == GatewayEnablementPolicy.REQUIRED || preference?.enabled != false
}
