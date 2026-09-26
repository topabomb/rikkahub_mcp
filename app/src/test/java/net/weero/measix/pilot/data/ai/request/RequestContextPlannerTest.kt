package net.weero.measix.pilot.data.ai.request

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
import me.rerere.ai.ui.ToolResultStatus
import me.rerere.ai.ui.ToolRuntimeState
import me.rerere.ai.ui.toMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import kotlin.uuid.Uuid
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `RequestContextPlanner` 的请求前职责：条数窗口与完整 USER 轮次对齐、保守 receipt、
 * 请求上下文 token 估算、以及 model-context（Disclosure）投影的锚定与 fail-closed。
 * 请求成功后的滚动压缩规划已迁至 `ToolOutputCompactionPlannerTest`。
 */
class RequestContextPlannerTest {
    private val planner = RequestContextPlanner()

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
            setOf(loc(message.id, "inline")),
            planner.receiptOf(listOf(message)).visibleInlineToolOutputs,
        )
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
            setOf(loc(terminal.id, "first")),
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
                        put("call_id", first.providerCallId)
                    })
                ),
            ).toMetadata(),
        )
        assertEquals(
            setOf(loc(opaque.id, "first")),
            planner.receiptOf(listOf(opaque)).visibleInlineToolOutputs,
        )
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

    private val stepId = Uuid.random()

    /** Deterministic localCallId per tool name so receipts can reference the exact call. */
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

    private fun alternating(size: Int): List<UIMessage> = (0 until size).map { index ->
        if (index % 2 == 0) UIMessage.user("u$index") else UIMessage.assistant("a$index")
    }

}
