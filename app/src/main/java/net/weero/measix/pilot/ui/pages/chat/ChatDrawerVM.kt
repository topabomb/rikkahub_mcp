package net.weero.measix.pilot.ui.pages.chat

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.weero.measix.pilot.R
import net.weero.measix.pilot.service.ConfigurationQueryService
import net.weero.measix.pilot.data.enterprise.RealmSelection
import me.rerere.common.configuration.ConfigurationReference
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import net.weero.measix.pilot.service.ConversationSummary
import net.weero.measix.pilot.service.ConversationFolderAccess
import net.weero.measix.pilot.service.ConversationFolderDirectory
import net.weero.measix.pilot.service.ConversationApplicationService
import net.weero.measix.pilot.service.ConversationActivity
import net.weero.measix.pilot.service.ConversationQueryService
import net.weero.measix.pilot.utils.toLocalString
import java.time.LocalDate
import java.time.ZoneId
import kotlin.uuid.Uuid

class ChatDrawerVM internal constructor(
    private val context: Application,
    configurationQueryService: ConfigurationQueryService,
    private val conversationQueryService: ConversationQueryService,
    private val conversationApplicationService: ConversationApplicationService,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    internal val assistantCatalog = configurationQueryService.observeAssistantCatalog()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val selectedFolder = MutableStateFlow<Pair<ConversationFolderAccess, Uuid>?>(null)
    private val assistantTarget = assistantCatalog.map { catalog ->
        catalog?.selected?.reference?.let { ConversationFolderAccess(catalog.selection, it) }
    }.distinctUntilChanged().onEach {
        selectedFolder.value = null
        saveScrollPosition(0, 0)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val selectedFolderId: StateFlow<Uuid?> = combine(assistantTarget, selectedFolder) { target, selected ->
        selected?.takeIf { it.first == target }?.second
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    internal suspend fun selectAssistant(selection: RealmSelection, assistantId: ConfigurationReference, createNew: Boolean) =
        conversationApplicationService.selectAssistantRequest(selection, assistantId, createNew)

    internal suspend fun moveToAssistant(conversation: ConversationSummary, assistantId: ConfigurationReference, selectForNewChats: Boolean) =
        conversationApplicationService.moveToAssistant(
            net.weero.measix.pilot.service.ConversationAssistantTarget(conversation.commandTarget, conversation.assistantId), assistantId, selectForNewChats)

    val folderDirectory: StateFlow<ConversationFolderDirectory?> = assistantTarget
        .flatMapLatest { if (it == null) flowOf(null) else conversationQueryService.foldersOfAssistant(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val conversationActivities: StateFlow<Map<Uuid, Set<ConversationActivity>>> =
        conversationQueryService.conversationActivities()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val conversations: Flow<PagingData<ConversationListItem>> =
        assistantTarget.flatMapLatest { target ->
            if (target == null) flowOf(PagingData.empty<ConversationSummary>()) else selectedFolder
                .map { selected -> selected?.takeIf { it.first == target }?.second }
                .distinctUntilChanged()
                .flatMapLatest { folderId ->
                    if (folderId == null) conversationQueryService.unfiledPaging(target)
                    else conversationQueryService.folderPaging(target, folderId)
                }
        }
            .map { pagingData ->
                pagingData
                    .map { ConversationListItem.Item(it) }
                    .insertSeparators<ConversationListItem.Item, ConversationListItem> { before, after ->
                        when {
                            before == null && after is ConversationListItem.Item -> {
                                if (after.conversation.isPinned) {
                                    ConversationListItem.PinnedHeader
                                } else {
                                    val afterDate = after.conversation.updateAt
                                        .atZone(ZoneId.systemDefault())
                                        .toLocalDate()
                                    ConversationListItem.DateHeader(
                                        date = afterDate,
                                        label = getDateLabel(afterDate)
                                    )
                                }
                            }

                            before is ConversationListItem.Item && after is ConversationListItem.Item -> {
                                if (before.conversation.isPinned && !after.conversation.isPinned) {
                                    val afterDate = after.conversation.updateAt
                                        .atZone(ZoneId.systemDefault())
                                        .toLocalDate()
                                    ConversationListItem.DateHeader(
                                        date = afterDate,
                                        label = getDateLabel(afterDate)
                                    )
                                } else if (!after.conversation.isPinned) {
                                    val beforeDate = before.conversation.updateAt
                                        .atZone(ZoneId.systemDefault())
                                        .toLocalDate()
                                    val afterDate = after.conversation.updateAt
                                        .atZone(ZoneId.systemDefault())
                                        .toLocalDate()

                                    if (beforeDate != afterDate) {
                                        ConversationListItem.DateHeader(
                                            date = afterDate,
                                            label = getDateLabel(afterDate)
                                        )
                                    } else {
                                        null
                                    }
                                } else {
                                    null
                                }
                            }

                            else -> null
                        }
                    }
            }
            .cachedIn(viewModelScope)

    val scrollIndex: Int get() = savedStateHandle["scrollIndex"] ?: 0
    val scrollOffset: Int get() = savedStateHandle["scrollOffset"] ?: 0

    fun saveScrollPosition(index: Int, offset: Int) {
        savedStateHandle["scrollIndex"] = index
        savedStateHandle["scrollOffset"] = offset
    }

    fun selectFolder(access: ConversationFolderAccess?, folderId: Uuid?) {
        if (access == null || access != assistantTarget.value) return
        selectedFolder.value = folderId?.let { access to it }
    }

    suspend fun createFolder(access: ConversationFolderAccess, name: String) =
        conversationApplicationService.createFolder(access, name)

    suspend fun renameFolder(access: ConversationFolderAccess, folderId: Uuid, name: String) =
        conversationApplicationService.renameFolder(access, folderId, name)

    suspend fun deleteFolder(access: ConversationFolderAccess, folderId: Uuid) {
        conversationApplicationService.deleteFolder(access, folderId)
        if (selectedFolderId.value == folderId) selectedFolder.value = null
    }

    suspend fun moveConversationToFolder(access: ConversationFolderAccess, conversation: ConversationSummary, folderId: Uuid?) =
        conversationApplicationService.moveToFolder(access, conversation, folderId)

    private fun getDateLabel(date: LocalDate): String {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        return when (date) {
            today -> context.getString(R.string.chat_page_today)
            yesterday -> context.getString(R.string.chat_page_yesterday)
            else -> date.toLocalString(date.year != today.year)
        }
    }
}
