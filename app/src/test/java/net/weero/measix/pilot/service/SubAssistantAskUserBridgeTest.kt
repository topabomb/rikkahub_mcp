package net.weero.measix.pilot.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.AssistantAffectScope
import net.weero.measix.pilot.data.model.AssistantRegex
import net.weero.measix.pilot.service.runtime.answerToolAtLocator
import net.weero.measix.pilot.service.runtime.normalizeSubAssistantCancellationReason
import net.weero.measix.pilot.service.runtime.preprocessSubAssistantTask
import net.weero.measix.pilot.service.runtime.recoverSubAssistantToolsAfterInterruption
import net.weero.measix.pilot.service.runtime.recoverSubAssistantToolsAfterRestart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SubAssistantAskUserBridgeTest {
    @Test
    fun `answer uses message id and ordinal and preserves sibling call`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                pendingAsk("duplicate"),
                pendingAsk("duplicate"),
            ),
        )

        val updated = answerToolAtLocator(
            messages = listOf(message),
            messageId = message.id,
            toolOrdinal = 1,
            answer = "{\"answers\":{\"choice\":\"yes\"}}",
        )!!

        val tools = updated.single().getTools()
        assertEquals(ToolApprovalState.Pending, tools[0].approvalState)
        assertEquals(
            ToolApprovalState.Answered("{\"answers\":{\"choice\":\"yes\"}}"),
            tools[1].approvalState,
        )
    }

    @Test
    fun `answer rejects wrong message or non ask-user tool`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(pendingAsk("id").copy(toolName = "workspace_write_file")),
        )

        assertNull(answerToolAtLocator(listOf(message), Uuid.random(), 0, "answer"))
        assertNull(answerToolAtLocator(listOf(message), message.id, 0, "answer"))
    }

    @Test
    fun `restart makes pending and in-flight child tools protocol complete and read-only`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                pendingAsk("question"),
                UIMessagePart.Tool(
                    toolCallId = "running",
                    toolName = "get_time_info",
                    input = "{}",
                ),
            ),
        )

        val recovered = message.recoverSubAssistantToolsAfterRestart()
        val tools = recovered.getTools()

        assertTrue(tools.all { it.isExecuted })
        assertEquals(ToolApprovalState.Denied("app_restarted"), tools[0].approvalState)
        assertEquals(ToolApprovalState.Auto, tools[1].approvalState)
        assertTrue((tools[0].output.single() as UIMessagePart.Text).text.contains("app_restarted"))
    }

    @Test
    fun `user stop makes child tools protocol complete for safe lineage reuse`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(pendingAsk("question")),
        )

        val recovered = message.recoverSubAssistantToolsAfterInterruption("user_cancelled")
        val tool = recovered.getTools().single()

        assertTrue(tool.isExecuted)
        assertEquals(ToolApprovalState.Denied("user_cancelled"), tool.approvalState)
        assertTrue((tool.output.single() as UIMessagePart.Text).text.contains("user_cancelled"))
    }

    @Test
    fun `unknown coroutine cancellation text is normalized to user cancelled`() {
        assertEquals("user_cancelled", normalizeSubAssistantCancellationReason("StandaloneCoroutine was cancelled"))
        assertEquals("target_removed", normalizeSubAssistantCancellationReason("assistant_removed"))
        assertEquals("target_access_revoked", normalizeSubAssistantCancellationReason("target_access_revoked"))
    }

    @Test
    fun `target user regex preprocessing is applied before child persistence`() {
        val target = Assistant(
            regexes = listOf(
                AssistantRegex(
                    id = Uuid.random(),
                    findRegex = "secret",
                    replaceString = "redacted",
                    affectingScope = setOf(AssistantAffectScope.USER),
                )
            )
        )

        assertEquals("redacted task", preprocessSubAssistantTask("secret task", target))
    }

    private fun pendingAsk(id: String) = UIMessagePart.Tool(
        toolCallId = id,
        toolName = "ask_user",
        input = "{}",
        approvalState = ToolApprovalState.Pending,
    )
}
