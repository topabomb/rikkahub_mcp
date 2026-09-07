package net.weero.measix.pilot.service.runtime

import net.weero.measix.pilot.testkit.sampledModelResult

import kotlinx.datetime.LocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.CURRENT_TOKEN_USAGE_SEMANTICS_VERSION
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.UsageCompleteness
import me.rerere.ai.core.ToolOutputPolicy
import me.rerere.ai.ui.ToolInteractionState
import kotlinx.datetime.toInstant
import me.rerere.ai.ui.ToolResultStatus
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.MessageTerminalStatus
import net.weero.measix.pilot.data.ai.request.ContextBudget
import net.weero.measix.pilot.data.ai.tools.REGENERABLE_TOOL_OUTPUT_FOLDED_MARKER
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * ConversationTransition 的 turn 链权威测试：START 新 owner / anchor、step checkpoint 与
 * tool output 压缩投影、tool interaction、FinalizeTurn 终态与恢复。
 * 核心：reducer 零 IO、纯函数、未被触及节点保持同一实例引用（structural sharing）。
 */
internal class TurnTransitionTest : ConversationTransitionTestBase() {
    // ---- START 新 owner / anchor 语义 ----

    @Test
    fun `START on an empty tree fails closed without a causal USER`() {
        val c = Conversation.ofId(Uuid.random())
        assertThrows(ConversationCommandConflictException::class.java) {
            TurnTransition.buildStartTurnCommand(
                c.toSnapshot(),
                turnId = Uuid.random(),
                modelContextCandidate = disclosureCandidate(),
            )
        }
    }

    @Test
    fun `START after a user message appends a single assistant node anchored to it`() {
        val userId = Uuid.random()
        val userNode = MessageNode.of(user(userId))
        val c = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(userNode))
        val assistantId = Uuid.random()
        val command = TurnTransition.buildStartTurnCommand(
            c.toSnapshot(),
            turnId = Uuid.random(),
            modelContextCandidate = disclosureCandidate(),
            assistantMessageId = assistantId,
        )
        assertEquals(userId, command.anchorMessageId)
        assertEquals(userNode.id, command.anchorNodeId)
        assertEquals(listOf(userId), command.expectedSelectedPrefixMessageIds)
        val r = ConversationTransition.apply(c.toSnapshot(), command)
        assertEquals(2, r.nodes.size)
        assertEquals(assistantId, r.nodes[1].messages.single().id)
        assertEquals(MessageRole.ASSISTANT, r.nodes[1].messages.single().role)
    }

    @Test
    fun `START on a non-terminal assistant adds a new variant instead of reusing the slot`() {
        val userId = Uuid.random()
        val assistantId = Uuid.random()
        val node = MessageNode.of(assistant(assistantId))
        val c = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(MessageNode.of(user(userId)), node))
        val replacementId = Uuid.random()
        val command = TurnTransition.buildStartTurnCommand(
            c.toSnapshot(),
            turnId = Uuid.random(),
            modelContextCandidate = disclosureCandidate(),
            assistantMessageId = replacementId,
        )
        // variant 追加：owner node 是既有 Assistant node，anchor 是其因果 USER。
        assertEquals(node.id, command.assistantNodeId)
        assertEquals(userId, command.anchorMessageId)
        // 被替换的旧 Assistant variant 退出目标 prefix（regenerate 语义）。
        assertEquals(listOf(userId), command.expectedSelectedPrefixMessageIds)

        val r = ConversationTransition.apply(c.toSnapshot(), command)
        assertEquals(2, r.nodes.size)
        assertEquals(2, r.nodes[1].messages.size)
        assertEquals(replacementId, r.nodes[1].currentMessage.id)
    }

    @Test
    fun `START on a terminal assistant regenerates in place and unselects the old variant`() {
        val userId = Uuid.random()
        val assistantId = Uuid.random()
        val finished = assistant(assistantId).copy(
            terminalStatus = MessageTerminalStatus.INCOMPLETE,
            terminalReason = "user_stop",
        )
        val node = MessageNode.of(finished)
        val c = Conversation.ofId(Uuid.random())
            .copy(messageNodes = listOf(MessageNode.of(user(userId)), node))
        val replacementId = Uuid.random()
        val command = TurnTransition.buildStartTurnCommand(
            c.toSnapshot(),
            turnId = Uuid.random(),
            modelContextCandidate = disclosureCandidate(),
            assistantMessageId = replacementId,
        )
        // regenerate 语义不豁免终态：旧 variant 退出目标 prefix，新 variant 追加到同一 node。
        assertEquals(node.id, command.assistantNodeId)
        assertEquals(userId, command.anchorMessageId)
        assertEquals(listOf(userId), command.expectedSelectedPrefixMessageIds)
        val r = ConversationTransition.apply(c.toSnapshot(), command)
        assertEquals(2, r.nodes.size)
        assertEquals(2, r.nodes[1].messages.size)
        assertEquals(replacementId, r.nodes[1].currentMessage.id)
    }

    @Test
    fun `START conflicts when the target branch changed after planStartTarget`() {
        val userId = Uuid.random()
        val c = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(MessageNode.of(user(userId))))
        val command = TurnTransition.buildStartTurnCommand(
            c.toSnapshot(),
            turnId = Uuid.random(),
            modelContextCandidate = disclosureCandidate(),
        )
        // 并发改成另一个 USER variant：锁内重算的 prefix token 不再匹配。
        val racedUserId = Uuid.random()
        val raced = c.copy(
            messageNodes = listOf(MessageNode.of(user(racedUserId))),
        ).toSnapshot()
        assertThrows(ConversationCommandConflictException::class.java) {
            ConversationTransition.apply(raced, command)
        }
    }

    @Test
    fun `fresh start creates a new assistant slot without inheriting prior turn usage`() {
        val previous = UIMessage.assistant("done").copy(
            usage = TokenUsage(
                inputTokens = 10_000,
                outputTokens = 500,
                peakRequestContextTokens = 10_500,
                observedProviderRequestCount = 2,
                successfulToolOutputCompactionBatchCount = 1,
                inputCompleteness = UsageCompleteness.COMPLETE,
                coreCompleteness = UsageCompleteness.COMPLETE,
                semanticsVersion = CURRENT_TOKEN_USAGE_SEMANTICS_VERSION,
            ),
        )
        val initial = Conversation.ofId(Uuid.random(), Uuid.random()).copy(
            messageNodes = listOf(MessageNode.of(previous), MessageNode.of(UIMessage.user("next"))),
        ).toSnapshot()
        val assistantMessageId = Uuid.random()

        val started = ConversationTransition.apply(
            initial,
            TurnTransition.buildStartTurnCommand(
                current = initial,
                turnId = Uuid.random(),
                modelContextCandidate = disclosureCandidate(),
                assistantMessageId = assistantMessageId,
                epoch = 1,
            ),
        )

        assertNull(started.nodes.last().currentMessage.usage)
    }

    @Test
    fun `delayed title and suggestion patches preserve completed tool output`() {
        val completedTool = UIMessagePart.Tool(
            localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "tool-1",
            toolName = "generate_image",
            input = "{}",
            output = listOf(UIMessagePart.Image("file:///result.png")),
        )
        val node = MessageNode.of(assistant(Uuid.random(), listOf(completedTool)))
        val snapshot = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(node)).toSnapshot()

        val updated = ConversationTransition.apply(
            snapshot,
            UpdateHeader(title = "Generated title", suggestions = listOf("Next")),
        )

        assertSame(node, updated.nodes.single())
        assertEquals(completedTool, updated.nodes.single().currentMessage.getTools().single())
        assertEquals("Generated title", updated.header.title)
        assertEquals(listOf("Next"), updated.header.chatSuggestions)
    }

    @Test
    fun `step checkpoint commits historical tool output replacements with the active message`() {
        val scenario = historicalCompactionScenario("inline tool result ".repeat(160))
        val change = ConversationTransition.plan(
            scenario.started,
            scenario.command,
            nowMillis = 1,
        )
        val durable = change as ConversationChange.Durable
        val checkpointed = durable.snapshot
        val mutation = (durable.write as ConversationWrite.Mutate).mutation

        assertEquals(scenario.historicalProjection, checkpointed.nodes.first().currentMessage)
        assertEquals(scenario.activeReplacement, checkpointed.nodes.last().currentMessage)
        assertEquals(
            setOf(checkpointed.nodes.first().id, checkpointed.nodes.last().id),
            mutation.upsertedNodes.mapTo(linkedSetOf(), MessageNode::id),
        )
    }

    @Test
    fun `no-tool terminal commits historical tool output replacements with the active message`() {
        val scenario = historicalCompactionScenario("inline tool result ".repeat(160))
        val finalize = FinalizeTurn(
            handle = scenario.command.turn,
            assistantMessage = scenario.activeReplacement,
            terminalStatus = TurnExecutionStatus.COMPLETED,
            terminalReason = null,
            toolOutputCompactionPatches = scenario.command.toolOutputCompactionPatches,
        )
        val change = ConversationTransition.plan(scenario.started, finalize, nowMillis = 1)
        val durable = change as ConversationChange.Durable
        val terminal = durable.snapshot
        val mutation = (durable.write as ConversationWrite.Mutate).mutation

        // 无 Tool Final step 的末批历史压缩随唯一 FinalizeTurn 落库，历史节点必须一并 upsert。
        assertEquals(scenario.historicalProjection, terminal.nodes.first().currentMessage)
        assertEquals(
            setOf(terminal.nodes.first().id, terminal.nodes.last().id),
            mutation.upsertedNodes.mapTo(linkedSetOf(), MessageNode::id),
        )
    }

    @Test
    fun `exact historical archive checkpoint replay is idempotent`() {
        val scenario = historicalCompactionScenario("durable tool result ".repeat(160))
        val committed = ConversationTransition.apply(scenario.started, scenario.command)

        val replayed = ConversationTransition.apply(committed, scenario.command)

        assertEquals(committed.nodes, replayed.nodes)
    }

    @Test
    fun `historical regenerable output folds without archive metadata and replays idempotently`() {
        val scenario = historicalCompactionScenario(
            inlineText = "numbered lookup result ".repeat(160),
            outputPolicy = ToolOutputPolicy.REGENERABLE_TEXT,
        )

        val committed = ConversationTransition.apply(scenario.started, scenario.command)
        val tool = committed.nodes.first().currentMessage.getTools().single()

        assertEquals(listOf(UIMessagePart.Text(REGENERABLE_TOOL_OUTPUT_FOLDED_MARKER)), tool.output)
        assertNull(tool.runtimeState.archive)
        assertEquals(committed.nodes, ConversationTransition.apply(committed, scenario.command).nodes)
    }

    @Test
    fun `active regenerable output accepts exactly 512 estimated tokens without archive metadata`() {
        val scenario = activeRegenerableCompactionScenario(
            ContextBudget.TOOL_OUTPUT_MINIMUM_RESULT_NET_RECLAIM_ESTIMATED_TOKENS,
        )

        val committed = ConversationTransition.apply(scenario.started, scenario.command)
        val tool = committed.nodes.last().currentMessage.getTools().single()

        assertEquals(listOf(UIMessagePart.Text(REGENERABLE_TOOL_OUTPUT_FOLDED_MARKER)), tool.output)
        assertNull(tool.runtimeState.archive)
    }

    @Test
    fun `active regenerable output rejects 511 estimated tokens of net reclaim`() {
        val scenario = activeRegenerableCompactionScenario(
            ContextBudget.TOOL_OUTPUT_MINIMUM_RESULT_NET_RECLAIM_ESTIMATED_TOKENS - 1,
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            ConversationTransition.apply(scenario.started, scenario.command)
        }

        assertTrue(error.message.orEmpty().contains("minimum estimated token reclaim"))
    }

    @Test
    fun `active regenerable output rejects a noncanonical marker`() {
        val scenario = activeRegenerableCompactionScenario(
            netReclaimEstimatedTokens =
                ContextBudget.TOOL_OUTPUT_MINIMUM_RESULT_NET_RECLAIM_ESTIMATED_TOKENS,
            markerText = "[wrong folded marker]",
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            ConversationTransition.apply(scenario.started, scenario.command)
        }

        assertTrue(error.message.orEmpty().contains("marker is invalid"))
    }

    @Test
    fun `active tool output cannot be folded by omitting the typed patch`() {
        val scenario = activeRegenerableCompactionScenario(
            ContextBudget.TOOL_OUTPUT_MINIMUM_RESULT_NET_RECLAIM_ESTIMATED_TOKENS,
        )
        val commandWithoutPatch = scenario.command.copy(toolOutputCompactionPatches = emptyList())

        assertThrows(IllegalArgumentException::class.java) {
            ConversationTransition.apply(scenario.started, commandWithoutPatch)
        }

    }

    @Test
    fun `historical archive checkpoint replay rejects a conflicting archive`() {
        val scenario = historicalCompactionScenario("durable tool result ".repeat(160))
        val committed = ConversationTransition.apply(scenario.started, scenario.command)
        val originalPatch = scenario.command.toolOutputCompactionPatches.single()
        val conflictingCommand = scenario.command.copy(
            toolOutputCompactionPatches = listOf(
                originalPatch.copy(
                    archive = requireNotNull(originalPatch.archive).copy(ref = 75),
                ),
            ),
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            ConversationTransition.apply(committed, conflictingCommand)
        }

        assertTrue(error.message.orEmpty().contains("conflicts with the committed archive"))
    }

    @Test
    fun `historical archive patch rejects output below minimum estimated token reclaim`() {
        val scenario = historicalCompactionScenario("x".repeat(127))

        val error = assertThrows(IllegalArgumentException::class.java) {
            ConversationTransition.apply(scenario.started, scenario.command)
        }

        assertTrue(error.message.orEmpty().contains("minimum estimated token reclaim"))
    }

    @Test
    fun `historical archive patch rejects a marker below minimum estimated token reclaim`() {
        val scenario = historicalCompactionScenario(
            inlineText = "x".repeat(256),
            markerTextOverride = "m".repeat(256),
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            ConversationTransition.apply(scenario.started, scenario.command)
        }

        assertTrue(error.message.orEmpty().contains("minimum estimated token reclaim"))
    }

    @Test
    fun `awaiting-user checkpoint may advance tool interaction state past the durable model output`() {
        val conversationId = Uuid.random()
        val assistantId = Uuid.random()
        val turnId = Uuid.random()
        val base = Conversation.ofId(conversationId).copy(
            messageNodes = listOf(MessageNode.of(user(Uuid.random()))),
        ).toSnapshot()
        val started = ConversationTransition.apply(
            base,
            TurnTransition.buildStartTurnCommand(
                current = base,
                turnId = turnId,
                modelContextCandidate = disclosureCandidate(),
                assistantMessageId = assistantId,
                epoch = 1,
            ),
        )
        val handle = TurnHandle(conversationId, 1, turnId, assistantId)
        val initialAssistant = started.nodes.last().currentMessage.let { raw -> raw.copy(parts = raw.parts.map {
            if (it is UIMessagePart.Step) it.copy(modelResult = sampledModelResult()) else it
        }) }
        val stepId = initialAssistant.parts.filterIsInstance<UIMessagePart.Step>().single().stepId
        val tool = UIMessagePart.Tool(
            localCallId = Uuid.random(), stepId = stepId, providerCallId = "call-1",
            toolName = "ask_user", input = "{}",
        )
        val running = ConversationTransition.apply(
            started,
            ModelResponseCheckpoint(
                turn = handle,
                step = StepHandle(stepId),
                assistantMessage = initialAssistant.copy(parts = initialAssistant.parts + tool),
                turnStatus = TurnExecutionStatus.RUNNING,
            ),
        )
        // The approval pause advances the same Tool to AwaitingApproval past the durable model output;
        // the compaction projection guard must not treat that interaction-state change as an out-of-patch rewrite.
        val awaiting = ConversationTransition.apply(
            running,
            ModelResponseCheckpoint(
                turn = handle,
                step = StepHandle(stepId),
                assistantMessage = initialAssistant.copy(parts = initialAssistant.parts +
                    tool.copy(interactionState = ToolInteractionState.AwaitingApproval)),
                turnStatus = TurnExecutionStatus.AWAITING_USER,
            ),
        )
        val finalTool = awaiting.nodes.last().currentMessage.parts
            .filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals(ToolInteractionState.AwaitingApproval, finalTool.interactionState)
    }

    // ---- FinalizeTurn terminalization ----

    @Test
    fun `finalize marks assistant terminal for failure status`() {
        val assistantId = Uuid.random()
        val node = MessageNode.of(assistant(assistantId))
        val c = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(node))
        val r = ConversationTransition.apply(
            c.toSnapshot(),
            FinalizeTurn(
                handle = handle(c.id, assistantId),
                assistantMessage = null,
                terminalStatus = TurnExecutionStatus.FAILED,
                terminalReason = "provider_error",
                terminalDetail = "sanitized provider detail",
            ),
        )
        val msg = r.nodes[0].messages.single()
        assertEquals(MessageTerminalStatus.FAILED, msg.terminalStatus)
        assertEquals("provider_error", msg.terminalReason)
        assertEquals("sanitized provider detail", msg.terminalDetail)
    }

    @Test
    fun `finalize completed keeps terminal null`() {
        val assistantId = Uuid.random()
        val node = MessageNode.of(assistant(assistantId))
        val c = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(node))
        val r = ConversationTransition.apply(
            c.toSnapshot(),
            FinalizeTurn(handle(c.id, assistantId), null, TurnExecutionStatus.COMPLETED, null),
        )
        assertNull(r.nodes[0].messages.single().terminalStatus)
    }

    @Test
    fun `finalize overwrites intermediate step time with stable turn finish time`() {
        val assistantId = Uuid.random()
        val stepFinishedAt = LocalDateTime(2026, 9, 1, 10, 0)
        val turnFinishedAt = LocalDateTime(2026, 9, 1, 10, 1)
        val message = assistant(assistantId).copy(finishedAt = stepFinishedAt)
        val conversation = Conversation.ofId(Uuid.random()).copy(
            messageNodes = listOf(MessageNode.of(message)),
        )

        val result = ConversationTransition.apply(
            conversation.toSnapshot(),
            FinalizeTurn(
                handle = handle(conversation.id, assistantId),
                assistantMessage = null,
                terminalStatus = TurnExecutionStatus.COMPLETED,
                terminalReason = null,
                finishedAt = turnFinishedAt,
            ),
        )

        assertEquals(turnFinishedAt, result.nodes.single().currentMessage.finishedAt)
    }

    @Test
    fun `regeneration failure before a chunk keeps the previous assistant variant`() {
        val conversationId = Uuid.random()
        val original = assistant(Uuid.random(), listOf(UIMessagePart.Text("completed answer")))
        val userNode = MessageNode.of(user(Uuid.random()))
        val node = MessageNode.of(original)
        val base = Conversation.ofId(conversationId).copy(messageNodes = listOf(userNode, node)).toSnapshot()
        val replacementId = Uuid.random()
        val started = ConversationTransition.apply(
            base,
            TurnTransition.buildStartTurnCommand(
                current = base,
                turnId = Uuid.random(),
                modelContextCandidate = disclosureCandidate(),
                assistantMessageId = replacementId,
            ),
        )

        val failed = ConversationTransition.apply(
            started,
            FinalizeTurn(
                handle(conversationId, replacementId),
                assistantMessage = null,
                terminalStatus = TurnExecutionStatus.FAILED,
                terminalReason = "provider_failed",
            ),
        )

        val assistantNode = failed.nodes.last()
        assertEquals(2, assistantNode.messages.size)
        assertEquals(original, assistantNode.messages.first())
        assertEquals(replacementId, assistantNode.currentMessage.id)
        assertEquals(MessageTerminalStatus.FAILED, assistantNode.currentMessage.terminalStatus)
    }

    @Test
    fun `finalize commits the assistant provided by the finalizer verbatim`() {
        val assistantId = Uuid.random()
        // 关闭工具由 TurnFinalizer 在提交前完成，reducer 只落库并标记终态，不再自行改写工具。
        val closedTool = UIMessagePart.Tool(
            localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "t1",
            toolName = "shell",
            input = "{}",
            output = listOf(UIMessagePart.Text("""{"status":"interrupted"}""")),
            resultStatus = me.rerere.ai.ui.ToolResultStatus.INTERRUPTED,
            interactionState = ToolInteractionState.AwaitingApproval,
        )
        val node = MessageNode.of(assistant(assistantId, listOf(UIMessagePart.Text("pre"), closedTool)))
        val c = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(node))
        val r = ConversationTransition.apply(
            c.toSnapshot(),
            FinalizeTurn(
                handle(c.id, assistantId),
                assistant(assistantId, listOf(UIMessagePart.Text("pre"), closedTool)),
                TurnExecutionStatus.INTERRUPTED,
                null,
            ),
        )
        val committed = r.nodes[0].messages.single()
        assertEquals(MessageTerminalStatus.INTERRUPTED, committed.terminalStatus)
        val tool = committed.parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertSame(closedTool, tool)
        assertTrue(tool.hasReplayResult)
    }

    @Test
    fun `historical nodes stay identical across FinalizeTurn`() {
        val finishedReasoning = UIMessagePart.Reasoning(
            reasoning = "already done",
            finishedAt = kotlin.time.Instant.fromEpochMilliseconds(1),
        )
        val histUser = MessageNode.of(user(Uuid.random()))
        val histAssistant = MessageNode.of(
            assistant(Uuid.random(), listOf(finishedReasoning, UIMessagePart.Text("old"))),
        )
        val activeId = Uuid.random()
        val active = MessageNode.of(
            assistant(
                activeId,
                listOf(
                    UIMessagePart.Reasoning(reasoning = "live", finishedAt = null),
                    UIMessagePart.Text("new"),
                ),
            ),
        )
        val c = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(histUser, histAssistant, active))
        val r = ConversationTransition.apply(
            c.toSnapshot(),
            FinalizeTurn(handle(c.id, activeId), null, TurnExecutionStatus.COMPLETED, null),
        )
        assertSame(histUser, r.nodes[0])
        assertSame(histAssistant, r.nodes[1])
        assertNotSame(active, r.nodes[2])
        val liveReasoning = r.nodes[2].messages.single().parts.filterIsInstance<UIMessagePart.Reasoning>().single()
        assertTrue(liveReasoning.finishedAt != null)
    }

    @Test
    fun `recovery refuses a turn whose owning assistant message is missing`() {
        val conversationId = Uuid.random()
        val assistantId = Uuid.random()
        val snapshot = Conversation.ofId(conversationId).copy(
            messageNodes = listOf(MessageNode.of(UIMessage.user("preserved"))),
        ).toSnapshot()

        val failure = runCatching {
            ConversationTransition.apply(
                snapshot,
                RecoverInterruptedTurn(
                    turnId = Uuid.random(),
                    assistantMessageId = assistantId,
                    assistantMessage = null,
                    terminalReason = "process_restarted",
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `ResolveToolInteraction marks tool approval`() {
        val assistantId = Uuid.random()
        val tool = UIMessagePart.Tool(
            localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "t1",
            toolName = "shell",
            input = "{}",
            interactionState = ToolInteractionState.AwaitingApproval,
        )
        val node = MessageNode.of(assistant(assistantId, listOf(tool)))
        val c = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(node))
        val r = ConversationTransition.apply(
            c.toSnapshot(),
            ResolveToolInteraction(assistantId, tool.stepId, tool.localCallId, ToolInteractionDecision.Deny("no"), handle(c.id, assistantId)),
        )
        val updated = r.nodes[0].messages.single().parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals(ToolInteractionState.Denied("no"), updated.interactionState)
    }

    @Test
    fun `approval updates the durable tool interaction state`() {
        val assistantId = Uuid.random()
        val pending = UIMessagePart.Tool(
            localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "t1",
            toolName = "shell",
            input = "{}",
            interactionState = ToolInteractionState.AwaitingApproval,
        )
        val durableMessage = assistant(assistantId, listOf(pending))
        val base = Conversation.ofId(Uuid.random()).copy(
            messageNodes = listOf(MessageNode.of(durableMessage)),
        ).toSnapshot()

        val reduced = ConversationTransition.apply(
            base,
            ResolveToolInteraction(assistantId, pending.stepId, pending.localCallId, ToolInteractionDecision.Approve, handle(base.conversationId, assistantId)),
        )

        assertEquals(durableMessage.parts.size, reduced.nodes.single().currentMessage.parts.size)
        assertEquals(
            ToolInteractionState.Approved,
            reduced.nodes.single().currentMessage.parts.filterIsInstance<UIMessagePart.Tool>().single().interactionState,
        )
    }
    @Test
    fun `checkpoint rejects a result fact that disagrees with transcript before committing`() {
        val messageId = Uuid.random()
        val tool = UIMessagePart.Tool(
            localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call",
            toolName = "shell", input = "{}", output = listOf(UIMessagePart.Text("failed")),
            resultStatus = ToolResultStatus.FAILED,
        )
        val message = assistant(messageId, listOf(tool))
        val current = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(MessageNode.of(message))).toSnapshot()
        val checkpoint = ToolResultCheckpoint(
            turn = handle(current.conversationId, messageId), step = StepHandle(tool.stepId),
            assistantMessage = message,
            toolResults = listOf(net.weero.measix.pilot.data.ai.ToolResultFact(
                me.rerere.ai.core.ToolCallLocator(messageId, tool.stepId, tool.localCallId), ToolResultStatus.COMPLETED,
            )),
        )
        val failure = runCatching { ConversationTransition.plan(current, checkpoint, 1) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message!!.contains("transcript and result"))
        assertSame(message, current.nodes.single().currentMessage)
    }

    @Test
    fun `checkpoint rejects a pending batch declared running`() {
        val messageId = Uuid.random()
        val tool = UIMessagePart.Tool(
            localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call",
            toolName = "shell", input = "{}", interactionState = ToolInteractionState.AwaitingApproval,
        )
        val message = assistant(messageId, listOf(tool))
        val current = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(MessageNode.of(message))).toSnapshot()
        val checkpoint = ModelResponseCheckpoint(
            turn = handle(current.conversationId, messageId), step = StepHandle(tool.stepId),
            assistantMessage = message, turnStatus = TurnExecutionStatus.RUNNING,
        )
        val failure = runCatching { ConversationTransition.plan(current, checkpoint, 1) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message!!.contains("pause status"))
    }

    @Test
    fun `finalization rejects an open predecessor instead of retroactively guessing Continue`() {
        val messageId = Uuid.random()
        val message = assistant(messageId).let { it.copy(parts = it.parts + TurnTransition.openStep(1).copy(modelResult = sampledModelResult("stop"))) }
        val current = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(MessageNode.of(message))).toSnapshot()
        val failure = runCatching {
            ConversationTransition.apply(current, FinalizeTurn(
                handle(current.conversationId, messageId), message, TurnExecutionStatus.COMPLETED, null,
            ))
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message!!.contains("previous Step must close"))
    }

    @Test
    fun `START and terminal commands preserve allocated identities and timestamps when reduced again`() {
        val current = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(MessageNode.of(user(Uuid.random())))).toSnapshot()
        val start = TurnTransition.buildStartTurnCommand(current, Uuid.random(), disclosureCandidate())
        val first = ConversationTransition.apply(current, start)
        val repeated = ConversationTransition.apply(current, start)
        assertEquals(first, repeated)
        val finishTime = kotlinx.datetime.LocalDateTime(2026, 9, 7, 12, 0)
        val terminal = FinalizeTurn(
            handle(first.conversationId, start.assistantMessageId), null,
            TurnExecutionStatus.CANCELLED, "user_stop", finishedAt = finishTime,
        )
        val closed = ConversationTransition.apply(first, terminal)
        assertEquals(closed, ConversationTransition.apply(first, terminal))
        assertEquals(finishTime, closed.nodes.last().currentMessage.finishedAt)
        assertEquals(
            finishTime.toInstant(kotlinx.datetime.TimeZone.currentSystemDefault()),
            closed.nodes.last().currentMessage.parts.filterIsInstance<UIMessagePart.Step>().single().finishedAt,
        )
    }

    @Test
    fun `checkpoint cannot replace a committed Step identity`() {
        val message = assistant(Uuid.random())
        val current = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(MessageNode.of(message))).toSnapshot()
        val replacement = message.copy(parts = message.parts.map {
            if (it is UIMessagePart.Step) it.copy(stepId = Uuid.random()) else it
        })
        val failure = runCatching { ConversationTransition.apply(current, ModelResponseCheckpoint(
            handle(current.conversationId, message.id),
            StepHandle(replacement.parts.filterIsInstance<UIMessagePart.Step>().single().stepId),
            replacement, turnStatus = TurnExecutionStatus.RUNNING,
        )) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message!!.contains("Step identity"))
    }

    @Test
    fun `an unsampled Step cannot publish a model checkpoint or complete the Turn`() {
        val message = assistant(Uuid.random()).let { it.copy(parts = it.parts.map { part ->
            if (part is UIMessagePart.Step) part.copy(modelResult = null) else part
        }) }
        val current = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(MessageNode.of(message))).toSnapshot()
        val owner = handle(current.conversationId, message.id)
        val stepId = message.parts.filterIsInstance<UIMessagePart.Step>().single().stepId
        assertThrows(IllegalArgumentException::class.java) {
            ConversationTransition.apply(current, ModelResponseCheckpoint(owner, StepHandle(stepId), message, TurnExecutionStatus.RUNNING))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConversationTransition.apply(current, FinalizeTurn(owner, message, TurnExecutionStatus.COMPLETED, null))
        }
        val cancelled = ConversationTransition.apply(current, FinalizeTurn(owner, message, TurnExecutionStatus.CANCELLED, "user_stop"))
        assertEquals(me.rerere.ai.ui.StepOutcome.Cancelled, cancelled.nodes.single().currentMessage.parts.filterIsInstance<UIMessagePart.Step>().single().outcome)
    }

}
