package net.weero.measix.pilot.service.runtime

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.core.ToolOutputPolicy
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.MessageTerminalStatus
import net.weero.measix.pilot.data.ai.CheckpointKind
import net.weero.measix.pilot.data.ai.ContextTrimmingPolicy
import net.weero.measix.pilot.data.ai.ToolOutputCompactionPatch
import net.weero.measix.pilot.data.ai.estimateStableTextTokens
import net.weero.measix.pilot.data.ai.tools.ToolInteractionKind
import net.weero.measix.pilot.data.ai.tools.ToolOutputArchive
import net.weero.measix.pilot.data.ai.tools.ToolOutputArchiveRef
import net.weero.measix.pilot.data.ai.tools.ToolRuntimeMetadata
import net.weero.measix.pilot.data.ai.tools.REGENERABLE_TOOL_OUTPUT_FOLDED_MARKER
import net.weero.measix.pilot.data.ai.tools.buildToolOutputMarker
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
 * ConversationTransition 权威测试。
 * 核心：reducer 零 IO、纯函数、未被触及节点保持同一实例引用（structural sharing）。
 */
class ConversationTransitionTest {
    private fun handle(conversationId: Uuid, assistantMessageId: Uuid) = TurnHandle(
        conversationId = conversationId,
        epoch = 1,
        turnId = Uuid.random(),
        assistantMessageId = assistantMessageId,
    )

    private fun assistant(id: Uuid, parts: List<UIMessagePart> = listOf(UIMessagePart.Text("hi"))): UIMessage =
        UIMessage(id = id, role = MessageRole.ASSISTANT, parts = parts)

    private fun user(id: Uuid): UIMessage =
        UIMessage(id = id, role = MessageRole.USER, parts = listOf(UIMessagePart.Text("q")))

    private data class HistoricalCompactionScenario(
        val started: ConversationAggregateSnapshot,
        val historicalProjection: UIMessage,
        val activeReplacement: UIMessage,
        val command: CommitCheckpoint,
    )

    private fun historicalCompactionScenario(
        inlineText: String,
        markerTextOverride: String? = null,
        outputPolicy: ToolOutputPolicy = ToolOutputPolicy.ARCHIVABLE_TEXT,
    ): HistoricalCompactionScenario {
        val conversationId = Uuid.random()
        val turnId = Uuid.random()
        val historicalId = Uuid.random()
        val activeId = Uuid.random()
        val historicalTool = UIMessagePart.Tool(
            toolCallId = "historical",
            toolName = "safe_tool",
            input = "{}",
            output = listOf(UIMessagePart.Text(inlineText)),
            metadata = ToolRuntimeMetadata.applyTo(
                null,
                ToolRuntimeMetadata.forResult(
                    interaction = ToolInteractionKind.NONE,
                    outputPolicy = outputPolicy.name,
                    terminalStatus = "completed",
                    resultBatchOrdinal = 0,
                ),
            ),
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
            ConversationTransition.buildStartTurnCommand(
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
                    metadata = durableArchive?.let { ToolRuntimeMetadata.withArchive(historicalTool.metadata, it) }
                        ?: historicalTool.metadata,
                ),
            ),
        )
        return HistoricalCompactionScenario(
            started = started,
            historicalProjection = historicalProjection,
            activeReplacement = activeReplacement,
            command = CommitCheckpoint(
                handle = TurnHandle(conversationId, 1, turnId, activeId),
                kind = CheckpointKind.STEP_COMPLETED,
                messages = listOf(historicalProjection, activeReplacement),
                turnStatus = TurnExecutionStatus.RUNNING,
                turnReason = null,
                toolExecution = null,
                toolOutputCompactionPatches = listOf(
                    ToolOutputCompactionPatch(
                        locator = ToolCallLocator(historicalId, 0),
                        marker = marker,
                        archive = durableArchive,
                    ),
                ),
            ),
        )
    }

    private data class ActiveCompactionScenario(
        val started: ConversationAggregateSnapshot,
        val command: CommitCheckpoint,
    )

    private fun activeRegenerableCompactionScenario(
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
            ConversationTransition.buildStartTurnCommand(
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
            toolCallId = "active-lookup",
            toolName = "read_tool_output",
            input = "{\"ref\":1}",
            output = listOf(UIMessagePart.Text(inlineText)),
            metadata = ToolRuntimeMetadata.applyTo(
                null,
                ToolRuntimeMetadata.forResult(
                    interaction = ToolInteractionKind.NONE,
                    outputPolicy = ToolOutputPolicy.REGENERABLE_TEXT.name,
                    terminalStatus = "completed",
                    resultBatchOrdinal = 0,
                ),
            ),
        )
        val active = requireNotNull(started.activeTurn)
        val sourceMessage = started.nodes.last().currentMessage.copy(parts = listOf(tool))
        val sourceMessages = active.messages.dropLast(1) + sourceMessage
        val durableNode = started.nodes.last().let { node ->
            node.copy(messages = node.messages.toMutableList().apply {
                set(node.selectIndex, sourceMessage)
            })
        }
        val source = started.copy(
            nodes = started.nodes.dropLast(1) + durableNode,
            activeTurn = active.copy(messages = sourceMessages),
        )
        val marker = UIMessagePart.Text(markerText)
        val projectedMessages = sourceMessages.dropLast(1) + sourceMessage.copy(
            parts = listOf(tool.copy(output = listOf(marker))),
        )
        return ActiveCompactionScenario(
            started = source,
            command = CommitCheckpoint(
                handle = TurnHandle(conversationId, 1, turnId, activeId),
                kind = CheckpointKind.STEP_COMPLETED,
                messages = projectedMessages,
                turnStatus = TurnExecutionStatus.RUNNING,
                turnReason = null,
                toolExecution = null,
                toolOutputCompactionPatches = listOf(
                    ToolOutputCompactionPatch(
                        locator = ToolCallLocator(activeId, 0),
                        marker = marker,
                        archive = null,
                    ),
                ),
            ),
        )
    }

    // ---- START 新 owner / anchor 语义 ----

    @Test
    fun `START on an empty tree fails closed without a causal USER`() {
        val c = Conversation.ofId(Uuid.random())
        assertThrows(ConversationCommandConflictException::class.java) {
            ConversationTransition.buildStartTurnCommand(
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
        val command = ConversationTransition.buildStartTurnCommand(
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
        val command = ConversationTransition.buildStartTurnCommand(
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
        val command = ConversationTransition.buildStartTurnCommand(
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
        val command = ConversationTransition.buildStartTurnCommand(
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
    fun `pin toggle does not invalidate search metadata`() {
        val old = Conversation.ofId(Uuid.random()).toSnapshot().header
        val updated = ConversationTransition.applyHeader(old, TogglePinned)
        val mutation = (ConversationTransition.planHeader(old, TogglePinned, old.updateAt).write).mutation

        assertTrue(updated.isPinned)
        assertEquals(false, mutation.searchMetadataChanged)
        assertEquals(true, mutation.headerPatch?.isPinned)
    }

    // ---- Structural sharing ----

    @Test
    fun `untouched nodes keep identical references`() {
        val n0 = MessageNode.of(user(Uuid.random()))
        val n1 = MessageNode.of(assistant(Uuid.random()))
        val n2 = MessageNode.of(user(Uuid.random()))
        val c = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(n0, n1, n2))
        // 只更新 header（title），不触碰节点
        val r = ConversationTransition.apply(c.toSnapshot(), UpdateHeader(title = "New Title"))
        assertEquals(3, r.nodes.size)
        assertSame(n0, r.nodes[0])
        assertSame(n1, r.nodes[1])
        assertSame(n2, r.nodes[2])
        assertEquals("New Title", r.header.title)
    }

    @Test
    fun `delayed title and suggestion patches preserve completed tool output`() {
        val completedTool = UIMessagePart.Tool(
            toolCallId = "tool-1",
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
        assertEquals(completedTool, updated.nodes.single().currentMessage.parts.single())
        assertEquals("Generated title", updated.header.title)
        assertEquals(listOf("Next"), updated.header.chatSuggestions)
    }

    @Test
    fun `structural sharing across node replacement`() {
        val n0 = MessageNode.of(user(Uuid.random()))
        val n1 = MessageNode.of(assistant(Uuid.random()))
        val c = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(n0, n1))
        val r = ConversationTransition.apply(c.toSnapshot(), AppendUserMessage(user(Uuid.random())))
        // 原节点引用不变，仅末尾追加
        assertSame(n0, r.nodes[0])
        assertSame(n1, r.nodes[1])
        assertEquals(3, r.nodes.size)
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
        val tool = committed.nodes.first().currentMessage.parts.single() as UIMessagePart.Tool

        assertEquals(listOf(UIMessagePart.Text(REGENERABLE_TOOL_OUTPUT_FOLDED_MARKER)), tool.output)
        assertNull(ToolRuntimeMetadata.archiveOf(tool.metadata))
        assertEquals(committed.nodes, ConversationTransition.apply(committed, scenario.command).nodes)
    }

    @Test
    fun `active regenerable output accepts exactly 512 estimated tokens without archive metadata`() {
        val scenario = activeRegenerableCompactionScenario(
            ContextTrimmingPolicy.TOOL_OUTPUT_MINIMUM_RESULT_NET_RECLAIM_ESTIMATED_TOKENS,
        )

        val committed = ConversationTransition.apply(scenario.started, scenario.command)
        val tool = committed.nodes.last().currentMessage.parts.single() as UIMessagePart.Tool

        assertEquals(listOf(UIMessagePart.Text(REGENERABLE_TOOL_OUTPUT_FOLDED_MARKER)), tool.output)
        assertNull(ToolRuntimeMetadata.archiveOf(tool.metadata))
    }

    @Test
    fun `active regenerable output rejects 511 estimated tokens of net reclaim`() {
        val scenario = activeRegenerableCompactionScenario(
            ContextTrimmingPolicy.TOOL_OUTPUT_MINIMUM_RESULT_NET_RECLAIM_ESTIMATED_TOKENS - 1,
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            ConversationTransition.apply(scenario.started, scenario.command)
        }

        assertTrue(error.message.orEmpty().contains("minimum estimated token reclaim"))
    }

    @Test
    fun `active compaction validates the durable node instead of transient streaming projection`() {
        val minimum = ContextTrimmingPolicy.TOOL_OUTPUT_MINIMUM_RESULT_NET_RECLAIM_ESTIMATED_TOKENS
        val scenario = activeRegenerableCompactionScenario(minimum - 1)
        val active = requireNotNull(scenario.started.activeTurn)
        val markerTokens = estimateStableTextTokens(REGENERABLE_TOOL_OUTPUT_FOLDED_MARKER)
        val transientMessage = active.messages.last().let { message ->
            val tool = message.parts.single() as UIMessagePart.Tool
            message.copy(parts = listOf(tool.copy(
                output = listOf(UIMessagePart.Text(
                    "x".repeat(((markerTokens + minimum) * 4).toInt()),
                )),
            )))
        }
        val transientlyDiverged = scenario.started.copy(
            activeTurn = active.copy(messages = active.messages.dropLast(1) + transientMessage),
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            ConversationTransition.apply(transientlyDiverged, scenario.command)
        }

        assertTrue(error.message.orEmpty().contains("minimum estimated token reclaim"))
    }

    @Test
    fun `active regenerable output rejects a noncanonical marker`() {
        val scenario = activeRegenerableCompactionScenario(
            netReclaimEstimatedTokens =
                ContextTrimmingPolicy.TOOL_OUTPUT_MINIMUM_RESULT_NET_RECLAIM_ESTIMATED_TOKENS,
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
            ContextTrimmingPolicy.TOOL_OUTPUT_MINIMUM_RESULT_NET_RECLAIM_ESTIMATED_TOKENS,
        )
        val commandWithoutPatch = scenario.command.copy(toolOutputCompactionPatches = emptyList())

        val error = assertThrows(IllegalArgumentException::class.java) {
            ConversationTransition.apply(scenario.started, commandWithoutPatch)
        }

        assertTrue(error.message.orEmpty().contains("outside its typed patch"))
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
    fun `first user append commits local title in the same mutation`() {
        val snapshot = Conversation.ofId(Uuid.random()).toSnapshot()
        val command = AppendUserMessage(user(Uuid.random()), initialTitle = "Immediate local title")
        val result = ConversationTransition.apply(snapshot, command)

        assertEquals("Immediate local title", result.header.title)
        assertEquals(1, result.nodes.size)
        val mutate = (
            ConversationTransition.plan(snapshot, command, snapshot.header.updateAt) as ConversationChange.Durable
            ).write as ConversationWrite.Mutate
        assertEquals("Immediate local title", mutate.mutation.headerPatch?.title)
        assertEquals(1, mutate.mutation.upsertedNodes.size)
    }

    @Test
    fun `later append cannot replace an established title`() {
        val snapshot = Conversation.ofId(Uuid.random())
            .copy(title = "Established title")
            .toSnapshot()
        val result = ConversationTransition.apply(
            snapshot,
            AppendUserMessage(user(Uuid.random()), initialTitle = "Another local title"),
        )

        assertEquals("Established title", result.header.title)
    }

    @Test
    fun `model title CAS cannot overwrite a title changed after generation began`() {
        val local = Conversation.ofId(Uuid.random()).copy(title = "Local").toSnapshot()
        val accepted = ConversationTransition.apply(local, UpdateTitleIfCurrent("Local", "Model"))
        val manual = ConversationTransition.apply(local, UpdateHeader(title = "Manual"))
        val rejected = ConversationTransition.apply(manual, UpdateTitleIfCurrent("Local", "Model"))

        assertEquals("Model", accepted.header.title)
        assertEquals("Manual", rejected.header.title)
        assertSame(manual, rejected)
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
                messages = null,
                terminalStatus = TurnExecutionStatus.FAILED,
                terminalReason = "provider_error",
                closeInterruptedTools = false,
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
            FinalizeTurn(handle(c.id, assistantId), null, TurnExecutionStatus.COMPLETED, null, closeInterruptedTools = false),
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
                messages = null,
                terminalStatus = TurnExecutionStatus.COMPLETED,
                terminalReason = null,
                closeInterruptedTools = false,
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
            ConversationTransition.buildStartTurnCommand(
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
                messages = null,
                terminalStatus = TurnExecutionStatus.FAILED,
                terminalReason = "provider_failed",
                closeInterruptedTools = false,
            ),
        )

        val assistantNode = failed.nodes.last()
        assertEquals(2, assistantNode.messages.size)
        assertEquals(original, assistantNode.messages.first())
        assertEquals(replacementId, assistantNode.currentMessage.id)
        assertEquals(MessageTerminalStatus.FAILED, assistantNode.currentMessage.terminalStatus)
    }

    @Test
    fun `finalize with closeInterruptedTools closes pending tools`() {
        val assistantId = Uuid.random()
        val tool = UIMessagePart.Tool(
            toolCallId = "t1",
            toolName = "shell",
            input = "{}",
            approvalState = ToolApprovalState.Pending,
        )
        val node = MessageNode.of(assistant(assistantId, listOf(UIMessagePart.Text("pre"), tool)))
        val c = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(node))
        val r = ConversationTransition.apply(
            c.toSnapshot(),
            FinalizeTurn(handle(c.id, assistantId), null, TurnExecutionStatus.INTERRUPTED, null, closeInterruptedTools = true),
        )
        val closedTool = r.nodes[0].messages.single().parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals(MessageTerminalStatus.INTERRUPTED, r.nodes[0].messages.single().terminalStatus)
        // interrupt 语义：写入 interrupted output（hasReplayResult=true），保留原 approvalState
        assertTrue(closedTool.hasReplayResult)
        val output = closedTool.output.filterIsInstance<UIMessagePart.Text>().single().text
        assertTrue(output.contains("interrupted"))
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
            FinalizeTurn(handle(c.id, activeId), null, TurnExecutionStatus.COMPLETED, null, closeInterruptedTools = false),
        )
        assertSame(histUser, r.nodes[0])
        assertSame(histAssistant, r.nodes[1])
        assertNotSame(active, r.nodes[2])
        val liveReasoning = r.nodes[2].messages.single().parts.filterIsInstance<UIMessagePart.Reasoning>().single()
        assertTrue(liveReasoning.finishedAt != null)
    }

    // ---- Tree operations ----

    @Test
    fun `deleteMessage removes the owning node`() {
        val target = user(Uuid.random())
        val c = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(
            MessageNode.of(target),
            MessageNode.of(assistant(Uuid.random())),
        ))
        val r = ConversationTransition.apply(c.toSnapshot(), DeleteMessage(target.id))
        assertEquals(1, r.nodes.size)
    }

    @Test
    fun `selectNodeVariant switches variant`() {
        val nodeId = Uuid.random()
        val v0 = user(Uuid.random())
        val v1 = user(Uuid.random())
        val node = MessageNode(id = nodeId, messages = listOf(v0, v1), selectIndex = 0)
        val c = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(node))
        val r = ConversationTransition.apply(c.toSnapshot(), SelectNodeVariant(nodeId, 1))
        assertEquals(1, r.nodes[0].selectIndex)
        assertSame(v0, r.nodes[0].messages[0])
        assertSame(v1, r.nodes[0].messages[1])
    }

    @Test
    fun `truncateToNodeIndex shrinks tree`() {
        val n0 = MessageNode.of(user(Uuid.random()))
        val n1 = MessageNode.of(assistant(Uuid.random()))
        val n2 = MessageNode.of(user(Uuid.random()))
        val c = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(n0, n1, n2))
        val r = ConversationTransition.apply(c.toSnapshot(), TruncateToNodeIndex(1))
        assertEquals(2, r.nodes.size)
        assertSame(n0, r.nodes[0])
        assertSame(n1, r.nodes[1])
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
                    messages = null,
                    terminalReason = "process_restarted",
                    closeInterruptedTools = true,
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `ResolveToolInteraction marks tool approval`() {
        val assistantId = Uuid.random()
        val tool = UIMessagePart.Tool(
            toolCallId = "t1",
            toolName = "shell",
            input = "{}",
            approvalState = ToolApprovalState.Pending,
        )
        val node = MessageNode.of(assistant(assistantId, listOf(tool)))
        val c = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(node))
        val r = ConversationTransition.apply(
            c.toSnapshot(),
            ResolveToolInteraction(assistantId, 0, ToolUserDecision.Deny("no"), handle(c.id, assistantId)),
        )
        val updated = r.nodes[0].messages.single().parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals(ToolApprovalState.Denied("no"), updated.approvalState)
    }

    @Test
    fun `ResolveToolInteraction rejects malformed runtime metadata`() {
        val assistantId = Uuid.random()
        val metadata = buildJsonObject {
            put("tool_runtime", Json.parseToJsonElement("""{"version":2,"interaction":"approval"}"""))
        }
        val tool = UIMessagePart.Tool(
            toolCallId = "t1",
            toolName = "shell",
            input = "{}",
            approvalState = ToolApprovalState.Pending,
            metadata = metadata,
        )
        val conversation = Conversation.ofId(Uuid.random()).copy(
            messageNodes = listOf(MessageNode.of(assistant(assistantId, listOf(tool)))),
        )

        val failure = runCatching {
            ConversationTransition.apply(
                conversation.toSnapshot(),
                ResolveToolInteraction(
                    assistantId,
                    0,
                    ToolUserDecision.Approve,
                    handle(conversation.id, assistantId),
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `approval updates durable and active messages without copying the streaming overlay into nodes`() {
        val assistantId = Uuid.random()
        val turnId = Uuid.random()
        val pending = UIMessagePart.Tool(
            toolCallId = "t1",
            toolName = "shell",
            input = "{}",
            approvalState = ToolApprovalState.Pending,
        )
        val durableMessage = assistant(assistantId, listOf(pending))
        val activeMessage = durableMessage.copy(parts = listOf(UIMessagePart.Text("overlay-only"), pending))
        val base = Conversation.ofId(Uuid.random()).copy(
            messageNodes = listOf(MessageNode.of(durableMessage)),
        ).toSnapshot().copy(
            activeTurn = ActiveTurnState(
                epoch = 1,
                turnId = turnId,
                assistantMessageId = assistantId,
                messages = listOf(activeMessage),
            ),
        )

        val reduced = ConversationTransition.apply(
            base,
            ResolveToolInteraction(assistantId, 0, ToolUserDecision.Approve, handle(base.conversationId, assistantId)),
        )

        assertEquals(1, reduced.nodes.single().currentMessage.parts.size)
        assertEquals(2, reduced.activeTurn?.messages?.single()?.parts?.size)
        assertEquals(
            ToolApprovalState.Approved,
            reduced.nodes.single().currentMessage.parts.filterIsInstance<UIMessagePart.Tool>().single().approvalState,
        )
        assertEquals(
            ToolApprovalState.Approved,
            reduced.activeTurn?.messages?.single()?.parts
                ?.filterIsInstance<UIMessagePart.Tool>()
                ?.single()
                ?.approvalState,
        )
    }
}
