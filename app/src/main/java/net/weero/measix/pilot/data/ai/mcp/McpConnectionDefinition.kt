package net.weero.measix.pilot.data.ai.mcp

import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.enterprise.EnterpriseAppliedVersion
import net.weero.measix.pilot.data.enterprise.EnterpriseRuntimeBinding
import net.weero.measix.pilot.data.enterprise.EnterpriseRuntimeProtocol
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

    class Managed(
        val access: RealmAccess.Enterprise,
        override val id: ConfigurationReference.Enterprise,
        override val name: String,
        val binding: EnterpriseRuntimeBinding,
        val version: EnterpriseAppliedVersion,
        val interactionId: String,
        surface: McpGatewaySurface?,
    ) : McpConnectionDefinition {
        init {
            require(id.authority == access.scope.authority && binding.resourceId == id.id)
            require(binding.protocol in setOf(EnterpriseRuntimeProtocol.MCP_STREAMABLE_HTTP, EnterpriseRuntimeProtocol.EXAMPLE))
            require(interactionId.matches(Regex("int_[0-9a-f-]{36}")))
        }
        override val enabled get() = true
        override val catalogKey get() = McpCatalogKey(access.scope, id)
        override val managed = McpManagedCatalog(version.generation, surface)
        // Bound the Provider namespace independently of localized names and long resource identifiers.
        override val namespace get() = managedMcpNamespace(id)
        val url get() = binding.endpoint ?: "https://local-enterprise.invalid/runtime/v1/resources/${id.id}/mcp"
        val headers: List<Pair<String, String>> get() = buildList {
            addAll(binding.headers.entries.map { it.key to it.value })
            binding.credential?.let { add("Authorization" to "Bearer $it") }
            add("X-Measix-Managed-Generation" to version.generation.toString())
            add("X-Measix-Interaction-Id" to interactionId)
        }
        override fun connectionFingerprint() = McpConnectionFingerprint("managed_streamable_http", url, name, headers)
        override fun mcpDefinitionDigest(): String = managedMcpDefinitionDigest(id, name, binding, version.generation)
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

internal fun managedMcpDefinitionDigest(
    id: ConfigurationReference.Enterprise,
    name: String,
    binding: EnterpriseRuntimeBinding,
    generation: Long,
): String = sha256(
    JsonInstant.encodeToString(binding.copy(headers = binding.headers.toSortedMap(String.CASE_INSENSITIVE_ORDER))) + "\u0000" + id + "\u0000" + name + "\u0000" + generation,
)
