package net.weero.measix.pilot.ui.pages.history

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.getCurrentAssistant
import net.weero.measix.pilot.service.ConversationApplicationService
import net.weero.measix.pilot.service.ConversationQueryService
import net.weero.measix.pilot.service.ConversationSummary

private const val TAG = "HistoryVM"

class HistoryVM(
    private val conversationQueryService: ConversationQueryService,
    private val settingsStore: SettingsStore,
    private val conversationApplicationService: ConversationApplicationService,
) : ViewModel() {
    val assistant = settingsStore.effectiveSettings.map { it.settings }
        .map { it.getCurrentAssistant() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val conversations = assistant.flatMapLatest { assistant ->
        if (assistant == null) flowOf(emptyList()) else conversationQueryService.conversationsOfAssistant(assistant.id)
    }.catch {
        if (it is CancellationException) throw it
        Log.e(TAG, "Error: ${it.message}")
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    suspend fun deleteForUndo(conversation: ConversationSummary): ConversationApplicationService.RestoreToken =
        conversationApplicationService.deleteForUndo(conversation.commandTarget)

    suspend fun deleteConversations(conversations: List<ConversationSummary>) =
        conversationApplicationService.deleteConversations(conversations.map { it.commandTarget })

    suspend fun togglePinStatus(conversation: ConversationSummary) =
        conversationApplicationService.togglePin(conversation.commandTarget)

    fun getPinnedConversations(): Flow<List<ConversationSummary>> =
        conversationQueryService.pinnedConversations()

    suspend fun restoreConversation(token: ConversationApplicationService.RestoreToken) =
        conversationApplicationService.restore(token)

    fun discardRestoreToken(token: ConversationApplicationService.RestoreToken) {
        conversationApplicationService.discardRestoreToken(token)
    }
}
