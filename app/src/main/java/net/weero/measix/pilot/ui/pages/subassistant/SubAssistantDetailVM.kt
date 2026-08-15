package net.weero.measix.pilot.ui.pages.subassistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
            failureDetail = parseRuntimeErrorDetailFromToolOutput(tool, json),
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

    /**
     * Link 只解析一次；Master 流式更新不能重启或取消 Child 状态收集。
     */
    private fun loadValidatedRun() {
        viewModelScope.launch {
            val masterId = runCatching { Uuid.parse(masterConversationId) }.getOrNull()
                ?: return@launch markUnavailable()

            // Step 1: 解析 link（一次性，不随 master 状态变化重复解析）
            val activeMaster = sessionRegistry.getSession(masterId)
            val initialMaster = activeMaster?.state?.value
                ?: conversationRepository.getConversationById(masterId)
                ?: return@launch markUnavailable()

            val link = resolveLinkOnce(masterId, initialMaster, activeMaster)
                ?: return@launch // resolveLinkOnce 已经设置了 Unavailable 或仍在 Loading

            // Step 2: link 已解析。child 收集和 master metadata 更新并行运行。
            //
            // Child 收集器：在 Main 上运行，收集 child StateFlow 或从 DB 一次性读取。
            // 绝不被 master 状态变化取消。
            launch {
                val activeChild = sessionRegistry.getSession(link.childConversationId)
                if (activeChild != null) {
                    activeChild.state.collect { child ->
                        updateReady(masterId, link, child)
                    }
                } else {
                    val child = conversationRepository.getConversationById(link.childConversationId)
                    if (child != null) {
                        updateReady(masterId, link, child)
                    } else {
                        markUnavailable()
                    }
                }
            }

            // Master metadata 收集器：在 Default 上运行，仅提取 metadata 更新。
            // 不遍历 child 数据，不取消 child 收集器。
            // 使用 Dispatchers.Default 避免在 Main 上执行 resolveSubAssistantDetailLink。
            if (activeMaster != null) {
                launch(Dispatchers.Default) {
                    activeMaster.state.collect { master ->
                        val result = resolveSubAssistantDetailLink(master, runId, json)
                        if (result is SubAssistantDetailLinkResult.Ready) {
                            _uiState.update { state ->
                                if (state is SubAssistantDetailUiState.Ready) {
                                    state.copy(
                                        link = mergeLiveSubAssistantDetailLink(
                                            previous = state.link,
                                            incoming = result.link,
                                        )
                                    )
                                } else {
                                    state
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 一次性解析 link。先从当前 master 状态尝试，如果未找到则监听 master StateFlow
     * 直到 link 出现（用于子助手调用刚开始、metadata 尚未写入的情况）。
     * 返回 null 表示已设置 Unavailable 或仍在等待（协程被取消）。
     */
    private suspend fun resolveLinkOnce(
        masterId: Uuid,
        initialMaster: Conversation,
        activeMaster: net.weero.measix.pilot.service.ConversationSession?,
    ): SubAssistantDetailLink? {
        // 先从当前状态尝试
        val initialResult = resolveSubAssistantDetailLink(initialMaster, runId, json)
        if (initialResult is SubAssistantDetailLinkResult.Ready) {
            return initialResult.link
        }
        if (initialResult is SubAssistantDetailLinkResult.Unavailable) {
            markUnavailable()
            return null
        }

        // 当前状态未找到。如果 master session 不活跃，说明数据已是最新但仍未找到。
        if (activeMaster == null) {
            markUnavailable()
            return null
        }

        // 监听 master StateFlow 直到 link 出现。
        // 此时 master 可能正在生成，tool metadata 尚未写入。
        // 使用 first：找到匹配值后自动取消收集并返回。
        val resolvedMaster = activeMaster.state.first { master ->
            resolveSubAssistantDetailLink(master, runId, json) !is SubAssistantDetailLinkResult.Pending
        }
        return when (val result = resolveSubAssistantDetailLink(resolvedMaster, runId, json)) {
            is SubAssistantDetailLinkResult.Ready -> result.link
            SubAssistantDetailLinkResult.Pending -> null
            SubAssistantDetailLinkResult.Unavailable -> {
                markUnavailable()
                null
            }
        }
    }

    private fun updateReady(masterId: Uuid, link: SubAssistantDetailLink, child: Conversation) {
        val timeline = resolveSubAssistantTimeline(masterId, link, child)
            ?: return markUnavailable()
        // 使用 update 而非直接赋值，避免覆盖 metadata collector 在
        // Dispatchers.Default 上并行写入的最新 metadata。
        _uiState.update { current ->
            val ready = current as? SubAssistantDetailUiState.Ready
            val effectiveMetadata = ready?.link?.metadata ?: link.metadata
            val effectiveFailureDetail = ready?.link?.failureDetail ?: link.failureDetail
            SubAssistantDetailUiState.Ready(
                link = link.copy(
                    metadata = effectiveMetadata,
                    failureDetail = effectiveFailureDetail,
                ),
                child = child,
                timeline = timeline,
            )
        }
    }

    private fun markUnavailable() {
        _uiState.value = SubAssistantDetailUiState.Unavailable
    }
}
