package net.weero.measix.pilot.ui.pages.share.handler

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.service.ConfigurationQueryService
import net.weero.measix.pilot.service.ConversationApplicationService

class ShareHandlerVM internal constructor(
    text: String,
    queries: ConfigurationQueryService,
    private val conversations: ConversationApplicationService,
) : ViewModel() {
    val shareText = text
    internal val catalog = queries.observeAssistantCatalog()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    internal suspend fun newDraft(selection: RealmSelection, assistant: ConfigurationReference) =
        conversations.newDraftRequest(selection, assistant)
}
