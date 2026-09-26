package net.weero.measix.pilot.ui.hooks

import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class ChatInputStateAndroidTest {
    @Test
    fun committedSubmissionClearsCapturedInputButPreservesEditsAndNewAttachments() {
        val oldAttachment = UIMessagePart.Image("file:///managed/old.png")
        val addedAttachment = UIMessagePart.Image("file:///managed/new.png")
        val state = ChatInputState()
        state.setMessageText("original")
        state.messageContent = listOf(oldAttachment)
        val submitted = state.captureSubmission()
        state.setMessageText("original with later edit")
        state.messageContent = listOf(oldAttachment, addedAttachment)
        state.completeSubmission(submitted)
        assertEquals(listOf(UIMessagePart.Text("original with later edit"), addedAttachment), state.getContents())

        val next = state.captureSubmission()
        state.completeSubmission(next)
        assertTrue(state.isEmpty())
    }

    @Test
    fun rejectedSubmissionAndLateResultAfterInputReplacementLeaveDraftIntact() {
        val state = ChatInputState()
        val attachment = UIMessagePart.Document("file:///managed/original.txt", "original.txt", "text/plain")
        state.setMessageText("unchanged after failure")
        state.messageContent = listOf(attachment)
        val rejected = state.captureSubmission()
        assertEquals(rejected.contents, state.getContents())
        state.clearInput()
        state.setMessageText("new draft")
        state.messageContent = listOf(attachment)
        state.completeSubmission(rejected)
        assertEquals(listOf(UIMessagePart.Text("new draft"), attachment), state.getContents())
    }

    @Test
    fun lateCompletionDoesNotRemoveReaddedAttachmentOrEditingInput() {
        val state = ChatInputState()
        val old = UIMessagePart.Image("file:///managed/same.png")
        state.messageContent = listOf(old)
        val submission = state.captureSubmission()
        val readded = old.copy()
        state.messageContent = listOf(readded)
        state.completeSubmission(submission)
        assertEquals(listOf(readded), state.getContents())
        val another = state.captureSubmission()
        state.editingMessage = Uuid.random()
        state.setMessageText("editing history")
        state.completeSubmission(another)
        assertEquals("editing history", state.textContent.text.toString())
        assertEquals(listOf(readded), state.messageContent)
    }

    @Test
    fun sameAttachmentInstanceRemovedAndReaddedGetsNewSelectionIdentity() {
        val state = ChatInputState()
        val part = UIMessagePart.Image("file:///managed/same.png")
        state.messageContent = listOf(part)
        val submitted = state.captureSubmission()
        state.messageContent = emptyList()
        state.messageContent = listOf(part)
        state.completeSubmission(submitted)
        assertEquals(listOf(part), state.messageContent)

        val second = state.captureSubmission()
        state.messageContent = listOf(part, part)
        state.completeSubmission(second)
        assertEquals(listOf(part), state.messageContent)
    }

    @Test
    fun attachmentOnlyInputHasNoEmptyTextAndEditPreservesOrderAndOwnership() {
        val image = UIMessagePart.Image("file:///managed/image.png")
        val state = ChatInputState()
        state.messageContent = listOf(image)
        state.setMessageText("  \n ")
        assertEquals(listOf(image), state.getContents())
        assertFalse(state.isEmpty())

        state.setContents(listOf(UIMessagePart.Text("Earlier"), image, UIMessagePart.Text("Last")))
        state.editingMessage = Uuid.random()
        state.setMessageText("")
        assertEquals(listOf(UIMessagePart.Text("Earlier"), image), state.getContents())
        assertFalse(state.shouldDeleteFileOnRemove(image))
        state.messageContent = emptyList()
        assertEquals(listOf(UIMessagePart.Text("Earlier")), state.getContents())
        assertFalse(state.isEmpty())
        state.clearInput()
        assertTrue(state.isEmpty())
    }

    @Test
    fun editingAttachmentOnlyMessageCanAddAndRemoveText() {
        val image = UIMessagePart.Image("attachment:original")
        val state = ChatInputState()
        state.setContents(listOf(image))
        state.editingMessage = Uuid.random()
        state.setMessageText("Caption")
        assertEquals(listOf(UIMessagePart.Text("Caption"), image), state.getContents())
        state.setMessageText(" ")
        assertEquals(listOf(image), state.getContents())
        state.messageContent = emptyList()
        assertTrue(state.isEmpty())
    }
}
