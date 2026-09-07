package net.weero.measix.pilot.data.ai.request

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * 唯一 UIMessage → ModelRequestMessage 转换边界的契约测试（Provider-independent）。
 * 核心不变量：durable 的 [UIMessagePart.Step] 绝不进入 Provider，其余 parts 顺序与身份逐字保留。
 */
class RequestAssemblerTest {
    private val assembler = RequestAssembler()

    private fun step(ordinal: Int) = UIMessagePart.Step(
        stepId = Uuid.random(),
        ordinal = ordinal,
        startedAt = Instant.fromEpochSeconds(0),
    )

    @Test
    fun `assemble drops Step and preserves order of model-visible parts`() {
        val text = UIMessagePart.Text("answer")
        val reasoning = UIMessagePart.Reasoning(reasoning = "thinking")
        val tool = UIMessagePart.Tool(
            localCallId = Uuid.random(),
            stepId = Uuid.random(),
            providerCallId = "call_1",
            toolName = "search",
            input = """{"q":"x"}""",
            output = listOf(UIMessagePart.Text("result")),
        )
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(step(0), text, reasoning, step(1), tool),
        )

        val assembled = assembler.assemble(listOf(message))

        assertEquals(listOf<UIMessagePart>(text, reasoning, tool), assembled.providerMessages.single().parts)
        // providerVisibleMessages 与 providerMessages 来自同一次 Step 丢弃，内容一致。
        assertEquals(assembled.providerMessages.single().parts, assembled.providerVisibleMessages.single().parts)
    }

    @Test
    fun `assemble empties a Step-only message`() {
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(step(0)))
        val assembled = assembler.assemble(listOf(message))
        assertTrue(assembled.providerMessages.single().parts.isEmpty())
    }

    @Test
    fun `assemble carries every provider-relevant field and drops durable-only identity`() {
        val metadata = buildJsonObject { put("opaque", JsonPrimitive("state")) }
        val modelId = Uuid.random()
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(step(0), UIMessagePart.Text("visible")),
            modelId = modelId,
            providerMetadata = metadata,
            providerReplayProjection = me.rerere.ai.ui.ProviderReplayProjection(completePartCount = 2, hasIncompleteTail = true),
        )

        val provider = assembler.assemble(listOf(message)).providerMessages.single()

        assertEquals(MessageRole.ASSISTANT, provider.role)
        assertEquals(modelId, provider.modelId)
        assertEquals(metadata, provider.providerMetadata)
        assertEquals(2, provider.providerReplayProjection?.completePartCount)
        assertTrue(provider.providerReplayProjection?.hasIncompleteTail == true)
    }

    @Test
    fun `assemble preserves tool call and result identity verbatim`() {
        val stepId = Uuid.random()
        val localCallId = Uuid.random()
        val tool = UIMessagePart.Tool(
            localCallId = localCallId,
            stepId = stepId,
            providerCallId = "call_42",
            toolName = "read_file",
            input = """{"path":"/a"}""",
            output = listOf(UIMessagePart.Text("body")),
        )
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(step(0), tool))

        val carried = assembler.assemble(listOf(message)).providerMessages.single().parts.single()
            as UIMessagePart.Tool

        assertEquals(stepId, carried.stepId)
        assertEquals(localCallId, carried.localCallId)
        assertEquals("call_42", carried.providerCallId)
        assertEquals("read_file", carried.toolName)
        assertEquals(listOf("body"), carried.output.filterIsInstance<UIMessagePart.Text>().map { it.text })
    }
}
