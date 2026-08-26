package net.weero.measix.pilot.ui.pages.subassistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import net.weero.measix.pilot.data.datastore.Settings
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
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.service.ConversationDetailRead
import net.weero.measix.pilot.service.SubAssistantDetailReader
import net.weero.measix.pilot.service.runtime.ConversationSnapshot
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
    master: ConversationSnapshot,
    runId: String,
    json: Json,
): SubAssistantDetailLinkResult {
    if (master.header.parentConversationId != null || runId.isBlank()) {
        return SubAssistantDetailLinkResult.Unavailable
    }

    val matches = buildList {
        master.renderNodes.forEach { node ->
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
    child: ConversationSnapshot,
): List<MessageNode>? {
    if (child.conversationId != link.childConversationId ||
        child.header.parentConversationId != masterConversationId ||
        child.header.assistantId != link.targetAssistantId
    ) {
        return null
    }

    val nodes = child.renderNodes
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
        val child: ConversationSnapshot,
        val timeline: List<MessageNode>,
    ) : SubAssistantDetailUiState
}

class SubAssistantDetailVM(
    private val masterConversationId: String,
    private val runId: String,
    private val detailReader: SubAssistantDetailReader,
    settingsStore: SettingsStore,
    private val json: Json,
) : ViewModel() {
    private val _uiState = MutableStateFlow<SubAssistantDetailUiState>(SubAssistantDetailUiState.Loading)
    val uiState: StateFlow<SubAssistantDetailUiState> = _uiState.asStateFlow()
    val settings: StateFlow<Settings> = settingsStore.effectiveSettings
        .map { it.settings }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    fun attachmentPreviews(): Map<String, String> =
        (_uiState.value as? SubAssistantDetailUiState.Ready)
            ?.child
            ?.let(detailReader::attachmentPreviews)
            .orEmpty()

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

            // Linkage is immutable for this detail instance and is resolved once.
            val masterRead = detailReader.read(masterId)
                ?: return@launch markUnavailable()

            val link = resolveLinkOnce(masterRead)
                ?: return@launch // resolveLinkOnce 已经设置了 Unavailable 或仍在 Loading

            // Child content and Master metadata are independent projections after linkage resolves.
            //
            // Child 收集器：在 Main 上运行，收集 child StateFlow 或从 DB 一次性读取。
            // 绝不被 master 状态变化取消。
            launch {
                val childRead = detailReader.read(link.childConversationId)
                    ?: return@launch markUnavailable()
                updateReady(masterId, link, childRead.initial)
                childRead.updates?.collect { child ->
                        updateReady(masterId, link, child)
                }
            }

            // Master metadata 收集器：在 Default 上运行，仅提取 metadata 更新。
            // 不遍历 child 数据，不取消 child 收集器。
            // 使用 Dispatchers.Default 避免在 Main 上执行 resolveSubAssistantDetailLink。
            if (masterRead.updates != null) {
                launch(Dispatchers.Default) {
                    masterRead.updates.collect { master ->
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
        masterRead: ConversationDetailRead,
    ): SubAssistantDetailLink? {
        // 先从当前状态尝试
        val initialResult = resolveSubAssistantDetailLink(masterRead.initial, runId, json)
        if (initialResult is SubAssistantDetailLinkResult.Ready) {
            return initialResult.link
        }
        if (initialResult is SubAssistantDetailLinkResult.Unavailable) {
            markUnavailable()
            return null
        }

        // 当前状态未找到；若 Master runtime 不驻留，持久化快照已经是最终查询结果。
        val updates = masterRead.updates
        if (updates == null) {
            markUnavailable()
            return null
        }

        // 监听 master StateFlow 直到 link 出现。
        // 此时 master 可能正在生成，tool metadata 尚未写入。
        // 使用 first：找到匹配值后自动取消收集并返回。
        val resolvedMaster = updates.first { master ->
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

    private fun updateReady(masterId: Uuid, link: SubAssistantDetailLink, child: ConversationSnapshot) {
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
