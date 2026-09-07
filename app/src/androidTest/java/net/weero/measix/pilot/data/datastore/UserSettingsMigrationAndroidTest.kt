package net.weero.measix.pilot.data.datastore

import android.content.Context
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class UserSettingsMigrationAndroidTest {
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
        block: suspend (androidx.datastore.core.DataStore<Preferences>) -> Unit,
    ) {
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(
            migrations = if (migrate) listOf(UserSettingsMigration()) else emptyList(),
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
