package net.weero.measix.pilot.data.datastore

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.ProviderSetting
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.utils.JsonInstant
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import me.rerere.common.configuration.EnterpriseAuthority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class UserSettingsMigrationAndroidTest {
    @Test
    fun navigationPreferenceAdoptsPersonalOnlyAndPreservesOtherPreferencesAfterReopen() = runBlocking {
        val file = testFile()
        val context = isolatedContext()
        val legacy = context.getSharedPreferences("MeasixPilot.preferences", Context.MODE_PRIVATE)
        val personalId = Uuid.random()
        val enterpriseId = Uuid.random()
        val enterprise = ConfigurationScope.Enterprise(EnterpriseAuthority("local:example", "deployment"), "alice")
        val initial = UserSettingsDocument.empty().let { document ->
            document.copy(preferences = document.preferences.withLastConversation(enterprise, enterpriseId))
        }
        try {
            assertTrue(legacy.edit().putString("lastConversationId", personalId.toString()).putBoolean("other", true).commit())
            withStore(file, false) { it.edit { values -> values[SettingsStore.USER_SETTINGS] = JsonInstant.encodeToString(initial) } }
            withStore(file, true, listOf(ConversationHistoryPreferenceMigration(context))) { store ->
                val document = JsonInstant.decodeFromString<UserSettingsDocument>(store.data.first()[SettingsStore.USER_SETTINGS]!!)
                assertEquals(personalId, document.preferences.lastConversation(ConfigurationScope.Personal))
                assertEquals(enterpriseId, document.preferences.lastConversation(enterprise))
                assertFalse(legacy.contains("lastConversationId"))
                assertTrue(legacy.getBoolean("other", false))
                store.edit { values ->
                    values[SettingsStore.USER_SETTINGS] = JsonInstant.encodeToString(document.withPersonalSettings(Settings()))
                }
            }
            withStore(file, false) { store ->
                val document = JsonInstant.decodeFromString<UserSettingsDocument>(store.data.first()[SettingsStore.USER_SETTINGS]!!)
                assertEquals(personalId, document.preferences.lastConversation(ConfigurationScope.Personal))
                assertEquals(enterpriseId, document.preferences.lastConversation(enterprise))
                assertNull(document.preferences.lastConversation(enterprise.copy(userId = "bob")))
            }
        } finally { file.delete(); legacy.edit().clear().commit() }
    }

    @Test
    fun failedNavigationMigrationRetainsLegacyKeyAndRetriesWithoutOverwritingScopedHistory() = runBlocking {
        val file = testFile()
        val context = isolatedContext()
        val legacy = context.getSharedPreferences("MeasixPilot.preferences", Context.MODE_PRIVATE)
        val migratedId = Uuid.random()
        val newerId = Uuid.random()
        try {
            assertTrue(legacy.edit().putString("lastConversationId", migratedId.toString()).commit())
            withStore(file, false) { store -> store.edit { it[SettingsStore.USER_SETTINGS] = JsonInstant.encodeToString(UserSettingsDocument.empty()) } }
            val before = file.readBytes()
            val reject = object : DataMigration<Preferences> {
                override suspend fun shouldMigrate(currentData: Preferences) = true
                override suspend fun migrate(currentData: Preferences): Preferences = error("reject before commit")
                override suspend fun cleanUp() = Unit
            }
            withStore(file, true, listOf(ConversationHistoryPreferenceMigration(context), reject)) { store ->
                try { store.data.first(); error("Migration must fail") }
                catch (expected: IllegalStateException) { assertEquals("reject before commit", expected.message) }
            }
            assertTrue(before.contentEquals(file.readBytes()))
            assertTrue(legacy.contains("lastConversationId"))
            withStore(file, true, listOf(ConversationHistoryPreferenceMigration(context))) { store ->
                val document = JsonInstant.decodeFromString<UserSettingsDocument>(store.data.first()[SettingsStore.USER_SETTINGS]!!)
                assertEquals(migratedId, document.preferences.lastConversation(ConfigurationScope.Personal))
                store.edit { values ->
                    values[SettingsStore.USER_SETTINGS] = JsonInstant.encodeToString(document.copy(
                        preferences = document.preferences.withLastConversation(ConfigurationScope.Personal, newerId),
                    ))
                }
            }
            // A crash after the DataStore commit but before legacy cleanup may leave the old key.
            assertTrue(legacy.edit().putString("lastConversationId", migratedId.toString()).commit())
            withStore(file, true, listOf(ConversationHistoryPreferenceMigration(context))) { store ->
                val document = JsonInstant.decodeFromString<UserSettingsDocument>(store.data.first()[SettingsStore.USER_SETTINGS]!!)
                assertEquals(newerId, document.preferences.lastConversation(ConfigurationScope.Personal))
                assertFalse(legacy.contains("lastConversationId"))
            }
        } finally { file.delete(); legacy.edit().clear().commit() }
    }

    private fun isolatedContext(): Context {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "navigation-migration-${Uuid.random()}"
        return object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("${name}-$nameSuffix", mode)
            private val nameSuffix = name
        }
    }

    @Test
    fun committedMigrationSurvivesReopenWithOriginalIdentityAndCredentials() = runBlocking {
        val file = testFile()
        val provider = ProviderSetting.OpenAI(
            id = ConfigurationReference.parse("11111111-1111-1111-1111-111111111111"),
            apiKey = "migration-test-key",
        )
        try {
            withStore(file, migrate = false) { store ->
                store.edit {
                    it[SettingsStore.PROVIDERS] = JsonInstant.encodeToString<List<ProviderSetting>>(listOf(provider))
                    it[SettingsStore.PENDING_MCP_CATALOG_MIGRATION] = "catalog-staging"
                }
            }
            withStore(file, migrate = true) { store ->
                val values = store.data.first()
                assertEquals(setOf(SettingsStore.USER_SETTINGS, SettingsStore.PENDING_MCP_CATALOG_MIGRATION), values.asMap().keys)
                val document = JsonInstant.decodeFromString<UserSettingsDocument>(values[SettingsStore.USER_SETTINGS]!!)
                assertEquals(provider, document.configuration.providers.single())
            }
            withStore(file, migrate = false) { store ->
                val values = store.data.first()
                assertFalse(values.contains(SettingsStore.PROVIDERS))
                assertEquals("catalog-staging", values[SettingsStore.PENDING_MCP_CATALOG_MIGRATION])
                val document = JsonInstant.decodeFromString<UserSettingsDocument>(values[SettingsStore.USER_SETTINGS]!!)
                assertEquals(provider, document.configuration.providers.single())
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectedMigrationLeavesOldBytesAvailableAfterReopen() = runBlocking {
        val file = testFile()
        try {
            withStore(file, migrate = false) { store ->
                store.edit { it[SettingsStore.PROVIDERS] = "invalid-json" }
            }
            val before = file.readBytes()
            var rejected = false
            withStore(file, migrate = true) { store ->
                try {
                    store.data.first()
                } catch (_: IllegalArgumentException) {
                    rejected = true
                }
            }
            assertTrue(rejected)
            assertTrue(before.contentEquals(file.readBytes()))
            withStore(file, migrate = false) { store ->
                val values = store.data.first()
                assertEquals("invalid-json", values[SettingsStore.PROVIDERS])
                assertFalse(values.contains(SettingsStore.USER_SETTINGS))
            }
        } finally {
            file.delete()
        }
    }

    private fun testFile(): File = File(
        ApplicationProvider.getApplicationContext<Context>().cacheDir,
        "user-settings-migration-${Uuid.random()}.preferences_pb",
    )

    private suspend fun withStore(
        file: File,
        migrate: Boolean,
        extraMigrations: List<DataMigration<Preferences>> = emptyList(),
        block: suspend (androidx.datastore.core.DataStore<Preferences>) -> Unit,
    ) {
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(
            migrations = (if (migrate) listOf(UserSettingsMigration()) else emptyList()) + extraMigrations,
            scope = CoroutineScope(job + Dispatchers.IO),
            produceFile = { file },
        )
        try {
            block(store)
        } finally {
            job.cancelAndJoin()
        }
    }
}
