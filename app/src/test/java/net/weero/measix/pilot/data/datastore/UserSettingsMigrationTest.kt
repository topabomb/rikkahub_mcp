package net.weero.measix.pilot.data.datastore

import me.rerere.common.configuration.ConfigurationReference

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.jsonObject
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import me.rerere.common.configuration.EnterpriseAuthority
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UserSettingsMigrationTest {
    private fun golden(): Settings = javaClass.getResourceAsStream("settings-golden.json")!!
        .reader().use { JsonInstant.decodeFromString(it.readText()) }

    @Test
    fun `user directory rejects enterprise identities and nested bindings on write and decode`() {
        val reference = ConfigurationReference.Enterprise(EnterpriseAuthority("local:example", "dep_example"), "mdl_example")
        val original = golden()
        val invalid = listOf(
            original.copy(providers = original.providers.mapIndexed { i, provider ->
                if (i == 0) provider.copyProvider(id = reference) else provider
            }),
            original.copy(providers = listOf(original.providers.first().copyProvider(models = listOf(
                me.rerere.ai.provider.Model(providerOverwrite = original.providers.first().copyProvider(id = reference)),
            )))),
            original.copy(assistants = original.assistants.map { it.copy(chatModelId = reference) }),
            original.copy(assistants = original.assistants.map { it.copy(allowedSubAssistantIds = setOf(reference)) }),
        )
        invalid.forEach { settings ->
            assertThrows(IllegalArgumentException::class.java) {
                UserSettingsDocument.empty().withPersonalSettings(settings)
            }
        }
        val personalOverwrite = original.providers.first().copyProvider(models = emptyList())
        val validNested = original.copy(providers = listOf(original.providers.first().copyProvider(models = listOf(
            me.rerere.ai.provider.Model(providerOverwrite = personalOverwrite),
        ))))
        val validDocument = UserSettingsDocument.empty().withPersonalSettings(validNested)
        val restored = JsonInstant.decodeFromString<UserSettingsDocument>(JsonInstant.encodeToString(validDocument))
        assertEquals(personalOverwrite, restored.configuration.providers.single().models.single().providerOverwrite)
        val document = UserSettingsDocument.empty().withPersonalSettings(original)
        val encoded = JsonInstant.encodeToString(document)
            .replace(original.providers.first().id.toString(), reference.toString())
        assertThrows(IllegalArgumentException::class.java) {
            JsonInstant.decodeFromString<UserSettingsDocument>(encoded)
        }
        val other = ConfigurationScope.Enterprise(EnterpriseAuthority("local:another", "dep_example"), "usr_one")
        assertThrows(IllegalArgumentException::class.java) {
            UserPreferences(scopes = listOf(ScopedUserPreferences(other, ResourceSelections(chatModelId = reference))))
        }
    }

    @Test
    fun `all personal fields survive the split with profile outside display preferences`() {
        val original = golden().copy(
            displaySetting = DisplaySetting(
                userNickname = "My profile",
                bubbleOpacity = 0.65f,
                showDateTimeInMessage = true,
                showTokenUsage = false,
                codeBlockAutoWrap = false,
                chatCustomFontPath = "content://fonts/personal-font",
                pasteLongTextThreshold = 3100,
                ttsOnlyReadQuoted = true,
                autoPlayTTSAfterGeneration = true,
                updateCheckDisabledUntilEpochMillis = 1900000000000L,
            ),
            pendingAssistantDeletions = listOf(PendingAssistantDeletion(ConfigurationReference.random())),
        )
        val document = UserSettingsDocument.empty().withPersonalSettings(original)
        val restored = JsonInstant.decodeFromString<UserSettingsDocument>(JsonInstant.encodeToString(document))
        assertEquals(JsonInstant.encodeToString(original), JsonInstant.encodeToString(restored.personalSettings()))
        assertEquals(original.pendingAssistantDeletions, restored.internalState.pendingAssistantDeletions)
        val encoded = JsonInstant.parseToJsonElement(JsonInstant.encodeToString(document)).jsonObject
        val display = encoded.getValue("preferences").jsonObject.getValue("common").jsonObject
            .getValue("display").jsonObject
        assertFalse("userAvatar" in display)
        assertFalse("userNickname" in display)
        assertFalse("providers" in encoded.getValue("preferences").jsonObject)
    }

    @Test
    fun `migration moves user credentials selections and tombstones in one preferences value`() = runTest {
        val settings = golden()
        val tombstone = PendingAssistantDeletion(settings.assistantId, "artifact:avatar", "artifact:background")
        val old = mutablePreferencesOf(
            SettingsStore.PROVIDERS to JsonInstant.encodeToString(settings.providers),
            SettingsStore.ASSISTANTS to JsonInstant.encodeToString(settings.assistants),
            SettingsStore.DISPLAY_SETTING to JsonInstant.encodeToString(settings.displaySetting),
            SettingsStore.WEBDAV_CONFIG to JsonInstant.encodeToString(settings.webDavConfig),
            SettingsStore.SELECT_MODEL to settings.chatModelId.toString(),
            SettingsStore.SELECT_ASSISTANT to settings.assistantId.toString(),
            SettingsStore.PENDING_ASSISTANT_DELETIONS to JsonInstant.encodeToString(listOf(tombstone)),
            SettingsStore.PENDING_MCP_CATALOG_MIGRATION to "catalog-owned-staging",
            LEGACY_DEVELOPER_MODE to true,
        )
        val migration = UserSettingsMigration()
        assertTrue(migration.shouldMigrate(old))
        val migrated = migration.migrate(old)
        assertEquals(
            setOf(SettingsStore.USER_SETTINGS, SettingsStore.PENDING_MCP_CATALOG_MIGRATION),
            migrated.asMap().keys,
        )
        val document = JsonInstant.decodeFromString<UserSettingsDocument>(migrated[SettingsStore.USER_SETTINGS]!!)
        assertEquals(JsonInstant.encodeToString(settings.providers), JsonInstant.encodeToString(document.configuration.providers))
        assertEquals(settings.webDavConfig, document.configuration.webDavConfig)
        assertEquals(settings.chatModelId, document.personalSettings().chatModelId)
        assertEquals(listOf(tombstone), document.internalState.pendingAssistantDeletions)
        assertEquals("catalog-owned-staging", migrated[SettingsStore.PENDING_MCP_CATALOG_MIGRATION])
        assertFalse(migration.shouldMigrate(migrated))
        assertTrue(old.contains(SettingsStore.PROVIDERS))
        assertFalse(old.contains(SettingsStore.USER_SETTINGS))
    }

    @Test
    fun `invalid old resource or tombstone aborts migration without erasing original state`() = runTest {
        for (key in listOf(SettingsStore.TTS_PROVIDERS, SettingsStore.PENDING_ASSISTANT_DELETIONS)) {
            val old = mutablePreferencesOf(key to "[{broken]")
            var failed = false
            try {
                UserSettingsMigration().migrate(old)
            } catch (_: SerializationException) {
                failed = true
            }
            assertTrue(failed)
            assertEquals("[{broken]", old[key])
            assertFalse(old.contains(SettingsStore.USER_SETTINGS))
        }
    }

    @Test
    fun `personal updates preserve another principal preferences and never copy its resources`() {
        val authority = EnterpriseAuthority("local:example", "dep_example")
        val enterprise = ScopedUserPreferences(
            ConfigurationScope.Enterprise(authority, "usr_one"),
            ResourceSelections(chatModelId = ConfigurationReference.Enterprise(authority, "mdl_example")),
        )
        val before = UserSettingsDocument.empty(preferences = UserPreferences(scopes = listOf(enterprise)))
        val after = before.withPersonalSettings(golden())
        assertEquals(enterprise, after.preferences.scopes.single { it.scope == enterprise.scope })
        assertEquals(2, after.preferences.scopes.size)
        assertEquals(golden().providers.map { it.id }, after.configuration.providers.map { it.id })
        assertThrows(IllegalArgumentException::class.java) {
            after.copy(preferences = UserPreferences(scopes = listOf(
                enterprise.copy(scope = ConfigurationScope.Personal),
            )))
        }
    }

    @Test
    fun `fresh install has personal defaults and unsupported schema never reads old keys`() = runTest {
        val fresh = UserSettingsMigration().migrate(emptyPreferences())
        val document = JsonInstant.decodeFromString<UserSettingsDocument>(fresh[SettingsStore.USER_SETTINGS]!!)
        assertEquals(DEFAULT_ASSISTANT_ID, document.personalSettings().assistantId)
        assertEquals(DEFAULT_AUTO_MODEL_ID, document.personalSettings().chatModelId)
        assertEquals(listOf(ConfigurationScope.Personal), document.preferences.scopes.map { it.scope })
        assertThrows(SerializationException::class.java) {
            JsonInstant.decodeFromString<UserSettingsDocument>("{}")
        }
        val future = mutablePreferencesOf(SettingsStore.USER_SETTINGS to "{\"schemaVersion\":999}")
        var rejected = false
        try {
            UserSettingsMigration().shouldMigrate(future)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
        assertEquals("{\"schemaVersion\":999}", future[SettingsStore.USER_SETTINGS])
    }
}
