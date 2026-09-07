package net.weero.measix.pilot.service
import net.weero.measix.pilot.service.turn.finishInterruptedToolAfterGenerationStop

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolInteractionState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlinx.serialization.json.Json
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallState
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallPhase
import net.weero.measix.pilot.data.ai.subassistant.buildInitialSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.getSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.mergeSubAssistantCallMetadata
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.toMessageNode
import net.weero.measix.pilot.data.datastore.DEFAULT_ASSISTANT_ID
import net.weero.measix.pilot.service.runtime.ConversationTransition
import net.weero.measix.pilot.service.runtime.TurnHandle
import net.weero.measix.pilot.service.runtime.ResolveToolInteraction
import net.weero.measix.pilot.service.runtime.toSnapshot
import org.junit.Assert.assertEquals
import net.weero.measix.pilot.service.runtime.ToolInteractionDecision
import org.junit.Test
import kotlin.uuid.Uuid

class ToolApprovalReducerTest {
    private val json = Json { encodeDefaults = true }
    private val stepId = Uuid.random()

    private fun approval(
        messageId: Uuid,
        localCallId: Uuid,
        decision: ToolInteractionDecision,
        conversationId: Uuid = Uuid.random(),
    ) = ResolveToolInteraction(
        messageId = messageId,
        stepId = stepId,
        localCallId = localCallId,
        decision = decision,
        handle = TurnHandle(conversationId, 1, Uuid.random(), messageId),
    )

    @Test
    fun `approval locator updates only the selected call when provider ids repeat`() {
        val firstId = Uuid.random()
        val secondId = Uuid.random()
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                pendingTool(firstId, "duplicate"),
                pendingTool(secondId, "duplicate"),
            ),
        )
        val conversation = Conversation(
            assistantId = DEFAULT_ASSISTANT_ID,
            messageNodes = listOf(message.toMessageNode()),
        )

        // HITL 审批走 ResolveToolInteraction 命令（reducer 唯一路径），按 localCallId 精确定位。
        val updated = ConversationTransition.apply(
            conversation.toSnapshot(),
            approval(message.id, secondId, ToolInteractionDecision.Answer("answer")),
        )

        val tools = updated.currentMessages().last().getTools()
        assertEquals(ToolInteractionState.AwaitingInput, tools[0].interactionState)
        assertEquals(ToolInteractionState.Answered("answer"), tools[1].interactionState)
    }

    @Test
    fun `attention keys include pending tools and bridged ask_user`() {
        val pending = pendingTool(Uuid.random(), "ask")
        val assistantCall = runningCallWithAskUser("ask-42")
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(pending, assistantCall),
        )

        val keys = collectUserAttentionKeys(listOf(message), json)

        assertEquals(
            setOf("tool:${message.id}:${pending.localCallId}", "ask:ask-42"),
            keys,
        )
        assertEquals(
            setOf("tool:${message.id}:${pending.localCallId}", "ask:ask-42"),
            collectUserAttentionKeys(listOf(message), json),
        )
    }

    @Test
    fun `attention keys ignore leftover ask_user on a terminal call`() {
        val leftover = runningCallWithAskUser("ask-stale").let { tool ->
            val metadata = tool.getSubAssistantCallMetadata(json)!!.copy(
                state = SubAssistantCallState.COMPLETED,
            )
            tool.mergeSubAssistantCallMetadata(json, metadata)
        }
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(leftover))
        assertEquals(emptySet<String>(), collectUserAttentionKeys(listOf(message), json))
    }

    @Test
    fun `a new ask_user interaction id is a new attention key`() {
        val first = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(runningCallWithAskUser("ask-1")))
        val second = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(runningCallWithAskUser("ask-2")))
        assertEquals(setOf("ask:ask-1"), collectUserAttentionKeys(listOf(first), json))
        assertEquals(setOf("ask:ask-2"), collectUserAttentionKeys(listOf(second), json))
    }

    @Test
    fun `stale message locator cannot mutate current branch`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(pendingTool(Uuid.random(), "id")),
        )
        val conversation = Conversation(
            assistantId = DEFAULT_ASSISTANT_ID,
            messageNodes = listOf(message.toMessageNode()),
        )

        // locator 指向不存在的消息 → reducer 返回原引用（无变更、不落库）
        val snapshot = conversation.toSnapshot()
        val updated = ConversationTransition.apply(
            snapshot,
            approval(Uuid.random(), Uuid.random(), ToolInteractionDecision.Approve),
        )
        assertEquals(snapshot, updated)
    }

    @Test
    fun `multiple pending decisions compose without losing an earlier answer`() {
        val firstId = Uuid.random()
        val secondId = Uuid.random()
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(pendingTool(firstId, "duplicate"), pendingTool(secondId, "duplicate")),
        )
        val conversation = Conversation(
            assistantId = DEFAULT_ASSISTANT_ID,
            messageNodes = listOf(message.toMessageNode()),
        )

        val afterFirst = ConversationTransition.apply(
            conversation.toSnapshot(),
            approval(message.id, firstId, ToolInteractionDecision.Answer("first"), conversation.id),
        )
        val afterSecond = ConversationTransition.apply(
            afterFirst,
            approval(message.id, secondId, ToolInteractionDecision.Answer("second"), conversation.id),
        )

        assertEquals(
            listOf(ToolInteractionState.Answered("first"), ToolInteractionState.Answered("second")),
            afterSecond.currentMessages().last().getTools().map { it.interactionState },
        )
    }

    @Test
    fun `stopping master generation closes running sub-assistant card`() {
        val targetId = Uuid.random()
        val metadata = buildInitialSubAssistantCallMetadata(
            runId = "run-1",
            targetAssistantId = targetId,
            targetNameSnapshot = "Reviewer",
        ).copy(state = SubAssistantCallState.RUNNING)
        val tool = UIMessagePart.Tool(
            localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call-1",
            toolName = "assistant_call",
            input = "{}",
            interactionState = ToolInteractionState.NotRequired,
        ).mergeSubAssistantCallMetadata(json, metadata)

        val stopped = finishInterruptedToolAfterGenerationStop(tool, json, "user_cancelled")
        val stoppedMetadata = stopped.getSubAssistantCallMetadata(json)!!

        assertEquals(SubAssistantCallState.STOPPED, stoppedMetadata.state)
        assertEquals("user_cancelled", stoppedMetadata.reason)
        assertEquals(null, stoppedMetadata.phase)
        assertEquals(null, stoppedMetadata.userInteraction)
        assertEquals(1, stopped.output.size)
        assertEquals(true, (stopped.output.single() as UIMessagePart.Text).text.contains("user_cancelled"))
        assertEquals(false, (stopped.output.single() as UIMessagePart.Text).text.contains("tool_calls"))
    }

    @Test
    fun `interrupted assistant call without metadata fails closed`() {
        val tool = UIMessagePart.Tool(
            localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call-missing-metadata",
            toolName = "assistant_call",
            input = "{}",
            interactionState = ToolInteractionState.NotRequired,
        )

        val failure = runCatching {
            finishInterruptedToolAfterGenerationStop(tool, json, "runtime_error")
        }.exceptionOrNull()

        assertEquals(true, failure is IllegalArgumentException)
    }

    @Test
    fun `stopping master generation includes tts_stats but not bulky extras by default`() {
        val stopped = finishInterruptedToolAfterGenerationStop(
            runningAssistantCall(input = "{}"),
            json,
            "runtime_error",
            childMessagesWithTts(),
        )
        val text = (stopped.output.single() as UIMessagePart.Text).text
        assertEquals("runtime_error", stopped.getSubAssistantCallMetadata(json)?.reason)
        assertEquals(true, text.contains("runtime_error"))
        assertEquals(true, text.contains("\"tts_stats\""))
        assertEquals(false, text.contains("\"tool_calls\""))
        assertEquals(false, text.contains("Spoken answer."))
    }

    @Test
    fun `stopping master generation returns extras when requested`() {
        val stopped = finishInterruptedToolAfterGenerationStop(
            runningAssistantCall(input = """{"extras":["tts","tool_calls"]}"""),
            json,
            "user_cancelled",
            childMessagesWithTts(),
        )
        val text = (stopped.output.single() as UIMessagePart.Text).text
        assertEquals(true, text.contains("user_cancelled"))
        assertEquals(true, text.contains("\"tts_stats\""))
        assertEquals(true, text.contains("\"tool_calls\""))
        assertEquals(true, text.contains("search_web"))
        assertEquals(true, text.contains("Spoken answer."))
    }

    @Test
    fun `stopping master generation does not project artifacts even when extras request them`() {
        val stopped = finishInterruptedToolAfterGenerationStop(
            runningAssistantCall(input = """{"extras":["artifacts","tts","tool_calls"]}"""),
            json,
            "user_cancelled",
            childMessagesWithTts(),
        )
        assertEquals(1, stopped.output.size)
        val text = (stopped.output.single() as UIMessagePart.Text).text
        assertEquals(true, text.contains("user_cancelled"))
        assertEquals(false, text.contains("\"artifacts\""))
        assertEquals(false, text.contains("artifact_delivery"))
        assertEquals(true, stopped.output.none { it is UIMessagePart.Image })
    }

    private fun runningCallWithAskUser(interactionId: String): UIMessagePart.Tool {
        val metadata = buildInitialSubAssistantCallMetadata(
            runId = "run-ask",
            targetAssistantId = Uuid.random(),
            targetNameSnapshot = "Reviewer",
        ).copy(
            state = SubAssistantCallState.RUNNING,
            phase = SubAssistantCallPhase.AWAITING_USER,
            userInteraction = net.weero.measix.pilot.data.ai.subassistant.SubAssistantUserInteraction(
                interactionId = interactionId,
                messageId = Uuid.random().toString(),
                localCallId = Uuid.random().toString(),
                toolName = "ask_user",
                input = "{}",
            ),
        )
        return UIMessagePart.Tool(
            localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call-ask",
            toolName = "assistant_call",
            input = "{}",
            interactionState = ToolInteractionState.NotRequired,
        ).mergeSubAssistantCallMetadata(json, metadata)
    }

    private fun runningAssistantCall(input: String): UIMessagePart.Tool {
        val metadata = buildInitialSubAssistantCallMetadata(
            runId = "run-1",
            targetAssistantId = Uuid.random(),
            targetNameSnapshot = "Reviewer",
        ).copy(
            state = SubAssistantCallState.RUNNING,
            childConversationId = Uuid.random().toString(),
            childTaskNodeId = childTaskId.toString(),
        )
        return UIMessagePart.Tool(
            localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call-1",
            toolName = "assistant_call",
            input = input,
            interactionState = ToolInteractionState.NotRequired,
        ).mergeSubAssistantCallMetadata(json, metadata)
    }

    private val childTaskId = Uuid.random()

    private fun childMessagesWithTts() = listOf(
        UIMessage(id = childTaskId, role = MessageRole.USER, parts = listOf(UIMessagePart.Text("do it"))),
        UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "s1",
                    toolName = "search_web",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("ok")),
                ),
                UIMessagePart.Tool(
                    localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "t1",
                    toolName = "text_to_speech",
                    input = """{"text":"Spoken answer."}""",
                    output = listOf(UIMessagePart.Text("""{"success":true}""")),
                ),
            ),
        ),
    )

    private fun pendingTool(localCallId: Uuid, providerCallId: String) = UIMessagePart.Tool(
        localCallId = localCallId, stepId = stepId, providerCallId = providerCallId,
        toolName = "ask_user",
        input = "{}",
        interactionState = ToolInteractionState.AwaitingInput,
    )
}
