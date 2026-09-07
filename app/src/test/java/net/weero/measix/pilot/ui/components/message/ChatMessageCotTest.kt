package net.weero.measix.pilot.ui.components.message

import me.rerere.ai.core.MessageRole
import kotlin.uuid.Uuid
import me.rerere.ai.ui.MessageTerminalStatus
import me.rerere.ai.ui.TurnTerminalReasons
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.ToolInteractionState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.mediaPersistenceFailurePart
import me.rerere.ai.util.ProviderFailureKind
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.ai.tools.local.GENERATE_IMAGE_TOOL_NAME
import net.weero.measix.pilot.ui.components.ui.selectCollapsedSteps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageCotTest {
    private fun tool(
        name: String,
        id: String = name,
        pending: Boolean = false,
    ) = UIMessagePart.Tool(
        localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = id,
        toolName = name,
        input = "{}",
        interactionState = if (pending) ToolInteractionState.AwaitingApproval else ToolInteractionState.NotRequired,
    )

    @Test
    fun `generate_image stays in the thinking timeline instead of a child-run card`() {
        val blocks = listOf(
            UIMessagePart.Reasoning("plan"),
            tool("search_web"),
            tool(GENERATE_IMAGE_TOOL_NAME),
            tool("read_file"),
        ).groupMessageParts()

        assertEquals(1, blocks.size)
        val steps = (blocks.single() as MessagePartBlock.ThinkingBlock).steps
        assertEquals(4, steps.size)
        assertTrue(steps[2] is ThinkingStep.ToolStep)
        assertEquals(GENERATE_IMAGE_TOOL_NAME, (steps[2] as ThinkingStep.ToolStep).tool.toolName)
        assertTrue(blocks.none { it is MessagePartBlock.SubAssistantCallBlock })
    }

    @Test
    fun `collapsed timeline keeps interleaved generate_image and pending tools`() {
        val steps = listOf(
            ThinkingStep.ToolStep(tool("search_web", "s1")),
            ThinkingStep.ToolStep(tool(GENERATE_IMAGE_TOOL_NAME, "img")),
            ThinkingStep.ToolStep(tool("read_file", "r1")),
            ThinkingStep.ToolStep(tool("search_web", "s2", pending = true)),
            ThinkingStep.ToolStep(tool("write_file", "w1")),
            ThinkingStep.ToolStep(tool("shell", "sh")),
        )

        val visible = selectCollapsedSteps(
            steps = steps,
            collapsedVisibleCount = 2,
            keepVisible = { it.shouldStayVisibleWhenCollapsed() },
        )

        assertEquals(
            listOf("generate_image", "search_web", "write_file", "shell"),
            visible.filterIsInstance<ThinkingStep.ToolStep>().map { it.tool.toolName },
        )
        assertEquals(
            listOf("img", "s2", "w1", "sh"),
            visible.filterIsInstance<ThinkingStep.ToolStep>().map { it.tool.providerCallId },
        )
    }

    @Test
    fun `collapsed timeline does not duplicate pinned tail steps`() {
        val completed = ThinkingStep.ToolStep(tool("search_web"))
        val pinned = ThinkingStep.ToolStep(tool("read_file", pending = true))
        val steps = listOf(completed, pinned)

        val visible = selectCollapsedSteps(
            steps = steps,
            collapsedVisibleCount = 2,
            keepVisible = { it.shouldStayVisibleWhenCollapsed() },
        )

        assertEquals(steps, visible)
    }

    @Test
    fun `ordinary completed tools are not pinned`() {
        val search = ThinkingStep.ToolStep(tool("search_web"))
        val image = ThinkingStep.ToolStep(tool(GENERATE_IMAGE_TOOL_NAME))
        val pending = ThinkingStep.ToolStep(tool("read_file", pending = true))
        val reasoning = ThinkingStep.ReasoningStep(UIMessagePart.Reasoning("think"))

        assertFalse(search.shouldStayVisibleWhenCollapsed())
        assertTrue(image.shouldStayVisibleWhenCollapsed())
        assertTrue(pending.shouldStayVisibleWhenCollapsed())
        assertFalse(reasoning.shouldStayVisibleWhenCollapsed())
    }

    @Test
    fun `media failure placeholders render at top level and inside tool output`() {
        val failure = mediaPersistenceFailurePart(
            UIMessagePart.Image(url = "data:image/png;base64,broken"),
        )
        val rendered = listOf(
            failure,
            tool("generate_image").copy(output = listOf(failure)),
        ).withMediaFailurePlaceholders("Image unavailable")

        assertEquals("Image unavailable", (rendered[0] as UIMessagePart.Text).text)
        val toolOutput = (rendered[1] as UIMessagePart.Tool).output.single() as UIMessagePart.Text
        assertEquals("Image unavailable", toolOutput.text)
    }

    @Test
    fun `every terminal status has a localized resource`() {
        assertEquals(
            R.string.chat_message_terminal_cancelled,
            terminalStatusTextResource(MessageTerminalStatus.CANCELLED),
        )
        assertEquals(
            R.string.chat_message_terminal_failed,
            terminalStatusTextResource(MessageTerminalStatus.FAILED),
        )
        assertEquals(
            R.string.chat_message_terminal_incomplete,
            terminalStatusTextResource(MessageTerminalStatus.INCOMPLETE),
        )
        assertEquals(
            R.string.chat_message_terminal_interrupted,
            terminalStatusTextResource(MessageTerminalStatus.INTERRUPTED),
        )
    }

    @Test
    fun `terminal reason selects a specific short label`() {
        assertEquals(
            R.string.error_title_rate_limited,
            terminalStatusTextResource(MessageTerminalStatus.FAILED, ProviderFailureKind.RATE_LIMITED.reason),
        )
        assertEquals(
            R.string.error_title_tool_loop_limit,
            terminalStatusTextResource(MessageTerminalStatus.INCOMPLETE, TurnTerminalReasons.TOOL_LOOP_LIMIT),
        )
        assertEquals(
            R.string.chat_message_terminal_superseded,
            terminalStatusTextResource(
                MessageTerminalStatus.CANCELLED,
                TurnTerminalReasons.SUPERSEDED_BY_NEW_TURN,
            ),
        )
    }

    @Test
    fun `empty cancelled assistant slot is hidden but partial content remains visible`() {
        val empty = UIMessage.assistant("").copy(
            terminalStatus = MessageTerminalStatus.CANCELLED,
            terminalReason = TurnTerminalReasons.USER_STOP,
        )
        val partial = empty.copy(parts = listOf(UIMessagePart.Text("partial")))

        assertTrue(shouldHideEmptyCancelledMessage(empty))
        assertFalse(shouldHideEmptyCancelledMessage(partial))
    }

    @Test
    fun `message header is emitted only when it has visible identity content`() {
        assertTrue(
            shouldShowChatMessageHeader(
                role = MessageRole.USER,
                hasRenderableParts = true,
                hasVisibleMessage = true,
                showUserAvatar = true,
                showAssistantIcon = false,
                showAssistantName = false,
                hasAssistantIdentity = false,
            ),
        )
        assertFalse(
            shouldShowChatMessageHeader(
                role = MessageRole.USER,
                hasRenderableParts = true,
                hasVisibleMessage = true,
                showUserAvatar = false,
                showAssistantIcon = false,
                showAssistantName = false,
                hasAssistantIdentity = false,
            ),
        )
        assertFalse(
            shouldShowChatMessageHeader(
                role = MessageRole.ASSISTANT,
                hasRenderableParts = true,
                hasVisibleMessage = true,
                showUserAvatar = false,
                showAssistantIcon = false,
                showAssistantName = false,
                hasAssistantIdentity = true,
            ),
        )
        assertTrue(
            shouldShowChatMessageHeader(
                role = MessageRole.ASSISTANT,
                hasRenderableParts = true,
                hasVisibleMessage = true,
                showUserAvatar = false,
                showAssistantIcon = true,
                showAssistantName = false,
                hasAssistantIdentity = true,
            ),
        )
    }
}
