package net.weero.measix.pilot.data.datastore

import net.weero.measix.pilot.data.model.withAssistantSearch

import me.rerere.common.configuration.ConfigurationReference
import android.content.Context
import android.util.Log
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.ai.mcp.McpServerConfig
import net.weero.measix.pilot.data.ai.mcp.McpLegacyCatalogMigrationPayload
import net.weero.measix.pilot.data.ai.mcp.normalizeMcpDefinitions
import net.weero.measix.pilot.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import net.weero.measix.pilot.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import net.weero.measix.pilot.data.ai.prompts.DEFAULT_TITLE_PROMPT
import net.weero.measix.pilot.data.ai.prompts.LEARNING_MODE_PROMPT
import me.rerere.asr.ASRProviderSetting
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.Avatar
import net.weero.measix.pilot.data.model.DEFAULT_SYSTEM_PROMPT
import net.weero.measix.pilot.data.model.InjectionPosition
import net.weero.measix.pilot.data.model.PromptInjection
import net.weero.measix.pilot.data.model.QuickMessage
import net.weero.measix.pilot.data.model.Tag
import net.weero.measix.pilot.data.model.normalizeDescription
import net.weero.measix.pilot.data.sync.s3.S3Config
import net.weero.measix.pilot.ui.theme.CustomTheme
import net.weero.measix.pilot.ui.theme.PresetThemes
import net.weero.measix.pilot.utils.JsonInstant
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting
import net.weero.measix.pilot.data.configuration.AssistantUsagePreferences
import net.weero.measix.pilot.data.configuration.ConfigurationResolver
import net.weero.measix.pilot.data.configuration.ConfigurationCategory
import net.weero.measix.pilot.data.configuration.ConfigurationKey
import net.weero.measix.pilot.data.configuration.GatewayPreference
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.configuration.ResolvedConfiguration
import net.weero.measix.pilot.data.enterprise.EnterpriseState
import net.weero.measix.pilot.data.files.ArtifactReferencePolicy

private const val TAG = "SettingsStore"

/** One user document and its realm projection captured under the configuration writer lock. */
internal class ExecutionConfigurationSnapshot(
    val userSettings: Settings,
    val configuration: ResolvedConfiguration,
    val userRevision: String,
)

private val Context.settingsStore by preferencesDataStore(
    name = "settings",
    produceMigrations = { context ->
        listOf(
            OcrSettingsMigration(context),
            SearchSelectionMigration(),
            McpLegacyCatalogSettingsMigration(),
            UserSettingsMigration(),
            ConversationHistoryPreferenceMigration(context),
        )
    },
)


/** Cold restore reads the same DataStore delegate before the application graph exists. */
internal suspend fun Context.readUserSettingsForBackupRestore(): UserSettingsDocument =
    JsonInstant.decodeFromString(requireNotNull(applicationContext.settingsStore.data.first()[SettingsStore.USER_SETTINGS]) {
        "user_settings_migration_incomplete"
    })

/**
 * `search_selected` was a UI list index. Persisting the selected service identity makes a
 * reorder or deletion unable to select a different search backend.
 */
internal class SearchSelectionMigration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        currentData.contains(SettingsStore.LEGACY_SEARCH_SELECTED)

    override suspend fun migrate(currentData: Preferences): Preferences {
        val services = currentData[SettingsStore.SEARCH_SERVICES]
            ?.let { raw -> runCatching { JsonInstant.decodeFromString<List<SearchServiceOptions>>(raw) }.getOrNull() }
            .orEmpty()
            .ifEmpty { listOf(SearchServiceOptions.DEFAULT) }
        val mutable = currentData.toMutablePreferences()
        if (!mutable.contains(SettingsStore.SELECTED_SEARCH_SERVICE_ID)) {
            val index = mutable[SettingsStore.LEGACY_SEARCH_SELECTED] ?: 0
            mutable[SettingsStore.SELECTED_SEARCH_SERVICE_ID] =
                services.getOrElse(index) { services.first() }.id.toString()
        }
        mutable.remove(SettingsStore.LEGACY_SEARCH_SELECTED)
        return mutable.toPreferences()
    }

    override suspend fun cleanUp() = Unit
}

class SettingsStore internal constructor(
    private val appContext: Context,
    private val scope: AppScope,
    private val dataStore: DataStore<Preferences> = appContext.settingsStore,
) {
    companion object {
        internal val USER_SETTINGS = stringPreferencesKey("user_settings")

        // UI设置
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val THEME_ID = stringPreferencesKey("theme_id")
        val CUSTOM_THEMES = stringPreferencesKey("custom_themes")
        val DISPLAY_SETTING = stringPreferencesKey("display_setting")

        // 模型选择
        val FAVORITE_MODELS = stringPreferencesKey("favorite_models")
        val SELECT_MODEL = stringPreferencesKey("chat_model")
        val FAST_MODEL = stringPreferencesKey("fast_model")
        val TITLE_MODEL = stringPreferencesKey("title_model")
        val ENABLE_SUGGESTION = booleanPreferencesKey("enable_suggestion")
        val SUGGESTION_MODEL = stringPreferencesKey("suggestion_model")
        val IMAGE_GENERATION_MODEL = stringPreferencesKey("image_generation_model")
        val TITLE_PROMPT = stringPreferencesKey("title_prompt")
        val SUGGESTION_PROMPT = stringPreferencesKey("suggestion_prompt")
        val ATTACHMENT_INSPECTION_MODEL = stringPreferencesKey("attachment_inspection_model")
        val COMPRESS_MODEL = stringPreferencesKey("compress_model")
        val COMPRESS_PROMPT = stringPreferencesKey("compress_prompt")

        // 提供商
        val PROVIDERS = stringPreferencesKey("providers")

        // 助手
        val SELECT_ASSISTANT = stringPreferencesKey("select_assistant")
        val ASSISTANTS = stringPreferencesKey("assistants")
        val ASSISTANT_TAGS = stringPreferencesKey("assistant_tags")

        // 搜索
        val SEARCH_SERVICES = stringPreferencesKey("search_services")
        val SEARCH_COMMON = stringPreferencesKey("search_common")
        val SELECTED_SEARCH_SERVICE_ID = stringPreferencesKey("selected_search_service_id")
        internal val LEGACY_SEARCH_SELECTED = intPreferencesKey("search_selected")

        // MCP
        val MCP_SERVERS = stringPreferencesKey("mcp_servers")
        internal val PENDING_MCP_CATALOG_MIGRATION = stringPreferencesKey("pending_mcp_catalog_migration")

        // WebDAV
        val WEBDAV_CONFIG = stringPreferencesKey("webdav_config")

        // S3
        val S3_CONFIG = stringPreferencesKey("s3_config")

        // TTS
        val TTS_PROVIDERS = stringPreferencesKey("tts_providers")
        val SELECTED_TTS_PROVIDER = stringPreferencesKey("selected_tts_provider")
        val DEFAULT_TTS_PLAYBACK_SPEED = floatPreferencesKey("default_tts_playback_speed")

        // ASR
        val ASR_PROVIDERS = stringPreferencesKey("asr_providers")
        val SELECTED_ASR_PROVIDER = stringPreferencesKey("selected_asr_provider")

        // 提示词注入
        val MODE_INJECTIONS = stringPreferencesKey("mode_injections")
        val QUICK_MESSAGES = stringPreferencesKey("quick_messages")

        // 备份提醒
        val BACKUP_REMINDER_CONFIG = stringPreferencesKey("backup_reminder_config")

        // 统计
        val LAUNCH_COUNT = intPreferencesKey("launch_count")

        // 更新检查
        val IGNORED_UPDATE_VERSION = stringPreferencesKey("ignored_update_version")

        // 子助手删除清理 tombstone（内部，不进入 UI）
        val PENDING_ASSISTANT_DELETIONS = stringPreferencesKey("pending_assistant_deletions")
    }

    /**
     * 串行化“读取最新值 → 修改 → DataStore 提交”，避免工具操作与用户设置并发时
     * 由整份旧 Settings 覆盖新值。
     */
    private val updateMutex = Mutex()

    private val userDocuments = dataStore.data
        .map { preferences ->
            val encoded = requireNotNull(preferences[USER_SETTINGS]) { "user_settings_migration_incomplete" }
            JsonInstant.decodeFromString<UserSettingsDocument>(encoded)
        }
        .distinctUntilChanged()

    internal suspend fun lastConversation(scope: ConfigurationScope): kotlin.uuid.Uuid? =
        userDocuments.first().preferences.lastConversation(scope)

    internal suspend fun rememberConversation(scope: ConfigurationScope, id: kotlin.uuid.Uuid) = updateMutex.withLock {
        commitUserDocument { document ->
            document.copy(preferences = document.preferences.withLastConversation(scope, id))
        }
    }

    internal val userMcpDefinitions = userDocuments
        .map { it.configuration.mcpServers.normalizeMcpDefinitions() }
        .distinctUntilChanged()

    /** Definition reads and connection commitment share the existing user-settings writer boundary. */
    internal suspend fun <T> withUserMcpDefinitions(
        operation: suspend (List<McpServerConfig>) -> T,
    ): T = updateMutex.withLock { operation(userDocuments.first().configuration.mcpServers.normalizeMcpDefinitions()) }

    private val userSettingsRaw = userDocuments.map { it.personalSettings() }
    private val _userSettings = MutableStateFlow(Settings.dummy())

    /** Shared user definitions and personal selections. Realm execution uses observeConfiguration instead. */
    internal val userSettings: StateFlow<Settings> = _userSettings.asStateFlow()

    internal fun observeConfiguration(
        enterpriseState: StateFlow<EnterpriseState>,
        requestedScope: ConfigurationScope? = null,
    ): Flow<ResolvedConfiguration> = combine(userDocuments, enterpriseState) { document, enterprise ->
        val selectedScope = (enterprise as? EnterpriseState.Available)?.manifest?.selectedScope ?: ConfigurationScope.Personal
        ConfigurationResolver.resolve(document, requestedScope ?: selectedScope, enterprise)
    }.distinctUntilChanged()

    /** Lock order is session, user configuration, then the caller's durable data transaction. */
    internal suspend fun <T> withResolvedConfiguration(
        scope: ConfigurationScope,
        enterpriseState: EnterpriseState,
        operation: suspend (ResolvedConfiguration) -> T,
    ): T = updateMutex.withLock {
        operation(ConfigurationResolver.resolve(userDocuments.first(), scope, enterpriseState))
    }

    internal suspend fun <T> withExecutionConfiguration(
        scope: ConfigurationScope,
        enterpriseState: EnterpriseState,
        operation: suspend (ExecutionConfigurationSnapshot) -> T,
    ): T = updateMutex.withLock {
        val encoded = requireNotNull(dataStore.data.first()[USER_SETTINGS]) { "user_settings_migration_incomplete" }
        val document = JsonInstant.decodeFromString<UserSettingsDocument>(encoded)
        val revision = java.security.MessageDigest.getInstance("SHA-256")
            .digest(encoded.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        operation(ExecutionConfigurationSnapshot(
            userSettings = document.personalSettings().withBuiltInDefinitions(),
            configuration = ConfigurationResolver.resolve(document, scope, enterpriseState),
            userRevision = revision,
        ))
    }

    /** Called while the enterprise session owner holds its authorization boundary. */
    internal suspend fun changeAssistantPreference(
        scope: ConfigurationScope,
        enterpriseState: EnterpriseState,
        assistantId: ConfigurationReference,
        change: net.weero.measix.pilot.data.configuration.AssistantPreferenceChange,
        requireOwner: () -> Unit,
        withArtifactCommit: (suspend (UserSettingsDocument, UserSettingsDocument, suspend () -> Unit) -> Unit)? = null,
        withCommit: suspend (suspend () -> Unit) -> Unit,
    ) = updateMutex.withLock {
        requireOwner()
        val before = userDocuments.first()
        val after = before.changeAssistantPreference(scope, enterpriseState, assistantId, change)
        withCommit {
            suspend fun commit() = commitUserDocument(requireOwner, artifactRootsOwned = withArtifactCommit != null) { after }
            if (withArtifactCommit == null) commit() else withArtifactCommit(before, after, ::commit)
        }
    }

    /** The session owner serializes authorization changes through the entire preference commit. */
    internal suspend fun updateResourceSelections(
        scope: ConfigurationScope,
        enterpriseState: EnterpriseState,
        requireOwner: () -> Unit = {},
        withCommit: suspend (suspend () -> Unit) -> Unit = { it() },
        transform: (ResourceSelections) -> ResourceSelections,
    ) = updateMutex.withLock {
        if (scope is ConfigurationScope.Enterprise) {
            require((enterpriseState as? EnterpriseState.Available)?.manifest?.session?.identity?.scope == scope) {
                "resource_selection_principal_mismatch"
            }
        }
        withCommit { commitUserDocument(requireOwner) { document ->
            val before = document.preferences.forScope(scope)
            val proposed = transform(before)
            val updated = document.copy(preferences = document.preferences.withSelections(scope, proposed))
            requireResourceSelectionsWriteAllowed(before, proposed, ConfigurationResolver.resolve(updated, scope, enterpriseState))
            updated
        } }
    }

    /** Gateway definition and effective usage remain separate; the resolver owns the enablement decision. */
    internal suspend fun updateGatewayPreference(
        scope: ConfigurationScope.Enterprise,
        enterpriseState: EnterpriseState.Available,
        gateway: ConfigurationReference.Enterprise,
        enabled: Boolean,
    ) = updateMutex.withLock {
        require(enterpriseState.manifest.session?.identity?.scope == scope) { "gateway_preference_principal_mismatch" }
        commitUserDocument { document ->
            val resolved = ConfigurationResolver.resolve(document, scope, enterpriseState)
            val item = resolved.catalog[ConfigurationKey(ConfigurationCategory.GATEWAY, gateway)]
            if (item?.gatewayEnablement?.canChange != true) {
                throw SettingsLockedException("gateways/${gateway.id}",
                    item?.access?.unavailableReason?.name ?: "gateway_enablement_not_controllable")
            }
            document.copy(preferences = document.preferences.withGateway(scope, GatewayPreference(gateway, enabled)))
        }
    }

    /** Both authorization locks remain owned until DataStore's independent writer acknowledges completion. */
    private suspend fun commitUserDocument(
        requireOwner: () -> Unit = {},
        artifactRootsOwned: Boolean = false,
        transform: (UserSettingsDocument) -> UserSettingsDocument,
    ) {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        withContext(NonCancellable) {
            dataStore.edit { preferences ->
                caller.ensureActive()
                requireOwner()
                val document = JsonInstant.decodeFromString<UserSettingsDocument>(
                    requireNotNull(preferences[USER_SETTINGS]) { "user_settings_migration_incomplete" },
                )
                val updated = transform(document)
                check(artifactRootsOwned || ArtifactReferencePolicy.scopedRoots(updated).all { (scope, roots) ->
                    (roots - ArtifactReferencePolicy.scopedRoots(document)[scope].orEmpty()).isEmpty()
                }) {
                    "artifact_root_write_requires_lifecycle_owner"
                }
                val encoded = JsonInstant.encodeToString(updated)
                // Before handing the value to the writer, cancellation can still abandon this mutation.
                caller.ensureActive()
                requireOwner()
                preferences[USER_SETTINGS] = encoded
            }
            publishUserSettings()
        }
        caller.ensureActive()
    }

    init {
        scope.launch {
            userDocuments.collect {
                // Read again under the writer so a delayed observer cannot regress a committed projection.
                updateMutex.withLock { publishUserSettings() }
            }
        }
    }

    private suspend fun publishUserSettings() {
        _userSettings.value = userSettingsRaw.first().materializeForRead()
    }

    /** Personal restore preserves domain preferences and pending deletion receipts in the same document. */
    internal suspend fun restoreLocal(
        settings: Settings,
        withArtifactRestore: suspend (Settings, suspend (Settings) -> Settings) -> Settings,
    ): Settings =
        updateMutex.withLock {
            val current = userSettingsRaw.first()
            withArtifactRestore(settings.withInternalStateFrom(current)) { prepared ->
                // The restore owner holds Artifact and has prepared the recoverable configuration roots.
                val restored = updateInternal(
                    current = current,
                    proposed = prepared,
                    withArtifactCommit = { _, _, commit -> commit() },
                )
                dataStore.edit { preferences ->
                    preferences.remove(PENDING_MCP_CATALOG_MIGRATION)
                }
                restored
            }
        }

    suspend fun updateLocal(transform: (Settings) -> Settings): Settings =
        updateLocalWithArtifactCommit(null, transform)

    internal suspend fun updateLocalWithArtifactCommit(
        withArtifactCommit: (suspend (UserSettingsDocument, UserSettingsDocument, suspend () -> Unit) -> Unit)?,
        transform: (Settings) -> Settings,
    ): Settings = updateMutex.withLock {
        val local = userSettingsRaw.first()
        val proposed = transform(local.materializeForRead())
        updateInternal(
            current = local,
            proposed = proposed,
            withArtifactCommit = withArtifactCommit,
        )
    }

    /** Artifact readers hold their lifecycle lock and read committed state without acquiring this writer. */
    internal suspend fun snapshotUserDocument(): UserSettingsDocument = userDocuments.first()

    /** The callback acquires Artifact only after this writer; detach cannot add or resurrect roots. */
    internal suspend fun <T> withArtifactRootDetach(
        operation: suspend (detach: suspend (Set<String>) -> Boolean) -> T,
    ): T = updateMutex.withLock {
        operation { tokens ->
            commitUserDocument { ArtifactReferencePolicy.detach(it, tokens) }
            ArtifactReferencePolicy.roots(userDocuments.first()).none(tokens::contains)
        }
    }

    /** Backup exports shared user definitions and Personal selections, never enterprise configuration. */
    internal suspend fun snapshotLocal(): Settings = userSettingsRaw.first().materializeForRead()

    internal suspend fun pendingMcpCatalogMigration(): PendingMcpCatalogMigration? =
        dataStore.data.first()[PENDING_MCP_CATALOG_MIGRATION]?.let { encoded ->
            PendingMcpCatalogMigration(
                encoded = encoded,
                payload = JsonInstant.decodeFromString(encoded),
            )
        }

    internal suspend fun completeMcpCatalogMigration(expectedEncoded: String) {
        dataStore.edit { preferences ->
            if (preferences[PENDING_MCP_CATALOG_MIGRATION] == expectedEncoded) {
                preferences.remove(PENDING_MCP_CATALOG_MIGRATION)
            }
        }
    }

    private suspend fun updateInternal(
        current: Settings,
        proposed: Settings,
        withArtifactCommit: (suspend (UserSettingsDocument, UserSettingsDocument, suspend () -> Unit) -> Unit)? = null,
    ): Settings {
        if (proposed.init) {
            Log.w(TAG, "Cannot update dummy settings")
            return current
        }
        val prepared = proposed.normalizeForPersistence().canonicalizeForDataStore()
        if (withArtifactCommit == null) {
            commitUserDocument { it.withPersonalSettings(prepared) }
        } else {
            val before = userDocuments.first()
            val after = before.withPersonalSettings(prepared)
            withArtifactCommit(before, after) {
                commitUserDocument(artifactRootsOwned = true) { it.withPersonalSettings(prepared) }
            }
        }
        return prepared.materializeForRead()
    }

}

internal data class PendingMcpCatalogMigration(
    val encoded: String,
    val payload: McpLegacyCatalogMigrationPayload,
)

/**
 * 跨进程重试的删除清理 tombstone。
 * 仅保存清理所需的 Assistant ID 与资源 URI 快照，不保存完整 Assistant 或 prompt。
 * Settings 提交后由 AssistantManagementService 消费，完成后原子移除。
 */
@Serializable
data class PendingAssistantDeletion(
    val assistantId: ConfigurationReference,
    val avatarUri: String? = null,
    val backgroundUri: String? = null,
)

@Serializable
data class Settings(
    @Transient
    val init: Boolean = false,
    val dynamicColor: Boolean = true,
    val themeId: String = PresetThemes[0].id,
    val customThemes: List<CustomTheme> = emptyList(),
    val displaySetting: DisplaySetting = DisplaySetting(),
    val favoriteModels: List<ConfigurationReference> = emptyList(),
    val chatModelId: ConfigurationReference = ConfigurationReference.random(),
    val fastModelId: ConfigurationReference = ConfigurationReference.random(),
    val titleModelId: ConfigurationReference? = null,
    val imageGenerationModelId: ConfigurationReference = ConfigurationReference.random(),
    val titlePrompt: String = DEFAULT_TITLE_PROMPT,
    val enableSuggestion: Boolean = true,
    val suggestionModelId: ConfigurationReference? = null,
    val suggestionPrompt: String = DEFAULT_SUGGESTION_PROMPT,
    val attachmentInspectionModelId: ConfigurationReference? = null,
    val compressModelId: ConfigurationReference = ConfigurationReference.random(),
    val compressPrompt: String = DEFAULT_COMPRESS_PROMPT,
    val assistantId: ConfigurationReference = DEFAULT_ASSISTANT_ID,
    val providers: List<ProviderSetting> = DEFAULT_PROVIDERS,
    val assistants: List<Assistant> = DEFAULT_ASSISTANTS,
    val assistantTags: List<Tag> = emptyList(),
    val searchServices: List<SearchServiceOptions> = listOf(SearchServiceOptions.DEFAULT),
    val searchCommonOptions: SearchCommonOptions = SearchCommonOptions(),
    val selectedSearchServiceId: ConfigurationReference? = null,
    val mcpServers: List<McpServerConfig> = emptyList(),
    val webDavConfig: WebDavConfig = WebDavConfig(),
    val s3Config: S3Config = S3Config(),
    val ttsProviders: List<TTSProviderSetting> = DEFAULT_TTS_PROVIDERS,
    val selectedTTSProviderId: ConfigurationReference = DEFAULT_SYSTEM_TTS_ID,
    val defaultTTSPlaybackSpeed: Float = 1.0f,
    val asrProviders: List<ASRProviderSetting> = emptyList(),
    val selectedASRProviderId: ConfigurationReference? = null,
    val modeInjections: List<PromptInjection.ModeInjection> = DEFAULT_MODE_INJECTIONS,
    val quickMessages: List<QuickMessage> = emptyList(),
    val backupReminderConfig: BackupReminderConfig = BackupReminderConfig(),
    val launchCount: Int = 0,
    val ignoredUpdateVersion: String = "",
    @Transient
    val pendingAssistantDeletions: List<PendingAssistantDeletion> = emptyList(),
) {
    companion object {
        // 构造一个用于初始化的settings, 但它不能用于保存，防止使用初始值存储
        fun dummy() = Settings(init = true)
    }
}

/**
 * 所有 Settings 整体写入共用的纯规范化逻辑，便于用 JVM 测试覆盖真实持久化语义。
 */
internal fun Settings.normalizeForPersistence(): Settings = copy(
    mcpServers = mcpServers.normalizeMcpDefinitions(),
    assistants = assistants.map { assistant ->
        assistant.copy(
            description = normalizeDescription(assistant.description),
            isSubAssistantGloballyVisible = assistant.allowAsSubAssistant &&
                assistant.isSubAssistantGloballyVisible,
        )
    },
    pendingAssistantDeletions = pendingAssistantDeletions.distinctBy { it.assistantId },
)

internal fun Settings.withInternalStateFrom(current: Settings): Settings = copy(
    pendingAssistantDeletions = current.pendingAssistantDeletions,
)

@Serializable
enum class ChatFontFamily {
    @SerialName("default")
    DEFAULT,
    @SerialName("serif")
    SERIF,
    @SerialName("monospace")
    MONOSPACE,

    @SerialName("custom")
    CUSTOM,
}

@Serializable
data class DisplaySetting(
    val userAvatar: Avatar = Avatar.Dummy,
    val userNickname: String = "",
    val useAppIconStyleLoadingIndicator: Boolean = true,
    val showUserAvatar: Boolean = true,
    val showAssistantBubble: Boolean = false,
    val bubbleOpacity: Float = 1.0f,
    val showModelIcon: Boolean = true,
    val showModelName: Boolean = true,
    val showDateTimeInMessage: Boolean = false,
    val showTokenUsage: Boolean = true,
    val showThinkingContent: Boolean = true,
    val autoCloseThinking: Boolean = true,
    val showUpdates: Boolean = true,
    val updateCheckDisabledUntilEpochMillis: Long = 0L,
    val showMessageJumper: Boolean = true,
    val messageJumperOnLeft: Boolean = false,
    val fontSizeRatio: Float = 1.0f,
    val enableMessageGenerationHapticEffect: Boolean = true,
    val enableMessageGenerationSoundEffect: Boolean = true,
    val skipCropImage: Boolean = true,
    val enableNotificationOnMessageGeneration: Boolean = true,
    val enableLiveUpdateNotification: Boolean = true,
    val codeBlockAutoWrap: Boolean = true,
    val codeBlockAutoCollapse: Boolean = true,
    val showLineNumbers: Boolean = false,
    val ttsOnlyReadQuoted: Boolean = false,
    val ttsOnlyReadOutsideBrackets: Boolean = false,
    val autoPlayTTSAfterGeneration: Boolean = false,
    val ttsToolSequentialPlayback: Boolean = true,
    val pasteLongTextAsFile: Boolean = false,
    val pasteLongTextThreshold: Int = 1000,
    val sendOnEnter: Boolean = false,
    val enableAutoScroll: Boolean = true,
    val enableLatexRendering: Boolean = true,
    val enableBlurEffect: Boolean = false,
    val chatFontFamily: ChatFontFamily = ChatFontFamily.DEFAULT,
    val chatCustomFontPath: String = "",
    val chatCustomFontName: String = "",
    val enableVolumeKeyScroll: Boolean = false,
    val volumeKeyScrollRatio: Float = 1.0f,
) {
    fun areUpdateChecksEnabled(nowEpochMillis: Long = System.currentTimeMillis()): Boolean {
        return showUpdates && nowEpochMillis >= updateCheckDisabledUntilEpochMillis
    }
}

@Serializable
data class WebDavConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val path: String = "measix_pilot_backups",
    val items: List<BackupItem> = listOf(
        BackupItem.DATABASE,
        BackupItem.FILES
    ),
) {
    @Serializable
    enum class BackupItem {
        DATABASE,
        FILES,
    }
}

@Serializable
data class BackupReminderConfig(
    val enabled: Boolean = false,
    val intervalDays: Int = 7,
    val lastBackupTime: Long = 0L,
)

fun Settings.isNotConfigured() = providers.none { provider ->
    provider.enabled && provider.models.isNotEmpty()
}

fun Settings.findModelById(uuid: ConfigurationReference?, fallback: ConfigurationReference? = null): Model? {
    if (uuid == null && fallback == null) return null
    return uuid?.let { this.providers.findModelById(it) }
        ?: fallback?.let { this.providers.findModelById(it) }
}

fun List<ProviderSetting>.findModelById(uuid: ConfigurationReference): Model? {
    this.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == uuid) {
                return model
            }
        }
    }
    return null
}

fun Settings.getChatModel(assistant: Assistant): Model? =
    providers.asSequence()
        .filter { it.enabled }
        .flatMap { it.models.asSequence() }
        .firstOrNull { model ->
            model.id == (assistant.chatModelId ?: chatModelId) &&
                model.type == ModelType.CHAT
        }?.withAssistantSearch(assistant)

fun Settings.getCurrentChatModel(): Model? = getChatModel(getCurrentAssistant())

fun Settings.getCurrentAssistant(): Assistant {
    return this.assistants.find { it.id == assistantId } ?: this.assistants.first()
}

fun Settings.getAssistantById(id: ConfigurationReference): Assistant? {
    return this.assistants.find { it.id == id }
}

fun Settings.getQuickMessagesOfAssistant(assistant: Assistant) =
    quickMessages.filter { it.id in assistant.quickMessageIds }

fun Settings.getSelectedTTSProvider(): TTSProviderSetting? {
    return selectedTTSProviderId.let { id ->
        ttsProviders.find { it.id == id }
    } ?: ttsProviders.firstOrNull()
}

fun Settings.getSelectedASRProvider(): ASRProviderSetting? {
    return selectedASRProviderId?.let { id ->
        asrProviders.find { it.id == id }
    } ?: asrProviders.firstOrNull()
}

fun Model.findProvider(providers: List<ProviderSetting>, checkOverwrite: Boolean = true): ProviderSetting? {
    val provider = findModelProviderFromList(providers.filter { it.enabled }) ?: return null
    val providerOverwrite = this.providerOverwrite
    if (checkOverwrite && providerOverwrite != null) {
        return providerOverwrite.copyProvider(models = emptyList())
    }
    return provider
}

private fun Model.findModelProviderFromList(providers: List<ProviderSetting>): ProviderSetting? {
    providers.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == this.id) {
                return setting
            }
        }
    }
    return null
}

internal val DEFAULT_ASSISTANT_ID = ConfigurationReference.parse("0950e2dc-9bd5-4801-afa3-aa887aa36b4e")
internal val DEFAULT_ASSISTANTS = listOf(
    Assistant(
        id = DEFAULT_ASSISTANT_ID,
        name = "",
        systemPrompt = DEFAULT_SYSTEM_PROMPT,
    ),
)

val DEFAULT_SYSTEM_TTS_ID = ConfigurationReference.parse("026a01a2-c3a0-4fd5-8075-80e03bdef200")
internal val DEFAULT_TTS_PROVIDERS = listOf(
    TTSProviderSetting.SystemTTS(
        id = DEFAULT_SYSTEM_TTS_ID,
        name = "",
    ),
)

internal val DEFAULT_ASSISTANTS_IDS = DEFAULT_ASSISTANTS.map { it.id }

val DEFAULT_MODE_INJECTIONS = listOf(
    PromptInjection.ModeInjection(
        id = ConfigurationReference.parse("b87eaf16-f5cd-4ac1-9e4f-b11ae3a61d74"),
        content = LEARNING_MODE_PROMPT,
        position = InjectionPosition.AFTER_SYSTEM_PROMPT,
        name = "Learning Mode"
    )
)
