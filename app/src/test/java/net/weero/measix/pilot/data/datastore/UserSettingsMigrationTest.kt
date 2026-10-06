package net.weero.measix.pilot.data.datastore

import me.rerere.common.configuration.ConfigurationReference

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.configuration.GatewayPreference
import net.weero.measix.pilot.data.configuration.LegacyEnterprisePrincipalEncoding
import kotlinx.serialization.encodeToString
import me.rerere.common.configuration.EnterpriseAuthority
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UserSettingsMigrationTest {
    @Test
    fun `enterprise principal migration removes URL source from scopes and references exactly once`() = runTest {
        val hash = "a".repeat(64)
        val encoded = """{"preferences":{"scopes":[{"scope":{"type":"enterprise","authority":{"sourceNamespace":"platform:$hash","deploymentId":"dep_example"},"userId":"user"},"selections":{"chatModelId":"managed~platform~$hash~dep_example~mdl_example"}}]}}"""
        val before = mutablePreferencesOf(SettingsStore.USER_SETTINGS to encoded)
        val migration = EnterprisePrincipalPreferencesMigration(
            SettingsStore.USER_SETTINGS,
            LegacyEnterprisePrincipalEncoding::migrateSettingsJson,
        )
        assertTrue(migration.shouldMigrate(before))
        val after = migration.migrate(before)
        val migrated = after[SettingsStore.USER_SETTINGS]!!
        assertFalse(migrated.contains("sourceNamespace"))
        assertTrue(migrated.contains("managed~dep_example~mdl_example"))
        assertFalse(migration.shouldMigrate(after))
    }

    @Test
    fun `settings migration rewrites only schema identity slots including preset tool metadata`() {
        val reference = "managed~platform~abc~dep_example~mdl_example"
        val current = "managed~dep_example~mdl_example"
        val raw = """{"configuration":{"opaque":"$reference"},"preferences":{"common":{"opaque":"$reference"},"scopes":[{
            "scope":{"type":"enterprise","authority":{"sourceNamespace":"platform:abc","deploymentId":"dep_example"},"userId":"user"},
            "selections":{"chatModelId":"$reference","unknown":"$reference"},
            "assistantUsage":[{"assistantId":"$reference","chatModelId":{"value":"$reference"},
                "tags":{"value":["$reference"]},"additionalSubAssistantIds":["$reference"],
                "messageTemplate":{"value":"$reference"},"enabledSkills":{"value":["$reference"]},
                "regexes":{"value":[{"id":"$reference","findRegex":"$reference","replaceString":"$reference"}]},
                "customHeaders":{"value":[{"name":"$reference","value":"$reference"}]},
                "customBodies":{"value":[{"key":"body","value":{"sourceNamespace":"platform:abc","deploymentId":"dep_example"}},
                    {"key":"nested","value":{"sourceNamespace":{"opaque":"$reference"},"deploymentId":["$reference"]}}]},
                "presetMessages":{"value":[{"modelId":"$reference","providerMetadata":{"modelId":"$reference"},
                    "parts":[{"type":"text","text":"$reference"},{"type":"tool","input":"$reference",
                        "metadata":{"sub_assistant_call":{"target_assistant_id":"$reference","summary":"$reference"}},
                        "output":[{"type":"text","text":"$reference"}]}]}]}}],
            "gateways":[{"gateway":"$reference","opaque":"$reference"}]}]}}""".trimIndent()
        val before = JsonInstant.parseToJsonElement(raw).jsonObject
        val migrated = requireNotNull(LegacyEnterprisePrincipalEncoding.migrateSettingsJson(raw))
        val after = JsonInstant.parseToJsonElement(migrated).jsonObject
        assertEquals(before["configuration"], after["configuration"])
        val originalPreferences = before.getValue("preferences").jsonObject
        val preferences = after.getValue("preferences").jsonObject
        assertEquals(originalPreferences["common"], preferences["common"])
        val oldScope = originalPreferences.getValue("scopes").jsonArray.single().jsonObject
        val scope = preferences.getValue("scopes").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive(current), scope.getValue("selections").jsonObject["chatModelId"])
        assertEquals(JsonPrimitive(reference), scope.getValue("selections").jsonObject["unknown"])
        val oldUsage = oldScope.getValue("assistantUsage").jsonArray.single().jsonObject
        val usage = scope.getValue("assistantUsage").jsonArray.single().jsonObject
        listOf("messageTemplate", "enabledSkills", "customHeaders", "customBodies").forEach {
            assertEquals(oldUsage[it], usage[it])
        }
        assertEquals(JsonPrimitive(current), usage["assistantId"])
        assertEquals(JsonPrimitive(current), usage.getValue("chatModelId").jsonObject["value"])
        val regex = usage.getValue("regexes").jsonObject.getValue("value").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive(current), regex["id"])
        assertEquals(JsonPrimitive(reference), regex["findRegex"])
        assertEquals(JsonPrimitive(reference), regex["replaceString"])
        val preset = usage.getValue("presetMessages").jsonObject.getValue("value").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive(current), preset["modelId"])
        val parts = preset.getValue("parts").jsonArray
        assertEquals(JsonPrimitive(reference), parts[0].jsonObject["text"])
        val tool = parts[1].jsonObject
        assertEquals(JsonPrimitive(current), tool.getValue("metadata").jsonObject.getValue("sub_assistant_call").jsonObject["target_assistant_id"])
        assertEquals(JsonPrimitive(reference), tool["input"])
        assertEquals(JsonPrimitive(reference), tool.getValue("output").jsonArray.single().jsonObject["text"])
        assertEquals(null, LegacyEnterprisePrincipalEncoding.migrateSettingsJson(migrated))
    }

    @Test
    fun `retired local preferences decode preserve personal settings and cannot merge with platform`() {
        val original = UserSettingsDocument.empty().withPersonalSettings(golden())
        val encoded = JsonInstant.parseToJsonElement(JsonInstant.encodeToString(original)).jsonObject
        val preferences = encoded.getValue("preferences").jsonObject
        fun legacy(source: String) = JsonInstant.parseToJsonElement("""{
            "scope":{"type":"enterprise","authority":{"sourceNamespace":"$source:example","deploymentId":"dep_example"},"userId":"usr_example"},
            "selections":{"assistantId":"managed~$source~example~dep_example~asd_example"},
            "assistantUsage":[],"gateways":[]}""").jsonObject
        val raw = JsonObject(encoded + ("preferences" to JsonObject(preferences + ("scopes" to JsonArray(
            preferences.getValue("scopes").jsonArray + legacy("local") + legacy("platform"),
        ))))).toString()
        val migrated = requireNotNull(LegacyEnterprisePrincipalEncoding.migrateSettingsJson(raw))
        val restored = JsonInstant.decodeFromString<UserSettingsDocument>(migrated)
        assertEquals(JsonInstant.encodeToString(original.personalSettings()), JsonInstant.encodeToString(restored.personalSettings()))
        val retired = restored.preferences.scopes[1]
        val platform = restored.preferences.scopes[2]
        val retiredScope = retired.scope as ConfigurationScope.Enterprise
        assertEquals("local:example" to "dep_example",
            me.rerere.common.configuration.RetiredLocalEnterpriseIdentity.decode(retiredScope.authority.deploymentId))
        assertTrue(retired.scope != platform.scope)
        assertTrue(retired.selections.assistantId != platform.selections.assistantId)
        assertEquals(retiredScope.authority, (retired.selections.assistantId as ConfigurationReference.Enterprise).authority)
        assertEquals(null, LegacyEnterprisePrincipalEncoding.migrateSettingsJson(migrated))
    }

    @Test
    fun `MCP migration changes only catalog ownership and preserves opaque tool schemas`() {
        val hash = "b".repeat(64)
        val legacyReference = "managed~platform~$hash~dep_example~mcp_example"
        val encoded = """{"formatVersion":1,"catalogs":[{"scope":{"type":"enterprise","authority":{"sourceNamespace":"platform:$hash","deploymentId":"dep_example"},"userId":"user"},"serverId":"$legacyReference","tools":[{"name":"opaque","inputSchema":{"sourceNamespace":"platform:$hash","deploymentId":"dep_example","default":"$legacyReference"}}]}]}"""
        val migrated = requireNotNull(LegacyEnterprisePrincipalEncoding.migrateMcpCatalogJson(encoded))
        assertTrue(migrated.contains("\"serverId\":\"managed~dep_example~mcp_example\""))
        assertTrue(migrated.contains("\"authority\":{\"deploymentId\":\"dep_example\"}"))
        assertTrue(migrated.contains("\"sourceNamespace\":\"platform:$hash\""))
        assertTrue(migrated.contains("\"default\":\"$legacyReference\""))
    }

    @Test
    fun `enterprise principal migration merges scopes and catalog owners retired from two addresses`() {
        val firstHash = "a".repeat(64)
        val secondHash = "b".repeat(64)
        fun scope(hash: String) =
            """{"type":"enterprise","authority":{"sourceNamespace":"platform:$hash","deploymentId":"dep_example"},"userId":"usr_example"}"""
        val settings = """{"preferences":{"scopes":[
            {"scope":${scope(firstHash)},"selections":{"assistantId":"managed~platform~$firstHash~dep_example~asd_first"},"assistantUsage":[{"assistantId":"managed~platform~$firstHash~dep_example~asd_first"}],"gateways":[],"lastConversationId":null},
            {"scope":${scope(secondHash)},"selections":{"assistantId":"managed~platform~$secondHash~dep_example~asd_second"},"assistantUsage":[{"assistantId":"managed~platform~$secondHash~dep_example~asd_second"}],"gateways":[],"lastConversationId":"00000000-0000-0000-0000-000000000002"}
        ]}}""".trimIndent()
        val migratedSettings = JsonInstant.parseToJsonElement(
            requireNotNull(LegacyEnterprisePrincipalEncoding.migrateSettingsJson(settings)),
        ).jsonObject.getValue("preferences").jsonObject.getValue("scopes").jsonArray
        assertEquals(1, migratedSettings.size)
        val merged = migratedSettings.single().jsonObject
        assertEquals("managed~dep_example~asd_second",
            merged.getValue("selections").jsonObject.getValue("assistantId").jsonPrimitive.content)
        assertEquals(2, merged.getValue("assistantUsage").jsonArray.size)

        fun catalog(hash: String, generation: Int) = """{
            "scope":${scope(hash)},"serverId":"managed~platform~$hash~dep_example~mcp_one",
            "revision":$generation,"definitionDigest":"sha256:${"c".repeat(64)}","catalogDigest":"sha256:${"d".repeat(64)}",
            "tools":[{"name":"tool-$generation","inputSchema":{"type":"object"}}],
            "managed":{"generation":$generation,"snapshotHash":"sha256:${"e".repeat(64)}","gatewaySurface":{"version":1,"hash":"sha256:${"f".repeat(64)}"}}
        }""".trimIndent()
        val catalogs = """{"formatVersion":1,"catalogs":[${catalog(firstHash, 1)},${catalog(secondHash, 2)}]}"""
        val migratedCatalogs = JsonInstant.parseToJsonElement(
            requireNotNull(LegacyEnterprisePrincipalEncoding.migrateMcpCatalogJson(catalogs)),
        ).jsonObject.getValue("catalogs").jsonArray
        assertEquals(1, migratedCatalogs.size)
        assertEquals(2, migratedCatalogs.single().jsonObject.getValue("revision").jsonPrimitive.int)
    }

    @Test
    fun `legacy assistant search inherits unchanged model tools through the real key migration`() = runTest {
        val model = me.rerere.ai.provider.Model(tools = setOf(me.rerere.ai.provider.BuiltInTools.Search))
        val provider = me.rerere.ai.provider.ProviderSetting.Google(models = listOf(model))
        val assistantJson = """[{"id":"00000000-0000-0000-0000-000000000001","name":"Legacy","chatModelId":"${model.id}","enableWebSearch":true}]"""
        val old = mutablePreferencesOf(
            SettingsStore.ASSISTANTS to assistantJson,
            SettingsStore.PROVIDERS to JsonInstant.encodeToString(listOf<me.rerere.ai.provider.ProviderSetting>(provider)),
        )
        val migrated = UserSettingsMigration().migrate(old)
        val document = JsonInstant.decodeFromString<UserSettingsDocument>(migrated[SettingsStore.USER_SETTINGS]!!)
        val restored = document.personalSettings()
        assertEquals(null, restored.assistants.single().builtInSearch)
        assertTrue(restored.assistants.single().enableWebSearch)
        assertEquals(model, restored.providers.single().models.single())
        assertEquals(model.tools, restored.getChatModel(restored.assistants.single())!!.tools)
        assertEquals(assistantJson, old[SettingsStore.ASSISTANTS])
    }

    private fun golden(): Settings = javaClass.getResourceAsStream("settings-golden.json")!!
        .reader().use { JsonInstant.decodeFromString(it.readText()) }

    @Test
    fun `user directory rejects enterprise identities and nested bindings on write and decode`() {
        val reference = ConfigurationReference.Enterprise(EnterpriseAuthority("dep_example"), "mdl_example")
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
        val other = ConfigurationScope.Enterprise(EnterpriseAuthority("dep_other"), "usr_one")
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
        val authority = EnterpriseAuthority("dep_example")
        val enterprise = ScopedUserPreferences(
            ConfigurationScope.Enterprise(authority, "usr_one"),
            ResourceSelections(chatModelId = ConfigurationReference.Enterprise(authority, "mdl_example")),
            gateways = listOf(GatewayPreference(ConfigurationReference.Enterprise(authority, "gw_example"), false)),
        )
        val before = UserSettingsDocument.empty(preferences = UserPreferences(scopes = listOf(enterprise)))
        val after = before.withPersonalSettings(golden())
        assertEquals(enterprise, after.preferences.scopes.single { it.scope == enterprise.scope })
        assertEquals(2, after.preferences.scopes.size)
        assertEquals(golden().providers.map { it.id }, after.configuration.providers.map { it.id })
        val encoded = JsonInstant.encodeToString(after)
        assertEquals(encoded, JsonInstant.encodeToString(JsonInstant.decodeFromString<UserSettingsDocument>(encoded)))
        assertThrows(IllegalArgumentException::class.java) {
            after.copy(preferences = UserPreferences(scopes = listOf(
                enterprise.copy(scope = ConfigurationScope.Personal),
            )))
        }
    }

    @Test
    fun `gateway preferences require one matching enterprise principal and reject duplicate resource entries`() {
        val authority = EnterpriseAuthority("dep_example")
        val scope = ConfigurationScope.Enterprise(authority, "alice")
        val preference = GatewayPreference(ConfigurationReference.Enterprise(authority, "gw_example"), false)
        assertThrows(IllegalArgumentException::class.java) {
            ScopedUserPreferences(ConfigurationScope.Personal, gateways = listOf(preference))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScopedUserPreferences(scope.copy(authority = authority.copy(deploymentId = "dep_other")), gateways = listOf(preference))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScopedUserPreferences(scope, gateways = listOf(preference, preference))
        }
        val preferences = UserPreferences(scopes = listOf(
            ScopedUserPreferences(scope, gateways = listOf(preference)),
            ScopedUserPreferences(scope.copy(userId = "bob"), gateways = listOf(preference.copy(enabled = true))),
        ))
        assertFalse(preferences.gateway(scope, preference.gateway)!!.enabled)
        assertTrue(preferences.gateway(scope.copy(userId = "bob"), preference.gateway)!!.enabled)
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
