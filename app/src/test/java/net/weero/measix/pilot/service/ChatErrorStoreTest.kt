package net.weero.measix.pilot.service

import kotlinx.coroutines.CancellationException
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
            error = RuntimeException("API rate limit exceeded"),
            conversationId = conversationId,
        )
        val second = ChatError(error = RuntimeException("second"))

        assertNotEquals(first.id, second.id)
        assertEquals("Generation Failed", first.title)
        assertEquals("API rate limit exceeded", first.error.message)
        assertEquals(conversationId, first.conversationId)
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
        assertEquals(listOf("second"), store.errors.value.map { it.error.message })
        store.clear()
        assertTrue(store.errors.value.isEmpty())
    }
}
