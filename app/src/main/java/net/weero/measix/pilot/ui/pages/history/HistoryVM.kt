package net.weero.measix.pilot.ui.pages.history

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import net.weero.measix.pilot.service.ConfigurationQueryService
import net.weero.measix.pilot.service.ConversationApplicationService
import net.weero.measix.pilot.service.ConversationQueryService
import net.weero.measix.pilot.service.ConversationSummary

private const val TAG = "HistoryVM"

class HistoryVM internal constructor(
    private val conversationQueryService: ConversationQueryService,
    configurationQueryService: ConfigurationQueryService,
    private val conversationApplicationService: ConversationApplicationService,
) : ViewModel() {
    val conversations = configurationQueryService.observeAssistantCatalog().flatMapLatest { catalog ->
        val reference = catalog?.selected?.reference
        if (reference == null) flowOf(emptyList()) else conversationQueryService.conversationsOfAssistant(reference)
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
