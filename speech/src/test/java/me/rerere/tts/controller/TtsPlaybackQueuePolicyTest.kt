package me.rerere.tts.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsPlaybackQueuePolicyTest {
    private fun chunk(text: String) = TtsChunk(index = 0, text = text)

    @Test
    fun `first tool call establishes its turn queue`() {
        val queue = TurnPlaybackQueue()

        assertTrue(queue.requiresReplacement("turn-1", replaceWithinSession = false))
        queue.append(listOf(chunk("first")), "turn-1", "master")
        assertFalse(queue.requiresReplacement("turn-1", replaceWithinSession = false))
    }

    @Test
    fun `sequential calls in same turn append across master and targets`() {
        val queue = TurnPlaybackQueue()
        queue.append(listOf(chunk("master-1")), "turn-1", "master")
        queue.append(listOf(chunk("target-1"), chunk("target-2")), "turn-1", "target")
        queue.append(listOf(chunk("master-2")), "turn-1", "master")

        val actual = generateSequence(queue::poll).toList()
        assertEquals(listOf("master-1", "target-1", "target-2", "master-2"), actual.map { it.chunk.text })
        assertEquals(listOf("master", "target", "target", "master"), actual.map { it.source })
        assertEquals(listOf(0, 1, 2, 3), actual.map { it.chunk.index })
    }

    @Test
    fun `disabled sequential playback replaces within same turn`() {
        val queue = TurnPlaybackQueue()
        queue.append(listOf(chunk("first")), "turn-1", "master")
        assertTrue(queue.requiresReplacement("turn-1", replaceWithinSession = true))
    }

    @Test
    fun `different turn always replaces even if caller requests append`() {
        val queue = TurnPlaybackQueue()
        queue.append(listOf(chunk("old")), "turn-1", "master")
        assertTrue(queue.requiresReplacement("turn-2", replaceWithinSession = false))
        queue.clear()
        queue.append(listOf(chunk("new")), "turn-2", "master")
        assertEquals("new", queue.poll()?.chunk?.text)
    }

    @Test
    fun `manual playback replaces a tool-owned queue`() {
        val queue = TurnPlaybackQueue()
        queue.append(listOf(chunk("tool")), "turn-1", "master")
        assertTrue(queue.requiresReplacement(null, replaceWithinSession = false))
    }

    @Test
    fun `same turn can append after playback ended without losing order identity`() {
        val queue = TurnPlaybackQueue()
        queue.append(listOf(chunk("first")), "turn-1", "master")
        assertEquals("first", queue.poll()?.chunk?.text)

        assertFalse(queue.requiresReplacement("turn-1", replaceWithinSession = false))
        queue.append(listOf(chunk("late")), "turn-1", "target")

        val late = queue.poll()
        assertEquals("late", late?.chunk?.text)
        assertEquals("target", late?.source)
        assertEquals(1, late?.chunk?.index)
    }

    @Test
    fun `controller skip removes the chunk and its source as one entry`() {
        val queue = TurnPlaybackQueue()
        queue.append(listOf(chunk("current")), "turn-1", "master")
        queue.append(listOf(chunk("skip-me")), "turn-1", "target-a")
        queue.append(listOf(chunk("next")), "turn-1", "target-b")

        assertEquals("current", queue.poll()?.chunk?.text)
        val skipped = queue.poll()
        val next = queue.poll()

        assertEquals("skip-me", skipped?.chunk?.text)
        assertEquals("target-a", skipped?.source)
        assertEquals("next", next?.chunk?.text)
        assertEquals("target-b", next?.source)
    }

    @Test
    fun `toolbar stop clears content and releases turn ownership`() {
        val queue = TurnPlaybackQueue()
        queue.append(listOf(chunk("queued")), "turn-1", "master")

        queue.clear()

        assertFalse(queue.hasPending())
        assertTrue(queue.requiresReplacement("turn-1", replaceWithinSession = false))
    }
}
