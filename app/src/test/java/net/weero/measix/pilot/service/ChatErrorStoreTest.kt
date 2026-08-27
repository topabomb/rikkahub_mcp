package net.weero.measix.pilot.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.rerere.ai.ui.MessageTerminalStatus
import me.rerere.ai.ui.TurnTerminalReasons
import me.rerere.ai.util.ProviderFailureKind
import net.weero.measix.pilot.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ChatErrorStoreTest {
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
        assertEquals(listOf("second"), store.errors.value.map { it.detail })
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
    }
}
