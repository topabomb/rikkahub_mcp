package net.weero.measix.pilot.ui.pages.subassistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.getSubAssistantCallMetadata
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.service.ConversationSessionRegistry
import kotlin.uuid.Uuid

data class SubAssistantDetailLink(
    val metadata: SubAssistantCallMetadata,
    val request: String,
    val childConversationId: Uuid,
    val childTaskMessageId: Uuid,
    val targetAssistantId: Uuid,
)

internal sealed interface SubAssistantDetailLinkResult {
    data class Ready(val link: SubAssistantDetailLink) : SubAssistantDetailLinkResult
    data object Unavailable : SubAssistantDetailLinkResult
}

internal fun resolveSubAssistantDetailLink(
    master: Conversation,
    runId: String,
    json: Json,
): SubAssistantDetailLinkResult {
    if (master.parentConversationId != null || runId.isBlank()) {
        return SubAssistantDetailLinkResult.Unavailable
    }

    val matches = buildList {
        master.messageNodes.forEach { node ->
            node.messages.forEach { message ->
                message.parts.filterIsInstance<UIMessagePart.Tool>().forEach { tool ->
                    if (tool.toolName != "assistant_call") return@forEach
                    val metadata = tool.getSubAssistantCallMetadata(json) ?: return@forEach
                    if (metadata.runId == runId) add(tool to metadata)
                }
            }
        }
    }
    if (matches.size != 1) return SubAssistantDetailLinkResult.Unavailable

    val (tool, metadata) = matches.single()
    val childConversationId = metadata.childConversationId?.let {
        runCatching { Uuid.parse(it) }.getOrNull()
    } ?: return SubAssistantDetailLinkResult.Unavailable
    val childTaskMessageId = metadata.childTaskNodeId?.let {
        runCatching { Uuid.parse(it) }.getOrNull()
    } ?: return SubAssistantDetailLinkResult.Unavailable
    val targetAssistantId = runCatching { Uuid.parse(metadata.targetAssistantId) }.getOrNull()
        ?: return SubAssistantDetailLinkResult.Unavailable
    val request = runCatching {
        val input = json.parseToJsonElement(tool.input).jsonObject
        (input["request"] ?: input["task"])?.jsonPrimitive?.contentOrNull.orEmpty()
    }.getOrDefault("")

    return SubAssistantDetailLinkResult.Ready(
        SubAssistantDetailLink(
            metadata = metadata,
            request = request,
            childConversationId = childConversationId,
            childTaskMessageId = childTaskMessageId,
            targetAssistantId = targetAssistantId,
        )
    )
}

internal fun resolveSubAssistantTimeline(
    masterConversationId: Uuid,
    link: SubAssistantDetailLink,
    child: Conversation,
): List<MessageNode>? {
    if (child.id != link.childConversationId ||
        child.parentConversationId != masterConversationId ||
        child.assistantId != link.targetAssistantId
    ) {
        return null
    }

    val startIndex = child.messageNodes.indexOfFirst { node ->
        node.messages.getOrNull(node.selectIndex)?.let { selected ->
            selected.id == link.childTaskMessageId && selected.role == MessageRole.USER
        } == true
    }
    if (startIndex < 0) return null

    val endExclusive = ((startIndex + 1) until child.messageNodes.size)
        .firstOrNull { index ->
            child.messageNodes[index].messages
                .getOrNull(child.messageNodes[index].selectIndex)?.role == MessageRole.USER
        } ?: child.messageNodes.size
    return child.messageNodes.subList(startIndex + 1, endExclusive)
}

sealed interface SubAssistantDetailUiState {
    data object Loading : SubAssistantDetailUiState
    data object Unavailable : SubAssistantDetailUiState
    data class Ready(
        val link: SubAssistantDetailLink,
        val child: Conversation,
        val timeline: List<MessageNode>,
    ) : SubAssistantDetailUiState
}

class SubAssistantDetailVM(
    private val masterConversationId: String,
    private val runId: String,
    private val conversationRepository: ConversationRepository,
    settingsStore: SettingsStore,
    private val sessionRegistry: ConversationSessionRegistry,
    private val json: Json,
) : ViewModel() {
    private val _uiState = MutableStateFlow<SubAssistantDetailUiState>(SubAssistantDetailUiState.Loading)
    val uiState: StateFlow<SubAssistantDetailUiState> = _uiState.asStateFlow()
    val settings = settingsStore.settingsFlow

    init {
        loadValidatedRun()
    }

    private fun loadValidatedRun() {
        viewModelScope.launch {
            val masterId = runCatching { Uuid.parse(masterConversationId) }.getOrNull()
                ?: return@launch markUnavailable()
            val activeMaster = sessionRegistry.getSession(masterId)
            if (activeMaster != null) {
                activeMaster.state.collectLatest { master ->
                    collectValidatedChild(masterId, master)
                }
            } else {
                val master = conversationRepository.getConversationById(masterId)
                    ?: return@launch markUnavailable()
                collectValidatedChild(masterId, master)
            }
        }
    }

    private suspend fun collectValidatedChild(masterId: Uuid, master: Conversation) {
        val link = when (val result = resolveSubAssistantDetailLink(master, runId, json)) {
            is SubAssistantDetailLinkResult.Ready -> result.link
            SubAssistantDetailLinkResult.Unavailable -> return markUnavailable()
        }
        val activeChild = sessionRegistry.getSession(link.childConversationId)
        if (activeChild != null) {
            activeChild.state.collect { child -> updateReady(masterId, link, child) }
        } else {
            val child = conversationRepository.getConversationById(link.childConversationId)
                ?: return markUnavailable()
            updateReady(masterId, link, child)
        }
    }

    private fun updateReady(masterId: Uuid, link: SubAssistantDetailLink, child: Conversation) {
        val timeline = resolveSubAssistantTimeline(masterId, link, child)
            ?: return markUnavailable()
        _uiState.value = SubAssistantDetailUiState.Ready(link, child, timeline)
    }

    private fun markUnavailable() {
        _uiState.value = SubAssistantDetailUiState.Unavailable
    }
}
