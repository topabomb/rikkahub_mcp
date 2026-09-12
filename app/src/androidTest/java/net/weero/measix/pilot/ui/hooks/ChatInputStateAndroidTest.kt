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
