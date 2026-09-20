package net.weero.measix.pilot.data.datastore

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences

/** Rewrites the retired URL-qualified principal representation before strict current readers run. */
internal class EnterprisePrincipalPreferencesMigration(
    private val key: Preferences.Key<String>,
    private val migrateDocument: (String) -> String?,
) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        currentData[key]?.let(migrateDocument) != null

    override suspend fun migrate(currentData: Preferences): Preferences {
        val encoded = currentData[key] ?: return currentData
        val migrated = migrateDocument(encoded) ?: return currentData
        return currentData.toMutablePreferences().apply { this[key] = migrated }.toPreferences()
    }

    override suspend fun cleanUp() = Unit
}
