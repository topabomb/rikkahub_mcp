package net.weero.measix.pilot.data.datastore

import android.content.Context
import android.annotation.SuppressLint
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.utils.JsonInstant
import kotlin.uuid.Uuid

/** Adopt the released navigation preference once; cleanup follows DataStore's successful commit. */
internal class ConversationHistoryPreferenceMigration(context: Context) : DataMigration<Preferences> {
    private val legacy = context.getSharedPreferences("MeasixPilot.preferences", Context.MODE_PRIVATE)
    override suspend fun shouldMigrate(currentData: Preferences) = legacy.contains(KEY)

    override suspend fun migrate(currentData: Preferences): Preferences {
        val document = JsonInstant.decodeFromString<UserSettingsDocument>(requireNotNull(currentData[SettingsStore.USER_SETTINGS]))
        val id = legacy.getString(KEY, null)?.let(Uuid::parseOrNull)
        val personal = ConfigurationScope.Personal
        val updated = if (id != null && document.preferences.lastConversation(personal) == null) {
            document.copy(preferences = document.preferences.withLastConversation(personal, id))
        } else document
        return currentData.toMutablePreferences().apply {
            this[SettingsStore.USER_SETTINGS] = JsonInstant.encodeToString(updated)
        }.toPreferences()
    }

    @SuppressLint("UseKtx") // DataStore must retry cleanup when the synchronous commit returns false.
    override suspend fun cleanUp() {
        check(legacy.edit().remove(KEY).commit()) { "conversation_history_migration_cleanup_failed" }
    }

    private companion object { const val KEY = "lastConversationId" }
}
