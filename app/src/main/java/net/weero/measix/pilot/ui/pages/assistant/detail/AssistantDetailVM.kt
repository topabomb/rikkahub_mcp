package net.weero.measix.pilot.ui.pages.assistant.detail

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.getChatModel
import net.weero.measix.pilot.data.db.entity.WorkspaceEntity
import net.weero.measix.pilot.service.ArtifactUseCase
import net.weero.measix.pilot.data.files.SkillManager
import net.weero.measix.pilot.data.files.SkillMetadata
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.AssistantMemory
import net.weero.measix.pilot.data.model.Avatar
import net.weero.measix.pilot.data.model.Tag
import net.weero.measix.pilot.data.repository.MemoryRepository
import net.weero.measix.pilot.data.repository.WorkspaceRepository
import kotlin.uuid.Uuid

private const val TAG = "AssistantDetailVM"

class AssistantDetailVM(
    private val id: String,
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val artifactUseCase: ArtifactUseCase,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
) : ViewModel() {
    private val assistantId = Uuid.parse(id)

    private val _skills = MutableStateFlow<List<SkillMetadata>>(emptyList())
    val skills = _skills.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _skills.value = skillManager.listSkills()
        }
    }

    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    val mcpServerConfigs = settingsStore
        .settingsFlow.map { settings ->
            settings.mcpServers
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val assistant: StateFlow<Assistant> = settingsStore
        .settingsFlow
        .map { settings ->
            settings.assistants.find { it.id == assistantId } ?: Assistant()
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = Assistant()
        )

    val memories = assistant
        .flatMapLatest { currentAssistant ->
            if (currentAssistant.useGlobalMemory) {
                memoryRepository.getGlobalMemoriesFlow()
            } else {
                memoryRepository.getMemoriesOfAssistantFlow(assistantId.toString())
            }
        }
        .stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val providers = settingsStore
        .settingsFlow
        .map { settings ->
            settings.providers
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    /**
     * 显式绑定的 Chat Model 必须有效；未绑定模型的子助手会在调用时继承 caller 的 RunSpec，
     * 因此不能在 Target 配置页误报为必然不可调用。
     */
    val hasValidChatModel: StateFlow<Boolean> =
        kotlinx.coroutines.flow.combine(settings, assistant) { settingsValue, assistantValue ->
            assistantValue.chatModelId == null || settingsValue.getChatModel(assistantValue) != null
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = true
        )

    val tags = settingsStore
        .settingsFlow
        .map { settings ->
            settings.assistantTags
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val workspaces: StateFlow<List<WorkspaceEntity>> = workspaceRepository
        .listFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    fun updateTags(tagIds: List<Uuid>, tags: List<Tag>) {
        viewModelScope.launch {
            settingsStore.updateAtomic { currentSettings ->
                currentSettings.copy(
                    assistantTags = tags,
                    assistants = currentSettings.assistants.map { currentAssistant ->
                        if (currentAssistant.id == assistantId) {
                            currentAssistant.copy(tags = tagIds.toList())
                        } else {
                            currentAssistant
                        }
                    },
                )
            }
            Log.d(TAG, "updateTags: ${tagIds.joinToString(",")}")
            cleanupUnusedTags()
        }
    }

    fun cleanupUnusedTags() {
        viewModelScope.launch {
            settingsStore.updateAtomic { currentSettings ->
                val validTagIds = currentSettings.assistantTags.map { it.id }.toSet()

                // 清理 assistant 中的无效 tag id
                val cleanedAssistants = currentSettings.assistants.map { assistant ->
                    val validTags = assistant.tags.filter { tagId ->
                        validTagIds.contains(tagId)
                    }
                    if (validTags.size != assistant.tags.size) {
                        assistant.copy(tags = validTags)
                    } else {
                        assistant
                    }
                }

                // 获取清理后的 assistant 中使用的 tag id
                val usedTagIds = cleanedAssistants.flatMap { it.tags }.toSet()

                // 清理未使用的 tags
                val cleanedTags = currentSettings.assistantTags.filter { tag ->
                    usedTagIds.contains(tag.id)
                }

                // 检查是否需要更新
                val needUpdateAssistants = cleanedAssistants != currentSettings.assistants
                val needUpdateTags = cleanedTags.size != currentSettings.assistantTags.size

                if (needUpdateAssistants || needUpdateTags) {
                    currentSettings.copy(
                        assistants = cleanedAssistants,
                        assistantTags = cleanedTags,
                    )
                } else {
                    currentSettings
                }
            }
        }
    }

    fun update(assistant: Assistant) {
        val pageSnapshot = this.assistant.value
        viewModelScope.launch {
            updateAndAwait(pageSnapshot, assistant)
        }
    }

    suspend fun importAvatar(uri: Uri) {
        importAssistantImage(uri) { current, localUri ->
            current.copy(avatar = Avatar.Image(localUri.toString()))
        }
    }

    suspend fun importBackground(uri: Uri) {
        importAssistantImage(uri) { current, localUri -> current.copy(background = localUri.toString()) }
    }

    private suspend fun importAssistantImage(uri: Uri, edit: (Assistant, Uri) -> Assistant) {
        val pageSnapshot = assistant.value
        var committedChange: Pair<Assistant, Assistant>? = null
        artifactUseCase.importSettingsImage(uri) { currentSettings, localUri ->
            val update = buildAssistantSettingsUpdate(currentSettings, pageSnapshot, edit(pageSnapshot, localUri))
            committedChange = update.change
            update.settings
        }
        collectReplacedArtifacts(committedChange)
    }

    private suspend fun updateAndAwait(pageSnapshot: Assistant, edited: Assistant) {
        var committedChange: Pair<Assistant, Assistant>? = null
        artifactUseCase.updateSettingsReferences { currentSettings ->
            val update = buildAssistantSettingsUpdate(currentSettings, pageSnapshot, edited)
            committedChange = update.change
            update.settings
        }
        collectReplacedArtifacts(committedChange)
    }

    private suspend fun collectReplacedArtifacts(change: Pair<Assistant, Assistant>?) {
        change?.let { (oldAssistant, newAssistant) ->
            if (oldAssistant.avatar != newAssistant.avatar || oldAssistant.background != newAssistant.background) {
                artifactUseCase.maintainStorage()
            }
        }
    }

    fun addMemory(memory: AssistantMemory) {
        viewModelScope.launch {
            val memoryAssistantId = if (assistant.value.useGlobalMemory) {
                MemoryRepository.GLOBAL_MEMORY_ID
            } else {
                assistantId.toString()
            }
            memoryRepository.addMemory(
                assistantId = memoryAssistantId,
                content = memory.content
            )
        }
    }

    fun updateMemory(memory: AssistantMemory) {
        viewModelScope.launch {
            val current = assistant.value
            val memoryId = if (current.useGlobalMemory) {
                MemoryRepository.GLOBAL_MEMORY_ID
            } else {
                assistantId.toString()
            }
            memoryRepository.updateContent(id = memory.id, content = memory.content, assistantId = memoryId)
        }
    }

    fun deleteMemory(memory: AssistantMemory) {
        viewModelScope.launch {
            val current = assistant.value
            val memoryId = if (current.useGlobalMemory) {
                MemoryRepository.GLOBAL_MEMORY_ID
            } else {
                assistantId.toString()
            }
            memoryRepository.deleteMemory(id = memory.id, assistantId = memoryId)
        }
    }

}

private data class AssistantSettingsUpdate(
    val settings: Settings,
    val change: Pair<Assistant, Assistant>?,
)

private fun buildAssistantSettingsUpdate(
    currentSettings: Settings,
    pageSnapshot: Assistant,
    edited: Assistant,
): AssistantSettingsUpdate {
    val oldAssistant = currentSettings.assistants.find { it.id == edited.id }
        ?: return AssistantSettingsUpdate(currentSettings, null)
    val baseline = pageSnapshot.takeIf { it.id == edited.id } ?: oldAssistant
    val mergedAssistant = mergeAssistantDelta(baseline, edited, oldAssistant)
    val subAssistantDisabled = oldAssistant.allowAsSubAssistant && !mergedAssistant.allowAsSubAssistant
    val finalAssistant = if (subAssistantDisabled) {
        mergedAssistant.copy(isSubAssistantGloballyVisible = false)
    } else {
        mergedAssistant
    }
    val assistants = currentSettings.assistants.map { candidate ->
        when {
            candidate.id == edited.id -> finalAssistant
            subAssistantDisabled && edited.id in candidate.allowedSubAssistantIds ->
                candidate.copy(allowedSubAssistantIds = candidate.allowedSubAssistantIds - edited.id)
            else -> candidate
        }
    }
    return AssistantSettingsUpdate(currentSettings.copy(assistants = assistants), oldAssistant to finalAssistant)
}

private fun <T> pickAssistantField(baseline: T, edited: T, current: T): T =
    if (edited != baseline) edited else current

/** 把页面相对其快照的字段 delta 应用到最新 Assistant，避免覆盖并发工具更新。 */
internal fun mergeAssistantDelta(
    baseline: Assistant,
    edited: Assistant,
    current: Assistant,
): Assistant {
    require(baseline.id == edited.id && edited.id == current.id)
    return current.copy(
        chatModelId = pickAssistantField(baseline.chatModelId, edited.chatModelId, current.chatModelId),
        name = pickAssistantField(baseline.name, edited.name, current.name),
        avatar = pickAssistantField(baseline.avatar, edited.avatar, current.avatar),
        useAssistantAvatar = pickAssistantField(
            baseline.useAssistantAvatar,
            edited.useAssistantAvatar,
            current.useAssistantAvatar,
        ),
        tags = pickAssistantField(baseline.tags, edited.tags, current.tags),
        systemPrompt = pickAssistantField(baseline.systemPrompt, edited.systemPrompt, current.systemPrompt),
        temperature = pickAssistantField(baseline.temperature, edited.temperature, current.temperature),
        topP = pickAssistantField(baseline.topP, edited.topP, current.topP),
        contextMessageLimit = pickAssistantField(
            baseline.contextMessageLimit,
            edited.contextMessageLimit,
            current.contextMessageLimit,
        ),
        streamOutput = pickAssistantField(baseline.streamOutput, edited.streamOutput, current.streamOutput),
        enableMemory = pickAssistantField(baseline.enableMemory, edited.enableMemory, current.enableMemory),
        useGlobalMemory = pickAssistantField(baseline.useGlobalMemory, edited.useGlobalMemory, current.useGlobalMemory),
        enableRecentChatsReference = pickAssistantField(
            baseline.enableRecentChatsReference,
            edited.enableRecentChatsReference,
            current.enableRecentChatsReference,
        ),
        messageTemplate = pickAssistantField(baseline.messageTemplate, edited.messageTemplate, current.messageTemplate),
        presetMessages = pickAssistantField(baseline.presetMessages, edited.presetMessages, current.presetMessages),
        quickMessageIds = pickAssistantField(baseline.quickMessageIds, edited.quickMessageIds, current.quickMessageIds),
        regexes = pickAssistantField(baseline.regexes, edited.regexes, current.regexes),
        reasoningLevel = pickAssistantField(baseline.reasoningLevel, edited.reasoningLevel, current.reasoningLevel),
        maxTokens = pickAssistantField(baseline.maxTokens, edited.maxTokens, current.maxTokens),
        customHeaders = pickAssistantField(baseline.customHeaders, edited.customHeaders, current.customHeaders),
        customBodies = pickAssistantField(baseline.customBodies, edited.customBodies, current.customBodies),
        mcpServers = pickAssistantField(baseline.mcpServers, edited.mcpServers, current.mcpServers),
        localTools = pickAssistantField(baseline.localTools, edited.localTools, current.localTools),
        enableWebSearch = pickAssistantField(baseline.enableWebSearch, edited.enableWebSearch, current.enableWebSearch),
        workspaceId = pickAssistantField(baseline.workspaceId, edited.workspaceId, current.workspaceId),
        background = pickAssistantField(baseline.background, edited.background, current.background),
        backgroundOpacity = pickAssistantField(
            baseline.backgroundOpacity,
            edited.backgroundOpacity,
            current.backgroundOpacity,
        ),
        useGradientBackground = pickAssistantField(
            baseline.useGradientBackground,
            edited.useGradientBackground,
            current.useGradientBackground,
        ),
        modeInjectionIds = pickAssistantField(baseline.modeInjectionIds, edited.modeInjectionIds, current.modeInjectionIds),
        enabledSkills = pickAssistantField(baseline.enabledSkills, edited.enabledSkills, current.enabledSkills),
        enableTimeReminder = pickAssistantField(
            baseline.enableTimeReminder,
            edited.enableTimeReminder,
            current.enableTimeReminder,
        ),
        allowConversationSystemPrompt = pickAssistantField(
            baseline.allowConversationSystemPrompt,
            edited.allowConversationSystemPrompt,
            current.allowConversationSystemPrompt,
        ),
        allowConversationPromptInjection = pickAssistantField(
            baseline.allowConversationPromptInjection,
            edited.allowConversationPromptInjection,
            current.allowConversationPromptInjection,
        ),
        description = pickAssistantField(baseline.description, edited.description, current.description),
        allowAsSubAssistant = pickAssistantField(
            baseline.allowAsSubAssistant,
            edited.allowAsSubAssistant,
            current.allowAsSubAssistant,
        ),
        isSubAssistantGloballyVisible = pickAssistantField(
            baseline.isSubAssistantGloballyVisible,
            edited.isSubAssistantGloballyVisible,
            current.isSubAssistantGloballyVisible,
        ),
        allowedSubAssistantIds = pickAssistantField(
            baseline.allowedSubAssistantIds,
            edited.allowedSubAssistantIds,
            current.allowedSubAssistantIds,
        ),
    )
}
