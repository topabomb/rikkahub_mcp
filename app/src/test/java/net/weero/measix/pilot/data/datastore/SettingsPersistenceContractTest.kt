package net.weero.measix.pilot.data.datastore

import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.json.jsonObject
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.uuid.Uuid

class SettingsPersistenceContractTest {
    @Test
    fun `settings json top-level shape remains compatible and excludes disclosure state`() {
        val settings = Settings(
            chatModelId = Uuid.parse("00000000-0000-0000-0000-000000000101"),
            fastModelId = Uuid.parse("00000000-0000-0000-0000-000000000102"),
            imageGenerationModelId = Uuid.parse("00000000-0000-0000-0000-000000000103"),
            compressModelId = Uuid.parse("00000000-0000-0000-0000-000000000104"),
            assistantId = Uuid.parse("00000000-0000-0000-0000-000000000105"),
        )
        val encoded = JsonInstant.encodeToString(settings)
        val keys = JsonInstant.parseToJsonElement(encoded).jsonObject.keys.toList()

        assertEquals(
            listOf(
                "dynamicColor", "themeId", "customThemes", "developerMode", "displaySetting",
                "favoriteModels", "chatModelId", "fastModelId", "titleModelId",
                "imageGenerationModelId", "titlePrompt", "enableSuggestion", "suggestionModelId",
                "suggestionPrompt", "attachmentInspectionModelId", "compressModelId", "compressPrompt",
                "assistantId", "providers", "assistants", "assistantTags", "searchServices",
                "searchCommonOptions", "selectedSearchServiceId", "mcpServers", "webDavConfig",
                "s3Config", "ttsProviders", "selectedTTSProviderId", "defaultTTSPlaybackSpeed",
                "asrProviders", "selectedASRProviderId", "modeInjections", "quickMessages",
                "backupReminderConfig", "launchCount", "ignoredUpdateVersion",
            ),
            keys,
        )
        assertEquals(encoded, JsonInstant.encodeToString(JsonInstant.decodeFromString<Settings>(encoded)))
        assertFalse(encoded.contains("modelContext", ignoreCase = true))
        assertFalse(encoded.contains("disclosure", ignoreCase = true))
    }

    /**
     * 逐字 golden：`settings.json` 是备份与旧版本兼容的唯一载荷，任何默认值、嵌套结构或枚举
     * 表示变化都必须显式更新 `settings-golden.json`。fixture 覆盖 Provider/Model 覆盖、
     * Assistant 枚举、显示设置、S3/WebDAV、TTS/ASR、模式注入、Quick Message 与备份配置，
     * 且不含任何随机默认值。
     */
    @Test
    fun `settings json golden preserves nested values defaults and enum representation`() {
        val golden = javaClass.getResourceAsStream("settings-golden.json")!!.reader().use { it.readText() }
            .trim()

        val encoded = JsonInstant.encodeToString(goldenFixture())
        assertEquals(golden, encoded)
        // 解码后再编码必须逐字相同；不用对象 equals，因为 search SDK 的 BingLocalOptions 非 data class。
        assertEquals(encoded, JsonInstant.encodeToString(JsonInstant.decodeFromString<Settings>(encoded)))
    }

    private fun goldenFixture(): Settings = Settings(
        dynamicColor = true,
        themeId = "theme-golden",
        developerMode = true,
        chatModelId = Uuid.parse("00000000-0000-0000-0000-000000000501"),
        fastModelId = Uuid.parse("00000000-0000-0000-0000-000000000502"),
        titleModelId = Uuid.parse("00000000-0000-0000-0000-000000000503"),
        imageGenerationModelId = Uuid.parse("00000000-0000-0000-0000-000000000504"),
        suggestionModelId = Uuid.parse("00000000-0000-0000-0000-000000000505"),
        attachmentInspectionModelId = Uuid.parse("00000000-0000-0000-0000-000000000506"),
        compressModelId = Uuid.parse("00000000-0000-0000-0000-000000000507"),
        assistantId = Uuid.parse("00000000-0000-0000-0000-000000000508"),
        titlePrompt = "title prompt",
        suggestionPrompt = "suggestion prompt",
        compressPrompt = "compress prompt",
        enableSuggestion = true,
        providers = listOf(
            me.rerere.ai.provider.ProviderSetting.OpenAI(
                id = Uuid.parse("00000000-0000-0000-0000-000000000511"),
                name = "Golden Provider",
                baseUrl = "https://golden.example/v1",
                apiKey = "golden-key",
                models = listOf(
                    me.rerere.ai.provider.Model(
                        id = Uuid.parse("00000000-0000-0000-0000-000000000512"),
                        modelId = "golden-model",
                        displayName = "Golden Model",
                        abilities = listOf(
                            me.rerere.ai.provider.ModelAbility.TOOL,
                            me.rerere.ai.provider.ModelAbility.REASONING,
                        ),
                        inputModalities = listOf(
                            me.rerere.ai.provider.Modality.TEXT,
                            me.rerere.ai.provider.Modality.IMAGE,
                        ),
                        customHeaders = listOf(
                            me.rerere.ai.provider.CustomHeader("X-Golden", "header-value"),
                        ),
                        customBodies = listOf(
                            me.rerere.ai.provider.CustomBody(
                                "golden_mode",
                                kotlinx.serialization.json.JsonPrimitive(true),
                            ),
                        ),
                    ),
                ),
            ),
        ),
        assistants = listOf(
            net.weero.measix.pilot.data.model.Assistant(
                id = Uuid.parse("00000000-0000-0000-0000-000000000521"),
                name = "Golden Assistant",
                description = "golden description",
                systemPrompt = "golden system prompt",
                messageTemplate = "{{ message }}",
                temperature = 0.7f,
                topP = 0.9f,
                maxTokens = 4096,
                reasoningLevel = me.rerere.ai.core.ReasoningLevel.HIGH,
                enableMemory = true,
                useGlobalMemory = true,
                allowConversationSystemPrompt = true,
                useAssistantAvatar = true,
                tags = listOf(Uuid.parse("00000000-0000-0000-0000-000000000522")),
                modeInjectionIds = setOf(Uuid.parse("00000000-0000-0000-0000-000000000523")),
                quickMessageIds = setOf(Uuid.parse("00000000-0000-0000-0000-000000000524")),
                localTools = listOf(
                    net.weero.measix.pilot.data.ai.tools.local.LocalToolOption.AssistantDelegation,
                ),
            ),
        ),
        // 默认搜索服务带随机 id，golden 必须钉死才能逐字比较。
        searchServices = listOf(
            me.rerere.search.SearchServiceOptions.BingLocalOptions(
                id = Uuid.parse("00000000-0000-0000-0000-000000000541"),
            ),
        ),
        assistantTags = listOf(
            net.weero.measix.pilot.data.model.Tag(
                id = Uuid.parse("00000000-0000-0000-0000-000000000522"),
                name = "golden-tag",
            ),
        ),
        modeInjections = listOf(
            net.weero.measix.pilot.data.model.PromptInjection.ModeInjection(
                id = Uuid.parse("00000000-0000-0000-0000-000000000523"),
                name = "golden injection",
                content = "injected content",
                position = net.weero.measix.pilot.data.model.InjectionPosition.AT_DEPTH,
                role = me.rerere.ai.core.MessageRole.SYSTEM,
                injectDepth = 2,
                priority = 5,
                enabled = true,
            ),
        ),
        quickMessages = listOf(
            net.weero.measix.pilot.data.model.QuickMessage(
                id = Uuid.parse("00000000-0000-0000-0000-000000000524"),
                title = "golden quick",
                content = "quick content",
            ),
        ),
        webDavConfig = WebDavConfig(url = "https://dav.example", username = "u", password = "p"),
        s3Config = net.weero.measix.pilot.data.sync.s3.S3Config(
            bucket = "golden-bucket",
            region = "us-east-1",
        ),
        selectedTTSProviderId = Uuid.parse("00000000-0000-0000-0000-000000000531"),
        defaultTTSPlaybackSpeed = 1.25f,
        selectedASRProviderId = Uuid.parse("00000000-0000-0000-0000-000000000532"),
        selectedSearchServiceId = Uuid.parse("00000000-0000-0000-0000-000000000541"),
        backupReminderConfig = BackupReminderConfig(
            enabled = true,
            intervalDays = 3,
            lastBackupTime = 1_700_000_000_000L,
        ),
        launchCount = 7,
        ignoredUpdateVersion = "9.9.9",
    )

    @Test
    fun `Preferences key names and value types remain compatible`() {
        fun names(keys: List<Preferences.Key<*>>) = keys.map { it.name }
        val booleanKeys: List<Preferences.Key<Boolean>> = listOf(
            SettingsStore.DYNAMIC_COLOR,
            SettingsStore.DEVELOPER_MODE,
            SettingsStore.ENABLE_SUGGESTION,
        )
        val intKeys: List<Preferences.Key<Int>> = listOf(
            SettingsStore.LEGACY_SEARCH_SELECTED,
            SettingsStore.LAUNCH_COUNT,
        )
        val floatKeys: List<Preferences.Key<Float>> = listOf(SettingsStore.DEFAULT_TTS_PLAYBACK_SPEED)
        val stringKeys: List<Preferences.Key<String>> = listOf(
            SettingsStore.THEME_ID, SettingsStore.CUSTOM_THEMES, SettingsStore.DISPLAY_SETTING,
            SettingsStore.FAVORITE_MODELS, SettingsStore.SELECT_MODEL, SettingsStore.FAST_MODEL,
            SettingsStore.TITLE_MODEL, SettingsStore.SUGGESTION_MODEL, SettingsStore.IMAGE_GENERATION_MODEL,
            SettingsStore.TITLE_PROMPT, SettingsStore.SUGGESTION_PROMPT,
            SettingsStore.ATTACHMENT_INSPECTION_MODEL, SettingsStore.COMPRESS_MODEL,
            SettingsStore.COMPRESS_PROMPT, SettingsStore.PROVIDERS, SettingsStore.SELECT_ASSISTANT,
            SettingsStore.ASSISTANTS, SettingsStore.ASSISTANT_TAGS, SettingsStore.SEARCH_SERVICES,
            SettingsStore.SEARCH_COMMON, SettingsStore.SELECTED_SEARCH_SERVICE_ID, SettingsStore.MCP_SERVERS,
            SettingsStore.PENDING_MCP_CATALOG_MIGRATION, SettingsStore.WEBDAV_CONFIG, SettingsStore.S3_CONFIG,
            SettingsStore.TTS_PROVIDERS, SettingsStore.SELECTED_TTS_PROVIDER, SettingsStore.ASR_PROVIDERS,
            SettingsStore.SELECTED_ASR_PROVIDER, SettingsStore.MODE_INJECTIONS, SettingsStore.QUICK_MESSAGES,
            SettingsStore.BACKUP_REMINDER_CONFIG, SettingsStore.IGNORED_UPDATE_VERSION,
            SettingsStore.PENDING_ASSISTANT_DELETIONS,
        )
        val actual = names(booleanKeys + intKeys + floatKeys + stringKeys).sorted()
        val expected = listOf(
            "asr_providers", "assistant_tags", "assistants", "attachment_inspection_model",
            "backup_reminder_config", "chat_model", "compress_model", "compress_prompt", "custom_themes",
            "default_tts_playback_speed", "developer_mode", "display_setting", "dynamic_color",
            "enable_suggestion", "fast_model", "favorite_models", "ignored_update_version",
            "image_generation_model", "launch_count", "mcp_servers", "mode_injections",
            "pending_assistant_deletions", "pending_mcp_catalog_migration", "providers", "quick_messages",
            "s3_config", "search_common", "search_selected", "search_services", "select_assistant",
            "selected_asr_provider", "selected_search_service_id", "selected_tts_provider", "suggestion_model",
            "suggestion_prompt", "theme_id", "title_model", "title_prompt", "tts_providers", "webdav_config",
        ).sorted()
        assertEquals(expected, actual)
        assertFalse(actual.any { "context" in it || "disclosure" in it })
    }
}
