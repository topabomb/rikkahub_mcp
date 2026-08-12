package net.weero.measix.pilot.ui.pages.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.Avatar
import net.weero.measix.pilot.data.repository.MemoryRepository
import net.weero.measix.pilot.service.AssistantManagementService
import kotlin.uuid.Uuid

class AssistantVM(
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val assistantManagementService: AssistantManagementService,
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    fun reorderAssistants(orderedIds: List<Uuid>) {
        viewModelScope.launch {
            settingsStore.updateAtomic { current ->
                val requestedIds = orderedIds.toSet()
                val assistantsById = current.assistants.associateBy { it.id }
                current.copy(
                    assistants = orderedIds.mapNotNull(assistantsById::get) +
                        current.assistants.filter { it.id !in requestedIds },
                )
            }
        }
    }

    fun reorderAssistantTags(orderedIds: List<Uuid>) {
        viewModelScope.launch {
            settingsStore.updateAtomic { current ->
                val requestedIds = orderedIds.toSet()
                val tagsById = current.assistantTags.associateBy { it.id }
                current.copy(
                    assistantTags = orderedIds.mapNotNull(tagsById::get) +
                        current.assistantTags.filter { it.id !in requestedIds },
                )
            }
        }
    }

    fun addAssistant(assistant: Assistant) {
        viewModelScope.launch {
            settingsStore.updateAtomic { current ->
                current.copy(
                    assistants = current.assistants.plus(assistant),
                )
            }
        }
    }

    fun removeAssistant(assistant: Assistant) {
        viewModelScope.launch {
            // UI 删除复用 AssistantManagementService，避免两套清理逻辑
            assistantManagementService.deleteAssistant(assistant.id)
        }
    }

    fun copyAssistant(assistant: Assistant) {
        viewModelScope.launch {
            val copiedAssistant = assistant.copy(
                id = Uuid.random(),
                name = "${assistant.name} (Clone)",
                avatar = if(assistant.avatar is Avatar.Image) Avatar.Dummy else assistant.avatar,
                // Clone 重置子助手授权：不继承全局可见和允许列表
                isSubAssistantGloballyVisible = false,
                allowedSubAssistantIds = emptySet(),
            )
            settingsStore.updateAtomic { current ->
                current.copy(
                    assistants = current.assistants.plus(copiedAssistant),
                )
            }
        }
    }

    fun getMemories(assistant: Assistant) =
        if (assistant.useGlobalMemory) {
            memoryRepository.getGlobalMemoriesFlow()
        } else {
            memoryRepository.getMemoriesOfAssistantFlow(assistant.id.toString())
        }
}
