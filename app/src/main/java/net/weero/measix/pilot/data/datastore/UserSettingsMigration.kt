package net.weero.measix.pilot.data.datastore

import me.rerere.common.configuration.ConfigurationReference
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import net.weero.measix.pilot.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import net.weero.measix.pilot.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import net.weero.measix.pilot.data.ai.prompts.DEFAULT_TITLE_PROMPT
import net.weero.measix.pilot.data.sync.s3.S3Config
import net.weero.measix.pilot.ui.theme.PresetThemes
import net.weero.measix.pilot.utils.JsonInstant
import me.rerere.search.SearchCommonOptions
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.datastore.SettingsStore.Companion.S3_CONFIG
import net.weero.measix.pilot.data.datastore.SettingsStore.Companion.DYNAMIC_COLOR
import net.weero.measix.pilot.data.datastore.SettingsStore.Companion.THEME_ID
import net.weero.measix.pilot.data.datastore.SettingsStore.Companion.CUSTOM_THEMES
import net.weero.measix.pilot.data.datastore.SettingsStore.Companion.DISPLAY_SETTING
import net.weero.measix.pilot.data.datastore.SettingsStore.Companion.FAVORITE_MODELS
import net.weero.measix.pilot.data.datastore.SettingsStore.Companion.SELECT_MODEL
import net.weero.measix.pilot.data.datastore.SettingsStore.Companion.FAST_MODEL
import net.weero.measix.pilot.data.datastore.SettingsStore.Companion.TITLE_MODEL
import net.weero.measix.pilot.data.datastore.SettingsStore.Companion.ENABLE_SUGGESTION
import net.weero.measix.pilot.data.datastore.SettingsStore.Companion.SUGGESTION_MODEL
import net.weero.measix.pilot.data.datastore.SettingsStore.Companion.IMAGE_GENERATION_MODEL
import net.weero.measix.pilot.data.datastore.SettingsStore.Companion.TITLE_PROMPT
import net.weero.measix.pilot.data.datastore.SettingsStore.Companion.SUGGESTION_PROMPT
import net.weero.measix.pilot.data.datastore.SettingsStore.Companion.ATTACHMENT_INSPECTION_MODEL
import net.weero.measix.pilot.data.datastore.SettingsStore.Companion.COMPRESS_MODEL
import net.weero.measix.pilot.data.datastore.SettingsStore.Companion.COMPRESS_PROMPT
import net.weero.measix.pilot.data.datastore.SettingsStore.Companion.PROVIDERS
import net.weero.measix.pilot.data.datastore.SettingsStore.Companion.SELECT_ASSISTANT
import net.weero.measix.pilot.data.datastore.SettingsStore.Companion.ASSISTANTS
import net.weero.measix.pilot.data.datastore.SettingsStore.Companion.ASSISTANT_TAGS
import net.weero.measix.pilot.data.datastore.SettingsStore.Companion.SEARCH_SERVICES
import net.weero.measix.pilot.data.datastore.SettingsStore.Companion.SEARCH_COMMON
import net.weero.measix.pilot.data.datastore.SettingsStore.Companion.SELECTED_SEARCH_SERVICE_ID
import net.weero.measix.pilot.data.datastore.SettingsStore.Companion.LEGACY_SEARCH_SELECTED
import net.weero.measix.pilot.data.datastore.SettingsStore.Companion.MCP_SERVERS
import net.weero.measix.pilot.data.datastore.SettingsStore.Companion.WEBDAV_CONFIG
import net.weero.measix.pilot.data.datastore.SettingsStore.Companion.TTS_PROVIDERS
import net.weero.measix.pilot.data.datastore.SettingsStore.Companion.SELECTED_TTS_PROVIDER
import net.weero.measix.pilot.data.datastore.SettingsStore.Companion.DEFAULT_TTS_PLAYBACK_SPEED
import net.weero.measix.pilot.data.datastore.SettingsStore.Companion.ASR_PROVIDERS
import net.weero.measix.pilot.data.datastore.SettingsStore.Companion.SELECTED_ASR_PROVIDER
import net.weero.measix.pilot.data.datastore.SettingsStore.Companion.MODE_INJECTIONS
import net.weero.measix.pilot.data.datastore.SettingsStore.Companion.QUICK_MESSAGES
import net.weero.measix.pilot.data.datastore.SettingsStore.Companion.BACKUP_REMINDER_CONFIG
import net.weero.measix.pilot.data.datastore.SettingsStore.Companion.LAUNCH_COUNT
import net.weero.measix.pilot.data.datastore.SettingsStore.Companion.IGNORED_UPDATE_VERSION
import net.weero.measix.pilot.data.datastore.SettingsStore.Companion.PENDING_ASSISTANT_DELETIONS

internal val LEGACY_DEVELOPER_MODE = androidx.datastore.preferences.core.booleanPreferencesKey("developer_mode")

private inline fun <reified T> Preferences.json(key: Preferences.Key<String>, default: T): T =
    this[key]?.let(JsonInstant::decodeFromString) ?: default

private fun Preferences.uuid(key: Preferences.Key<String>): ConfigurationReference? =
    this[key]?.let(ConfigurationReference::parse)


/** Atomically adopts the old key set; no runtime reader falls back to it. */
internal class UserSettingsMigration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val encoded = currentData[SettingsStore.USER_SETTINGS] ?: return true
        JsonInstant.decodeFromString<UserSettingsDocument>(encoded)
        return false
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val old = readLegacySettings(currentData)
        val prepared = UserSettingsDocument.empty().withPersonalSettings(old)
        val personal = prepared.preferences.forScope(ConfigurationScope.Personal)
        val document = prepared.copy(
            preferences = prepared.preferences.copy(
                scopes = listOf(ScopedUserPreferences(
                    ConfigurationScope.Personal,
                    personal.copy(
                        chatModelId = personal.chatModelId.takeIf { currentData.contains(SELECT_MODEL) },
                        fastModelId = personal.fastModelId.takeIf { currentData.contains(FAST_MODEL) },
                        titleModelId = personal.titleModelId.takeIf { currentData.contains(TITLE_MODEL) },
                        imageGenerationModelId = personal.imageGenerationModelId.takeIf { currentData.contains(IMAGE_GENERATION_MODEL) },
                        suggestionModelId = personal.suggestionModelId.takeIf { currentData.contains(SUGGESTION_MODEL) },
                        attachmentInspectionModelId = personal.attachmentInspectionModelId.takeIf { currentData.contains(ATTACHMENT_INSPECTION_MODEL) },
                        compressModelId = personal.compressModelId.takeIf { currentData.contains(COMPRESS_MODEL) },
                        assistantId = personal.assistantId.takeIf { currentData.contains(SELECT_ASSISTANT) },
                        selectedSearchServiceId = personal.selectedSearchServiceId.takeIf { currentData.contains(SELECTED_SEARCH_SERVICE_ID) },
                        selectedTTSProviderId = personal.selectedTTSProviderId.takeIf { currentData.contains(SELECTED_TTS_PROVIDER) },
                        selectedASRProviderId = personal.selectedASRProviderId.takeIf { currentData.contains(SELECTED_ASR_PROVIDER) },
                    ),
                )),
            ),
        )
        return currentData.toMutablePreferences().apply {
            this[SettingsStore.USER_SETTINGS] = JsonInstant.encodeToString(document)
            remove(DYNAMIC_COLOR)
            remove(THEME_ID)
            remove(CUSTOM_THEMES)
            remove(DISPLAY_SETTING)
            remove(LEGACY_DEVELOPER_MODE)
            remove(FAVORITE_MODELS)
            remove(SELECT_MODEL)
            remove(FAST_MODEL)
            remove(TITLE_MODEL)
            remove(ENABLE_SUGGESTION)
            remove(SUGGESTION_MODEL)
            remove(IMAGE_GENERATION_MODEL)
            remove(TITLE_PROMPT)
            remove(SUGGESTION_PROMPT)
            remove(ATTACHMENT_INSPECTION_MODEL)
            remove(COMPRESS_MODEL)
            remove(COMPRESS_PROMPT)
            remove(PROVIDERS)
            remove(SELECT_ASSISTANT)
            remove(ASSISTANTS)
            remove(ASSISTANT_TAGS)
            remove(SEARCH_SERVICES)
            remove(SEARCH_COMMON)
            remove(SELECTED_SEARCH_SERVICE_ID)
            remove(LEGACY_SEARCH_SELECTED)
            remove(MCP_SERVERS)
            remove(WEBDAV_CONFIG)
            remove(S3_CONFIG)
            remove(TTS_PROVIDERS)
            remove(SELECTED_TTS_PROVIDER)
            remove(DEFAULT_TTS_PLAYBACK_SPEED)
            remove(ASR_PROVIDERS)
            remove(SELECTED_ASR_PROVIDER)
            remove(MODE_INJECTIONS)
            remove(QUICK_MESSAGES)
            remove(BACKUP_REMINDER_CONFIG)
            remove(LAUNCH_COUNT)
            remove(IGNORED_UPDATE_VERSION)
            remove(PENDING_ASSISTANT_DELETIONS)
        }.toPreferences()
    }

    override suspend fun cleanUp() = Unit
}

private fun readLegacySettings(preferences: Preferences): Settings = Settings(
    favoriteModels = preferences.json(FAVORITE_MODELS, emptyList()),
    chatModelId = preferences[SELECT_MODEL]?.let { ConfigurationReference.parse(it) }
        ?: DEFAULT_AUTO_MODEL_ID,
    fastModelId = preferences[FAST_MODEL]?.let { ConfigurationReference.parse(it) }
        ?: DEFAULT_AUTO_MODEL_ID,
    titleModelId = preferences[TITLE_MODEL]?.let { ConfigurationReference.parse(it) },
    enableSuggestion = preferences[ENABLE_SUGGESTION] != false,
    suggestionModelId = preferences[SUGGESTION_MODEL]?.let { ConfigurationReference.parse(it) },
    imageGenerationModelId = preferences[IMAGE_GENERATION_MODEL]?.let { ConfigurationReference.parse(it) }
        ?: DEFAULT_AUTO_MODEL_ID,
    titlePrompt = preferences[TITLE_PROMPT] ?: DEFAULT_TITLE_PROMPT,
    suggestionPrompt = preferences[SUGGESTION_PROMPT] ?: DEFAULT_SUGGESTION_PROMPT,
    attachmentInspectionModelId = preferences[ATTACHMENT_INSPECTION_MODEL]?.let { ConfigurationReference.parse(it) },
    compressModelId = preferences[COMPRESS_MODEL]?.let { ConfigurationReference.parse(it) } ?: DEFAULT_AUTO_MODEL_ID,
    compressPrompt = preferences[COMPRESS_PROMPT] ?: DEFAULT_COMPRESS_PROMPT,
    assistantId = preferences[SELECT_ASSISTANT]?.let { ConfigurationReference.parse(it) }
        ?: DEFAULT_ASSISTANT_ID,
    assistantTags = preferences.json(ASSISTANT_TAGS, emptyList()),
    providers = preferences.json(PROVIDERS, emptyList()),
    assistants = preferences.json(ASSISTANTS, emptyList()),
    dynamicColor = preferences[DYNAMIC_COLOR] != false,
    themeId = preferences[THEME_ID] ?: PresetThemes[0].id,
    customThemes = preferences.json(CUSTOM_THEMES, emptyList()),
    displaySetting = preferences.json(DISPLAY_SETTING, DisplaySetting()),
    searchServices = preferences.json(SEARCH_SERVICES, emptyList()),
    searchCommonOptions = preferences.json(SEARCH_COMMON, SearchCommonOptions()),
    selectedSearchServiceId = preferences.uuid(SELECTED_SEARCH_SERVICE_ID),
    mcpServers = preferences.json(MCP_SERVERS, emptyList()),
    webDavConfig = preferences.json(WEBDAV_CONFIG, WebDavConfig()),
    s3Config = preferences.json(S3_CONFIG, S3Config()),
    ttsProviders = preferences.json(TTS_PROVIDERS, emptyList()),
    selectedTTSProviderId = preferences[SELECTED_TTS_PROVIDER]?.let { ConfigurationReference.parse(it) }
        ?: DEFAULT_SYSTEM_TTS_ID,
    defaultTTSPlaybackSpeed = preferences[DEFAULT_TTS_PLAYBACK_SPEED]?.coerceIn(0.5f, 2.0f) ?: 1.0f,
    asrProviders = preferences.json(ASR_PROVIDERS, emptyList()),
    selectedASRProviderId = preferences[SELECTED_ASR_PROVIDER]?.let { ConfigurationReference.parse(it) },
    modeInjections = preferences.json(MODE_INJECTIONS, emptyList()),
    quickMessages = preferences.json(QUICK_MESSAGES, emptyList()),
    backupReminderConfig = preferences.json(BACKUP_REMINDER_CONFIG, BackupReminderConfig()),
    launchCount = preferences[LAUNCH_COUNT] ?: 0,
    ignoredUpdateVersion = preferences[IGNORED_UPDATE_VERSION] ?: "",
    pendingAssistantDeletions = preferences.json(PENDING_ASSISTANT_DELETIONS, emptyList()),
)
