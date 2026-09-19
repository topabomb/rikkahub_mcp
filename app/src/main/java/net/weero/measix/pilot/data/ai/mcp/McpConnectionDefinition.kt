package net.weero.measix.pilot.data.ai.mcp

import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.enterprise.EnterpriseAppliedVersion
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.utils.JsonInstant

/** A connection input is transient. Managed credentials never become a user-editable server definition. */
internal sealed interface McpConnectionDefinition {
    val id: ConfigurationReference
    val name: String
    val enabled: Boolean
    val catalogKey: McpCatalogKey
    val managed: McpManagedCatalog?
    val namespace: String
    fun connectionFingerprint(): McpConnectionFingerprint
    fun mcpDefinitionDigest(): String
    fun toolPolicy(name: String): McpToolPolicy?

    class User(val config: McpServerConfig) : McpConnectionDefinition {
        override val id get() = config.id
        override val name get() = config.commonOptions.name
        override val enabled get() = config.commonOptions.enable && name.isNotBlank()
        override val catalogKey get() = McpCatalogKey(ConfigurationScope.Personal, id)
        override val managed: McpManagedCatalog? get() = null
        override val namespace get() = name
        override fun connectionFingerprint() = config.connectionFingerprint()
        override fun mcpDefinitionDigest() = config.mcpDefinitionDigest()
        override fun toolPolicy(name: String) = config.commonOptions.toolPolicyByName()[name]
    }

    class ManagedPlatform(
        val access: RealmAccess.Enterprise,
        override val id: ConfigurationReference.Enterprise,
        override val name: String,
        val execution: net.weero.measix.pilot.data.enterprise.EnterpriseExecution.Platform,
        val authOwnership: net.weero.measix.pilot.data.enterprise.PlatformMcpDefinitionAuthOwnership,
        val version: EnterpriseAppliedVersion,
        val interactionId: String,
        private val credential: suspend () -> String,
    ) : McpConnectionDefinition {
        init {
            require(id.authority == access.scope.authority)
            require(interactionId.matches(Regex("int_[0-9a-f-]{36}")))
        }
        override val enabled get() = true
        override val catalogKey get() = McpCatalogKey(access.scope, id)
        override val managed = McpManagedCatalog(version.generation, null)
        override val namespace get() = managedMcpNamespace(id)
        val url get() = execution.connection.runtime(id.id, execution.runtimePaths.getValue(id.id))
        private val publicHeaders get() = listOf(
            "X-Measix-Managed-Generation" to version.generation.toString(),
            "X-Measix-Interaction-Id" to interactionId,
        )
        suspend fun requestHeaders() = publicHeaders + ("Authorization" to "Bearer ${credential()}")
        override fun connectionFingerprint() = McpConnectionFingerprint("platform_streamable_http", url, name, publicHeaders)
        override fun mcpDefinitionDigest() = platformMcpDefinitionDigest(id, name, execution, version.generation, authOwnership)
        override fun toolPolicy(name: String): McpToolPolicy? = null
    }

}

/** Personal maintenance is shared; enterprise connections belong to their original interaction and Session. */
data class McpRuntimeKey(
    val serverId: ConfigurationReference,
    val access: RealmAccess.Enterprise? = null,
    val interactionId: String? = null,
) {
    init {
        require((access == null) == (interactionId == null))
        require(access != null || serverId is ConfigurationReference.User)
        if (serverId is ConfigurationReference.Enterprise) require(serverId.authority == access?.scope?.authority)
    }
}

internal enum class McpDefinitionUse { EXECUTION, CATALOG_PUBLICATION }

internal fun managedMcpNamespace(id: ConfigurationReference.Enterprise): String = "enterprise_" + sha256(id.toString()).take(16)

/** Public route and release facts define catalog identity; access-token rotation never invalidates schemas. */
internal fun platformMcpDefinitionDigest(
    id: ConfigurationReference.Enterprise,
    name: String,
    execution: net.weero.measix.pilot.data.enterprise.EnterpriseExecution.Platform,
    generation: Long,
    authOwnership: net.weero.measix.pilot.data.enterprise.PlatformMcpDefinitionAuthOwnership,
): String = sha256(JsonInstant.encodeToString(listOf(id.toString(), name, execution.connection.origin,
    execution.connection.discovery.deploymentId, execution.releaseId, execution.snapshotHash, generation.toString(),
    execution.connection.runtime(id.id, execution.runtimePaths.getValue(id.id)), authOwnership.name)))
