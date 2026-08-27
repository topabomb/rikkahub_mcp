package net.weero.measix.pilot.ui.pages.subassistant

import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallState
import net.weero.measix.pilot.data.ai.subassistant.buildInitialSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.mergeSubAssistantCallMetadata
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.model.toMessageNode
import net.weero.measix.pilot.service.runtime.toSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SubAssistantDetailResolverTest {
    private val json = Json { encodeDefaults = true }
    private val masterId = Uuid.random()
    private val callerId = Uuid.random()
    private val targetId = Uuid.random()
    private val childId = Uuid.random()
    private val task = UIMessage.user("Review this")

    @Test
    fun `valid master run resolves and timeline stops before next user request`() {
        val master = masterWithTools(listOf(callTool("run-1")))
        val answer = UIMessage.assistant("First answer")
        val nextTask = UIMessage.user("Next request")
        val laterAnswer = UIMessage.assistant("Must stay hidden")
        val child = childWithNodes(task, answer, nextTask, laterAnswer)

        val linkResult = resolveSubAssistantDetailLink(master, "run-1", json)
        assertTrue(linkResult is SubAssistantDetailLinkResult.Ready)
        val link = (linkResult as SubAssistantDetailLinkResult.Ready).link
        val timeline = resolveSubAssistantTimeline(masterId, link, child)!!

        assertEquals("Review this", link.request)
        assertNull(link.failureDetail)
        assertEquals(listOf(answer.id), timeline.map { it.currentMessage.id })
    }

    @Test
    fun `failed run exposes tool result detail for the detail page`() {
        val metadata = buildInitialSubAssistantCallMetadata(
            runId = "run-fail",
            targetAssistantId = targetId,
            targetNameSnapshot = "Reviewer",
        ).copy(
            childConversationId = childId.toString(),
            childTaskNodeId = task.id.toString(),
            state = SubAssistantCallState.FAILED,
            reason = "runtime_error",
        )
        val tool = UIMessagePart.Tool(
            toolCallId = "fail",
            toolName = "assistant_call",
            input = """{"assistant_id":"$targetId","request":"Review this"}""",
            output = listOf(
                UIMessagePart.Text(
                    """{"status":"failed","reason":"runtime_error","detail":"HttpException: Failed to get response: 429"}""",
                ),
            ),
        ).mergeSubAssistantCallMetadata(json, metadata)

        val link = (resolveSubAssistantDetailLink(masterWithTools(listOf(tool)), "run-fail", json)
            as SubAssistantDetailLinkResult.Ready).link

        assertEquals("HttpException: Failed to get response: 429", link.failureDetail)
    }

    @Test
    fun `live metadata update keeps later failure detail`() {
        val running = (resolveSubAssistantDetailLink(masterWithTools(listOf(callTool("run-1"))), "run-1", json)
            as SubAssistantDetailLinkResult.Ready).link
        val failedTool = failedCall("run-1")
        val failed = (resolveSubAssistantDetailLink(masterWithTools(listOf(failedTool)), "run-1", json)
            as SubAssistantDetailLinkResult.Ready).link
        val merged = mergeLiveSubAssistantDetailLink(previous = running, incoming = failed)
        val laterMetadataWithoutDetail = mergeLiveSubAssistantDetailLink(
            previous = merged,
            incoming = failed.copy(failureDetail = null),
        )

        assertEquals(SubAssistantCallState.FAILED, merged.metadata.state)
        assertEquals("HttpException: Failed to get response: 429", merged.failureDetail)
        assertEquals("HttpException: Failed to get response: 429", laterMetadataWithoutDetail.failureDetail)
    }

    @Test
    fun `attachment preview update rejects a newer child snapshot with the same id`() {
        val link = (resolveSubAssistantDetailLink(masterWithTools(listOf(callTool("run-1"))), "run-1", json)
            as SubAssistantDetailLinkResult.Ready).link
        val firstChild = childWithNodes(task)
        val newerChild = childWithNodes(task, UIMessage.assistant("new output"))
        val ready = SubAssistantDetailUiState.Ready(link, firstChild, emptyList())

        assertTrue(isCurrentChildSnapshot(ready, firstChild))
        assertTrue(firstChild.conversationId == newerChild.conversationId)
        assertTrue(!isCurrentChildSnapshot(ready, newerChild))
    }

    @Test
    fun `duplicate run id in master is rejected`() {
        val master = masterWithTools(listOf(callTool("run-1"), callTool("run-1")))

        assertEquals(
            SubAssistantDetailLinkResult.Unavailable,
            resolveSubAssistantDetailLink(master, "run-1", json),
        )
    }

    @Test
    fun `missing run is terminally unavailable instead of pending forever`() {
        assertEquals(
            SubAssistantDetailLinkResult.Unavailable,
            resolveSubAssistantDetailLink(masterWithTools(emptyList()), "missing", json),
        )
    }

    @Test
    fun `active run without child link remains pending`() {
        val metadata = buildInitialSubAssistantCallMetadata(
            runId = "run-pending",
            targetAssistantId = targetId,
            targetNameSnapshot = "Reviewer",
        ).copy(state = SubAssistantCallState.STARTING)
        val tool = UIMessagePart.Tool(
            toolCallId = "pending",
            toolName = "assistant_call",
            input = """{"assistant_id":"$targetId","request":"Review this"}""",
        ).mergeSubAssistantCallMetadata(json, metadata)

        assertEquals(
            SubAssistantDetailLinkResult.Pending,
            resolveSubAssistantDetailLink(masterWithTools(listOf(tool)), "run-pending", json),
        )
    }

    @Test
    fun `malformed child link is unavailable even while run is active`() {
        val metadata = buildInitialSubAssistantCallMetadata(
            runId = "run-malformed",
            targetAssistantId = targetId,
            targetNameSnapshot = "Reviewer",
        ).copy(
            childConversationId = "not-a-uuid",
            childTaskNodeId = task.id.toString(),
            state = SubAssistantCallState.RUNNING,
        )
        val tool = UIMessagePart.Tool(
            toolCallId = "malformed",
            toolName = "assistant_call",
            input = "{}",
        ).mergeSubAssistantCallMetadata(json, metadata)

        assertEquals(
            SubAssistantDetailLinkResult.Unavailable,
            resolveSubAssistantDetailLink(masterWithTools(listOf(tool)), "run-malformed", json),
        )
    }

    @Test
    fun `child from another master or target is rejected`() {
        val link = (resolveSubAssistantDetailLink(masterWithTools(listOf(callTool("run-1"))), "run-1", json)
            as SubAssistantDetailLinkResult.Ready).link

        assertNull(
            resolveSubAssistantTimeline(
                masterId,
                link,
                childWithNodes(task).let { child ->
                    child.copy(header = child.header.copy(parentConversationId = Uuid.random()))
                },
            )
        )
        assertNull(
            resolveSubAssistantTimeline(
                masterId,
                link,
                childWithNodes(task).let { child ->
                    child.copy(header = child.header.copy(assistantId = Uuid.random()))
                },
            )
        )
    }

    @Test
    fun `task id in an unselected branch is rejected`() {
        val link = (resolveSubAssistantDetailLink(masterWithTools(listOf(callTool("run-1"))), "run-1", json)
            as SubAssistantDetailLinkResult.Ready).link
        val selectedOtherTask = UIMessage.user("Selected branch")
        val branchedNode = MessageNode(
            messages = listOf(task, selectedOtherTask),
            selectIndex = 1,
        )
        val child = childWithNodes().copy(nodes = listOf(branchedNode))

        assertNull(resolveSubAssistantTimeline(masterId, link, child))
    }

    private fun failedCall(runId: String): UIMessagePart.Tool {
        val metadata = buildInitialSubAssistantCallMetadata(
            runId = runId,
            targetAssistantId = targetId,
            targetNameSnapshot = "Reviewer",
        ).copy(
            childConversationId = childId.toString(),
            childTaskNodeId = task.id.toString(),
            state = SubAssistantCallState.FAILED,
            reason = "runtime_error",
        )
        return UIMessagePart.Tool(
            toolCallId = runId,
            toolName = "assistant_call",
            input = """{"assistant_id":"$targetId","request":"Review this"}""",
            output = listOf(
                UIMessagePart.Text(
                    """{"status":"failed","reason":"runtime_error","detail":"HttpException: Failed to get response: 429"}""",
                ),
            ),
        ).mergeSubAssistantCallMetadata(json, metadata)
    }

    private fun callTool(runId: String): UIMessagePart.Tool {
        val metadata = buildInitialSubAssistantCallMetadata(
            runId = runId,
            targetAssistantId = targetId,
            targetNameSnapshot = "Reviewer",
        ).copy(
            childConversationId = childId.toString(),
            childTaskNodeId = task.id.toString(),
            state = SubAssistantCallState.COMPLETED,
        )
        return UIMessagePart.Tool(
            toolCallId = runId,
            toolName = "assistant_call",
            input = """{"assistant_id":"$targetId","request":"Review this"}""",
            output = listOf(UIMessagePart.Text("done")),
        ).mergeSubAssistantCallMetadata(json, metadata)
    }

    private fun masterWithTools(tools: List<UIMessagePart.Tool>) = Conversation(
        id = masterId,
        assistantId = callerId,
        messageNodes = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = tools).toMessageNode()),
    ).toSnapshot()

    private fun childWithNodes(vararg messages: UIMessage) = Conversation(
        id = childId,
        assistantId = targetId,
        messageNodes = messages.map { it.toMessageNode() },
        parentConversationId = masterId,
    ).toSnapshot()
}
