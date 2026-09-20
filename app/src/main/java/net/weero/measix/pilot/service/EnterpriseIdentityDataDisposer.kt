package net.weero.measix.pilot.service

import net.weero.measix.pilot.data.ai.mcp.McpCatalogStore
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.repository.MemoryRepository

/**
 * Coordinates principal-scoped deletion without taking storage ownership from
 * the existing conversation, file, memory, settings, or catalog owners.
 * Every owner operation is idempotent so a durable CLOSING exit can resume.
 */
internal class EnterpriseIdentityDataDisposer(
    private val conversations: ConversationApplicationService,
    private val files: FileManagementApplicationService,
    private val memories: MemoryRepository,
    private val settings: SettingsStore,
    private val catalogs: McpCatalogStore,
) {
    suspend fun clear(scope: ConfigurationScope.Enterprise) {
        catalogs.clearEnterpriseScope(scope)
        conversations.clearEnterpriseScope(scope)
        files.clearEnterpriseScope(scope)
        memories.clearEnterpriseScope(scope)
        settings.clearEnterprisePreferences(scope)
    }
}
