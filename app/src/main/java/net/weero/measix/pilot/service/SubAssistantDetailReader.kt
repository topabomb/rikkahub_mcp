package net.weero.measix.pilot.service

import me.rerere.common.configuration.ConfigurationReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.getSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.parseRuntimeErrorDetailFromToolOutput
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.service.runtime.ConversationPresentationSnapshot
import net.weero.measix.pilot.utils.JsonInstant
import kotlin.uuid.Uuid

data class SubAssistantDetailLink(
    val metadata: SubAssistantCallMetadata,
    val request: String,
    val childConversationId: Uuid,
    val childTaskMessageId: Uuid,
    val targetAssistantId: ConfigurationReference,
    val failureDetail: String? = null,
)

internal fun mergeLiveSubAssistantDetailLink(
    previous: SubAssistantDetailLink,
    incoming: SubAssistantDetailLink,
): SubAssistantDetailLink = previous.copy(
    metadata = incoming.metadata,
    failureDetail = incoming.failureDetail ?: previous.failureDetail,
)

internal sealed interface SubAssistantDetailLinkResult {
    data class Ready(val link: SubAssistantDetailLink) : SubAssistantDetailLinkResult
    data object Pending : SubAssistantDetailLinkResult
    data object Unavailable : SubAssistantDetailLinkResult
}

internal fun resolveSubAssistantDetailLink(
    master: ConversationPresentationSnapshot,
    runId: String,
    json: Json,
): SubAssistantDetailLinkResult {
    if (master.header.parentConversationId != null || runId.isBlank()) {
        return SubAssistantDetailLinkResult.Unavailable
    }

    val matches = buildList {
        master.nodes.forEach { node ->
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
    fun missingLink(): SubAssistantDetailLinkResult =
        if (metadata.state.isTerminal()) {
            SubAssistantDetailLinkResult.Unavailable
        } else {
            SubAssistantDetailLinkResult.Pending
        }
    val childConversationIdRaw = metadata.childConversationId ?: return missingLink()
    val childConversationId = runCatching { Uuid.parse(childConversationIdRaw) }.getOrNull()
        ?: return SubAssistantDetailLinkResult.Unavailable
    val childTaskMessageIdRaw = metadata.childTaskNodeId ?: return missingLink()
    val childTaskMessageId = runCatching { Uuid.parse(childTaskMessageIdRaw) }.getOrNull()
        ?: return SubAssistantDetailLinkResult.Unavailable
    val targetAssistantId = runCatching { ConfigurationReference.parse(metadata.targetAssistantId) }.getOrNull()
        ?: return SubAssistantDetailLinkResult.Unavailable
    val request = runCatching {
        val input = json.parseToJsonElement(tool.input).jsonObject
            input["request"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }.getOrDefault("")

    return SubAssistantDetailLinkResult.Ready(
        SubAssistantDetailLink(
            metadata = metadata,
            request = request,
            childConversationId = childConversationId,
            childTaskMessageId = childTaskMessageId,
            targetAssistantId = targetAssistantId,
            failureDetail = parseRuntimeErrorDetailFromToolOutput(tool, json),
        )
    )
}

internal fun resolveSubAssistantTimeline(
    masterConversationId: Uuid,
    link: SubAssistantDetailLink,
    child: ConversationPresentationSnapshot,
): List<MessageNode>? {
    if (child.conversationId != link.childConversationId ||
        child.header.parentConversationId != masterConversationId ||
        child.header.assistantId != link.targetAssistantId
    ) {
        return null
    }

    val nodes = child.nodes
    val startIndex = nodes.indexOfFirst { node ->
        node.messages.getOrNull(node.selectIndex)?.let { selected ->
            selected.id == link.childTaskMessageId && selected.role == MessageRole.USER
        } == true
    }
    if (startIndex < 0) return null

    val endExclusive = ((startIndex + 1) until nodes.size)
        .firstOrNull { index ->
            nodes[index].messages
                .getOrNull(nodes[index].selectIndex)?.role == MessageRole.USER
        } ?: nodes.size
    return nodes.subList(startIndex + 1, endExclusive)
}

sealed interface SubAssistantDetailUiState {
    data object Loading : SubAssistantDetailUiState
    data object Unavailable : SubAssistantDetailUiState
    data class Ready(
        val link: SubAssistantDetailLink,
        val child: ConversationPresentationSnapshot,
        val timeline: List<MessageNode>,
        val attachmentPreviews: Map<String, net.weero.measix.pilot.service.AttachmentPreview> = emptyMap(),
    ) : SubAssistantDetailUiState
}

/** Read-only child detail projection; the parent page remains the authority for this subscription. */
class SubAssistantDetailReader(
    private val queryService: ConversationQueryService,
    private val projectionDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    fun observe(source: ConversationViewLease?, runId: String): Flow<SubAssistantDetailUiState> {
        if (source == null) return flowOf(SubAssistantDetailUiState.Unavailable)
        return queryService.observeForView<SubAssistantDetailUiState>(source, SubAssistantDetailUiState.Unavailable) {
            channelFlow {
                val state = MutableStateFlow<SubAssistantDetailUiState>(SubAssistantDetailUiState.Loading)
                launch { state.collect { send(it) } }
                val masterLinks = queryService.observeConversation(source).mapNotNull { read ->
                    when (read) {
                        is ConversationReadState.Ready -> read.snapshot
                        ConversationReadState.Loading -> null
                        else -> error("sub_assistant_master_unavailable")
                    }
                }.map { resolveSubAssistantDetailLink(it, runId, JsonInstant) }
                    .flowOn(projectionDispatcher)
                    .shareIn(this, SharingStarted.Eagerly, replay = 1)
                val resolved = masterLinks.first { it !is SubAssistantDetailLinkResult.Pending }
                val link = (resolved as? SubAssistantDetailLinkResult.Ready)?.link
                    ?: error("sub_assistant_link_unavailable")

                fun requireSameLink(result: SubAssistantDetailLinkResult): SubAssistantDetailLink {
                    val updated = (result as? SubAssistantDetailLinkResult.Ready)?.link
                        ?: error("sub_assistant_link_unavailable")
                    check(updated.childConversationId == link.childConversationId &&
                        updated.childTaskMessageId == link.childTaskMessageId && updated.targetAssistantId == link.targetAssistantId) {
                        "sub_assistant_link_changed"
                    }
                    return updated
                }

                launch {
                    queryService.observeChildForView(source, link.childConversationId)
                        .combine(queryService.attachmentPreviewChanges()) { child, _ -> child }
                        .collectLatest { child ->
                            val timeline = resolveSubAssistantTimeline(source.conversationId, link, child)
                                ?: error("sub_assistant_child_link_mismatch")
                            val previews = queryService.attachmentPreviews(source, child)
                            currentCoroutineContext().ensureActive()
                            state.update { current ->
                                val latest = requireSameLink(masterLinks.replayCache.last())
                                val previous = (current as? SubAssistantDetailUiState.Ready)?.link ?: link
                                SubAssistantDetailUiState.Ready(mergeLiveSubAssistantDetailLink(previous, latest), child, timeline, previews)
                            }
                        }
                }
                // Master metadata changes do not restart the child subscription.
                launch {
                    masterLinks.collect { result ->
                        val updated = requireSameLink(result)
                        state.update { current ->
                            if (current is SubAssistantDetailUiState.Ready) {
                                current.copy(link = mergeLiveSubAssistantDetailLink(current.link, updated))
                            } else current
                        }
                    }
                }
            }.catch { error ->
                if (error is CancellationException) throw error
                // Child read failures cannot close the borrowed parent page capability.
                emit(SubAssistantDetailUiState.Unavailable)
            }
        }
    }
}
