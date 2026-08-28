package net.weero.measix.pilot.data.datastore

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import net.weero.measix.pilot.data.ai.mcp.migrateLegacyMcpServersJson
import net.weero.measix.pilot.utils.JsonInstant

/** Atomically stages legacy remote schemas before Settings rewrites them as policy-only records. */
internal class McpLegacyCatalogSettingsMigration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        !currentData.contains(SettingsStore.PENDING_MCP_CATALOG_MIGRATION) &&
            currentData[SettingsStore.MCP_SERVERS]?.let(::migrateLegacyMcpServersJson) != null

    override suspend fun migrate(currentData: Preferences): Preferences {
        if (currentData.contains(SettingsStore.PENDING_MCP_CATALOG_MIGRATION)) return currentData
        val migration = currentData[SettingsStore.MCP_SERVERS]
            ?.let(::migrateLegacyMcpServersJson)
            ?: return currentData
        return currentData.toMutablePreferences().apply {
            this[SettingsStore.MCP_SERVERS] = migration.normalizedServersJson
            this[SettingsStore.PENDING_MCP_CATALOG_MIGRATION] =
                JsonInstant.encodeToString(migration.payload)
        }.toPreferences()
    }

    override suspend fun cleanUp() = Unit
}
