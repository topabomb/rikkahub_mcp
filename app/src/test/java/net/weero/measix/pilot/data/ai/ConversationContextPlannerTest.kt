package net.weero.measix.pilot.data.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.core.ToolOutputPolicy
import me.rerere.ai.core.freeze
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.OpenAIResponseMetadata
import me.rerere.ai.ui.OpenAIResponseWireFormat
import me.rerere.ai.ui.ProviderReplayProjection
import me.rerere.ai.ui.toMetadata
import net.weero.measix.pilot.data.ai.tools.REGENERABLE_TOOL_OUTPUT_FOLDED_MARKER
import net.weero.measix.pilot.data.ai.tools.ToolRuntimeMetadata
import net.weero.measix.pilot.data.model.ConversationModelContextEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationContextPlannerTest {
    private val planner = ConversationContextPlanner()

    @Test
    fun `request plan preserves disabled and threshold histories`() {
        val messages = alternating(10)
        assertEquals(messages, planner.planRequest(messages, messageLimit = 0).messages)
        assertEquals(messages, planner.planRequest(messages, messageLimit = 10).messages)
        assertEquals(emptyList<UIMessage>(), planner.planRequest(emptyList(), messageLimit = 10).messages)
    }

    @Test
    fun `request plan keeps stable complete user turn boundaries`() {
        val messages = alternating(30)
        val starts = (11..14).map { size -> planner.planRequest(messages.take(size), messageLimit = 10).messages.first() }
        assertEquals(1, starts.distinct().size)
        assertEquals(MessageRole.USER, starts.first().role)
        assertEquals(messages[4], starts.first())
        assertEquals(messages[10], planner.planRequest(messages.take(15), messageLimit = 10).messages.first())
    }

    @Test
    fun `request plan aligns assistant start to preceding user`() {
        val messages = listOf(
            UIMessage.user("old"), UIMessage.assistant("old answer"), UIMessage.user("tool question"),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool("x", "result"))),
            UIMessage.assistant("final"), UIMessage.user("new"),
        )
        assertEquals(messages.subList(2, messages.size), planner.planRequest(messages, messageLimit = 4).messages)
    }

    @Test
    fun `receipt records only final visible inline tool outputs`() {
        val inline = tool("inline", "result")
        val archived = tool("archived", "marker", archive = true)
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(inline, archived))
        assertEquals(
            setOf(ToolCallLocator(message.id, 0)),
            planner.receiptOf(listOf(message)).visibleInlineToolOutputs,
        )
    }

    @Test
    fun `compaction waits for receipt and estimated token high watermark`() {
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool("shell", "x".repeat(200_000))))
        assertTrue(planner.planPostStepCompaction(messageList(message), ModelStepReceipt(emptySet())).candidates.isEmpty())
        val receipt = ModelStepReceipt(setOf(ToolCallLocator(message.id, 0)))
        assertTrue(planner.planPostStepCompaction(messageList(message), receipt).candidates.isEmpty())
        val plan = planner.planPostStepCompaction(
            messageList(message), receipt,
            ToolOutputBudget(protectedRecentBatches = 0, protectedRecentEstimatedTokens = 0),
        )
        assertEquals(1, plan.candidates.size)
    }

    @Test
    fun `estimated token high watermark triggers at exactly 48K`() {
        val highWatermark = ContextTrimmingPolicy.TOOL_OUTPUT_HIGH_WATERMARK_ESTIMATED_TOKENS
        val below = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(tool("below", "b".repeat(((highWatermark - 1) * 4).toInt()))),
        )
        val exact = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(tool("exact", "e".repeat((highWatermark * 4).toInt()))),
        )
        val budget = ToolOutputBudget(protectedRecentBatches = 0, protectedRecentEstimatedTokens = 0)

        assertTrue(
            planner.planPostStepCompaction(
                listOf(below),
                ModelStepReceipt(setOf(ToolCallLocator(below.id, 0))),
                budget,
            ).candidates.isEmpty(),
        )
        assertEquals(
            listOf("exact"),
            planner.planPostStepCompaction(
                listOf(exact),
                ModelStepReceipt(setOf(ToolCallLocator(exact.id, 0))),
                budget,
            ).candidates.map { it.toolName },
        )
    }

    @Test
    fun `batch net reclaim triggers at exactly 24K`() {
        val minimumBatch = ContextTrimmingPolicy.TOOL_OUTPUT_MINIMUM_BATCH_NET_RECLAIM_ESTIMATED_TOKENS
        val markerTokens = estimateStableTextTokens(REGENERABLE_TOOL_OUTPUT_FOLDED_MARKER)
        fun message(name: String, netReclaim: Long) = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(tool(
                name,
                "x".repeat(((markerTokens + netReclaim) * 4).toInt()),
                policy = ToolOutputPolicy.REGENERABLE_TEXT,
            )),
        )
        val below = message("below", minimumBatch - 1)
        val exact = message("exact", minimumBatch)
        val budget = ToolOutputBudget(
            highWatermarkEstimatedTokens = 1,
            lowWatermarkEstimatedTokens = 0,
            minimumBatchNetReclaimEstimatedTokens = minimumBatch,
            protectedRecentBatches = 0,
            protectedRecentEstimatedTokens = 0,
        )

        assertTrue(
            planner.planPostStepCompaction(
                listOf(below),
                ModelStepReceipt(setOf(ToolCallLocator(below.id, 0))),
                budget,
            ).candidates.isEmpty(),
        )
        assertEquals(
            listOf("exact"),
            planner.planPostStepCompaction(
                listOf(exact),
                ModelStepReceipt(setOf(ToolCallLocator(exact.id, 0))),
                budget,
            ).candidates.map { it.toolName },
        )
    }

    @Test
    fun `compaction preserves recent batches and recent estimated tokens`() {
        val messages = (0..3).map { index ->
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool("t$index", "$index".repeat(24_000))))
        }
        val receipt = ModelStepReceipt(messages.map { ToolCallLocator(it.id, 0) }.toSet())
        val plan = planner.planPostStepCompaction(
            messages, receipt,
            ToolOutputBudget(
                highWatermarkEstimatedTokens = 20_000,
                lowWatermarkEstimatedTokens = 5_000,
                minimumBatchNetReclaimEstimatedTokens = 5_000,
            ),
        )
        assertTrue(plan.candidates.isNotEmpty())
        assertFalse(plan.candidates.any { it.locator.messageId in messages.takeLast(2).map(UIMessage::id) })
    }

    @Test
    fun `receipt excludes terminal tail and unmatched opaque response tools`() {
        val first = tool("first", "one")
        val second = tool("second", "two")
        val terminal = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(first, second),
            providerReplayProjection = ProviderReplayProjection(
                completePartCount = 1,
                hasIncompleteTail = true,
            ),
        )
        assertEquals(
            setOf(ToolCallLocator(terminal.id, 0)),
            planner.receiptOf(listOf(terminal)).visibleInlineToolOutputs,
        )

        val opaque = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(first, second),
            providerMetadata = OpenAIResponseMetadata(
                wireFormat = OpenAIResponseWireFormat.OPENAI,
                outputItemGroups = listOf(
                    listOf(buildJsonObject {
                        put("type", "function_call")
                        put("call_id", first.toolCallId)
                    })
                ),
            ).toMetadata(),
        )
        assertEquals(
            setOf(ToolCallLocator(opaque.id, 0)),
            planner.receiptOf(listOf(opaque)).visibleInlineToolOutputs,
        )
    }

    @Test
    fun `one assistant message may contain multiple protected tool batches`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = buildList {
                repeat(4) { index ->
                    add(tool("t$index", "$index".repeat(24_000), batchOrdinal = index))
                }
            },
        )
        val receipt = ModelStepReceipt((0..3).map { ToolCallLocator(message.id, it) }.toSet())
        val plan = planner.planPostStepCompaction(
            listOf(message),
            receipt,
            ToolOutputBudget(
                highWatermarkEstimatedTokens = 20_000,
                lowWatermarkEstimatedTokens = 5_000,
                minimumBatchNetReclaimEstimatedTokens = 5_000,
            ),
        )

        assertEquals(listOf(0, 1), plan.candidates.map { it.locator.toolOrdinal })
    }

    @Test
    fun `preserved text contributes to pressure but is never selected`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                tool("preserve", "p".repeat(40_000), policy = ToolOutputPolicy.PRESERVE),
                UIMessagePart.Text("next step"),
                tool("eligible", "e".repeat(30_000)),
            ),
        )
        val receipt = ModelStepReceipt(setOf(ToolCallLocator(message.id, 0), ToolCallLocator(message.id, 1)))
        val plan = planner.planPostStepCompaction(
            listOf(message),
            receipt,
            ToolOutputBudget(
                highWatermarkEstimatedTokens = 10_000,
                lowWatermarkEstimatedTokens = 4_000,
                minimumBatchNetReclaimEstimatedTokens = 4_000,
                protectedRecentBatches = 0,
                protectedRecentEstimatedTokens = 0,
            ),
        )

        assertEquals(listOf("eligible"), plan.candidates.map { it.toolName })
        assertEquals(plan.candidates.single().netReclaimEstimatedTokens, plan.netReclaimedEstimatedTokens)
    }

    @Test
    fun `only explicit completed or failed results can be archived`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                tool("completed", "c".repeat(500), terminalStatus = "completed"),
                tool("failed", "f".repeat(500), terminalStatus = "failed"),
                tool("denied", "d".repeat(500), terminalStatus = "denied"),
                tool("answered", "a".repeat(500), terminalStatus = "answered"),
                tool("unknown", "u".repeat(500), terminalStatus = null),
            ),
        )
        val receipt = ModelStepReceipt((0..4).map { ToolCallLocator(message.id, it) }.toSet())
        val plan = planner.planPostStepCompaction(
            listOf(message),
            receipt,
            ToolOutputBudget(
                highWatermarkEstimatedTokens = 1,
                lowWatermarkEstimatedTokens = 0,
                minimumBatchNetReclaimEstimatedTokens = 1,
                minimumResultNetReclaimEstimatedTokens = 1,
                protectedRecentBatches = 0,
                protectedRecentEstimatedTokens = 0,
            ),
        )

        assertEquals(listOf("completed", "failed"), plan.candidates.map { it.toolName })
    }

    @Test
    fun `results reclaiming less than the per result token minimum are never candidates`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                tool("short", "s".repeat(2_000)),
                tool("failed-marker-too-long", "failure ".repeat(200), terminalStatus = "failed"),
                tool("useful", "u".repeat(3_000)),
            ),
        )
        val receipt = ModelStepReceipt((0..2).map { ToolCallLocator(message.id, it) }.toSet())
        val plan = planner.planPostStepCompaction(
            listOf(message),
            receipt,
            ToolOutputBudget(
                highWatermarkEstimatedTokens = 1,
                lowWatermarkEstimatedTokens = 0,
                minimumBatchNetReclaimEstimatedTokens = 1,
                minimumResultNetReclaimEstimatedTokens = 600,
                protectedRecentBatches = 0,
                protectedRecentEstimatedTokens = 0,
            ),
        )

        assertEquals(listOf("useful"), plan.candidates.map { it.toolName })
        assertTrue(
            plan.candidates.single().netReclaimEstimatedTokens >=
                ContextTrimmingPolicy.TOOL_OUTPUT_MINIMUM_RESULT_NET_RECLAIM_ESTIMATED_TOKENS,
        )
    }

    @Test
    fun `per result threshold accepts exactly the policy minimum of net reclaim`() {
        val minimum = ContextTrimmingPolicy.TOOL_OUTPUT_MINIMUM_RESULT_NET_RECLAIM_ESTIMATED_TOKENS
        val markerTokens = estimateStableTextTokens(REGENERABLE_TOOL_OUTPUT_FOLDED_MARKER)
        val below = "b".repeat(((markerTokens + minimum - 1) * 4).toInt())
        val exact = "e".repeat(((markerTokens + minimum) * 4).toInt())
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                tool("below", below, policy = ToolOutputPolicy.REGENERABLE_TEXT),
                tool("exact", exact, policy = ToolOutputPolicy.REGENERABLE_TEXT),
            ),
        )
        val plan = planner.planPostStepCompaction(
            listOf(message),
            ModelStepReceipt(setOf(ToolCallLocator(message.id, 0), ToolCallLocator(message.id, 1))),
            ToolOutputBudget(
                highWatermarkEstimatedTokens = 1,
                lowWatermarkEstimatedTokens = 0,
                minimumBatchNetReclaimEstimatedTokens = 1,
                protectedRecentBatches = 0,
                protectedRecentEstimatedTokens = 0,
            ),
        )

        assertEquals(listOf("exact"), plan.candidates.map { it.toolName })
        assertEquals(minimum, plan.candidates.single().netReclaimEstimatedTokens)
    }

    @Test
    fun `tool output token estimate is deterministic across ascii unicode and surrogate pairs`() {
        assertEquals(1, estimateStableTextTokens("abcd"))
        assertEquals(2, estimateStableTextTokens("abcde"))
        assertEquals(2, estimateStableTextTokens("中文"))
        assertEquals(1, estimateStableTextTokens("😀"))
        assertEquals(3, estimateStableTextTokens("abcd中😀"))
        // 连续数字按最多 3 位一段，逗号会切断数字段；随机浮点不能再按全文 ÷4。
        assertEquals(2, estimateStableTextTokens("1234"))
        assertEquals(5, estimateStableTextTokens("1,2,3"))
        assertEquals(8, estimateStableTextTokens("0.1234567890123456"))
    }

    @Test
    fun `request context estimate uses the final message projection and tool schema`() {
        val schema = buildJsonObject { put("type", "object") }
        val messages = listOf(UIMessage.user("abcd"))
        val tools = listOf(
            Tool(
                name = "echo",
                description = "abcde",
                parameters = { schema },
                execute = { emptyList() },
            ),
        )

        val expected = 4L +
            estimateStableTextTokens("USER") +
            1L +
            estimateStableTextTokens("abcd") +
            8L +
            estimateStableTextTokens("echo") +
            estimateStableTextTokens("abcde") +
            estimateStableTextTokens(schema.toString())

        assertEquals(expected, planner.estimateRequestContextTokens(messages, tools.map { it.freeze() }))
    }

    @Test
    fun `latest completed user turn is not specially protected`() {
        val completed = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(tool("completed-turn", "o".repeat(30_000))),
        )
        val current = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(tool("current-turn", "n".repeat(30_000))),
        )
        val messages = listOf(
            UIMessage.user("previous"),
            completed,
            UIMessage.user("current"),
            current,
        )
        val receipt = ModelStepReceipt(
            setOf(ToolCallLocator(completed.id, 0), ToolCallLocator(current.id, 0)),
        )
        val plan = planner.planPostStepCompaction(
            messages,
            receipt,
            ToolOutputBudget(
                highWatermarkEstimatedTokens = 1,
                lowWatermarkEstimatedTokens = 0,
                minimumBatchNetReclaimEstimatedTokens = 1,
                protectedRecentBatches = 0,
                protectedRecentEstimatedTokens = 0,
            ),
        )

        assertEquals(listOf("completed-turn", "current-turn"), plan.candidates.map { it.toolName })
    }

    @Test
    fun `lookup output follows normal trimming while preserve media and archived outputs do not`() {
        val preserve = tool("preserve", "x".repeat(30_000), policy = ToolOutputPolicy.PRESERVE)
        val lookup = tool(
            "read_tool_output",
            "x".repeat(30_000),
            policy = ToolOutputPolicy.REGENERABLE_TEXT,
        )
        val media = tool("media", "x".repeat(30_000)).copy(
            output = listOf(UIMessagePart.Text("x".repeat(30_000)), UIMessagePart.Image("https://example.com/a.png"))
        )
        val archived = tool("archived", "x".repeat(30_000), archive = true)
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(preserve, lookup, media, archived))
        val receipt = ModelStepReceipt((0..3).map { ToolCallLocator(message.id, it) }.toSet())
        val plan = planner.planPostStepCompaction(
            listOf(message), receipt,
            ToolOutputBudget(
                highWatermarkEstimatedTokens = 1,
                lowWatermarkEstimatedTokens = 0,
                minimumBatchNetReclaimEstimatedTokens = 1,
                minimumResultNetReclaimEstimatedTokens = 1,
                protectedRecentBatches = 0,
                protectedRecentEstimatedTokens = 0,
            ),
        )
        assertEquals(listOf("read_tool_output"), plan.candidates.map { it.toolName })
        assertEquals(ToolOutputPolicy.REGENERABLE_TEXT, plan.candidates.single().outputPolicy)
    }

    @Test
    fun `planner applies one completed and failed protocol without branching on built in or MCP tool names`() {
        val names = listOf(
            "eval_javascript",
            "get_time_info",
            "clipboard_tool",
            "text_to_speech",
            "get_screen_time",
            "calendar_query",
            "calendar_create",
            "generate_image",
            "inspect_attachments",
            "memory_tool",
            "use_skill",
            "recent_chats",
            "conversation_search",
            "search_web",
            "scrape_web",
            "workspace_read_file",
            "workspace_write_file",
            "workspace_edit_file",
            "workspace_shell",
            "assistant_manage",
            "assistant_inspect",
            "assistant_call",
            "read_tool_output",
            "grep_tool_output",
            "mcp__server__remote",
        )
        for (name in names) {
            for (status in listOf("completed", "failed")) {
                val message = UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(tool(name, "result ".repeat(5_000), terminalStatus = status)),
                )
                val plan = planner.planPostStepCompaction(
                    listOf(message),
                    ModelStepReceipt(setOf(ToolCallLocator(message.id, 0))),
                    ToolOutputBudget(
                        highWatermarkEstimatedTokens = 1,
                        lowWatermarkEstimatedTokens = 0,
                        minimumBatchNetReclaimEstimatedTokens = 1,
                        minimumResultNetReclaimEstimatedTokens = 1,
                        protectedRecentBatches = 0,
                        protectedRecentEstimatedTokens = 0,
                    ),
                )

                assertEquals("$name/$status", listOf(name), plan.candidates.map { it.toolName })
                assertEquals("$name/$status", listOf(status), plan.candidates.map { it.terminalStatus })
            }
        }
    }

    private fun tool(
        name: String,
        text: String,
        policy: ToolOutputPolicy = ToolOutputPolicy.ARCHIVABLE_TEXT,
        archive: Boolean = false,
        batchOrdinal: Int? = null,
        terminalStatus: String? = "completed",
    ): UIMessagePart.Tool {
        var metadata: JsonObject = ToolRuntimeMetadata.applyTo(
            null,
            ToolRuntimeMetadata.forResult(
                interaction = net.weero.measix.pilot.data.ai.tools.ToolInteractionKind.NONE,
                outputPolicy = policy.name,
                terminalStatus = terminalStatus,
                resultBatchOrdinal = batchOrdinal,
            ),
        )
        if (archive) {
            metadata = ToolRuntimeMetadata.withArchive(
                metadata,
                net.weero.measix.pilot.data.ai.tools.ToolOutputArchive(
                    1,
                    net.weero.measix.pilot.data.ai.tools.ToolOutputArchiveRef("tool_outputs/a.txt", "text/plain"),
                    text.length.toLong(), 1,
                ),
            )
        }
        return UIMessagePart.Tool("call-$name", name, "{}", listOf(UIMessagePart.Text(text)), metadata = metadata)
    }

    private fun alternating(size: Int): List<UIMessage> = (0 until size).map { index ->
        if (index % 2 == 0) UIMessage.user("u$index") else UIMessage.assistant("a$index")
    }

    private fun messageList(message: UIMessage) = listOf(message)

    // ---- model-context projection（权威方案 §8.2 / §8.3 / §8.4）----

    private fun contextEntry(anchor: UIMessage, content: String): ConversationModelContextEntry =
        ConversationModelContextEntry(
            ownerNodeId = kotlin.uuid.Uuid.random(),
            ownerMessageId = kotlin.uuid.Uuid.random(),
            anchorNodeId = kotlin.uuid.Uuid.random(),
            anchorMessageId = anchor.id,
            content = content,
        )

    private fun locatorsFor(messages: List<UIMessage>): Map<kotlin.uuid.Uuid, DurableMessageLocator> {
        val node = kotlin.uuid.Uuid.random()
        return messages.associate { it.id to DurableMessageLocator(node, it.id) }
    }

    @Test
    fun `baseline before the retained window projects onto the first real USER`() {
        val branch = alternating(5) // u0 a1 u2 a3 u4
        val window = branch.subList(2, branch.size)
        val plan = planner.planRequest(
            durableMessages = branch,
            durableLocators = locatorsFor(branch),
            modelContextEntries = listOf(contextEntry(branch[0], "SNAPSHOT-1")),
            messageLimit = 3,
        )
        assertEquals(window.map { it.id }, plan.messages.map { it.id })
        assertEquals(listOf(window.first().id to "SNAPSHOT-1"), plan.contextProjections.map { it.anchorMessageId to it.content })
    }

    /**
     * 回归锁：replay projection 会丢弃空白等不可上传消息，窗口对齐必须以投影后列表为基准；
     * 用未投影 branch 推导窗口起点会把“分支里存在空白消息”放大成每次请求都失败。
     */
    @Test
    fun `blank durable messages dropped by replay projection do not break window alignment`() {
        val branch = alternating(5)
        val blank = UIMessage(
            id = kotlin.uuid.Uuid.random(),
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("   ")),
        )
        val plan = planner.planRequest(
            durableMessages = branch + blank,
            durableLocators = locatorsFor(branch + blank),
            modelContextEntries = listOf(contextEntry(branch[0], "SNAPSHOT-1")),
            messageLimit = 3,
        )
        assertTrue(plan.messages.none { it.id == blank.id })
        assertEquals("SNAPSHOT-1", plan.contextProjections.single().content)
    }

    @Test
    fun `anchor dropped by replay projection fails closed instead of guessing`() {
        val blank = UIMessage(
            id = kotlin.uuid.Uuid.random(),
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text(" ")),
        )
        val branch = listOf(blank) + alternating(4)
        assertThrows(IllegalStateException::class.java) {
            planner.planRequest(
                durableMessages = branch,
                durableLocators = locatorsFor(branch),
                modelContextEntries = listOf(contextEntry(blank, "SNAPSHOT-BLANK")),
                messageLimit = 3,
            )
        }
    }

    @Test
    fun `later in-window snapshots keep their own anchors and older baselines drop`() {
        val branch = alternating(5)
        val e1 = contextEntry(branch[0], "SNAPSHOT-U0")
        val e2 = contextEntry(branch[2], "SNAPSHOT-U2")
        val e3 = contextEntry(branch[4], "SNAPSHOT-U4")
        val plan = planner.planRequest(
            durableMessages = branch,
            durableLocators = locatorsFor(branch),
            modelContextEntries = listOf(e1, e2, e3),
            messageLimit = 3,
        )
        // 窗口 [u2 a3 u4]：baseline = anchor 不晚于第一条真实 USER 的最近一条 = e2；
        // e1 早于 baseline 不发送；e3 保持在自身 anchor 前。
        assertEquals(
            listOf(branch[2].id to "SNAPSHOT-U2", branch[4].id to "SNAPSHOT-U4"),
            plan.contextProjections.map { it.anchorMessageId to it.content },
        )
    }

    @Test
    fun `snapshots without entries produce no projections`() {
        val branch = alternating(5)
        val plan = planner.planRequest(
            durableMessages = branch,
            durableLocators = locatorsFor(branch),
            modelContextEntries = emptyList(),
            messageLimit = 3,
        )
        assertTrue(plan.contextProjections.isEmpty())
    }

    @Test
    fun `active context with missing durable locator fails closed`() {
        val branch = alternating(5)
        val locators = locatorsFor(branch).minus(branch[2].id)
        assertThrows(IllegalArgumentException::class.java) {
            planner.planRequest(
                durableMessages = branch,
                durableLocators = locators,
                modelContextEntries = listOf(contextEntry(branch[0], "SNAPSHOT-1")),
                messageLimit = 3,
            )
        }
    }

    @Test
    fun `projections attach as the first parts of the anchor USER keeping user parts`() {
        val originals = listOf(
            UIMessagePart.Text("hello"),
            UIMessagePart.Image("image://x"),
            UIMessagePart.Document("file://document", "document.pdf", "application/pdf"),
            UIMessagePart.Audio("file://audio"),
            UIMessagePart.Video("file://video"),
        )
        val anchor = UIMessage(role = MessageRole.USER, parts = originals)
        val result = planner.applyContextProjections(
            transformedMessages = listOf(UIMessage.system("s"), anchor),
            projections = listOf(
                ModelContextProjection(anchor.id, "SNAPSHOT-A"),
                ModelContextProjection(anchor.id, "SNAPSHOT-B"),
            ),
            originsByMessageId = mapOf(anchor.id to RequestMessageOrigin.Durable(DurableMessageLocator(kotlin.uuid.Uuid.random(), anchor.id))),
        )
        assertEquals(
            listOf("SNAPSHOT-A", "SNAPSHOT-B", "hello"),
            result.last().parts.filterIsInstance<UIMessagePart.Text>().map { it.text },
        )
        assertEquals(originals, result.last().parts.drop(2))
        originals.forEachIndexed { index, part ->
            assertSame(part, result.last().parts[index + 2])
        }
    }

    @Test
    fun `projections fail when the retained anchor is missing after transforms`() {
        val anchor = UIMessage.user("dropped")
        assertThrows(IllegalStateException::class.java) {
            planner.applyContextProjections(
                transformedMessages = emptyList(),
                projections = listOf(ModelContextProjection(anchor.id, "SNAPSHOT")),
                originsByMessageId = mapOf(anchor.id to RequestMessageOrigin.Durable(DurableMessageLocator(kotlin.uuid.Uuid.random(), anchor.id))),
            )
        }
    }

    @Test
    fun `projections fail when transforms duplicate a durable anchor identity`() {
        val anchor = UIMessage.user("durable")
        assertThrows(IllegalStateException::class.java) {
            planner.applyContextProjections(
                transformedMessages = listOf(anchor, anchor.copy(parts = listOf(UIMessagePart.Text("duplicate")))),
                projections = listOf(ModelContextProjection(anchor.id, "SNAPSHOT")),
                originsByMessageId = mapOf(
                    anchor.id to RequestMessageOrigin.Durable(DurableMessageLocator(kotlin.uuid.Uuid.random(), anchor.id)),
                ),
            )
        }
    }

    @Test
    fun `projections never attach to synthetic or non-USER messages`() {
        val synthetic = UIMessage.user("time reminder")
        val assistant = UIMessage.assistant("answer")
        assertThrows(IllegalStateException::class.java) {
            planner.applyContextProjections(
                transformedMessages = listOf(synthetic),
                projections = listOf(ModelContextProjection(synthetic.id, "SNAPSHOT")),
                originsByMessageId = mapOf(synthetic.id to RequestMessageOrigin.Synthetic(SyntheticMessageKind.TIME_REMINDER)),
            )
        }
        assertThrows(IllegalStateException::class.java) {
            planner.applyContextProjections(
                transformedMessages = listOf(assistant),
                projections = listOf(ModelContextProjection(assistant.id, "SNAPSHOT")),
                originsByMessageId = mapOf(assistant.id to RequestMessageOrigin.Durable(DurableMessageLocator(kotlin.uuid.Uuid.random(), assistant.id))),
            )
        }
    }
}
