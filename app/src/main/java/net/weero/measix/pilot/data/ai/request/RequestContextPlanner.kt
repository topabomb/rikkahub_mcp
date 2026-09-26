package net.weero.measix.pilot.data.ai.request

import kotlin.math.roundToInt
import me.rerere.ai.core.FrozenToolDefinition
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.confirmedReplayableToolOrdinals
import me.rerere.ai.ui.findUserTurnStart
import me.rerere.ai.ui.replaySafeProjection
import net.weero.measix.pilot.data.model.*
import net.weero.measix.pilot.service.ConversationDisclosureSnapshotService
import net.weero.measix.pilot.service.DisclosureFact
import net.weero.measix.pilot.service.DisclosureToolOutcome
import kotlin.uuid.Uuid

/** 一条请求消息在 durable 树中的精确位置。 */
data class DurableMessageLocator(
    val nodeId: Uuid,
    val messageId: Uuid,
)

/**
 * 请求消息的唯一来源事实。来源只能显式登记，不得靠 role、列表位置或对象引用猜测：
 * Durable 携带 locator；管线合成的内容携带 kind。
 */
sealed interface RequestMessageOrigin {
    data class Durable(val locator: DurableMessageLocator) : RequestMessageOrigin
    data class Synthetic(val kind: SyntheticMessageKind) : RequestMessageOrigin
}

/** 本管线会合成的全部消息来源；新增注入路径必须同步登记 kind，不允许匿名 synthetic。 */
enum class SyntheticMessageKind {
    SYSTEM_PROMPT,
    PROMPT_INJECTION,
    TIME_REMINDER,
    APPLICATION_CONTEXT,
}

/** The admitted source and exact location travel together; content is already rendered. */
internal data class ModelContextProjection(
    val entryId: Uuid,
    val owner: ContextMessageLocator,
    val role: MessageRole,
    val placement: ContextPlacement,
    val content: String,
    val source: ConversationContextSource,
)

/** Tool system prompt 与 Provider 请求共用的请求级投影。 */
internal data class RequestContextPlan(
    val messages: List<UIMessage>,
    val originsByMessageId: Map<Uuid, RequestMessageOrigin>,
    val contextProjections: List<ModelContextProjection>,
    val disclosureFacts: List<DisclosureFact> = emptyList(),
    val previouslyDisclosed: Set<DisclosureSection> = emptySet(),
)

/** 成功 Provider 请求中可保守确认已进入最终投影的 inline Tool Result locator。 */
internal data class ModelRequestReceipt(
    val visibleInlineToolOutputs: Set<ToolCallLocator>,
)

/** 唯一历史窗口、context 选择 planner（仅请求前）。 */
internal class RequestContextPlanner {

    /**
     * Only admitted historical disclosure is replayed here. Other sources are selected by the
     * current frozen request. Current configuration never enters this historical projection.
     * The caller certifies built-in identity, namespace and execution phase for every tool fact.
     */
    fun planRequest(
        durableMessages: List<UIMessage>,
        durableLocators: Map<Uuid, DurableMessageLocator> = emptyMap(),
        modelContextEntries: List<ConversationModelContextEntry> = emptyList(),
        contextAdmissions: List<ConversationContextAdmission> = emptyList(),
        messageLimit: Int,
        requestOwner: ContextMessageLocator? = null,
        requestStepId: Uuid? = null,
        applicableDisclosureSections: (ConversationModelContextEntry) -> Set<DisclosureSection> = { emptySet() },
        classifyTool: (ToolCallLocator, UIMessagePart.Tool) -> DisclosureFact.Tool? = { _, _ -> null },
    ): RequestContextPlan {
        require((requestOwner == null) == (requestStepId == null)) { "context_request_boundary_incomplete" }
        val branch = if (requestOwner == null) durableMessages else {
            val index = durableMessages.indexOfFirst { it.id == requestOwner.messageId }
            require(index >= 0 && durableLocators[requestOwner.messageId]?.nodeId == requestOwner.nodeId) {
                "context_request_owner_missing"
            }
            val message = durableMessages[index]
            val boundary = message.parts.indexOfFirst { it is UIMessagePart.Step && it.stepId == requestStepId }
            require(boundary >= 0) { "context_request_step_missing" }
            val past = message.parts.take(boundary)
            val boundaryStep = (message.parts[boundary] as UIMessagePart.Step).copy(modelResult = null, outcome = null, finishedAt = null)
            durableMessages.take(index) + message.copy(parts = past + boundaryStep,
                providerMetadata = metadataForParts(past),
                terminalStatus = null, terminalReason = null, terminalDetail = null, providerReplayProjection = null)
        }
        val projected = branch.mapNotNull { message ->
            message.replaySafeProjection() ?: message.takeIf { it.id == requestOwner?.messageId }
        }
        val boundaryOnly = projected.lastOrNull()?.takeIf { it.id == requestOwner?.messageId && !it.isValidToUpload() }
        val history = if (boundaryOnly == null) projected else projected.dropLast(1)
        val window = limitContext(history, messageLimit) + listOfNotNull(boundaryOnly)
        val branchIndex = branch.withIndex().associate { it.value.id to it.index }
        val applicability = ConversationModelContextApplicability.index(branch)
        val byId = modelContextEntries.associateBy { it.id }
        val events = mutableListOf<DisclosureEvent>()
        val seen = mutableSetOf<DisclosureSection>()
        val projections = mutableListOf<ModelContextProjection>()
        val candidates = mutableListOf<ModelContextProjection>()
        val closed = mutableListOf<ModelContextProjection>()
        fun missingAt(entry: ConversationModelContextEntry, owner: ContextMessageLocator, stepId: Uuid?) {
            val source = entry.payload.source as ConversationContextSource.Disclosure
            val supplied = ConversationDisclosureSnapshotService.readSections(
                (entry.payload.body as ConversationContextBody.Inline).text).keys
            val sections = supplied.intersect(applicableDisclosureSections(entry)).ifEmpty {
                if (source.namespace == null) supplied else emptySet()
            }
            if (sections.isEmpty()) return
            seen += sections
            val index = branchIndex.getValue(owner.messageId)
            val boundary = stepId?.let { id -> branch[index].parts.indexOfFirst {
                it is UIMessagePart.Step && it.stepId == id
            }.also { require(it >= 0) { "context_request_step_missing" } } * 2 } ?: -1
            events += DisclosureEvent(index, boundary, DisclosureFact.Missing(sections))
        }
        val orderedAdmissions = contextAdmissions.filter { admission ->
            val index = branchIndex[admission.owner.messageId] ?: return@filter false
            val message = branch[index]
            message.parts.any { it is UIMessagePart.Step && it.stepId == admission.stepId }
        }.sortedWith(compareBy({ branchIndex.getValue(it.owner.messageId) }, { admission ->
            branch[branchIndex.getValue(admission.owner.messageId)].parts.indexOfFirst {
                it is UIMessagePart.Step && it.stepId == admission.stepId }
        }))
        orderedAdmissions.groupBy { it.owner }.forEach { (owner, admissions) ->
            val steps = branch[branchIndex.getValue(owner.messageId)].parts.filterIsInstance<UIMessagePart.Step>()
            val resolved = resolveUsesAt(admissions, owner, admissions.last().stepId, steps, includeOmitted = true)
            resolved.uses.forEach { use ->
                val entry = requireNotNull(byId[use.entryId]) { "context_contribution_missing: ${use.entryId}" }
                if (entry.payload.source !is ConversationContextSource.Disclosure) return@forEach
                if (!applicability.applicable(entry)) {
                    // A selected Assistant can outlive an edit to its causal USER. Its saved
                    // body remains historical, but must not be replayed at that retired anchor.
                    missingAt(entry, owner, admissions.last { request -> request.uses.any { it.entryId == use.entryId } }.stepId)
                    return@forEach
                }
                val projection = ModelContextProjection(entry.id, owner, use.role, use.placement,
                    (entry.payload.body as ConversationContextBody.Inline).text, entry.payload.source)
                if (use.placement == ContextPlacement.Omitted) {
                    val boundary = admissions.last { request -> request.uses.any { it.entryId == use.entryId } }
                    closed += projection.copy(placement = ContextPlacement.BeforeStep(boundary.stepId))
                } else candidates += projection
            }
        }
        // Historical format 1/2 rows have no request admission. Their saved causal anchor is their
        // original location, never a license to move the old body to the current window start.
        val admittedIds = orderedAdmissions.flatMap { it.uses }.mapTo(mutableSetOf()) { it.entryId }
        modelContextEntries.filter { it.id !in admittedIds && it.stepId == null &&
            it.payload.source is ConversationContextSource.Disclosure && it.ownerMessageId in branchIndex }.forEach { entry ->
            if (!applicability.applicable(entry)) {
                missingAt(entry, ContextMessageLocator(entry.ownerNodeId, entry.ownerMessageId), null)
                return@forEach
            }
            candidates += ModelContextProjection(entry.id, ContextMessageLocator(entry.ownerNodeId, entry.ownerMessageId),
                MessageRole.USER, ContextPlacement.BeforeMessage(ContextMessageLocator(entry.anchorNodeId, entry.anchorMessageId)),
                (entry.payload.body as ConversationContextBody.Inline).text, entry.payload.source)
        }
        // An entry may be reused at a later request location. Keep its earliest retained location,
        // so the same immutable contribution is not inserted twice in a single model request.
        val chosen = candidates.groupBy { it.entryId }.values.map { uses ->
            uses.firstOrNull { placementVisible(it, window) } ?: uses.first()
        }
        val activeIds = chosen.mapTo(hashSetOf()) { it.entryId }
        closed.filter { it.entryId !in activeIds }.forEach { projection ->
            val entry = byId.getValue(projection.entryId)
            val supplied = ConversationDisclosureSnapshotService.readSections(projection.content).keys
            val applicable = supplied.intersect(applicableDisclosureSections(entry))
            val sections = applicable.ifEmpty {
                if ((entry.payload.source as ConversationContextSource.Disclosure).namespace == null) supplied else emptySet()
            }
            if (sections.isNotEmpty()) {
                seen += sections
                val position = positionOf(projection, branch)
                events += DisclosureEvent(position.first, position.second, DisclosureFact.Missing(sections))
            }
        }
        chosen.forEach { projection ->
            val entry = byId.getValue(projection.entryId)
            val supplied = ConversationDisclosureSnapshotService.readSections(projection.content).keys
            val sections = supplied.intersect(applicableDisclosureSections(entry))
            val position = positionOf(projection, branch)
            if (sections.isEmpty()) {
                // Unknown historical scope proves prior disclosure, not current-scope knowledge.
                // Explicitly incompatible known scopes do not establish even this evidence.
                if ((entry.payload.source as ConversationContextSource.Disclosure).namespace == null) {
                    seen += supplied
                    events += DisclosureEvent(position.first, position.second, DisclosureFact.Missing(supplied))
                }
                return@forEach
            }
            seen += sections
            if (sections == supplied && placementVisible(projection, window)) {
                projections += projection
                events += DisclosureEvent(position.first, position.second, DisclosureFact.Snapshot(projection.content, sections))
            } else events += DisclosureEvent(position.first, position.second, DisclosureFact.Missing(sections))
        }
        val visibleById = window.associateBy { it.id }
        branch.forEachIndexed { messageIndex, original ->
            val visible = visibleById[original.id]
            val confirmed = visible?.confirmedReplayableToolOrdinals().orEmpty()
            val actualTools = visible?.parts?.filterIsInstance<UIMessagePart.Tool>().orEmpty()
            original.parts.forEachIndexed { partIndex, part ->
                if (part !is UIMessagePart.Tool) return@forEachIndexed
                val locator = ToolCallLocator(original.id, part.stepId, part.localCallId)
                val ordinal = actualTools.indexOfFirst { it.stepId == part.stepId && it.localCallId == part.localCallId }
                val actual = actualTools.getOrNull(ordinal)
                val fact = classifyTool(locator, actual ?: part) ?: return@forEachIndexed
                if (!fact.appliesToCurrentScope || fact.outcome == DisclosureToolOutcome.NOT_EXECUTED) return@forEachIndexed
                seen += fact.builtin.section
                val known = ordinal in confirmed && actual?.runtimeState?.archive == null
                events += DisclosureEvent(messageIndex, partIndex * 2 + 1,
                    if (known) fact else DisclosureFact.Missing(setOf(fact.builtin.section)))
            }
        }
        if (projections.isNotEmpty()) window.forEach { message ->
            require(durableLocators.containsKey(message.id)) { "retained request message has no durable locator: ${message.id}" }
        }
        val origins = window.mapNotNull { message ->
            durableLocators[message.id]?.let { message.id to RequestMessageOrigin.Durable(it) }
        }.toMap()
        return RequestContextPlan(window, origins, projections,
            events.sortedWith(compareBy({ it.messageIndex }, { it.partPosition })).map { it.fact }, seen)
    }

    /** Caller supplies the exact admitted current selection as additional projections. */
    fun applyContextProjections(
        transformedMessages: List<UIMessage>,
        projections: List<ModelContextProjection>,
        originsByMessageId: Map<Uuid, RequestMessageOrigin>,
        onProjected: (ModelContextProjection, UIMessagePart.Text, Uuid, Boolean) -> Unit = { _, _, _, _ -> },
    ): List<UIMessage> = projectAdmittedContext(transformedMessages, projections, originsByMessageId, onProjected)

    private data class DisclosureEvent(val messageIndex: Int, val partPosition: Int, val fact: DisclosureFact)

    private fun positionOf(projection: ModelContextProjection, messages: List<UIMessage>): Pair<Int, Int> {
        val placement = projection.placement
        val messageId = when (placement) {
            is ContextPlacement.BeforeMessage -> placement.message.messageId
            is ContextPlacement.MessagePart -> placement.message.messageId
            else -> projection.owner.messageId
        }
        val index = messages.indexOfFirst { it.id == messageId }
        require(index >= 0) { "context_placement_off_branch: $messageId" }
        val part = when (placement) {
            is ContextPlacement.MessagePart -> placement.partIndex * 2
            is ContextPlacement.BeforeStep -> messages[index].parts.indexOfFirst {
                it is UIMessagePart.Step && it.stepId == placement.stepId }.also {
                    require(it >= 0) { "context_placement_step_missing" }
                } * 2
            else -> -1
        }
        return index to part
    }

    private fun placementVisible(projection: ModelContextProjection, window: List<UIMessage>): Boolean = when (val placement = projection.placement) {
        is ContextPlacement.BeforeMessage -> window.any { it.id == placement.message.messageId }
        is ContextPlacement.MessagePart -> window.any { it.id == placement.message.messageId && placement.partIndex <= it.parts.size }
        is ContextPlacement.BeforeStep -> window.any { it.id == projection.owner.messageId && it.parts.any { part ->
            part is UIMessagePart.Step && part.stepId == placement.stepId } }
        else -> false
    }

    /**
     * 从 Step 已丢弃、即将交给 Provider 的最终 durable 投影生成保守 receipt：终态消息只看完整前缀；
     * Responses opaque replay 只登记原始 function_call 容器实际能配对的本地结果。
     */
    fun receiptOf(providerVisibleMessages: List<UIMessage>): ModelRequestReceipt = ModelRequestReceipt(
        providerVisibleMessages.flatMap { message ->
            val confirmedOrdinals = message.confirmedReplayableToolOrdinals()
            message.parts.filterIsInstance<UIMessagePart.Tool>().mapIndexedNotNull { ordinal, tool ->
                ToolCallLocator(message.id, tool.stepId, tool.localCallId).takeIf {
                    ordinal in confirmedOrdinals && tool.runtimeState.archive == null
                }
            }
        }.toSet(),
    )

    /**
     * 在 Provider 调用前，对经过全部输入 transformer 的最终请求投影做稳定粗估。
     * 文本与工具 schema 使用统一 code-point 规则；媒体使用固定占位，避免 base64 长度冒充模型 token。
     */
    fun estimateRequestContextTokens(
        providerVisibleMessages: List<UIMessage>,
        tools: List<FrozenToolDefinition>,
    ): Long {
        var total = 0L
        fun add(tokens: Long) {
            total = if (Long.MAX_VALUE - total < tokens) Long.MAX_VALUE else total + tokens
        }
        fun addText(value: String) = add(estimateStableTextTokens(value))
        fun addParts(parts: List<UIMessagePart>) {
            parts.forEach { part ->
                add(REQUEST_PART_OVERHEAD_ESTIMATED_TOKENS)
                when (part) {
                    is UIMessagePart.Text -> addText(part.text)
                    is UIMessagePart.Reasoning -> addText(part.reasoning)
                    is UIMessagePart.Step -> Unit
                    is UIMessagePart.Tool -> {
                        addText(part.providerCallId)
                        addText(part.toolName)
                        addText(part.input)
                        addParts(part.output)
                    }
                    is UIMessagePart.Document -> {
                        addText(part.fileName)
                        addText(part.mime)
                        add(REQUEST_MEDIA_PLACEHOLDER_ESTIMATED_TOKENS)
                    }
                    is UIMessagePart.Image,
                    is UIMessagePart.Audio,
                    is UIMessagePart.Video,
                    -> add(REQUEST_MEDIA_PLACEHOLDER_ESTIMATED_TOKENS)
                }
            }
        }

        providerVisibleMessages.forEach { message ->
            add(REQUEST_MESSAGE_OVERHEAD_ESTIMATED_TOKENS)
            addText(message.role.name)
            addParts(message.parts)
        }
        tools.forEach { tool ->
            add(REQUEST_TOOL_DEFINITION_OVERHEAD_ESTIMATED_TOKENS)
            addText(tool.name)
            addText(tool.description)
            tool.parameters?.toString()?.let(::addText)
        }
        return total
    }

    private fun limitContext(messages: List<UIMessage>, limit: Int): List<UIMessage> {
        if (limit <= 0 || messages.size <= limit) return messages
        val target = (limit * CONTEXT_KEEP_RATIO).roundToInt().coerceIn(1, limit)
        val stride = (limit - target).coerceAtLeast(1)
        val steppedStartIndex = (((messages.size - limit) / stride + 1) * stride).coerceAtMost(messages.lastIndex)
        return messages.subList(messages.findUserTurnStart(steppedStartIndex), messages.size)
    }

    private companion object {
        /** 普通历史超限时每次保留窗口的一半，形成稳定的阶梯式请求边界。 */
        const val CONTEXT_KEEP_RATIO = 0.5f
        const val REQUEST_MESSAGE_OVERHEAD_ESTIMATED_TOKENS = 4L
        const val REQUEST_PART_OVERHEAD_ESTIMATED_TOKENS = 1L
        const val REQUEST_TOOL_DEFINITION_OVERHEAD_ESTIMATED_TOKENS = 8L
        const val REQUEST_MEDIA_PLACEHOLDER_ESTIMATED_TOKENS = 256L
    }
}
