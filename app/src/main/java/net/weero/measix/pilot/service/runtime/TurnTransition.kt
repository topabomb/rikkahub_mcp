package net.weero.measix.pilot.service.runtime

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.core.ToolOutputPolicy
import me.rerere.ai.ui.MessageTerminalStatus
import me.rerere.ai.ui.StepOutcome
import me.rerere.ai.ui.ToolInteractionState
import me.rerere.ai.ui.ToolResultStatus
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.finishReasoning
import net.weero.measix.pilot.data.ai.request.ContextBudget
import net.weero.measix.pilot.data.ai.request.DurableMessageLocator
import net.weero.measix.pilot.data.ai.ToolExecutionFact
import net.weero.measix.pilot.data.ai.ToolOutputCompactionPatch
import net.weero.measix.pilot.data.ai.request.TurnModelContextProjection
import net.weero.measix.pilot.data.ai.request.estimateStableTextTokens
import net.weero.measix.pilot.data.ai.tools.REGENERABLE_TOOL_OUTPUT_FOLDED_MARKER
import net.weero.measix.pilot.data.ai.tools.buildToolOutputMarker
import net.weero.measix.pilot.data.ai.tools.canonicalizeToolOutput
import net.weero.measix.pilot.data.ai.tools.virtualLineCount
import net.weero.measix.pilot.data.db.entity.ToolExecutionEntity
import net.weero.measix.pilot.data.db.entity.TurnExecutionEntity
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.model.ConversationModelContextApplicability
import net.weero.measix.pilot.data.model.ConversationModelContextEntry
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.service.ConversationDisclosureSnapshotService
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Turn / Step / Tool transcript 的唯一 reducer。结构事实（header / tree / variant）归
 * [ConversationTransition]；本对象只负责一次 Turn 生命周期内的 transcript 变换与执行事实。
 * 两者由同一个 [ConversationCommandCoordinator] 分发，仍是一个写入口、一个事务。
 */
internal object TurnTransition {

    /** 把一条 Turn 命令归约到新的 transcript 快照；结构命令不在此列。 */
    internal fun reduce(
        current: ConversationAggregateSnapshot,
        command: ConversationCommand,
    ): ConversationAggregateSnapshot = when (command) {
        is StartTurn -> startTurn(current, command)
        is TurnCheckpoint -> commitCheckpoint(current, command)
        is FinalizeTurn -> finalizeTurn(current, command)
        is RecoverInterruptedTurn -> recoverInterruptedTurn(current, command)
        is ResolveToolInteraction -> resolveToolInteraction(current, command)
        else -> error("TurnTransition received a non-turn command: ${command::class.simpleName}")
    }

    internal fun factsOf(
        conversationId: Uuid,
        command: ConversationCommand,
        nowMillis: Long,
    ): ExecutionFacts? = when (command) {
        is StartTurn -> ExecutionFacts(
            turn = buildTurn(
                conversationId,
                command.turnId,
                TurnExecutionStatus.RUNNING,
                null,
                command.assistantMessageId,
                nowMillis,
            ),
            toolExecution = null,
            turnOperation = TurnExecutionOperation.START,
        )
        is ModelResponseCheckpoint -> ExecutionFacts(
            turn = buildTurn(
                conversationId,
                command.turn.turnId,
                command.turnStatus,
                null,
                command.turn.assistantMessageId,
                nowMillis,
            ),
            toolExecution = null,
        )
        is ToolExecutionStartedCheckpoint,
        is ToolExecutionUpdatedCheckpoint,
        is ToolResultCheckpoint,
        -> ExecutionFacts(
            turn = buildTurn(
                conversationId,
                command.turn.turnId,
                TurnExecutionStatus.RUNNING,
                null,
                command.turn.assistantMessageId,
                nowMillis,
            ),
            toolExecution = command.toolExecution?.toEntity(
                turnId = command.turn.turnId,
                nowMillis = nowMillis,
            ),
        )
        is FinalizeTurn -> ExecutionFacts(
            turn = buildTurn(
                conversationId,
                command.handle.turnId,
                command.terminalStatus,
                command.terminalReason,
                command.handle.assistantMessageId,
                nowMillis,
            ),
            toolExecution = null,
        )
        is RecoverInterruptedTurn -> ExecutionFacts(
            turn = buildTurn(
                conversationId,
                command.turnId,
                TurnExecutionStatus.INTERRUPTED,
                command.terminalReason,
                command.assistantMessageId,
                nowMillis,
            ),
            toolExecution = null,
            turnOperation = TurnExecutionOperation.RECOVER,
        )
        else -> null
    }

    private fun buildTurn(
        conversationId: Uuid,
        turnId: Uuid,
        status: TurnExecutionStatus,
        reason: String?,
        assistantMessageId: Uuid?,
        nowMillis: Long,
    ): TurnExecutionEntity = TurnExecutionEntity(
        turnId = turnId.toString(),
        conversationId = conversationId.toString(),
        assistantMessageId = assistantMessageId?.toString(),
        status = status,
        reason = reason,
        createdAt = nowMillis,
        updatedAt = nowMillis,
    )

    /**
     * `START` 目标分支的唯一纯规划入口。regenerate/edit 边界与"新 owner
     * 是既有 Assistant node 的新 variant 还是新 node"都只在这里判定一次；调用方与锁内校验
     * 共用同一个函数，不存在第二套"排除哪个 variant"的判断。
     *
     * 返回的 [StartTurnTarget.selectedPrefixMessageIds] 是结构变换后、新 owner 之前的目标
     * selected branch；将被替换的 unselected Assistant variant 已被排除，因此 regenerate 后
     * 它拥有的 baseline 自然退出比较。
     *
     * 没有因果 USER anchor 的树不能开始模型请求：fail-closed，不按 role 伪造。
     */
    internal fun planStartTarget(
        current: ConversationAggregateSnapshot,
        preallocatedAssistantNodeId: Uuid,
        assistantMessageId: Uuid,
    ): StartTurnTarget {
        if (current.nodes.isEmpty()) {
            throw ConversationCommandConflictException(
                "conversation ${current.conversationId} has no message tree to anchor a START",
            )
        }
        val last = current.nodes.last()
        val lastMsg = last.currentMessage
        check(assistantMessageId != lastMsg.id) {
            "START owner variant collides with the selected message: $assistantMessageId"
        }
        // 树末条是 Assistant 只可能来自 regenerate（新 turn / 编辑都会先提交 USER）：一律按
        // 把将被替换的旧 variant 视为 unselected；terminalStatus 不豁免，failed /
        // cancelled 的旧回答同样不得留在目标分支。
        val appendVariantToExistingAssistantNode = lastMsg.role == MessageRole.ASSISTANT
        val prefix = if (appendVariantToExistingAssistantNode) {
            current.currentMessages().dropLast(1)
        } else {
            current.currentMessages()
        }
        val anchorIndex = prefix.indexOfLast { it.role == MessageRole.USER }
        if (anchorIndex < 0) {
            throw ConversationCommandConflictException(
                "conversation ${current.conversationId} has no causal USER anchor for the new turn",
            )
        }
        val anchor = prefix[anchorIndex]
        val anchorNode = prefixNodeOf(current.nodes, anchor.id)
            ?: throw ConversationCommandConflictException("causal USER anchor is off the tree: ${anchor.id}")
        return StartTurnTarget(
            assistantNodeId = if (appendVariantToExistingAssistantNode) last.id else preallocatedAssistantNodeId,
            assistantMessageId = assistantMessageId,
            anchorNodeId = anchorNode.id,
            anchorMessageId = anchor.id,
            selectedPrefixMessageIds = prefix.map { it.id },
            appendVariantToExistingAssistantNode = appendVariantToExistingAssistantNode,
        )
    }

    private fun prefixNodeOf(nodes: List<MessageNode>, messageId: Uuid): MessageNode? =
        nodes.firstOrNull { node -> node.messages.any { it.id == messageId } }

    /**
     * Turn 启动时请求侧 model-context 投影的唯一入口：
     * branch = 当前 selected 消息序列（含 active owner 末条），entries 用唯一适用谓词过滤。
     * 该 Turn 的所有 step、审批 continuation 与重试只复用这份结果，不重新判定。
     */
    internal fun projectTurnModelContext(
        current: ConversationAggregateSnapshot,
    ): TurnModelContextProjection {
        val branch = current.currentMessages()
        val locators = buildMap {
            current.nodes.forEach { node ->
                node.messages.forEach { message -> put(message.id, DurableMessageLocator(node.id, message.id)) }
            }
        }
        return TurnModelContextProjection(
            entries = current.modelContextEntries.filter {
                ConversationModelContextApplicability.applicable(it, branch)
            },
            locators = locators,
        )
    }

    /**
     * 完整 StartTurn 命令的唯一构造入口（调用方与测试共用，杜绝手拼 anchor/token）。
     * 锁内 plan 会重算目标分支；stale 计划在 [ConversationCommandCoordinator.startTurn]
     * 一律 conflict。
     */
    internal fun buildStartTurnCommand(
        current: ConversationAggregateSnapshot,
        turnId: Uuid,
        modelContextCandidate: String,
        assistantNodeId: Uuid = Uuid.random(),
        assistantMessageId: Uuid = Uuid.random(),
        epoch: Long = 0L,
    ): StartTurn {
        val target = planStartTarget(current, assistantNodeId, assistantMessageId)
        return StartTurn(
            turnId = turnId,
            assistantNodeId = target.assistantNodeId,
            assistantMessageId = target.assistantMessageId,
            anchorNodeId = target.anchorNodeId,
            anchorMessageId = target.anchorMessageId,
            expectedSelectedPrefixMessageIds = target.selectedPrefixMessageIds,
            modelContextCandidate = modelContextCandidate,
            epoch = epoch,
        )
    }

    private fun startTurn(current: ConversationAggregateSnapshot, command: StartTurn): ConversationAggregateSnapshot {
        val target = planStartTarget(current, command.assistantNodeId, command.assistantMessageId)
        // 锁内 CAS：调用者拿到的 snapshot 与执行锁内的 committed snapshot 之间任何
        // prefix variant / anchor 结构变化都在这里冲突，绝不接受 stale 计划。
        if (target.selectedPrefixMessageIds != command.expectedSelectedPrefixMessageIds) {
            throw ConversationCommandConflictException(
                "START target selected branch changed after planStartTarget for conversation ${current.conversationId}",
            )
        }
        if (target.anchorNodeId != command.anchorNodeId || target.anchorMessageId != command.anchorMessageId) {
            throw ConversationCommandConflictException(
                "START causal anchor changed after planStartTarget: ${command.anchorMessageId} -> ${target.anchorMessageId}",
            )
        }
        if (target.assistantNodeId != command.assistantNodeId) {
            throw ConversationCommandConflictException(
                "START owner node identity changed after planStartTarget: ${command.assistantNodeId}",
            )
        }
        // 命令协议只接受合法 canonical envelope；畸形内容不得进入 durable 历史。
        ConversationDisclosureSnapshotService.requireCanonical(command.modelContextCandidate)

        val slot = openAssistantMessage(command.assistantMessageId)
        val nodes = current.nodes.toMutableList()
        if (target.appendVariantToExistingAssistantNode) {
            val last = nodes[nodes.lastIndex]
            nodes[nodes.lastIndex] = last.copy(
                messages = last.messages + slot,
                selectIndex = last.messages.size,
            )
        } else {
            nodes.add(
                MessageNode(
                    id = target.assistantNodeId,
                    messages = listOf(slot),
                    selectIndex = 0,
                ),
            )
        }
        // baseline 判等：目标分支上最近一份适用 Snapshot 的 content 与 candidate
        // 逐字相同则不新增 row；变化才由新 owner 追加完整 baseline。
        val branch = nodes.map { it.currentMessage }
        val applicable = current.modelContextEntries.filter {
            ConversationModelContextApplicability.applicable(it, branch)
        }
        val baseline = applicable.maxByOrNull { entry ->
            branch.indexOfFirst { it.id == entry.ownerMessageId }
        }
        val entries = if (baseline?.content == command.modelContextCandidate) {
            current.modelContextEntries
        } else {
            current.modelContextEntries + ConversationModelContextEntry(
                ownerNodeId = target.assistantNodeId,
                ownerMessageId = target.assistantMessageId,
                anchorNodeId = target.anchorNodeId,
                anchorMessageId = target.anchorMessageId,
                content = command.modelContextCandidate,
            )
        }
        return current.copy(nodes = nodes, modelContextEntries = entries)
    }

    private fun finalizeTurn(current: ConversationAggregateSnapshot, command: FinalizeTurn): ConversationAggregateSnapshot {
        var result = current
        val assistantMessageId = command.handle.assistantMessageId
        val patches = command.toolOutputCompactionPatches
        require(patches.map { it.locator }.distinct().size == patches.size) {
            "Tool Output compaction patches contain duplicate locators"
        }
        val activePatches = patches.filter { it.locator.assistantMessageId == assistantMessageId }
        command.assistantMessage?.let { projected ->
            // 只有 COMPLETED 终态是 model-output rooting：取消/失败终态会关闭未完工具（无 patch），不参与压缩守卫。
            if (command.terminalStatus == TurnExecutionStatus.COMPLETED) {
                validateActiveCompactionProjection(current, assistantMessageId, projected, activePatches)
            }
            result = result.replaceMessageById(assistantMessageId, projected, requireLastNode = true)
        }
        patches.filterNot { it.locator.assistantMessageId == assistantMessageId }.forEach { patch ->
            result = applyHistoricalToolOutputCompactionPatch(result, patch)
        }
        result = result.finishReasoning(assistantMessageId)
        toMessageTerminalStatus(command.terminalStatus)?.let { status ->
            result = result.markAssistantTerminalInternal(
                assistantMessageId,
                status,
                command.terminalReason,
                command.terminalDetail,
            )
        }
        result = result.markAssistantFinishedAt(assistantMessageId, command.finishedAt)
        // 收口唯一仍开放的尾部 Step：COMPLETED→Final，其余终态映射到对应 StepOutcome；
        // 其间任何未闭合的前序 Step 一并落 Continue（§6.2 不变量）。
        return result.closingStepsInPlace(assistantMessageId, stepOutcomeForTerminal(command.terminalStatus))
    }

    private fun recoverInterruptedTurn(
        current: ConversationAggregateSnapshot,
        command: RecoverInterruptedTurn,
    ): ConversationAggregateSnapshot {
        var result = current
        command.assistantMessage?.let {
            result = result.replaceMessageById(command.assistantMessageId, it, requireLastNode = false)
        }
        require(result.findMessage(command.assistantMessageId)?.role == MessageRole.ASSISTANT) {
            "Recovery target is not an assistant message: ${command.assistantMessageId}"
        }
        val targetMessage = result.findMessage(command.assistantMessageId)
        if (targetMessage != null) {
            val closedMessage = net.weero.measix.pilot.service.turn.closeOpenTurnForProcessRestart(targetMessage, emptySet())
            if (closedMessage !== targetMessage) {
                result = result.replaceMessageById(command.assistantMessageId, closedMessage, requireLastNode = false)
            }
        }
        result = result.finishReasoning(command.assistantMessageId)
        result = result.markAssistantTerminalInternal(
            command.assistantMessageId,
            MessageTerminalStatus.INTERRUPTED,
            command.terminalReason,
        )
        return result.markAssistantFinishedAt(command.assistantMessageId, command.finishedAt)
    }

    /** 所有终态统一覆盖中间 step 时间，确保消息 Total 表示完整 turn 生命周期。 */
    private fun ConversationAggregateSnapshot.markAssistantFinishedAt(
        messageId: Uuid,
        finishedAt: kotlinx.datetime.LocalDateTime,
    ): ConversationAggregateSnapshot {
        val message = findMessage(messageId) ?: return this
        return replaceMessageById(messageId, message.copy(finishedAt = finishedAt), requireLastNode = false)
    }

    private fun ConversationAggregateSnapshot.finishReasoning(messageId: Uuid): ConversationAggregateSnapshot {
        val message = findMessage(messageId) ?: return this
        val unfinished = message.parts.any { it is UIMessagePart.Reasoning && it.finishedAt == null }
        return if (unfinished) replaceMessageById(messageId, message.finishReasoning(), false) else this
    }

    private fun toMessageTerminalStatus(status: TurnExecutionStatus): MessageTerminalStatus? = when (status) {
        TurnExecutionStatus.CANCELLED -> MessageTerminalStatus.CANCELLED
        TurnExecutionStatus.FAILED -> MessageTerminalStatus.FAILED
        TurnExecutionStatus.INCOMPLETE -> MessageTerminalStatus.INCOMPLETE
        TurnExecutionStatus.INTERRUPTED -> MessageTerminalStatus.INTERRUPTED
        else -> null
    }

    private fun stepOutcomeForTerminal(status: TurnExecutionStatus): StepOutcome = when (status) {
        TurnExecutionStatus.COMPLETED -> StepOutcome.Final
        TurnExecutionStatus.CANCELLED -> StepOutcome.Cancelled
        TurnExecutionStatus.FAILED -> StepOutcome.Failed
        TurnExecutionStatus.INCOMPLETE -> StepOutcome.Incomplete
        TurnExecutionStatus.INTERRUPTED -> StepOutcome.Interrupted
        else -> error("finalizeTurn received non-terminal status $status")
    }

    /**
     * 关闭 transcript 中仍开放的 Step：非尾部一律 `Continue`，尾部按 [trailingOutcome] 落定。
     * [trailingOutcome] 为 null 表示 checkpoint 阶段——尾部 Step 仍开放，等待下一次采样或终态。
     * 已闭合的 Step 不重开，保证幂等重放。
     */
    private fun UIMessage.closingSteps(trailingOutcome: StepOutcome?): UIMessage {
        val stepIndices = parts.indices.filter { parts[it] is UIMessagePart.Step }
        if (stepIndices.isEmpty()) return this
        val trailingIndex = stepIndices.last()
        var changed = false
        val updated = parts.mapIndexed { index, part ->
            val step = part as? UIMessagePart.Step ?: return@mapIndexed part
            if (step.outcome != null) return@mapIndexed part
            val outcome = if (index == trailingIndex) trailingOutcome ?: return@mapIndexed part else StepOutcome.Continue
            changed = true
            step.copy(outcome = outcome)
        }
        return if (changed) copy(parts = updated) else this
    }

    private fun ConversationAggregateSnapshot.closingStepsInPlace(
        messageId: Uuid,
        trailingOutcome: StepOutcome,
    ): ConversationAggregateSnapshot {
        val message = findMessage(messageId) ?: return this
        val closed = message.closingSteps(trailingOutcome)
        return if (closed === message) this else replaceMessageById(messageId, closed, requireLastNode = false)
    }

    private fun resolveToolInteraction(
        current: ConversationAggregateSnapshot,
        command: ResolveToolInteraction,
    ): ConversationAggregateSnapshot {
        val durableMessage = current.findMessage(command.messageId) ?: return current
        val updatedDurableMessage = resolveToolInteractionInMessage(durableMessage, command) ?: return current
        return current.replaceMessageById(
            messageId = command.messageId,
            replacement = updatedDurableMessage,
            requireLastNode = true,
        )
    }

    private fun commitCheckpoint(
        current: ConversationAggregateSnapshot,
        command: TurnCheckpoint,
    ): ConversationAggregateSnapshot {
        val patches = if (command is ModelResponseCheckpoint) command.toolOutputCompactionPatches else emptyList()
        require(patches.map { it.locator }.distinct().size == patches.size) {
            "Tool Output compaction patches contain duplicate locators"
        }
        val assistantMessageId = command.turn.assistantMessageId
        val activePatches = patches.filter { it.locator.assistantMessageId == assistantMessageId }
        // The projection guard validates a durable model-output commit against the already-durable
        // Assistant. The awaiting-user commit legitimately advances Tool interaction state
        // (NotRequired → AwaitingApproval) past that durable version, so it is not a compaction source.
        if (command is ModelResponseCheckpoint && command.turnStatus == TurnExecutionStatus.RUNNING) {
            validateActiveCompactionProjection(current, assistantMessageId, command.assistantMessage, activePatches)
        }
        var replaced = current.replaceMessageById(
            assistantMessageId,
            command.assistantMessage.closingSteps(trailingOutcome = null),
            requireLastNode = true,
        )
        patches.filterNot { it.locator.assistantMessageId == assistantMessageId }.forEach { patch ->
            replaced = applyHistoricalToolOutputCompactionPatch(replaced, patch)
        }
        return replaced
    }

    /**
     * 校验一次 durable model-output 提交（RUNNING checkpoint 或无 Tool 终态）对 active Assistant 的
     * 压缩改写：既有 Tool 只能被其 typed patch 改变，正文/新 Step 等不参与比较。
     */
    private fun validateActiveCompactionProjection(
        current: ConversationAggregateSnapshot,
        assistantMessageId: Uuid,
        projected: UIMessage,
        activePatches: List<ToolOutputCompactionPatch>,
    ) {
        val activeSource = current.nodes.lastOrNull()?.currentMessage
            ?.takeIf { it.id == assistantMessageId }
            ?: error("Active Tool Output compaction source is missing")
        val expected = activePatches.fold(activeSource) { message, patch ->
            applyToolOutputCompactionPatch(message, patch)
        }
        require(projected.id == assistantMessageId) { "Active Tool Output compaction projection is missing" }
        activeSource.parts.filterIsInstance<UIMessagePart.Tool>().forEach { sourceTool ->
            require(
                toolByLocalCallId(projected, sourceTool.localCallId) ==
                    toolByLocalCallId(expected, sourceTool.localCallId),
            ) {
                "Active Tool Output projection changed an existing Tool outside its typed patch"
            }
        }
    }

    /**
     * 历史改写只接受 locator 指向的已消费纯文本 Tool Result，并只替换该 Tool 的 output/archive。
     * 可归档文本必须携带新 Artifact；可再生回查结果只能写固定 marker，不得复制 payload。
     * 任何正文、usage、时间、Tool 身份或其他 part 都没有进入命令协议，因而不能被顺带回写。
     */
    private fun applyHistoricalToolOutputCompactionPatch(
        current: ConversationAggregateSnapshot,
        patch: ToolOutputCompactionPatch,
    ): ConversationAggregateSnapshot {
        val node = current.nodes.firstOrNull { it.currentMessage.id == patch.locator.assistantMessageId }
            ?: error("Historical Tool Output patch is not on the selected branch: ${patch.locator.assistantMessageId}")
        val message = node.currentMessage
        require(message.role == MessageRole.ASSISTANT) {
            "Historical Tool Output patch must target an Assistant message"
        }
        val updated = applyToolOutputCompactionPatch(message, patch)
        return current.replaceMessageById(
            patch.locator.assistantMessageId,
            updated,
            requireLastNode = false,
        )
    }

    /** 对单条 Assistant 的一个 locator 应用并验证窄 Tool Output 压缩协议。 */
    private fun applyToolOutputCompactionPatch(
        message: UIMessage,
        patch: ToolOutputCompactionPatch,
    ): UIMessage {
        require(message.role == MessageRole.ASSISTANT) {
            "Tool Output compaction patch must target an Assistant message"
        }
        var matched = false
        val updatedParts = message.parts.map { part ->
            if (part !is UIMessagePart.Tool || part.localCallId != patch.locator.localCallId) return@map part
            require(!matched) { "Tool Output compaction patch matched more than one Tool" }
            val existingArchive = part.runtimeState.archive
            if (existingArchive != null) {
                // 同一 checkpoint 在“durable 已提交、lease 发布结果未返回”后可能被恢复重放。
                // 精确相同的 patch 必须幂等；不同归档仍是冲突，不能覆盖已有 durable 事实。
                require(existingArchive == patch.archive && part.output == listOf(patch.marker)) {
                    "Tool Output archive patch conflicts with the committed archive"
                }
                matched = true
                return@map part
            }
            val outputPolicy = part.runtimeState.outputPolicy
            if (patch.archive == null &&
                outputPolicy == ToolOutputPolicy.REGENERABLE_TEXT &&
                part.output == listOf(patch.marker) &&
                patch.marker.text == REGENERABLE_TOOL_OUTPUT_FOLDED_MARKER
            ) {
                matched = true
                return@map part
            }
            val expectedPolicy = if (patch.archive == null) {
                ToolOutputPolicy.REGENERABLE_TEXT
            } else {
                ToolOutputPolicy.ARCHIVABLE_TEXT
            }
            require(
                outputPolicy == expectedPolicy &&
                    part.output.isNotEmpty() &&
                    part.output.all { it is UIMessagePart.Text }
            ) { "Tool Output patch target has the wrong compaction policy" }
            val inlineText = part.output.filterIsInstance<UIMessagePart.Text>()
                .joinToString("\n") { it.text }
            require(
                estimateStableTextTokens(inlineText) - estimateStableTextTokens(patch.marker.text) >=
                    ContextBudget.TOOL_OUTPUT_MINIMUM_RESULT_NET_RECLAIM_ESTIMATED_TOKENS
            ) {
                "Tool Output patch is below the minimum estimated token reclaim"
            }
            val terminalStatus = part.resultStatus?.wireName
            require(terminalStatus == "completed" || terminalStatus == "failed") {
                "Tool Output patch target has no compactable terminal status"
            }
            val archive = patch.archive
            val runtimeState = if (archive == null) {
                require(patch.marker.text == REGENERABLE_TOOL_OUTPUT_FOLDED_MARKER) {
                    "Regenerable Tool Output marker is invalid"
                }
                part.runtimeState
            } else {
                val canonical = canonicalizeToolOutput(inlineText)
                require(archive.characters == canonical.length.toLong()) {
                    "Tool Output archive character count does not match"
                }
                require(archive.lines == virtualLineCount(canonical)) {
                    "Tool Output archive line count does not match"
                }
                require(patch.marker.text == buildToolOutputMarker(archive, terminalStatus, canonical)) {
                    "Tool Output marker does not match the archive"
                }
                part.runtimeState.copy(archive = archive)
            }
            matched = true
            part.copy(output = listOf(patch.marker), runtimeState = runtimeState)
        }
        require(matched) { "Tool Output patch locator does not resolve a Tool" }
        return message.copy(parts = updatedParts)
    }

    private fun toolByLocalCallId(message: UIMessage, localCallId: Uuid): UIMessagePart.Tool =
        message.parts.filterIsInstance<UIMessagePart.Tool>().firstOrNull { it.localCallId == localCallId }
            ?: error("Tool Output patch locator does not resolve a projected Tool")

    internal fun resolveToolInteractionInMessage(
        message: UIMessage,
        command: ResolveToolInteraction,
    ): UIMessage? {
        var matched = false
        val updatedParts = message.parts.map { part ->
            // locator 三元组必须整体命中：stepId 与 localCallId 任一不符即视为陈旧命令，绝不解析。
            if (part !is UIMessagePart.Tool ||
                part.stepId != command.stepId ||
                part.localCallId != command.localCallId
            ) {
                return@map part
            }
            if (part.hasReplayResult) return null
            if (part.interactionState == command.interaction) {
                matched = true
                return@map part
            }
            if (!part.isPending) return null
            if (!command.decision.matches(part.interactionState)) return null
            matched = true
            part.copy(interactionState = command.interaction)
        }
        return if (matched) message.copy(parts = updatedParts) else null
    }

    /** reducer 在 durable 写入边界再次校验 typed 决策与挂起交互类型一致。 */
    private fun ToolInteractionDecision.matches(awaiting: ToolInteractionState): Boolean = when (this) {
        ToolInteractionDecision.Approve,
        is ToolInteractionDecision.Deny,
        -> awaiting is ToolInteractionState.AwaitingApproval

        is ToolInteractionDecision.Answer -> awaiting is ToolInteractionState.AwaitingInput
    }

    /**
     * START 与首个 [UIMessagePart.Step] 同事务落库：已提交 Turn 至少有一个 Step，模型首字前的
     * 失败也有显式 Step 可收口。首个采样复用该 Step（accumulator 从草稿尾部 Step 播种）。
     */
    private fun openAssistantMessage(id: Uuid): UIMessage = UIMessage(
        id = id,
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Step(
                stepId = Uuid.random(),
                ordinal = 0,
                startedAt = Clock.System.now(),
            ),
        ),
    )

    private fun ConversationAggregateSnapshot.findMessage(messageId: Uuid): UIMessage? {
        nodes.lastOrNull()?.messages?.firstOrNull { it.id == messageId }?.let { return it }
        return nodes.asSequence().flatMap { it.messages.asSequence() }.firstOrNull { it.id == messageId }
    }

    private fun ConversationAggregateSnapshot.replaceMessageById(
        messageId: Uuid,
        replacement: UIMessage,
        requireLastNode: Boolean,
    ): ConversationAggregateSnapshot {
        require(replacement.id == messageId) {
            "Replacement message id ${replacement.id} does not match target $messageId"
        }
        val lastIndex = nodes.lastIndex
        val nodeIndex = if (lastIndex >= 0 && nodes[lastIndex].messages.any { it.id == messageId }) {
            lastIndex
        } else {
            check(!requireLastNode) { "Active assistant message is not in the last node" }
            nodes.indexOfFirst { node -> node.messages.any { it.id == messageId } }
        }
        check(nodeIndex >= 0) { "Assistant message is missing from the durable tree: $messageId" }
        val node = nodes[nodeIndex]
        val messageIndex = node.messages.indexOfFirst { it.id == messageId }
        if (node.messages[messageIndex] == replacement && node.selectIndex == messageIndex) return this
        val updatedMessages = node.messages.toMutableList().apply { set(messageIndex, replacement) }
        val updatedNodes = nodes.toMutableList().apply {
            set(nodeIndex, node.copy(messages = updatedMessages, selectIndex = messageIndex))
        }
        return copy(nodes = updatedNodes)
    }

    private fun ConversationAggregateSnapshot.markAssistantTerminalInternal(
        messageId: Uuid?,
        status: MessageTerminalStatus,
        reason: String?,
        detail: String? = null,
    ): ConversationAggregateSnapshot {
        if (messageId == null) return this
        val message = findMessage(messageId)?.takeIf { it.role == MessageRole.ASSISTANT } ?: return this
        return replaceMessageById(
            messageId,
            message.copy(
                terminalStatus = status,
                terminalReason = reason,
                terminalDetail = detail,
            ),
            requireLastNode = false,
        )
    }
}

/**
 * `START` 目标分支规划的结果：一次 `START` 将使用的目标 selected
 * prefix、新 owner node/message 与因果 USER anchor。调用方把它逐项填入 StartTurn 命令，
 * 锁内由同一函数复核。
 */
internal data class StartTurnTarget(
    val assistantNodeId: Uuid,
    val assistantMessageId: Uuid,
    val anchorNodeId: Uuid,
    val anchorMessageId: Uuid,
    val selectedPrefixMessageIds: List<Uuid>,
    val appendVariantToExistingAssistantNode: Boolean,
)

internal fun cancelPendingToolByUser(tool: UIMessagePart.Tool): UIMessagePart.Tool = tool.copy(
    output = listOf(
        UIMessagePart.Text(
            """{"status":"cancelled","error":"Generation cancelled by user before tool execution completed."}""",
        ),
    ),
    interactionState = ToolInteractionState.Denied("Generation cancelled by user"),
    resultStatus = ToolResultStatus.CANCELLED,
)

internal fun interruptPendingTool(tool: UIMessagePart.Tool): UIMessagePart.Tool = tool.copy(
    output = listOf(
        UIMessagePart.Text(
            """{"status":"interrupted","error":"Tool execution was interrupted before completion."}""",
        ),
    ),
    resultStatus = ToolResultStatus.INTERRUPTED,
)

/**
 * 把 loop 产出的执行事实归约为 durable `tool_execution` 行。Room 实体只在此处构造，
 * 生成循环不感知持久 schema；时间戳复用本次事务的 nowMillis，与 [buildTurn] 同口径。
 */
private fun ToolExecutionFact.toEntity(turnId: Uuid, nowMillis: Long): ToolExecutionEntity = ToolExecutionEntity(
    executionId = executionId,
    turnId = turnId.toString(),
    stepId = stepId.toString(),
    localCallId = localCallId.toString(),
    status = status,
    reason = null,
    childConversationId = childConversationId,
    childTurnId = childTurnId,
    subAssistantRunId = subAssistantRunId,
    createdAt = nowMillis,
    updatedAt = nowMillis,
)
