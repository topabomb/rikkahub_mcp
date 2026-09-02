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
import net.weero.measix.pilot.service.runtime.ActiveTurnState
import net.weero.measix.pilot.service.runtime.ConversationPresentation
import net.weero.measix.pilot.service.runtime.ConversationTurnPhase
import net.weero.measix.pilot.service.runtime.ToolCallPhase
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
    private val active = ActiveTurnState(1, turnId, message.id, listOf(message))

    @Test
    fun `idle and stopping suppress even pending interactions`() {
        listOf(ConversationTurnPhase.IDLE, ConversationTurnPhase.STOPPING).forEach { phase ->
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
            val feedback = requireNotNull(model(ConversationTurnPhase.PREPARING, requestId, previous).turnFeedback)
            assertEquals(requestId, feedback.turnId)
            assertNull(feedback.outputCharacters)
            assertFalse(feedback.awaitingUser)
        }
        assertNull(model(ConversationTurnPhase.PREPARING, requestId = null).turnFeedback)
    }

    @Test
    fun `output comes only from the owning assistant and ignores history and unrelated projections`() {
        val unrelated = UIMessage.assistant("not this assistant")
        val original = model(activeTurn = active.copy(messages = listOf(message, unrelated)))
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
        val tool = UIMessagePart.Tool(toolCallId = "approval", toolName = "test", input = "{}")
        val pending = message.copy(parts = listOf(tool))
        val phases = mapOf(ToolCallLocator(message.id, 0) to ToolCallPhase.AWAITING_APPROVAL)
        val feedback = requireNotNull(model(
            ConversationTurnPhase.AWAITING_USER,
            requestId = null,
            activeTurn = active.copy(messages = listOf(pending)),
            phases = phases,
        ).turnFeedback)
        assertTrue(feedback.awaitingUser)
        assertEquals(setOf("tool:${message.id}:0"), feedback.attentionKeys)
        assertTrue(requireNotNull(model(ConversationTurnPhase.AWAITING_USER).turnFeedback).awaitingUser)
    }

    @Test
    fun `executing child waiting for a valid answer overrides generating work feedback`() {
        val tool = childTool(childMetadata())
        val feedback = childFeedback(tool, ToolCallPhase.EXECUTING)
        assertTrue(feedback.awaitingUser)
        assertEquals(setOf("ask:question-1"), feedback.attentionKeys)
    }

    @Test
    fun `historical uncommitted and terminal child interactions cannot stop active work`() {
        val tool = childTool(childMetadata())
        listOf(ToolCallPhase.CALL_STREAMING, ToolCallPhase.READY, ToolCallPhase.COMPLETED).forEach {
            assertFalse(childFeedback(tool, it).awaitingUser)
        }
        listOf(
            childMetadata().copy(state = SubAssistantCallState.COMPLETED),
            childMetadata().copy(state = SubAssistantCallState.STARTING),
            childMetadata().copy(phase = SubAssistantCallPhase.TOOL_EXECUTING),
            childMetadata().copy(userInteraction = null),
            childMetadata().copy(userInteraction = childMetadata().userInteraction!!.copy(interactionId = " ")),
        ).forEach { metadata ->
            assertFalse(childFeedback(childTool(metadata), ToolCallPhase.EXECUTING).awaitingUser)
        }
        // typed 子阶段与 interaction 才是语义 owner，显示名称变化不应抹掉已提交等待态。
        assertTrue(childFeedback(
            childTool(childMetadata().copy(
                userInteraction = childMetadata().userInteraction!!.copy(toolName = "other"),
            )),
            ToolCallPhase.EXECUTING,
        ).awaitingUser)
        assertTrue(childFeedback(tool.copy(toolName = "other"), ToolCallPhase.EXECUTING).awaitingUser)
        assertFalse(childFeedback(tool.copy(metadata = null), ToolCallPhase.EXECUTING).awaitingUser)
    }

    @Test
    fun `approval continuation does not reclassify retained metadata as a new wait during preparation`() {
        val tool = childTool(childMetadata())
        val feedback = requireNotNull(model(
            ConversationTurnPhase.PREPARING,
            activeTurn = active.copy(messages = listOf(message.copy(parts = listOf(tool)))),
            phases = mapOf(ToolCallLocator(message.id, 0) to ToolCallPhase.EXECUTING),
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
            UIMessagePart.Tool("call", "tool", "{}", output = listOf(UIMessagePart.Text("x".repeat(1_000)))),
            UIMessagePart.Image("https://example.test/image.png"),
        )
        val original = model(activeTurn = active.copy(messages = listOf(message.copy(parts = parts))))
        assertEquals(13L, requireNotNull(original.turnFeedback).outputCharacters)
        val tool = childTool(childMetadata())
        assertEquals(
            childFeedback(tool, ToolCallPhase.EXECUTING).outputCharacters,
            childFeedback(tool.copy(metadata = null), ToolCallPhase.EXECUTING).outputCharacters,
        )
    }

    @Test
    fun `committed owning slot supplies output baseline before streaming including reused messages`() {
        val initial = model(activeTurn = active.copy(messages = emptyList()))
        assertNull(requireNotNull(initial.turnFeedback).outputCharacters)
        val committed = initial.copy(snapshot = initial.snapshot.copy(nodes = listOf(MessageNode.of(message))))
        assertEquals(6L, requireNotNull(committed.turnFeedback).outputCharacters)
        val streamed = committed.copy(snapshot = committed.snapshot.copy(activeTurn = active))
        assertEquals(committed.turnFeedback, streamed.turnFeedback)
        val unrelated = initial.copy(snapshot = initial.snapshot.copy(nodes = listOf(MessageNode.of(UIMessage.assistant("other")))))
        assertNull(requireNotNull(unrelated.turnFeedback).outputCharacters)
        val missingOwner = committed.copy(snapshot = committed.snapshot.copy(
            activeTurn = active.copy(messages = listOf(UIMessage.assistant("wrong owner"))),
        ))
        assertNull(requireNotNull(missingOwner.turnFeedback).outputCharacters)
    }

    private fun childMetadata() = SubAssistantCallMetadata(
        runId = "child-run",
        targetAssistantId = Uuid.random().toString(),
        targetNameSnapshot = "child",
        state = SubAssistantCallState.RUNNING,
        phase = SubAssistantCallPhase.AWAITING_USER,
        userInteraction = SubAssistantUserInteraction("question-1", "child-message", 0, "ask_user", "{}"),
    )

    private fun childTool(metadata: SubAssistantCallMetadata) = UIMessagePart.Tool(
        toolCallId = "child", toolName = "assistant_call", input = "{}",
    ).mergeSubAssistantCallMetadata(JsonInstant, metadata)

    private fun childFeedback(tool: UIMessagePart.Tool, phase: ToolCallPhase) = requireNotNull(model(
        activeTurn = active.copy(messages = listOf(message.copy(parts = listOf(tool)))),
        phases = mapOf(ToolCallLocator(message.id, 0) to phase),
    ).turnFeedback)

    private fun model(
        phase: ConversationTurnPhase = ConversationTurnPhase.GENERATING,
        requestId: Uuid? = turnId,
        activeTurn: ActiveTurnState? = active,
        phases: Map<ToolCallLocator, ToolCallPhase> = emptyMap(),
    ) = ConversationUiModel(
        snapshot = Conversation.ofId(Uuid.random(), Uuid.random())
            .toSnapshot()
            .toPresentationSnapshot()
            .copy(activeTurn = activeTurn),
        presentation = ConversationPresentation(requestId, phase, null, phases),
    )
}
