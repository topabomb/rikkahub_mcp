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
import net.weero.measix.pilot.data.model.ConversationModelContextEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import kotlin.uuid.Uuid
import org.junit.Assert.assertThrows
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

    // ---- model-context projection ----

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
