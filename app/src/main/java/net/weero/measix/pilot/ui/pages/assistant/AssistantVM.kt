package net.weero.measix.pilot.ui.pages.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.EffectiveSettingsSnapshot
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.SettingsLockedException
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.Avatar
import net.weero.measix.pilot.data.repository.MemoryRepository
import net.weero.measix.pilot.service.AssistantManagementService
import net.weero.measix.pilot.service.ArtifactUseCase
import kotlin.uuid.Uuid

class AssistantVM(
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val assistantManagementService: AssistantManagementService,
    private val artifactUseCase: ArtifactUseCase,
) : ViewModel() {
    private val _lockedChanges = MutableSharedFlow<SettingsLockedException>(extraBufferCapacity = 1)
    val lockedChanges = _lockedChanges.asSharedFlow()
    internal val effectiveSettings: StateFlow<EffectiveSettingsSnapshot> = settingsStore.effectiveSettings
    val settings: StateFlow<Settings> = settingsStore.effectiveSettings.map { it.settings }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    fun reorderAssistants(orderedIds: List<Uuid>) {
        updateSettings {
            artifactUseCase.updateSettingsReferences { current ->
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
        updateSettings {
            artifactUseCase.updateSettingsReferences { current ->
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
        updateSettings {
            artifactUseCase.updateSettingsReferences { current ->
                current.copy(
                    assistants = current.assistants.plus(assistant),
                )
            }
        }
    }

    fun removeAssistant(assistant: Assistant) {
        updateSettings { assistantManagementService.deleteAssistant(assistant.id) }
    }

    fun copyAssistant(assistant: Assistant) {
        updateSettings {
            val copiedAssistant = assistant.copy(
                id = Uuid.random(),
                name = "${assistant.name} (Clone)",
                avatar = if(assistant.avatar is Avatar.Image) Avatar.Dummy else assistant.avatar,
                // Clone 重置子助手授权：不继承全局可见和允许列表
                isSubAssistantGloballyVisible = false,
                allowedSubAssistantIds = emptySet(),
            )
            artifactUseCase.updateSettingsReferences { current ->
                current.copy(
                    assistants = current.assistants.plus(copiedAssistant),
                )
            }
        }
    }

    private fun updateSettings(update: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                update()
            } catch (error: SettingsLockedException) {
                _lockedChanges.emit(error)
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
