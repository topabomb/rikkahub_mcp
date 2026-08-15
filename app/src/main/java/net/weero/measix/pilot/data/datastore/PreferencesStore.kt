package net.weero.measix.pilot.data.datastore

import android.content.Context
import android.util.Log
import androidx.datastore.core.IOException
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.ai.mcp.McpServerConfig
import net.weero.measix.pilot.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import net.weero.measix.pilot.data.ai.prompts.DEFAULT_OCR_PROMPT
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

private const val TAG = "PreferencesStore"

private val Context.settingsStore by preferencesDataStore(
    name = "settings"
)

class SettingsStore(
    context: Context,
    scope: AppScope,
    private val writePolicy: SettingsWritePolicy = SettingsWritePolicy.AllowAll,
) {
    companion object {
        // 版本号
        val VERSION = intPreferencesKey("data_version")

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
        val OCR_MODEL = stringPreferencesKey("ocr_model")
        val OCR_PROMPT = stringPreferencesKey("ocr_prompt")
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
        val SEARCH_SELECTED = intPreferencesKey("search_selected")

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

    private val dataStore = context.settingsStore

    /**
     * 串行化“读取最新值 → 修改 → DataStore 提交”，避免工具操作与用户设置并发时
     * 由整份旧 Settings 覆盖新值。
     */
    private val updateMutex = Mutex()

    val settingsFlowRaw = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }.map { preferences ->
            Settings(
                favoriteModels = preferences[FAVORITE_MODELS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                chatModelId = preferences[SELECT_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                fastModelId = preferences[FAST_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                titleModelId = preferences[TITLE_MODEL]?.let { Uuid.parse(it) },
                enableSuggestion = preferences[ENABLE_SUGGESTION] != false,
                suggestionModelId = preferences[SUGGESTION_MODEL]?.let { Uuid.parse(it) },
                imageGenerationModelId = preferences[IMAGE_GENERATION_MODEL]?.let { Uuid.parse(it) } ?: Uuid.random(),
                titlePrompt = preferences[TITLE_PROMPT] ?: DEFAULT_TITLE_PROMPT,
                suggestionPrompt = preferences[SUGGESTION_PROMPT] ?: DEFAULT_SUGGESTION_PROMPT,
                ocrModelId = preferences[OCR_MODEL]?.let { Uuid.parse(it) } ?: Uuid.random(),
                ocrPrompt = preferences[OCR_PROMPT] ?: DEFAULT_OCR_PROMPT,
                compressModelId = preferences[COMPRESS_MODEL]?.let { Uuid.parse(it) } ?: DEFAULT_AUTO_MODEL_ID,
                compressPrompt = preferences[COMPRESS_PROMPT] ?: DEFAULT_COMPRESS_PROMPT,
                assistantId = preferences[SELECT_ASSISTANT]?.let { Uuid.parse(it) }
                    ?: DEFAULT_ASSISTANT_ID,
                assistantTags = preferences[ASSISTANT_TAGS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                providers = JsonInstant.decodeFromString(preferences[PROVIDERS] ?: "[]"),
                assistants = JsonInstant.decodeFromString(preferences[ASSISTANTS] ?: "[]"),
                dynamicColor = preferences[DYNAMIC_COLOR] != false,
                themeId = preferences[THEME_ID] ?: PresetThemes[0].id,
                customThemes = preferences[CUSTOM_THEMES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                developerMode = preferences[DEVELOPER_MODE] == true,
                displaySetting = JsonInstant.decodeFromString(preferences[DISPLAY_SETTING] ?: "{}"),
                searchServices = preferences[SEARCH_SERVICES]?.let {
                    JsonInstant.decodeListLenient<SearchServiceOptions>(it)
                }?.ifEmpty { listOf(SearchServiceOptions.DEFAULT) } ?: listOf(SearchServiceOptions.DEFAULT),
                searchCommonOptions = preferences[SEARCH_COMMON]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: SearchCommonOptions(),
                searchServiceSelected = preferences[SEARCH_SELECTED] ?: 0,
                mcpServers = preferences[MCP_SERVERS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                webDavConfig = preferences[WEBDAV_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: WebDavConfig(),
                s3Config = preferences[S3_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: S3Config(),
                ttsProviders = preferences[TTS_PROVIDERS]?.let {
                    JsonInstant.decodeListLenient<TTSProviderSetting>(it)
                } ?: emptyList(),
                selectedTTSProviderId = preferences[SELECTED_TTS_PROVIDER]?.let { Uuid.parse(it) }
                    ?: DEFAULT_SYSTEM_TTS_ID,
                defaultTTSPlaybackSpeed = preferences[DEFAULT_TTS_PLAYBACK_SPEED]?.coerceIn(0.5f, 2.0f) ?: 1.0f,
                asrProviders = preferences[ASR_PROVIDERS]?.let {
                    JsonInstant.decodeListLenient<ASRProviderSetting>(it)
                } ?: emptyList(),
                selectedASRProviderId = preferences[SELECTED_ASR_PROVIDER]?.let { Uuid.parse(it) },
                modeInjections = preferences[MODE_INJECTIONS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                quickMessages = preferences[QUICK_MESSAGES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                backupReminderConfig = preferences[BACKUP_REMINDER_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: BackupReminderConfig(),
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
            )
        }
        .map { it.materializeForRead() }

    val settingsFlow = settingsFlowRaw
        .distinctUntilChanged()
        .toMutableStateFlow(scope, Settings.dummy())

    /** 备份恢复是完整外部快照，但不得清空未完成的内部删除 tombstone。 */
    suspend fun restoreFromBackup(settings: Settings) {
        updateMutex.withLock {
            val current = settingsFlow.first { !it.init }
            updateInternal(
                current = current,
                proposed = settings.withInternalStateFrom(current),
                source = SettingsWriteSource.BACKUP_RESTORE,
            )
        }
    }

    suspend fun update(fn: (Settings) -> Settings) {
        updateAtomic(fn = fn)
    }

    /**
     * 原子 transform：由内部 Mutex 串行化“读取最新值 → 修改 → DataStore 提交”。
     * AssistantManagementService、Assistant 编辑页和 UI 删除均使用该入口。
     */
    suspend fun updateAtomic(fn: (Settings) -> Settings) {
        updateAtomicAndGet(source = SettingsWriteSource.LOCAL, fn = fn)
    }

    /** 仅供需要在提交成功后执行文件补偿的领域服务取得策略处理后的实际提交值。 */
    internal suspend fun updateAtomicAndGet(fn: (Settings) -> Settings): Settings =
        updateAtomicAndGet(source = SettingsWriteSource.LOCAL, fn = fn)

    private suspend fun updateAtomicAndGet(
        source: SettingsWriteSource,
        fn: (Settings) -> Settings,
    ): Settings = updateMutex.withLock {
        // StateFlow 的初始值是不可持久化的 dummy；启动早期的原子操作必须等待真实 DataStore 快照。
        val current = settingsFlow.first { !it.init }
        val updated = fn(current)
        updateInternal(current = current, proposed = updated, source = source)
    }

    private suspend fun updateInternal(
        current: Settings,
        proposed: Settings,
        source: SettingsWriteSource,
    ): Settings {
        if (proposed.init) {
            Log.w(TAG, "Cannot update dummy settings")
            return current
        }
        return commitSettings(
            current = current,
            proposed = proposed,
            source = source,
            policy = writePolicy,
            persist = { normalizedSettings ->
                dataStore.edit { preferences ->
                    preferences[DYNAMIC_COLOR] = normalizedSettings.dynamicColor
                    preferences[THEME_ID] = normalizedSettings.themeId
                    preferences[CUSTOM_THEMES] = JsonInstant.encodeToString(normalizedSettings.customThemes)
                    preferences[DEVELOPER_MODE] = normalizedSettings.developerMode
                    preferences[DISPLAY_SETTING] = JsonInstant.encodeToString(normalizedSettings.displaySetting)
                    preferences[FAVORITE_MODELS] = JsonInstant.encodeToString(normalizedSettings.favoriteModels)
                    preferences[SELECT_MODEL] = normalizedSettings.chatModelId.toString()
                    preferences[FAST_MODEL] = normalizedSettings.fastModelId.toString()
                    normalizedSettings.titleModelId?.let {
                        preferences[TITLE_MODEL] = it.toString()
                    } ?: preferences.remove(TITLE_MODEL)
                    preferences[ENABLE_SUGGESTION] = normalizedSettings.enableSuggestion
                    normalizedSettings.suggestionModelId?.let {
                        preferences[SUGGESTION_MODEL] = it.toString()
                    } ?: preferences.remove(SUGGESTION_MODEL)
                    preferences[IMAGE_GENERATION_MODEL] = normalizedSettings.imageGenerationModelId.toString()
                    preferences[TITLE_PROMPT] = normalizedSettings.titlePrompt
                    preferences[SUGGESTION_PROMPT] = normalizedSettings.suggestionPrompt
                    preferences[OCR_MODEL] = normalizedSettings.ocrModelId.toString()
                    preferences[OCR_PROMPT] = normalizedSettings.ocrPrompt
                    preferences[COMPRESS_MODEL] = normalizedSettings.compressModelId.toString()
                    preferences[COMPRESS_PROMPT] = normalizedSettings.compressPrompt
                    preferences[PROVIDERS] = JsonInstant.encodeToString(normalizedSettings.providers)
                    preferences[ASSISTANTS] = JsonInstant.encodeToString(normalizedSettings.assistants)
                    preferences[SELECT_ASSISTANT] = normalizedSettings.assistantId.toString()
                    preferences[ASSISTANT_TAGS] = JsonInstant.encodeToString(normalizedSettings.assistantTags)
                    preferences[SEARCH_SERVICES] = JsonInstant.encodeToString(normalizedSettings.searchServices)
                    preferences[SEARCH_COMMON] = JsonInstant.encodeToString(normalizedSettings.searchCommonOptions)
                    preferences[SEARCH_SELECTED] = normalizedSettings.searchServiceSelected
                    preferences[MCP_SERVERS] = JsonInstant.encodeToString(normalizedSettings.mcpServers)
                    preferences[WEBDAV_CONFIG] = JsonInstant.encodeToString(normalizedSettings.webDavConfig)
                    preferences[S3_CONFIG] = JsonInstant.encodeToString(normalizedSettings.s3Config)
                    preferences[TTS_PROVIDERS] = JsonInstant.encodeToString(normalizedSettings.ttsProviders)
                    normalizedSettings.selectedTTSProviderId.let {
                        preferences[SELECTED_TTS_PROVIDER] = it.toString()
                    }
                    preferences[DEFAULT_TTS_PLAYBACK_SPEED] = normalizedSettings.defaultTTSPlaybackSpeed
                    preferences[ASR_PROVIDERS] = JsonInstant.encodeToString(normalizedSettings.asrProviders)
                    normalizedSettings.selectedASRProviderId?.let {
                        preferences[SELECTED_ASR_PROVIDER] = it.toString()
                    } ?: preferences.remove(SELECTED_ASR_PROVIDER)
                    preferences[MODE_INJECTIONS] = JsonInstant.encodeToString(normalizedSettings.modeInjections)
                    preferences[QUICK_MESSAGES] = JsonInstant.encodeToString(normalizedSettings.quickMessages)
                    preferences[BACKUP_REMINDER_CONFIG] =
                        JsonInstant.encodeToString(normalizedSettings.backupReminderConfig)
                    preferences[LAUNCH_COUNT] = normalizedSettings.launchCount
                    preferences[IGNORED_UPDATE_VERSION] = normalizedSettings.ignoredUpdateVersion
                    preferences[PENDING_ASSISTANT_DELETIONS] =
                        JsonInstant.encodeToString(normalizedSettings.pendingAssistantDeletions)
                }
            },
            // persist 正常返回后才发布，避免写盘失败时内存状态领先于持久化状态。
            publish = { settingsFlow.value = it },
        )
    }

    suspend fun updateAssistant(assistantId: Uuid) {
        updateAtomic { settings ->
            settings.copy(assistantId = assistantId)
        }
    }

    suspend fun updateAssistantModel(assistantId: Uuid, modelId: Uuid) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(chatModelId = modelId)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantReasoningLevel(assistantId: Uuid, reasoningLevel: ReasoningLevel) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(reasoningLevel = reasoningLevel)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantWebSearch(assistantId: Uuid, enabled: Boolean) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(enableWebSearch = enabled)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantMcpServers(assistantId: Uuid, mcpServers: Set<Uuid>) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(mcpServers = mcpServers)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantInjections(
        assistantId: Uuid,
        modeInjectionIds: Set<Uuid>,
        quickMessageIds: Set<Uuid> = emptySet(),
    ) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(
                            modeInjectionIds = modeInjectionIds,
                            quickMessageIds = quickMessageIds,
                        )
                    } else {
                        assistant
                    }
                }
            )
        }
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
    val ocrModelId: Uuid = Uuid.random(),
    val ocrPrompt: String = DEFAULT_OCR_PROMPT,
    val compressModelId: Uuid = Uuid.random(),
    val compressPrompt: String = DEFAULT_COMPRESS_PROMPT,
    val assistantId: Uuid = DEFAULT_ASSISTANT_ID,
    val providers: List<ProviderSetting> = DEFAULT_PROVIDERS,
    val assistants: List<Assistant> = DEFAULT_ASSISTANTS,
    val assistantTags: List<Tag> = emptyList(),
    val searchServices: List<SearchServiceOptions> = listOf(SearchServiceOptions.DEFAULT),
    val searchCommonOptions: SearchCommonOptions = SearchCommonOptions(),
    val searchServiceSelected: Int = 0,
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
)

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
