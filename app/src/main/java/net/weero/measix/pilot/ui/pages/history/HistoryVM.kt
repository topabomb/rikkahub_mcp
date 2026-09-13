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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import net.weero.measix.pilot.service.ConfigurationQueryService
import net.weero.measix.pilot.service.ConversationApplicationService
import net.weero.measix.pilot.service.ConversationQueryService
import net.weero.measix.pilot.service.ConversationSummary
import net.weero.measix.pilot.service.AssistantCatalogReadState
import net.weero.measix.pilot.utils.userVisibleDiagnostic

private const val TAG = "HistoryVM"

class HistoryVM internal constructor(
    private val conversationQueryService: ConversationQueryService,
    configurationQueryService: ConfigurationQueryService,
    private val conversationApplicationService: ConversationApplicationService,
) : ViewModel() {
    internal val assistantCatalog = configurationQueryService.observeAssistantCatalog()
        .stateIn(viewModelScope, SharingStarted.Eagerly, AssistantCatalogReadState.Loading)
    private val _readFailure = MutableStateFlow<String?>(null)
    internal val readFailure = _readFailure.asStateFlow()
    val conversations = assistantCatalog.flatMapLatest { state ->
        val reference = (state as? AssistantCatalogReadState.Available)?.catalog?.selected?.reference
        if (reference == null) flowOf(emptyList()) else conversationQueryService.conversationsOfAssistant(reference)
    }.onEach { _readFailure.value = null }.catch { error ->
        if (error is CancellationException) throw error
        Log.e(TAG, "Conversation history query failed", error)
        _readFailure.value = error.userVisibleDiagnostic()
        emit(emptyList())
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
