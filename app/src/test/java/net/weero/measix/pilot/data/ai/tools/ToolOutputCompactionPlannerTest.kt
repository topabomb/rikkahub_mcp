package net.weero.measix.pilot.data.ai.tools

import kotlinx.datetime.Instant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.core.ToolOutputPolicy
import me.rerere.ai.ui.ToolResultStatus
import me.rerere.ai.ui.ToolRuntimeState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.request.ContextBudget
import net.weero.measix.pilot.data.ai.request.ModelRequestReceipt
import net.weero.measix.pilot.data.ai.request.ToolOutputBudget
import net.weero.measix.pilot.data.ai.request.estimateStableTextTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * `ToolOutputCompactionPlanner.planAfterSuccessfulRequest` 的纯规划契约（请求成功后、落盘之前）：
 * 只有本次 receipt 仍 inline 的、终态 completed/failed 的、净回收达阈值的、且不在保护窗口内的历史
 * tool result 才成为候选；不区分内置与 MCP 工具名。计划阶段不触碰文件或消息。
 */
class ToolOutputCompactionPlannerTest {
    private val compactionPlanner = ToolOutputCompactionPlanner()

    @Test
    fun `compaction waits for receipt and estimated token high watermark`() {
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool("shell", "x".repeat(200_000))))
        assertTrue(compactionPlanner.planAfterSuccessfulRequest(messageList(message), ModelRequestReceipt(emptySet())).candidates.isEmpty())
        val receipt = ModelRequestReceipt(setOf(loc(message.id, "shell")))
        assertTrue(compactionPlanner.planAfterSuccessfulRequest(messageList(message), receipt).candidates.isEmpty())
        val plan = compactionPlanner.planAfterSuccessfulRequest(
            messageList(message), receipt,
            ToolOutputBudget(protectedRecentBatches = 0, protectedRecentEstimatedTokens = 0),
        )
        assertEquals(1, plan.candidates.size)
    }

    @Test
    fun `estimated token high watermark triggers at exactly 48K`() {
        val highWatermark = ContextBudget.TOOL_OUTPUT_HIGH_WATERMARK_ESTIMATED_TOKENS
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
            compactionPlanner.planAfterSuccessfulRequest(
                listOf(below),
                ModelRequestReceipt(setOf(loc(below.id, "below"))),
                budget,
            ).candidates.isEmpty(),
        )
        assertEquals(
            listOf("exact"),
            compactionPlanner.planAfterSuccessfulRequest(
                listOf(exact),
                ModelRequestReceipt(setOf(loc(exact.id, "exact"))),
                budget,
            ).candidates.map { it.toolName },
        )
    }

    @Test
    fun `batch net reclaim triggers at exactly 24K`() {
        val minimumBatch = ContextBudget.TOOL_OUTPUT_MINIMUM_BATCH_NET_RECLAIM_ESTIMATED_TOKENS
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
            compactionPlanner.planAfterSuccessfulRequest(
                listOf(below),
                ModelRequestReceipt(setOf(loc(below.id, "below"))),
                budget,
            ).candidates.isEmpty(),
        )
        assertEquals(
            listOf("exact"),
            compactionPlanner.planAfterSuccessfulRequest(
                listOf(exact),
                ModelRequestReceipt(setOf(loc(exact.id, "exact"))),
                budget,
            ).candidates.map { it.toolName },
        )
    }

    @Test
    fun `compaction preserves recent batches and recent estimated tokens`() {
        val messages = (0..3).map { index ->
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool("t$index", "$index".repeat(24_000))))
        }
        val receipt = ModelRequestReceipt(messages.mapIndexed { i, m -> loc(m.id, "t$i") }.toSet())
        val plan = compactionPlanner.planAfterSuccessfulRequest(
            messages, receipt,
            ToolOutputBudget(
                highWatermarkEstimatedTokens = 20_000,
                lowWatermarkEstimatedTokens = 5_000,
                minimumBatchNetReclaimEstimatedTokens = 5_000,
            ),
        )
        assertTrue(plan.candidates.isNotEmpty())
        assertFalse(plan.candidates.any { it.locator.assistantMessageId in messages.takeLast(2).map(UIMessage::id) })
    }

    @Test
    fun `one assistant message may contain multiple protected tool batches`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = buildList {
                repeat(4) { index ->
                    add(UIMessagePart.Step(stepId = stepId, ordinal = index, startedAt = Instant.fromEpochSeconds(0)))
                    add(tool("t$index", "$index".repeat(24_000)))
                }
            },
        )
        val receipt = ModelRequestReceipt((0..3).map { loc(message.id, "t$it") }.toSet())
        val plan = compactionPlanner.planAfterSuccessfulRequest(
            listOf(message),
            receipt,
            ToolOutputBudget(
                highWatermarkEstimatedTokens = 20_000,
                lowWatermarkEstimatedTokens = 5_000,
                minimumBatchNetReclaimEstimatedTokens = 5_000,
            ),
        )

        assertEquals(listOf(stableId("t0"), stableId("t1")), plan.candidates.map { it.locator.localCallId })
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
        val receipt = ModelRequestReceipt(setOf(loc(message.id, "preserve"), loc(message.id, "eligible")))
        val plan = compactionPlanner.planAfterSuccessfulRequest(
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
                tool("completed", "c".repeat(500), terminalStatus = ToolResultStatus.COMPLETED),
                tool("failed", "f".repeat(500), terminalStatus = ToolResultStatus.FAILED),
                tool("denied", "d".repeat(500), terminalStatus = ToolResultStatus.DENIED),
                tool("answered", "a".repeat(500), terminalStatus = ToolResultStatus.ANSWERED),
                tool("unknown", "u".repeat(500), terminalStatus = null),
            ),
        )
        val receipt = ModelRequestReceipt(listOf("completed", "failed", "denied", "answered", "unknown").map { loc(message.id, it) }.toSet())
        val plan = compactionPlanner.planAfterSuccessfulRequest(
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
                tool("failed-marker-too-long", "failure ".repeat(200), terminalStatus = ToolResultStatus.FAILED),
                tool("useful", "u".repeat(3_000)),
            ),
        )
        val receipt = ModelRequestReceipt(listOf("short", "failed-marker-too-long", "useful").map { loc(message.id, it) }.toSet())
        val plan = compactionPlanner.planAfterSuccessfulRequest(
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
                ContextBudget.TOOL_OUTPUT_MINIMUM_RESULT_NET_RECLAIM_ESTIMATED_TOKENS,
        )
    }

    @Test
    fun `per result threshold accepts exactly the policy minimum of net reclaim`() {
        val minimum = ContextBudget.TOOL_OUTPUT_MINIMUM_RESULT_NET_RECLAIM_ESTIMATED_TOKENS
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
        val plan = compactionPlanner.planAfterSuccessfulRequest(
            listOf(message),
            ModelRequestReceipt(setOf(loc(message.id, "below"), loc(message.id, "exact"))),
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
        val receipt = ModelRequestReceipt(
            setOf(loc(completed.id, "completed-turn"), loc(current.id, "current-turn")),
        )
        val plan = compactionPlanner.planAfterSuccessfulRequest(
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
        val receipt = ModelRequestReceipt(listOf("preserve", "read_tool_output", "media", "archived").map { loc(message.id, it) }.toSet())
        val plan = compactionPlanner.planAfterSuccessfulRequest(
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
            for (status in listOf(ToolResultStatus.COMPLETED, ToolResultStatus.FAILED)) {
                val message = UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(tool(name, "result ".repeat(5_000), terminalStatus = status)),
                )
                val plan = compactionPlanner.planAfterSuccessfulRequest(
                    listOf(message),
                    ModelRequestReceipt(setOf(loc(message.id, name))),
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
                assertEquals("$name/$status", listOf(status.wireName), plan.candidates.map { it.terminalStatus })
            }
        }
    }

    // ── 与 RequestContextPlannerTest 共享形状的最小夹具（测试自包含，非生产第二来源）──

    private val stepId = Uuid.random()

    private fun stableId(name: String): Uuid {
        val h = name.hashCode().toLong() and 0xffffffffL
        return Uuid.parse("00000000-0000-0000-0000-" + h.toString(16).padStart(12, '0'))
    }

    private fun loc(messageId: Uuid, name: String) = ToolCallLocator(messageId, stepId, stableId(name))

    private fun tool(
        name: String,
        text: String,
        policy: ToolOutputPolicy = ToolOutputPolicy.ARCHIVABLE_TEXT,
        archive: Boolean = false,
        terminalStatus: ToolResultStatus? = ToolResultStatus.COMPLETED,
    ): UIMessagePart.Tool {
        val archiveRef = if (archive) {
            me.rerere.ai.ui.ToolOutputArchive(
                1,
                me.rerere.ai.ui.ToolOutputArchiveRef("tool_outputs/a.txt", "text/plain"),
                text.length.toLong(), 1,
            )
        } else {
            null
        }
        return UIMessagePart.Tool(
            localCallId = stableId(name),
            stepId = stepId,
            providerCallId = "call-$name",
            toolName = name,
            input = "{}",
            output = listOf(UIMessagePart.Text(text)),
            resultStatus = terminalStatus,
            runtimeState = ToolRuntimeState(policy, archiveRef),
        )
    }

    private fun messageList(message: UIMessage) = listOf(message)
}
