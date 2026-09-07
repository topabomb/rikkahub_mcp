package net.weero.measix.pilot.service.runtime

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.core.ToolOutputPolicy
import me.rerere.ai.ui.ToolOutputArchive
import me.rerere.ai.ui.ToolOutputArchiveRef
import me.rerere.ai.ui.ToolResultStatus
import me.rerere.ai.ui.ToolRuntimeState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.ToolOutputCompactionPatch
import net.weero.measix.pilot.data.ai.request.estimateStableTextTokens
import net.weero.measix.pilot.data.ai.tools.REGENERABLE_TOOL_OUTPUT_FOLDED_MARKER
import net.weero.measix.pilot.data.ai.tools.buildToolOutputMarker
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.MessageNode
import kotlin.uuid.Uuid

/**
 * ConversationTransition 权威测试的共享 fixture：reducer 零 IO、纯函数、未被触及节点保持
 * 同一实例引用（structural sharing）。turn/step/tool 与 header/tree 两类关注点分别由
 * [TurnTransitionTest] 与 [ConversationTransitionTest] 继承本基类后各自断言。
 */
internal abstract class ConversationTransitionTestBase {
    protected fun handle(conversationId: Uuid, assistantMessageId: Uuid) = TurnHandle(
        conversationId = conversationId,
        epoch = 1,
        turnId = Uuid.random(),
        assistantMessageId = assistantMessageId,
    )

    protected fun assistant(id: Uuid, parts: List<UIMessagePart> = listOf(UIMessagePart.Text("hi"))): UIMessage =
        UIMessage(id = id, role = MessageRole.ASSISTANT, parts = parts)

    protected fun user(id: Uuid): UIMessage =
        UIMessage(id = id, role = MessageRole.USER, parts = listOf(UIMessagePart.Text("q")))

    protected data class HistoricalCompactionScenario(
        val started: ConversationAggregateSnapshot,
        val historicalProjection: UIMessage,
        val activeReplacement: UIMessage,
        val command: ModelResponseCheckpoint,
    )

    protected fun historicalCompactionScenario(
        inlineText: String,
        markerTextOverride: String? = null,
        outputPolicy: ToolOutputPolicy = ToolOutputPolicy.ARCHIVABLE_TEXT,
    ): HistoricalCompactionScenario {
        val conversationId = Uuid.random()
        val turnId = Uuid.random()
        val historicalId = Uuid.random()
        val activeId = Uuid.random()
        val historicalTool = UIMessagePart.Tool(
            localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "historical",
            toolName = "safe_tool",
            input = "{}",
            output = listOf(UIMessagePart.Text(inlineText)),
            resultStatus = ToolResultStatus.COMPLETED,
            runtimeState = ToolRuntimeState(outputPolicy),
        )
        val historical = assistant(historicalId, listOf(historicalTool))
        val base = Conversation.ofId(conversationId).copy(
            messageNodes = listOf(
                MessageNode.of(historical),
                MessageNode.of(user(Uuid.random())),
            ),
        ).toSnapshot()
        val started = ConversationTransition.apply(
            base,
            TurnTransition.buildStartTurnCommand(
                current = base,
                turnId = turnId,
                modelContextCandidate = disclosureCandidate(),
                assistantMessageId = activeId,
                epoch = 1,
            ),
        )
        val activeReplacement = started.nodes.last().currentMessage.copy(
            parts = listOf(UIMessagePart.Text("current step")),
        )
        val archive = ToolOutputArchive(
            ref = 74,
            artifact = ToolOutputArchiveRef("tool_outputs/74.txt", "text/plain"),
            characters = inlineText.length.toLong(),
            lines = 1,
        )
        val durableArchive = archive.takeIf { outputPolicy == ToolOutputPolicy.ARCHIVABLE_TEXT }
        val marker = UIMessagePart.Text(markerTextOverride ?: if (durableArchive == null) {
            REGENERABLE_TOOL_OUTPUT_FOLDED_MARKER
        } else {
            buildToolOutputMarker(durableArchive, "completed", inlineText)
        })
        val historicalProjection = historical.copy(
            parts = listOf(
                historicalTool.copy(
                    output = listOf(marker),
                    runtimeState = historicalTool.runtimeState.copy(archive = durableArchive),
                ),
            ),
        )
        return HistoricalCompactionScenario(
            started = started,
            historicalProjection = historicalProjection,
            activeReplacement = activeReplacement,
            command = ModelResponseCheckpoint(
                turn = TurnHandle(conversationId, 1, turnId, activeId),
                step = StepHandle(Uuid.random()),
                assistantMessage = activeReplacement,
                turnStatus = TurnExecutionStatus.RUNNING,
                toolOutputCompactionPatches = listOf(
                    ToolOutputCompactionPatch(
                        locator = ToolCallLocator(historicalId, historicalTool.stepId, historicalTool.localCallId),
                        marker = marker,
                        archive = durableArchive,
                    ),
                ),
            ),
        )
    }

    protected data class ActiveCompactionScenario(
        val started: ConversationAggregateSnapshot,
        val command: ModelResponseCheckpoint,
    )

    protected fun activeRegenerableCompactionScenario(
        netReclaimEstimatedTokens: Long,
        markerText: String = REGENERABLE_TOOL_OUTPUT_FOLDED_MARKER,
    ): ActiveCompactionScenario {
        val conversationId = Uuid.random()
        val turnId = Uuid.random()
        val activeId = Uuid.random()
        val baseSnapshot = Conversation.ofId(conversationId).copy(
            messageNodes = listOf(
                MessageNode.of(assistant(Uuid.random())),
                MessageNode.of(user(Uuid.random())),
            ),
        ).toSnapshot()
        val started = ConversationTransition.apply(
            baseSnapshot,
            TurnTransition.buildStartTurnCommand(
                current = baseSnapshot,
                turnId = turnId,
                modelContextCandidate = disclosureCandidate(),
                assistantMessageId = activeId,
                epoch = 1,
            ),
        )
        val markerTokens = estimateStableTextTokens(markerText)
        val inlineText = "x".repeat(((markerTokens + netReclaimEstimatedTokens) * 4).toInt())
        val tool = UIMessagePart.Tool(
            localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "active-lookup",
            toolName = "read_tool_output",
            input = "{\"ref\":1}",
            output = listOf(UIMessagePart.Text(inlineText)),
            resultStatus = ToolResultStatus.COMPLETED,
            runtimeState = ToolRuntimeState(ToolOutputPolicy.REGENERABLE_TEXT),
        )
        val sourceMessage = started.nodes.last().currentMessage.copy(parts = listOf(tool))
        val sourceMessages = started.currentMessages().dropLast(1) + sourceMessage
        val durableNode = started.nodes.last().let { node ->
            node.copy(messages = node.messages.toMutableList().apply {
                set(node.selectIndex, sourceMessage)
            })
        }
        val source = started.copy(
            nodes = started.nodes.dropLast(1) + durableNode,
        )
        val marker = UIMessagePart.Text(markerText)
        val projectedAssistant = sourceMessage.copy(
            parts = listOf(tool.copy(output = listOf(marker))),
        )
        return ActiveCompactionScenario(
            started = source,
            command = ModelResponseCheckpoint(
                turn = TurnHandle(conversationId, 1, turnId, activeId),
                step = StepHandle(Uuid.random()),
                assistantMessage = projectedAssistant,
                turnStatus = TurnExecutionStatus.RUNNING,
                toolOutputCompactionPatches = listOf(
                    ToolOutputCompactionPatch(
                        locator = ToolCallLocator(activeId, tool.stepId, tool.localCallId),
                        marker = marker,
                        archive = null,
                    ),
                ),
            ),
        )
    }
}
