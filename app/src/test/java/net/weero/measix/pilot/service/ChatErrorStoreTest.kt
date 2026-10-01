package net.weero.measix.pilot.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.rerere.ai.ui.MessageTerminalStatus
import me.rerere.ai.ui.TurnTerminalReasons
import me.rerere.ai.util.ProviderFailureKind
import net.weero.measix.pilot.R
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ChatErrorStoreTest {
    @Test fun `read failures deduplicate only within their conversation and can return after dismissal`() {
        val store = ChatErrorStore()
        val error = ChatError(detail = "IOException: favorites unavailable", conversationId = Uuid.random(),
            solution = ChatErrorSolution.RetryConversationReads, retention = ChatErrorRetention.UNTIL_DISMISSED)
        store.add(error)
        store.add(error.copy(id = Uuid.random()))
        assertEquals(1, store.errors.value.size)
        store.add(error.copy(id = Uuid.random(), conversationId = Uuid.random()))
        assertEquals(2, store.errors.value.size)
        store.dismiss(error.id)
        store.add(error.copy(id = Uuid.random()))
        assertEquals(2, store.errors.value.size)
    }

    @Test
    fun `command diagnostics preserve full cause and cleanup failures while redacting credentials`() {
        val cause = java.io.IOException("payload read failed " + "x".repeat(900))
        val error = IllegalStateException("Cannot import Authorization: Bearer command-secret", cause)
        error.addSuppressed(java.io.IOException("temporary-file cleanup failed"))
        val store = ChatErrorStore()
        store.add(error)
        val diagnostic = store.errors.value.single().detail
        assertTrue(diagnostic.contains("IllegalStateException"))
        assertTrue(diagnostic.contains("Caused by: IOException: payload read failed"))
        assertTrue(diagnostic.contains("x".repeat(900)))
        assertTrue(diagnostic.contains("Suppressed: IOException: temporary-file cleanup failed"))
        org.junit.Assert.assertFalse(diagnostic.contains("command-secret"))
    }

    @Test
    fun `chat errors preserve diagnostics and receive unique identities`() {
        val conversationId = Uuid.random()
        val first = ChatError(
            title = "Generation Failed",
            detail = "API rate limit exceeded",
            conversationId = conversationId,
            retention = ChatErrorRetention.UNTIL_DISMISSED,
        )
        val second = ChatError(detail = "second")

        assertNotEquals(first.id, second.id)
        assertEquals("Generation Failed", first.title)
        assertEquals("API rate limit exceeded", first.detail)
        assertEquals(conversationId, first.conversationId)
        assertEquals(ChatErrorRetention.UNTIL_DISMISSED, first.retention)
        assertTrue(first.timestamp <= System.currentTimeMillis())
    }

    @Test
    fun `store ignores cancellation and supports deterministic dismiss and clear`() {
        val store = ChatErrorStore()
        store.add(CancellationException("cancelled"))
        store.add(IllegalStateException("first"))
        store.add(IllegalStateException("second"))

        assertEquals(2, store.errors.value.size)
        store.dismiss(store.errors.value.first().id)
        assertEquals(listOf("IllegalStateException: second"), store.errors.value.map { it.detail })
        store.clear(Uuid.random())
        assertTrue(store.errors.value.isEmpty())
    }

    @Test
    fun `conversation projection isolates errors and terminal reopening replaces the same source`() = runTest {
        val firstConversation = Uuid.random()
        val secondConversation = Uuid.random()
        val sourceMessageId = Uuid.random()
        val store = ChatErrorStore()

        store.add(ChatError(detail = "global"))
        store.add(
            ChatError(
                detail = "first diagnostic",
                conversationId = firstConversation,
                sourceMessageId = sourceMessageId,
            )
        )
        store.add(ChatError(detail = "other", conversationId = secondConversation))
        store.add(
            ChatError(
                detail = "reopened diagnostic",
                conversationId = firstConversation,
                sourceMessageId = sourceMessageId,
            )
        )

        assertEquals(
            listOf("global", "reopened diagnostic"),
            store.errorsFor(firstConversation).first().map { it.detail },
        )
        assertEquals(3, store.errors.value.size)

        store.clear(firstConversation)
        assertEquals(listOf("other"), store.errors.value.map { it.detail })
    }

    @Test
    fun `provider action follows original model ownership and preserves managed service diagnostics`() {
        val context = mockk<android.content.Context> {
            every { getString(any()) } answers { "resource-${firstArg<Int>()}" }
        }
        val managed = me.rerere.common.configuration.ConfigurationReference.Enterprise(
            me.rerere.common.configuration.EnterpriseAuthority("original-enterprise"), "mdl_original",
        )
        val personal = me.rerere.common.configuration.ConfigurationReference.random()
        for (reason in listOf(ProviderFailureKind.AUTH_FAILED.reason, ProviderFailureKind.PERMISSION_DENIED.reason,
            ProviderFailureKind.QUOTA_EXHAUSTED.reason)) {
            fun failure(model: me.rerere.common.configuration.ConfigurationReference?) = requireNotNull(terminalChatError(
                context, Uuid.random(), Uuid.random(), MessageTerminalStatus.FAILED, reason,
                "HTTP 401: original upstream diagnostic", model,
            ))
            val enterpriseError = failure(managed)
            assertEquals(null, enterpriseError.solution)
            assertEquals("resource-${R.string.chat_enterprise_model_service_configuration}\n\nHTTP 401: original upstream diagnostic", enterpriseError.detail)
            val userError = failure(personal)
            assertEquals(ChatErrorSolution.CheckProviderSettings, userError.solution)
            assertEquals("HTTP 401: original upstream diagnostic", userError.detail)
            assertEquals(null, failure(null).solution)
        }
        val budget = requireNotNull(terminalChatError(context, Uuid.random(), Uuid.random(), MessageTerminalStatus.FAILED,
            net.weero.measix.pilot.data.enterprise.EnterpriseRuntimeProblemCodes.BUDGET_EXHAUSTED, "budget rejected", managed))
        assertEquals(ChatErrorSolution.ViewEnterpriseUsage, budget.solution)
    }

    @Test
    fun `terminal presentation distinguishes provider kinds incomplete and cancellation`() {
        assertEquals(
            R.string.error_title_quota_exhausted,
            terminalMessagePresentation(
                MessageTerminalStatus.FAILED,
                ProviderFailureKind.QUOTA_EXHAUSTED.reason,
            ).titleResource,
        )
        assertEquals(
            R.string.error_title_response_incomplete,
            terminalMessagePresentation(
                MessageTerminalStatus.INCOMPLETE,
                TurnTerminalReasons.PROVIDER_INCOMPLETE,
            ).statusResource,
        )
        assertEquals(
            R.string.chat_message_terminal_user_stopped,
            terminalMessagePresentation(
                MessageTerminalStatus.CANCELLED,
                TurnTerminalReasons.USER_STOP,
            ).statusResource,
        )
        assertEquals(
            ChatErrorSolution.ViewEnterpriseUsage,
            terminalMessagePresentation(
                MessageTerminalStatus.FAILED,
                net.weero.measix.pilot.data.enterprise.EnterpriseRuntimeProblemCodes.BUDGET_EXHAUSTED,
            ).solution,
        )
    }

}
