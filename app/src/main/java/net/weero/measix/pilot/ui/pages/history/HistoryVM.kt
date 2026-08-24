package net.weero.measix.pilot.ui.pages.history

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.getCurrentAssistant
import net.weero.measix.pilot.service.ConversationApplicationService
import net.weero.measix.pilot.service.ConversationQueryService
import net.weero.measix.pilot.service.ConversationSummary
import kotlin.uuid.Uuid

private const val TAG = "HistoryVM"

class HistoryVM(
    private val conversationQueryService: ConversationQueryService,
    private val settingsStore: SettingsStore,
    private val conversationApplicationService: ConversationApplicationService,
) : ViewModel() {
    val assistant = settingsStore.settingsFlow
        .map { it.getCurrentAssistant() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val conversations = assistant.flatMapLatest { assistant ->
        conversationQueryService.conversationsOfAssistant(assistant?.id ?: Uuid.random())
    }.catch {
        Log.e(TAG, "Error: ${it.message}")
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    suspend fun deleteForUndo(conversationId: Uuid): ConversationApplicationService.RestoreToken =
        conversationApplicationService.deleteForUndo(conversationId)

    fun deleteAllConversations() {
        val assistant = assistant.value ?: return
        viewModelScope.launch {
            conversationApplicationService.deleteOfAssistant(assistant.id)
        }
    }

    fun togglePinStatus(conversationId: Uuid) {
        viewModelScope.launch {
            conversationApplicationService.togglePin(conversationId)
        }
    }

    fun getPinnedConversations(): Flow<List<ConversationSummary>> =
        conversationQueryService.pinnedConversations()

    suspend fun restoreConversation(token: ConversationApplicationService.RestoreToken) =
        conversationApplicationService.restore(token)

    fun discardRestoreToken(token: ConversationApplicationService.RestoreToken) {
        conversationApplicationService.discardRestoreToken(token)
    }
}
