package net.weero.measix.pilot.data.datastore

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataMigration
import androidx.datastore.core.IOException
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
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
import net.weero.measix.pilot.utils.decodeListLenient
import net.weero.measix.pilot.utils.toMutableStateFlow
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting
import kotlin.uuid.Uuid

private const val TAG = "SettingsStore"

private val Context.settingsStore by preferencesDataStore(
    name = "settings",
    produceMigrations = { context ->
        listOf(
            OcrSettingsMigration(context),
            SearchSelectionMigration(),
        )
    },
)

private inline fun <reified T> Preferences.json(key: Preferences.Key<String>, default: T): T =
    this[key]?.let(JsonInstant::decodeFromString) ?: default

private inline fun <reified T> Preferences.lenientList(key: Preferences.Key<String>): List<T> =
    this[key]?.let(JsonInstant::decodeListLenient) ?: emptyList()

private fun Preferences.uuid(key: Preferences.Key<String>): Uuid? =
    this[key]?.let { runCatching { Uuid.parse(it) }.getOrNull() }

private inline fun <reified T> androidx.datastore.preferences.core.MutablePreferences.writeJson(
    key: Preferences.Key<String>,
    value: T,
) {
    this[key] = JsonInstant.encodeToString(value)
}

private fun androidx.datastore.preferences.core.MutablePreferences.writeUuid(
    key: Preferences.Key<String>,
    value: Uuid?,
) {
    value?.let { this[key] = it.toString() } ?: remove(key)
}

private data class SettingsDefaultPath(
    val path: String,
    val key: Preferences.Key<String>,
    val isPersisted: (Settings) -> Boolean,
)

private val SETTINGS_DEFAULT_PATHS = listOf(
    SettingsDefaultPath("defaults/chatModelId", SettingsStore.SELECT_MODEL) { true },
    SettingsDefaultPath("defaults/fastModelId", SettingsStore.FAST_MODEL) { true },
    SettingsDefaultPath("defaults/titleModelId", SettingsStore.TITLE_MODEL) { it.titleModelId != null },
    SettingsDefaultPath("defaults/imageGenerationModelId", SettingsStore.IMAGE_GENERATION_MODEL) { true },
    SettingsDefaultPath("defaults/attachmentInspectionModelId", SettingsStore.ATTACHMENT_INSPECTION_MODEL) { it.attachmentInspectionModelId != null },
    SettingsDefaultPath("defaults/compressModelId", SettingsStore.COMPRESS_MODEL) { true },
    SettingsDefaultPath("defaults/assistantId", SettingsStore.SELECT_ASSISTANT) { true },
    SettingsDefaultPath("defaults/selectedSearchServiceId", SettingsStore.SELECTED_SEARCH_SERVICE_ID) { it.selectedSearchServiceId != null },
    SettingsDefaultPath("defaults/selectedTTSProviderId", SettingsStore.SELECTED_TTS_PROVIDER) { true },
    SettingsDefaultPath("defaults/selectedASRProviderId", SettingsStore.SELECTED_ASR_PROVIDER) { it.selectedASRProviderId != null },
)

private fun Preferences.explicitDefaultPaths(): Set<String> =
    SETTINGS_DEFAULT_PATHS.filterTo(linkedSetOf()) { contains(it.key) }.mapTo(linkedSetOf()) { it.path }

private data class LocalSettingsSnapshot(
    val settings: Settings,
    val explicitDefaultPaths: Set<String>,
)

private fun Settings.persistedDefaultPaths(): Set<String> =
    SETTINGS_DEFAULT_PATHS.filterTo(linkedSetOf()) { it.isPersisted(this) }.mapTo(linkedSetOf()) { it.path }

/**
 * `search_selected` was a UI list index. Persisting the selected service identity makes a
 * reorder, deletion, or managed overlay unable to select a different search backend.
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

class SettingsStore private constructor(
    private val appContext: Context,
    private val scope: AppScope,
    runtime: ManagedConfigurationRuntime,
) {
    private val nowMillis = runtime.nowMillis

    constructor(appContext: Context, scope: AppScope) : this(
        appContext = appContext,
        scope = scope,
        runtime = managedConfigurationRuntime(),
    )

    companion object {
        internal fun forManagedStateTest(
            appContext: Context,
            scope: AppScope,
            runtime: ManagedConfigurationRuntime,
        ): SettingsStore = SettingsStore(appContext, scope, runtime)

        // UI设置
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val THEME_ID = stringPreferencesKey("theme_id")
        val CUSTOM_THEMES = stringPreferencesKey("custom_themes")
        val DISPLAY_SETTING = stringPreferencesKey("display_setting")
        val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")

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

    private val dataStore = appContext.settingsStore

    /**
     * 串行化“读取最新值 → 修改 → DataStore 提交”，避免工具操作与用户设置并发时
     * 由整份旧 Settings 覆盖新值。
     */
    private val updateMutex = Mutex()

    private val localSettingsRaw = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }.map { preferences ->
            LocalSettingsSnapshot(
                settings = Settings(
                favoriteModels = preferences.json(FAVORITE_MODELS, emptyList()),
                chatModelId = preferences[SELECT_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                fastModelId = preferences[FAST_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                titleModelId = preferences[TITLE_MODEL]?.let { Uuid.parse(it) },
                enableSuggestion = preferences[ENABLE_SUGGESTION] != false,
                suggestionModelId = preferences[SUGGESTION_MODEL]?.let { Uuid.parse(it) },
                imageGenerationModelId = preferences[IMAGE_GENERATION_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                titlePrompt = preferences[TITLE_PROMPT] ?: DEFAULT_TITLE_PROMPT,
                suggestionPrompt = preferences[SUGGESTION_PROMPT] ?: DEFAULT_SUGGESTION_PROMPT,
                attachmentInspectionModelId = preferences[ATTACHMENT_INSPECTION_MODEL]?.let { Uuid.parse(it) },
                compressModelId = preferences[COMPRESS_MODEL]?.let { Uuid.parse(it) } ?: DEFAULT_AUTO_MODEL_ID,
                compressPrompt = preferences[COMPRESS_PROMPT] ?: DEFAULT_COMPRESS_PROMPT,
                assistantId = preferences[SELECT_ASSISTANT]?.let { Uuid.parse(it) }
                    ?: DEFAULT_ASSISTANT_ID,
                assistantTags = preferences.json(ASSISTANT_TAGS, emptyList()),
                providers = preferences.json(PROVIDERS, emptyList()),
                assistants = preferences.json(ASSISTANTS, emptyList()),
                dynamicColor = preferences[DYNAMIC_COLOR] != false,
                themeId = preferences[THEME_ID] ?: PresetThemes[0].id,
                customThemes = preferences.json(CUSTOM_THEMES, emptyList()),
                developerMode = preferences[DEVELOPER_MODE] == true,
                displaySetting = preferences.json(DISPLAY_SETTING, DisplaySetting()),
                searchServices = preferences.lenientList(SEARCH_SERVICES),
                searchCommonOptions = preferences.json(SEARCH_COMMON, SearchCommonOptions()),
                selectedSearchServiceId = preferences.uuid(SELECTED_SEARCH_SERVICE_ID),
                mcpServers = preferences.json(MCP_SERVERS, emptyList()),
                webDavConfig = preferences.json(WEBDAV_CONFIG, WebDavConfig()),
                s3Config = preferences.json(S3_CONFIG, S3Config()),
                ttsProviders = preferences.lenientList(TTS_PROVIDERS),
                selectedTTSProviderId = preferences[SELECTED_TTS_PROVIDER]?.let { Uuid.parse(it) }
                    ?: DEFAULT_SYSTEM_TTS_ID,
                defaultTTSPlaybackSpeed = preferences[DEFAULT_TTS_PLAYBACK_SPEED]?.coerceIn(0.5f, 2.0f) ?: 1.0f,
                asrProviders = preferences.lenientList(ASR_PROVIDERS),
                selectedASRProviderId = preferences[SELECTED_ASR_PROVIDER]?.let { Uuid.parse(it) },
                modeInjections = preferences.json(MODE_INJECTIONS, emptyList()),
                quickMessages = preferences.json(QUICK_MESSAGES, emptyList()),
                backupReminderConfig = preferences.json(BACKUP_REMINDER_CONFIG, BackupReminderConfig()),
                launchCount = preferences[LAUNCH_COUNT] ?: 0,
                ignoredUpdateVersion = preferences[IGNORED_UPDATE_VERSION] ?: "",
                pendingAssistantDeletions = preferences[PENDING_ASSISTANT_DELETIONS]?.let { encoded ->
                    runCatching {
                        JsonInstant.decodeFromString<List<PendingAssistantDeletion>>(encoded)
                    }.getOrElse { error ->
                        Log.e(TAG, "Unable to decode pending assistant deletions", error)
                        emptyList()
                    }
                } ?: emptyList(),
                ),
                explicitDefaultPaths = preferences.explicitDefaultPaths(),
            )
        }

    private val localSettings = localSettingsRaw
        .distinctUntilChanged()
        .toMutableStateFlow(scope, LocalSettingsSnapshot(Settings.dummy(), emptySet()))

    private val managedConfiguration = ManagedConfigurationStorage(appContext, runtime)
    private val managedSnapshot = MutableStateFlow<ManagedConfigurationSnapshot?>(null)
    private var managedExpiryJob: Job? = null
    private var effectiveRevision = 0L
    private var publishedEffectiveInputs: Pair<LocalSettingsSnapshot, ManagedConfigurationSnapshot>? = null
    private val _effectiveSettings = MutableStateFlow(
        EffectiveSettingsSnapshot(
            settings = Settings.dummy(),
            access = SettingsAccessIndex(),
            revision = 0,
            managedState = ManagedConfigurationState.ABSENT,
        ),
    )

    /** The aggregate's only externally visible configuration read model. */
    internal val effectiveSettings: StateFlow<EffectiveSettingsSnapshot> = _effectiveSettings.asStateFlow()

    init {
        scope.launch {
            updateMutex.withLock {
                publishManagedSnapshot(managedConfiguration.loadSnapshot())
            }
        }
        scope.launch {
            combine(localSettings, managedSnapshot.filterNotNull()) { local, managed -> local to managed }
                .collect { (local, managed) ->
                    updateMutex.withLock {
                        publishEffectiveSnapshot(local, managed)
                    }
                }
        }
    }

    /** 备份恢复替换 Local shadow，但不得清空未完成的内部删除 tombstone。 */
    suspend fun restoreLocal(settings: Settings): Settings =
        updateMutex.withLock {
            val current = localSettings.first { !it.settings.init }.settings
            updateInternal(
                current = current,
                proposed = settings.withInternalStateFrom(current),
            )
        }

    suspend fun updateLocal(transform: (Settings) -> Settings): Settings = updateMutex.withLock {
        val localSnapshot = localSettings.first { !it.settings.init }
        val local = localSnapshot.settings
        val localReadModel = local.materializeForRead()
        val effective = EffectiveSettingsResolver.resolve(
            local = localReadModel,
            managed = managedSnapshot.filterNotNull().first(),
            revision = effectiveSettings.value.revision,
            explicitLocalDefaults = localSnapshot.explicitDefaultPaths,
        )
        val proposed = transform(localReadModel)
        requireLocalSettingsWriteAllowed(
            currentLocal = localReadModel,
            currentEffective = effective,
            proposedLocal = proposed,
        )
        updateInternal(
            current = local,
            proposed = proposed,
        )
    }

    /** Backup is a durable format boundary and exports only the Local shadow. */
    internal suspend fun snapshotLocal(): Settings = localSettings.first { !it.settings.init }.settings.materializeForRead()

    /** Applies one verified managed aggregate without exposing a second configuration owner. */
    internal suspend fun applyManagedSnapshot(envelope: ByteArray): ManagedApplyResult = updateMutex.withLock {
        val previous = managedSnapshot.filterNotNull().first()
        when (val prepared = managedConfiguration.prepare(envelope, previous)) {
            is ManagedConfigurationPreparation.Rejected -> ManagedApplyResult.Rejected(prepared.reason)
            is ManagedConfigurationPreparation.Accepted -> {
                publishManagedSnapshot(prepared.snapshot)
                scope.launch { managedConfiguration.cleanupRetired(envelope, prepared.generation) }
                ManagedApplyResult.Applied(prepared.generation)
            }
        }
    }

    /** Serializes time-driven degradation with local writes and managed generation changes. */
    private suspend fun publishManagedSnapshot(snapshot: ManagedConfigurationSnapshot) {
        managedSnapshot.value = snapshot
        publishEffectiveSnapshot(localSettings.first { !it.settings.init }, snapshot)
        managedExpiryJob?.cancel()
        val expiresAt = snapshot.expiresAtEpochMillis ?: return
        if (snapshot.state != ManagedConfigurationState.ACTIVE) return
        managedExpiryJob = scope.launch {
            val now = nowMillis()
            delay(if (expiresAt <= now) 0L else expiresAt - now)
            updateMutex.withLock {
                val current = managedSnapshot.filterNotNull().first()
                if (
                    current.generation == snapshot.generation &&
                    current.state == ManagedConfigurationState.ACTIVE &&
                    current.expiresAtEpochMillis != null &&
                    current.expiresAtEpochMillis <= nowMillis()
                ) {
                    publishManagedSnapshot(current.copy(state = ManagedConfigurationState.DEGRADED))
                }
            }
        }
    }

    /** Publishes only the latest Local/Managed pair after its durable owner has committed. */
    private fun publishEffectiveSnapshot(
        local: LocalSettingsSnapshot,
        managed: ManagedConfigurationSnapshot,
    ) {
        if (local.settings.init || local != localSettings.value || managed != managedSnapshot.value) return
        val inputs = local to managed
        if (inputs == publishedEffectiveInputs) return
        _effectiveSettings.value = EffectiveSettingsResolver.resolve(
            local = local.settings,
            managed = managed,
            revision = ++effectiveRevision,
            explicitLocalDefaults = local.explicitDefaultPaths,
        )
        publishedEffectiveInputs = inputs
    }

    private suspend fun updateInternal(
        current: Settings,
        proposed: Settings,
    ): Settings {
        if (proposed.init) {
            Log.w(TAG, "Cannot update dummy settings")
            return current
        }
        return commitSettings(
            proposed = proposed,
            persist = { normalizedSettings ->
                dataStore.edit { preferences ->
                    preferences[DYNAMIC_COLOR] = normalizedSettings.dynamicColor
                    preferences[THEME_ID] = normalizedSettings.themeId
                    preferences.writeJson(CUSTOM_THEMES, normalizedSettings.customThemes)
                    preferences[DEVELOPER_MODE] = normalizedSettings.developerMode
                    preferences.writeJson(DISPLAY_SETTING, normalizedSettings.displaySetting)
                    preferences.writeJson(FAVORITE_MODELS, normalizedSettings.favoriteModels)
                    preferences[SELECT_MODEL] = normalizedSettings.chatModelId.toString()
                    preferences[FAST_MODEL] = normalizedSettings.fastModelId.toString()
                    preferences.writeUuid(TITLE_MODEL, normalizedSettings.titleModelId)
                    preferences[ENABLE_SUGGESTION] = normalizedSettings.enableSuggestion
                    preferences.writeUuid(SUGGESTION_MODEL, normalizedSettings.suggestionModelId)
                    preferences[IMAGE_GENERATION_MODEL] = normalizedSettings.imageGenerationModelId.toString()
                    preferences[TITLE_PROMPT] = normalizedSettings.titlePrompt
                    preferences[SUGGESTION_PROMPT] = normalizedSettings.suggestionPrompt
                    preferences.writeUuid(ATTACHMENT_INSPECTION_MODEL, normalizedSettings.attachmentInspectionModelId)
                    preferences[COMPRESS_MODEL] = normalizedSettings.compressModelId.toString()
                    preferences[COMPRESS_PROMPT] = normalizedSettings.compressPrompt
                    preferences.writeJson(PROVIDERS, normalizedSettings.providers)
                    preferences.writeJson(ASSISTANTS, normalizedSettings.assistants)
                    preferences[SELECT_ASSISTANT] = normalizedSettings.assistantId.toString()
                    preferences.writeJson(ASSISTANT_TAGS, normalizedSettings.assistantTags)
                    preferences.writeJson(SEARCH_SERVICES, normalizedSettings.searchServices)
                    preferences.writeJson(SEARCH_COMMON, normalizedSettings.searchCommonOptions)
                    preferences.writeUuid(SELECTED_SEARCH_SERVICE_ID, normalizedSettings.selectedSearchServiceId)
                    preferences.writeJson(MCP_SERVERS, normalizedSettings.mcpServers)
                    preferences.writeJson(WEBDAV_CONFIG, normalizedSettings.webDavConfig)
                    preferences.writeJson(S3_CONFIG, normalizedSettings.s3Config)
                    preferences.writeJson(TTS_PROVIDERS, normalizedSettings.ttsProviders)
                    preferences.writeUuid(SELECTED_TTS_PROVIDER, normalizedSettings.selectedTTSProviderId)
                    preferences[DEFAULT_TTS_PLAYBACK_SPEED] = normalizedSettings.defaultTTSPlaybackSpeed
                    preferences.writeJson(ASR_PROVIDERS, normalizedSettings.asrProviders)
                    preferences.writeUuid(SELECTED_ASR_PROVIDER, normalizedSettings.selectedASRProviderId)
                    preferences.writeJson(MODE_INJECTIONS, normalizedSettings.modeInjections)
                    preferences.writeJson(QUICK_MESSAGES, normalizedSettings.quickMessages)
                    preferences.writeJson(BACKUP_REMINDER_CONFIG, normalizedSettings.backupReminderConfig)
                    preferences[LAUNCH_COUNT] = normalizedSettings.launchCount
                    preferences[IGNORED_UPDATE_VERSION] = normalizedSettings.ignoredUpdateVersion
                    preferences.writeJson(PENDING_ASSISTANT_DELETIONS, normalizedSettings.pendingAssistantDeletions)
                }
            },
            // persist 正常返回后才发布，避免写盘失败时内存状态领先于持久化状态。
            publish = { committed ->
                val localSnapshot = LocalSettingsSnapshot(committed, committed.persistedDefaultPaths())
                localSettings.value = localSnapshot
                publishEffectiveSnapshot(
                    localSnapshot,
                    managedSnapshot.filterNotNull().first(),
                )
            },
        ).materializeForRead()
    }

}

/**
 * 跨进程重试的删除清理 tombstone。
 * 仅保存清理所需的 Assistant ID 与资源 URI 快照，不保存完整 Assistant 或 prompt。
 * Settings 提交后由 AssistantManagementService 消费，完成后原子移除。
 */
@Serializable
data class PendingAssistantDeletion(
    val assistantId: Uuid,
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
    val developerMode: Boolean = false,
    val displaySetting: DisplaySetting = DisplaySetting(),
    val favoriteModels: List<Uuid> = emptyList(),
    val chatModelId: Uuid = Uuid.random(),
    val fastModelId: Uuid = Uuid.random(),
    val titleModelId: Uuid? = null,
    val imageGenerationModelId: Uuid = Uuid.random(),
    val titlePrompt: String = DEFAULT_TITLE_PROMPT,
    val enableSuggestion: Boolean = true,
    val suggestionModelId: Uuid? = null,
    val suggestionPrompt: String = DEFAULT_SUGGESTION_PROMPT,
    val attachmentInspectionModelId: Uuid? = null,
    val compressModelId: Uuid = Uuid.random(),
    val compressPrompt: String = DEFAULT_COMPRESS_PROMPT,
    val assistantId: Uuid = DEFAULT_ASSISTANT_ID,
    val providers: List<ProviderSetting> = DEFAULT_PROVIDERS,
    val assistants: List<Assistant> = DEFAULT_ASSISTANTS,
    val assistantTags: List<Tag> = emptyList(),
    val searchServices: List<SearchServiceOptions> = listOf(SearchServiceOptions.DEFAULT),
    val searchCommonOptions: SearchCommonOptions = SearchCommonOptions(),
    val selectedSearchServiceId: Uuid? = null,
    val mcpServers: List<McpServerConfig> = emptyList(),
    val webDavConfig: WebDavConfig = WebDavConfig(),
    val s3Config: S3Config = S3Config(),
    val ttsProviders: List<TTSProviderSetting> = DEFAULT_TTS_PROVIDERS,
    val selectedTTSProviderId: Uuid = DEFAULT_SYSTEM_TTS_ID,
    val defaultTTSPlaybackSpeed: Float = 1.0f,
    val asrProviders: List<ASRProviderSetting> = emptyList(),
    val selectedASRProviderId: Uuid? = null,
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

fun Settings.findModelById(uuid: Uuid?, fallback: Uuid? = null): Model? {
    if (uuid == null && fallback == null) return null
    return uuid?.let { this.providers.findModelById(it) }
        ?: fallback?.let { this.providers.findModelById(it) }
}

fun List<ProviderSetting>.findModelById(uuid: Uuid): Model? {
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
        }

fun Settings.getCurrentChatModel(): Model? = getChatModel(getCurrentAssistant())

fun Settings.getCurrentAssistant(): Assistant {
    return this.assistants.find { it.id == assistantId } ?: this.assistants.first()
}

fun Settings.getAssistantById(id: Uuid): Assistant? {
    return this.assistants.find { it.id == id }
}

/** Resolves the assistant owned by a conversation, falling back only if it was deleted. */
fun Settings.getConversationAssistant(assistantId: Uuid): Assistant =
    getAssistantById(assistantId) ?: getCurrentAssistant()

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

internal val DEFAULT_ASSISTANT_ID = Uuid.parse("0950e2dc-9bd5-4801-afa3-aa887aa36b4e")
internal val DEFAULT_ASSISTANTS = listOf(
    Assistant(
        id = DEFAULT_ASSISTANT_ID,
        name = "",
        systemPrompt = DEFAULT_SYSTEM_PROMPT,
    ),
)

val DEFAULT_SYSTEM_TTS_ID = Uuid.parse("026a01a2-c3a0-4fd5-8075-80e03bdef200")
internal val DEFAULT_TTS_PROVIDERS = listOf(
    TTSProviderSetting.SystemTTS(
        id = DEFAULT_SYSTEM_TTS_ID,
        name = "",
    ),
)

internal val DEFAULT_ASSISTANTS_IDS = DEFAULT_ASSISTANTS.map { it.id }

val DEFAULT_MODE_INJECTIONS = listOf(
    PromptInjection.ModeInjection(
        id = Uuid.parse("b87eaf16-f5cd-4ac1-9e4f-b11ae3a61d74"),
        content = LEARNING_MODE_PROMPT,
        position = InjectionPosition.AFTER_SYSTEM_PROMPT,
        name = "Learning Mode"
    )
)
