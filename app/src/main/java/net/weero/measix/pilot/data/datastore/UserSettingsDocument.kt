package net.weero.measix.pilot.data.datastore

import kotlinx.serialization.Serializable
import me.rerere.ai.provider.ProviderSetting
import me.rerere.asr.ASRProviderSetting
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting
import net.weero.measix.pilot.data.ai.mcp.McpServerConfig
import net.weero.measix.pilot.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import net.weero.measix.pilot.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import net.weero.measix.pilot.data.ai.prompts.DEFAULT_TITLE_PROMPT
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.configuration.AssistantUsagePreferences
import net.weero.measix.pilot.data.configuration.GatewayPreference
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.Avatar
import net.weero.measix.pilot.data.model.PromptInjection
import net.weero.measix.pilot.data.model.QuickMessage
import net.weero.measix.pilot.data.model.Tag
import net.weero.measix.pilot.data.sync.s3.S3Config
import net.weero.measix.pilot.ui.theme.CustomTheme
import net.weero.measix.pilot.ui.theme.PresetThemes

/** User definitions have one durable copy, regardless of where they are used. */
@Serializable
internal data class UserConfiguration(
    val providers: List<ProviderSetting> = DEFAULT_PROVIDERS,
    val assistants: List<Assistant> = DEFAULT_ASSISTANTS,
    val assistantTags: List<Tag> = emptyList(),
    val searchServices: List<SearchServiceOptions> = listOf(SearchServiceOptions.DEFAULT),
    val searchCommonOptions: SearchCommonOptions = SearchCommonOptions(),
    val mcpServers: List<McpServerConfig> = emptyList(),
    val ttsProviders: List<TTSProviderSetting> = DEFAULT_TTS_PROVIDERS,
    val asrProviders: List<ASRProviderSetting> = emptyList(),
    val modeInjections: List<PromptInjection.ModeInjection> = DEFAULT_MODE_INJECTIONS,
    val quickMessages: List<QuickMessage> = emptyList(),
    val titlePrompt: String = DEFAULT_TITLE_PROMPT,
    val suggestionPrompt: String = DEFAULT_SUGGESTION_PROMPT,
    val compressPrompt: String = DEFAULT_COMPRESS_PROMPT,
    val webDavConfig: WebDavConfig = WebDavConfig(),
    val s3Config: S3Config = S3Config(),
    val profile: UserProfile = UserProfile(),
) {
    init {
        val references = buildList {
            val pendingProviders = ArrayDeque(providers)
            while (pendingProviders.isNotEmpty()) {
                val provider = pendingProviders.removeFirst()
                add(provider.id)
                provider.models.forEach { model ->
                    add(model.id)
                    model.providerOverwrite?.let(pendingProviders::addLast)
                }
            }
            assistants.forEach { assistant ->
                add(assistant.id)
                assistant.chatModelId?.let(::add)
                addAll(assistant.tags)
                addAll(assistant.quickMessageIds)
                addAll(assistant.mcpServers)
                addAll(assistant.modeInjectionIds)
                addAll(assistant.allowedSubAssistantIds)
                addAll(assistant.regexes.map { it.id })
                addAll(assistant.presetMessages.mapNotNull { it.modelId })
            }
            addAll(assistantTags.map { it.id })
            addAll(searchServices.map { it.id })
            addAll(mcpServers.map { it.id })
            addAll(ttsProviders.map { it.id })
            addAll(asrProviders.map { it.id })
            addAll(modeInjections.map { it.id })
            addAll(quickMessages.map { it.id })
        }
        require(references.all { it is ConfigurationReference.User }) {
            "enterprise_reference_in_user_configuration"
        }
    }
}

@Serializable
internal data class UserProfile(
    val avatar: Avatar = Avatar.Dummy,
    val nickname: String = "",
)

/** Display behavior is shared; the user's profile is owned by UserConfiguration. */
@Serializable
internal data class DisplayPreferences(
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
    fun project(profile: UserProfile): DisplaySetting = DisplaySetting(
        userAvatar = profile.avatar,
        userNickname = profile.nickname,
        useAppIconStyleLoadingIndicator = useAppIconStyleLoadingIndicator,
        showUserAvatar = showUserAvatar,
        showAssistantBubble = showAssistantBubble,
        bubbleOpacity = bubbleOpacity,
        showModelIcon = showModelIcon,
        showModelName = showModelName,
        showDateTimeInMessage = showDateTimeInMessage,
        showTokenUsage = showTokenUsage,
        showThinkingContent = showThinkingContent,
        autoCloseThinking = autoCloseThinking,
        showUpdates = showUpdates,
        updateCheckDisabledUntilEpochMillis = updateCheckDisabledUntilEpochMillis,
        showMessageJumper = showMessageJumper,
        messageJumperOnLeft = messageJumperOnLeft,
        fontSizeRatio = fontSizeRatio,
        enableMessageGenerationHapticEffect = enableMessageGenerationHapticEffect,
        enableMessageGenerationSoundEffect = enableMessageGenerationSoundEffect,
        skipCropImage = skipCropImage,
        enableNotificationOnMessageGeneration = enableNotificationOnMessageGeneration,
        enableLiveUpdateNotification = enableLiveUpdateNotification,
        codeBlockAutoWrap = codeBlockAutoWrap,
        codeBlockAutoCollapse = codeBlockAutoCollapse,
        showLineNumbers = showLineNumbers,
        ttsOnlyReadQuoted = ttsOnlyReadQuoted,
        ttsOnlyReadOutsideBrackets = ttsOnlyReadOutsideBrackets,
        autoPlayTTSAfterGeneration = autoPlayTTSAfterGeneration,
        ttsToolSequentialPlayback = ttsToolSequentialPlayback,
        pasteLongTextAsFile = pasteLongTextAsFile,
        pasteLongTextThreshold = pasteLongTextThreshold,
        sendOnEnter = sendOnEnter,
        enableAutoScroll = enableAutoScroll,
        enableLatexRendering = enableLatexRendering,
        enableBlurEffect = enableBlurEffect,
        chatFontFamily = chatFontFamily,
        chatCustomFontPath = chatCustomFontPath,
        chatCustomFontName = chatCustomFontName,
        enableVolumeKeyScroll = enableVolumeKeyScroll,
        volumeKeyScrollRatio = volumeKeyScrollRatio,
    )
}

@Serializable
internal data class CommonUserPreferences(
    val dynamicColor: Boolean = true,
    val themeId: String = PresetThemes[0].id,
    val customThemes: List<CustomTheme> = emptyList(),
    val defaultTTSPlaybackSpeed: Float = 1.0f,
    val backupReminderConfig: BackupReminderConfig = BackupReminderConfig(),
    val display: DisplayPreferences = DisplayPreferences(),
)

@Serializable
internal data class ResourceSelections(
    val favoriteModels: List<ConfigurationReference> = emptyList(),
    val chatModelId: ConfigurationReference? = null,
    val fastModelId: ConfigurationReference? = null,
    val titleModelId: ConfigurationReference? = null,
    val imageGenerationModelId: ConfigurationReference? = null,
    val enableSuggestion: Boolean = true,
    val suggestionModelId: ConfigurationReference? = null,
    val attachmentInspectionModelId: ConfigurationReference? = null,
    val compressModelId: ConfigurationReference? = null,
    val assistantId: ConfigurationReference? = null,
    val selectedSearchServiceId: ConfigurationReference? = null,
    val selectedTTSProviderId: ConfigurationReference? = null,
    val selectedASRProviderId: ConfigurationReference? = null,
)

@Serializable
internal data class ScopedUserPreferences(
    val scope: ConfigurationScope,
    val selections: ResourceSelections = ResourceSelections(),
    val assistantUsage: List<AssistantUsagePreferences> = emptyList(),
    val gateways: List<GatewayPreference> = emptyList(),
) {
    init {
        require(scope is ConfigurationScope.Enterprise || assistantUsage.isEmpty()) { "personal_assistants_use_shared_definitions" }
        require(assistantUsage.map { it.assistantId }.distinct().size == assistantUsage.size) { "duplicate_assistant_usage" }
        require(gateways.isEmpty() || scope is ConfigurationScope.Enterprise) { "gateway_preferences_require_enterprise_scope" }
        require(gateways.all { scope is ConfigurationScope.Enterprise && it.gateway.authority == scope.authority }) {
            "foreign_gateway_in_scope_preferences"
        }
        require(gateways.map { it.gateway }.distinct().size == gateways.size) { "duplicate_gateway_preference" }
    }
}

@Serializable
internal data class UserPreferences(
    val common: CommonUserPreferences = CommonUserPreferences(),
    val scopes: List<ScopedUserPreferences> = listOf(ScopedUserPreferences(ConfigurationScope.Personal)),
) {
    init {
        require(scopes.map { it.scope }.distinct().size == scopes.size) { "duplicate_preference_scope" }
        scopes.forEach { scoped ->
            require((scoped.selections.references() + scoped.assistantUsage.flatMap { it.references() }).all { reference ->
                reference is ConfigurationReference.User ||
                    (scoped.scope is ConfigurationScope.Enterprise &&
                        reference is ConfigurationReference.Enterprise && reference.authority == scoped.scope.authority)
            }) { "foreign_reference_in_scope_preferences" }
        }
    }

    fun forScope(scope: ConfigurationScope): ResourceSelections =
        scopes.singleOrNull { it.scope == scope }?.selections ?: ResourceSelections()

    fun gateway(scope: ConfigurationScope.Enterprise, reference: ConfigurationReference.Enterprise): GatewayPreference? =
        scopes.singleOrNull { it.scope == scope }?.gateways?.singleOrNull { it.gateway == reference }

    fun withGateway(scope: ConfigurationScope.Enterprise, preference: GatewayPreference): UserPreferences {
        val existing = scopes.singleOrNull { it.scope == scope } ?: ScopedUserPreferences(scope)
        val updated = existing.copy(gateways = existing.gateways.filterNot { it.gateway == preference.gateway } + preference)
        return copy(scopes = scopes.filterNot { it.scope == scope } + updated)
    }

    fun withSelections(scope: ConfigurationScope, selections: ResourceSelections): UserPreferences {
        val existing = scopes.singleOrNull { it.scope == scope } ?: ScopedUserPreferences(scope)
        return copy(scopes = scopes.filterNot { it.scope == scope } + existing.copy(selections = selections))
    }

    fun assistantUsage(scope: ConfigurationScope, assistantId: ConfigurationReference): AssistantUsagePreferences? =
        scopes.singleOrNull { it.scope == scope }?.assistantUsage?.singleOrNull { it.assistantId == assistantId }

    fun withAssistantUsage(scope: ConfigurationScope.Enterprise, usage: AssistantUsagePreferences): UserPreferences {
        val existing = scopes.singleOrNull { it.scope == scope } ?: ScopedUserPreferences(scope)
        val updated = existing.copy(assistantUsage = existing.assistantUsage.filterNot { it.assistantId == usage.assistantId } + usage)
        return copy(scopes = scopes.filterNot { it.scope == scope } + updated)
    }

    fun resetAssistantUsage(scope: ConfigurationScope.Enterprise, assistantId: ConfigurationReference): UserPreferences =
        copy(scopes = scopes.map { scoped ->
            if (scoped.scope == scope) scoped.copy(assistantUsage = scoped.assistantUsage.filterNot { it.assistantId == assistantId }) else scoped
        })
}

@Serializable
internal data class SettingsInternalState(
    val launchCount: Int = 0,
    val ignoredUpdateVersion: String = "",
    val pendingAssistantDeletions: List<PendingAssistantDeletion> = emptyList(),
)

/** Configuration and preferences are published by one DataStore transaction. */
@Serializable
internal data class UserSettingsDocument(
    val schemaVersion: Int,
    val configuration: UserConfiguration,
    val preferences: UserPreferences,
    val internalState: SettingsInternalState,
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) { "unsupported_user_settings_schema" }
        require(internalState.pendingAssistantDeletions.all { it.assistantId is ConfigurationReference.User }) {
            "enterprise_reference_in_user_deletion"
        }
    }

    fun personalSettings(): Settings {
        val common = preferences.common
        val selected = preferences.forScope(ConfigurationScope.Personal)
        return Settings(
            providers = configuration.providers,
            assistants = configuration.assistants,
            assistantTags = configuration.assistantTags,
            searchServices = configuration.searchServices,
            searchCommonOptions = configuration.searchCommonOptions,
            mcpServers = configuration.mcpServers,
            ttsProviders = configuration.ttsProviders,
            asrProviders = configuration.asrProviders,
            modeInjections = configuration.modeInjections,
            quickMessages = configuration.quickMessages,
            titlePrompt = configuration.titlePrompt,
            suggestionPrompt = configuration.suggestionPrompt,
            compressPrompt = configuration.compressPrompt,
            webDavConfig = configuration.webDavConfig,
            s3Config = configuration.s3Config,
            dynamicColor = common.dynamicColor,
            themeId = common.themeId,
            customThemes = common.customThemes,
            defaultTTSPlaybackSpeed = common.defaultTTSPlaybackSpeed,
            backupReminderConfig = common.backupReminderConfig,
            displaySetting = common.display.project(configuration.profile),
            favoriteModels = selected.favoriteModels,
            chatModelId = selected.chatModelId ?: DEFAULT_AUTO_MODEL_ID,
            fastModelId = selected.fastModelId ?: DEFAULT_AUTO_MODEL_ID,
            titleModelId = selected.titleModelId,
            imageGenerationModelId = selected.imageGenerationModelId ?: DEFAULT_AUTO_MODEL_ID,
            enableSuggestion = selected.enableSuggestion,
            suggestionModelId = selected.suggestionModelId,
            attachmentInspectionModelId = selected.attachmentInspectionModelId,
            compressModelId = selected.compressModelId ?: DEFAULT_AUTO_MODEL_ID,
            assistantId = selected.assistantId ?: DEFAULT_ASSISTANT_ID,
            selectedSearchServiceId = selected.selectedSearchServiceId,
            selectedTTSProviderId = selected.selectedTTSProviderId ?: DEFAULT_SYSTEM_TTS_ID,
            selectedASRProviderId = selected.selectedASRProviderId,
            launchCount = internalState.launchCount,
            ignoredUpdateVersion = internalState.ignoredUpdateVersion,
            pendingAssistantDeletions = internalState.pendingAssistantDeletions,
        )
    }

    fun withPersonalSettings(settings: Settings): UserSettingsDocument {
        require(!settings.init) { "dummy_settings_cannot_be_persisted" }
        val personal = ResourceSelections(
            favoriteModels = settings.favoriteModels,
            chatModelId = settings.chatModelId,
            fastModelId = settings.fastModelId,
            titleModelId = settings.titleModelId,
            imageGenerationModelId = settings.imageGenerationModelId,
            enableSuggestion = settings.enableSuggestion,
            suggestionModelId = settings.suggestionModelId,
            attachmentInspectionModelId = settings.attachmentInspectionModelId,
            compressModelId = settings.compressModelId,
            assistantId = settings.assistantId,
            selectedSearchServiceId = settings.selectedSearchServiceId,
            selectedTTSProviderId = settings.selectedTTSProviderId,
            selectedASRProviderId = settings.selectedASRProviderId,
        )
        return copy(
            configuration = UserConfiguration(
                providers = settings.providers,
                assistants = settings.assistants,
                assistantTags = settings.assistantTags,
                searchServices = settings.searchServices,
                searchCommonOptions = settings.searchCommonOptions,
                mcpServers = settings.mcpServers,
                ttsProviders = settings.ttsProviders,
                asrProviders = settings.asrProviders,
                modeInjections = settings.modeInjections,
                quickMessages = settings.quickMessages,
                titlePrompt = settings.titlePrompt,
                suggestionPrompt = settings.suggestionPrompt,
                compressPrompt = settings.compressPrompt,
                webDavConfig = settings.webDavConfig,
                s3Config = settings.s3Config,
                profile = UserProfile(settings.displaySetting.userAvatar, settings.displaySetting.userNickname),
            ),
            preferences = preferences.copy(
                common = CommonUserPreferences(
                    dynamicColor = settings.dynamicColor,
                    themeId = settings.themeId,
                    customThemes = settings.customThemes,
                    defaultTTSPlaybackSpeed = settings.defaultTTSPlaybackSpeed,
                    backupReminderConfig = settings.backupReminderConfig,
                    display = DisplayPreferences(
                        useAppIconStyleLoadingIndicator = settings.displaySetting.useAppIconStyleLoadingIndicator,
                        showUserAvatar = settings.displaySetting.showUserAvatar,
                        showAssistantBubble = settings.displaySetting.showAssistantBubble,
                        bubbleOpacity = settings.displaySetting.bubbleOpacity,
                        showModelIcon = settings.displaySetting.showModelIcon,
                        showModelName = settings.displaySetting.showModelName,
                        showDateTimeInMessage = settings.displaySetting.showDateTimeInMessage,
                        showTokenUsage = settings.displaySetting.showTokenUsage,
                        showThinkingContent = settings.displaySetting.showThinkingContent,
                        autoCloseThinking = settings.displaySetting.autoCloseThinking,
                        showUpdates = settings.displaySetting.showUpdates,
                        updateCheckDisabledUntilEpochMillis = settings.displaySetting.updateCheckDisabledUntilEpochMillis,
                        showMessageJumper = settings.displaySetting.showMessageJumper,
                        messageJumperOnLeft = settings.displaySetting.messageJumperOnLeft,
                        fontSizeRatio = settings.displaySetting.fontSizeRatio,
                        enableMessageGenerationHapticEffect = settings.displaySetting.enableMessageGenerationHapticEffect,
                        enableMessageGenerationSoundEffect = settings.displaySetting.enableMessageGenerationSoundEffect,
                        skipCropImage = settings.displaySetting.skipCropImage,
                        enableNotificationOnMessageGeneration = settings.displaySetting.enableNotificationOnMessageGeneration,
                        enableLiveUpdateNotification = settings.displaySetting.enableLiveUpdateNotification,
                        codeBlockAutoWrap = settings.displaySetting.codeBlockAutoWrap,
                        codeBlockAutoCollapse = settings.displaySetting.codeBlockAutoCollapse,
                        showLineNumbers = settings.displaySetting.showLineNumbers,
                        ttsOnlyReadQuoted = settings.displaySetting.ttsOnlyReadQuoted,
                        ttsOnlyReadOutsideBrackets = settings.displaySetting.ttsOnlyReadOutsideBrackets,
                        autoPlayTTSAfterGeneration = settings.displaySetting.autoPlayTTSAfterGeneration,
                        ttsToolSequentialPlayback = settings.displaySetting.ttsToolSequentialPlayback,
                        pasteLongTextAsFile = settings.displaySetting.pasteLongTextAsFile,
                        pasteLongTextThreshold = settings.displaySetting.pasteLongTextThreshold,
                        sendOnEnter = settings.displaySetting.sendOnEnter,
                        enableAutoScroll = settings.displaySetting.enableAutoScroll,
                        enableLatexRendering = settings.displaySetting.enableLatexRendering,
                        enableBlurEffect = settings.displaySetting.enableBlurEffect,
                        chatFontFamily = settings.displaySetting.chatFontFamily,
                        chatCustomFontPath = settings.displaySetting.chatCustomFontPath,
                        chatCustomFontName = settings.displaySetting.chatCustomFontName,
                        enableVolumeKeyScroll = settings.displaySetting.enableVolumeKeyScroll,
                        volumeKeyScrollRatio = settings.displaySetting.volumeKeyScrollRatio,
                    ),
                ),
                scopes = preferences.scopes.filterNot { it.scope == ConfigurationScope.Personal } +
                    ScopedUserPreferences(ConfigurationScope.Personal, personal),
            ),
            internalState = SettingsInternalState(
                launchCount = settings.launchCount,
                ignoredUpdateVersion = settings.ignoredUpdateVersion,
                pendingAssistantDeletions = settings.pendingAssistantDeletions,
            ),
        )
    }

    companion object {
        const val SCHEMA_VERSION = 1

        fun empty(preferences: UserPreferences = UserPreferences()): UserSettingsDocument = UserSettingsDocument(
            schemaVersion = SCHEMA_VERSION,
            configuration = UserConfiguration(),
            preferences = preferences,
            internalState = SettingsInternalState(),
        )
    }
}

private fun ResourceSelections.references(): List<ConfigurationReference> =
    favoriteModels + listOfNotNull(
        chatModelId,
        fastModelId,
        titleModelId,
        imageGenerationModelId,
        suggestionModelId,
        attachmentInspectionModelId,
        compressModelId,
        assistantId,
        selectedSearchServiceId,
        selectedTTSProviderId,
        selectedASRProviderId,
    )
