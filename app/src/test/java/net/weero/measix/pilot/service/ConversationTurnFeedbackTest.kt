package net.weero.measix.pilot.service

import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallPhase
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallState
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantUserInteraction
import net.weero.measix.pilot.data.ai.subassistant.mergeSubAssistantCallMetadata
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.service.runtime.TurnStreamProjection
import net.weero.measix.pilot.service.runtime.ConversationPresentation
import net.weero.measix.pilot.service.runtime.ConversationRuntimeSnapshot
import net.weero.measix.pilot.service.runtime.TurnLivePhase
import net.weero.measix.pilot.service.runtime.ToolLivePhase
import net.weero.measix.pilot.service.runtime.toPresentationSnapshot
import net.weero.measix.pilot.service.runtime.toSnapshot
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationTurnFeedbackTest {
    private val turnId = Uuid.random()
    private val message = UIMessage.assistant("stream")
    private val active = TurnStreamProjection(1, turnId, message.id, message)

    @Test
    fun `idle and stopping suppress even pending interactions`() {
        listOf<TurnLivePhase?>(null, TurnLivePhase.STOPPING).forEach { phase ->
            assertNull(model(phase = phase).turnFeedback)
        }
    }

    @Test
    fun `durable generating state without a request cannot emit a work heartbeat`() {
        assertNull(model(requestId = null).turnFeedback)
        assertNull(model(activeTurn = null).turnFeedback)
        assertNull(model(requestId = Uuid.random()).turnFeedback)
    }

    @Test
    fun `preparing request works before assistant exists and ignores old turn parts`() {
        val requestId = Uuid.random()
        listOf(null, active).forEach { previous ->
            val feedback = requireNotNull(model(TurnLivePhase.PREPARING, requestId, previous).turnFeedback)
            assertEquals(requestId, feedback.turnId)
            assertNull(feedback.outputCharacters)
            assertFalse(feedback.awaitingUser)
        }
        assertNull(model(TurnLivePhase.PREPARING, requestId = null).turnFeedback)
    }

    @Test
    fun `output comes only from the owning assistant and ignores durable history`() {
        val unrelated = UIMessage.assistant("not this assistant")
        val original = model(activeTurn = active)
        assertEquals(6L, requireNotNull(original.turnFeedback).outputCharacters)
        val changed = original.copy(
            snapshot = original.snapshot.copy(
                nodes = listOf(MessageNode.of(unrelated)),
                header = original.snapshot.header.copy(title = "changed"),
            ),
            attachmentPreviews = mapOf("irrelevant" to "preview"),
        )
        assertEquals(original.turnFeedback, changed.turnFeedback)
    }

    @Test
    fun `approval uses committed phases and may have no live request`() {
        val tool = UIMessagePart.Tool(localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "approval", toolName = "test", input = "{}")
        val pending = message.copy(parts = listOf(tool))
        val phases = mapOf(ToolCallLocator(message.id, tool.stepId, tool.localCallId) to ToolLivePhase.AWAITING_APPROVAL)
        val feedback = requireNotNull(model(
            TurnLivePhase.AWAITING_USER,
            requestId = null,
            activeTurn = active.copy(assistantMessage = pending),
            phases = phases,
        ).turnFeedback)
        assertTrue(feedback.awaitingUser)
        assertEquals(setOf("tool:${message.id}:${tool.localCallId}"), feedback.attentionKeys)
        assertTrue(requireNotNull(model(TurnLivePhase.AWAITING_USER).turnFeedback).awaitingUser)
    }

    @Test
    fun `executing child waiting for a valid answer overrides generating work feedback`() {
        val tool = childTool(childMetadata())
        val feedback = childFeedback(tool, ToolLivePhase.EXECUTING)
        assertTrue(feedback.awaitingUser)
        assertEquals(setOf("ask:question-1"), feedback.attentionKeys)
    }

    @Test
    fun `historical uncommitted and terminal child interactions cannot stop active work`() {
        val tool = childTool(childMetadata())
        listOf(ToolLivePhase.CALL_STREAMING, ToolLivePhase.READY, ToolLivePhase.COMPLETED).forEach {
            assertFalse(childFeedback(tool, it).awaitingUser)
        }
        listOf(
            childMetadata().copy(state = SubAssistantCallState.COMPLETED),
            childMetadata().copy(state = SubAssistantCallState.STARTING),
            childMetadata().copy(phase = SubAssistantCallPhase.TOOL_EXECUTING),
            childMetadata().copy(userInteraction = null),
            childMetadata().copy(userInteraction = childMetadata().userInteraction!!.copy(interactionId = " ")),
        ).forEach { metadata ->
            assertFalse(childFeedback(childTool(metadata), ToolLivePhase.EXECUTING).awaitingUser)
        }
        // typed 子阶段与 interaction 才是语义 owner，显示名称变化不应抹掉已提交等待态。
        assertTrue(childFeedback(
            childTool(childMetadata().copy(
                userInteraction = childMetadata().userInteraction!!.copy(toolName = "other"),
            )),
            ToolLivePhase.EXECUTING,
        ).awaitingUser)
        assertTrue(childFeedback(tool.copy(toolName = "other"), ToolLivePhase.EXECUTING).awaitingUser)
        assertFalse(childFeedback(tool.copy(metadata = null), ToolLivePhase.EXECUTING).awaitingUser)
    }

    @Test
    fun `approval continuation does not reclassify retained metadata as a new wait during preparation`() {
        val tool = childTool(childMetadata())
        val feedback = requireNotNull(model(
            TurnLivePhase.PREPARING,
            activeTurn = active.copy(assistantMessage = message.copy(parts = listOf(tool))),
            phases = mapOf(ToolCallLocator(message.id, tool.stepId, tool.localCallId) to ToolLivePhase.EXECUTING),
        ).turnFeedback)
        assertFalse(feedback.awaitingUser)
        assertTrue(feedback.attentionKeys.isEmpty())
        assertEquals(2L, feedback.outputCharacters)
    }

    @Test
    fun `output volume sums text reasoning and tool input without counting replay output or media urls`() {
        val parts = listOf(
            UIMessagePart.Text("answer"),
            UIMessagePart.Reasoning("think"),
            UIMessagePart.Tool(localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call", toolName = "tool", input = "{}", output = listOf(UIMessagePart.Text("x".repeat(1_000)))),
            UIMessagePart.Image("https://example.test/image.png"),
        )
        val original = model(activeTurn = active.copy(assistantMessage = message.copy(parts = parts)))
        assertEquals(13L, requireNotNull(original.turnFeedback).outputCharacters)
        val tool = childTool(childMetadata())
        assertEquals(
            childFeedback(tool, ToolLivePhase.EXECUTING).outputCharacters,
            childFeedback(tool.copy(metadata = null), ToolLivePhase.EXECUTING).outputCharacters,
        )
    }

    @Test
    fun `committed owning slot supplies output baseline before streaming including reused messages`() {
        val initial = model(activeTurn = active.copy(assistantMessage = null))
        assertNull(requireNotNull(initial.turnFeedback).outputCharacters)
        val committed = initial.copy(snapshot = initial.snapshot.copy(nodes = listOf(MessageNode.of(message))))
        assertEquals(6L, requireNotNull(committed.turnFeedback).outputCharacters)
        val streamed = committed.copy(snapshot = committed.snapshot.copy(stream = active))
        assertEquals(committed.turnFeedback, streamed.turnFeedback)
        val unrelated = initial.copy(snapshot = initial.snapshot.copy(nodes = listOf(MessageNode.of(UIMessage.assistant("other")))))
        assertNull(requireNotNull(unrelated.turnFeedback).outputCharacters)
    }

    private fun childMetadata() = SubAssistantCallMetadata(
        runId = "child-run",
        targetAssistantId = Uuid.random().toString(),
        targetNameSnapshot = "child",
        state = SubAssistantCallState.RUNNING,
        phase = SubAssistantCallPhase.AWAITING_USER,
        userInteraction = SubAssistantUserInteraction("question-1", "child-message", "call-1", "ask_user", "{}"),
    )

    private fun childTool(metadata: SubAssistantCallMetadata) = UIMessagePart.Tool(
        localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "child", toolName = "assistant_call", input = "{}",
    ).mergeSubAssistantCallMetadata(JsonInstant, metadata)

    private fun childFeedback(tool: UIMessagePart.Tool, phase: ToolLivePhase) = requireNotNull(model(
        activeTurn = active.copy(assistantMessage = message.copy(parts = listOf(tool))),
        phases = mapOf(ToolCallLocator(message.id, tool.stepId, tool.localCallId) to phase),
    ).turnFeedback)

    private fun model(
        phase: TurnLivePhase? = TurnLivePhase.MODEL_STREAMING,
        requestId: Uuid? = turnId,
        activeTurn: TurnStreamProjection? = active,
        phases: Map<ToolCallLocator, ToolLivePhase> = emptyMap(),
    ) = ConversationUiModel(
        snapshot = ConversationRuntimeSnapshot(
            durable = Conversation.ofId(Uuid.random(), Uuid.random()).toSnapshot(),
            stream = activeTurn,
        ).toPresentationSnapshot(),
        presentation = ConversationPresentation(requestId, phase, null, phases),
    )
}
