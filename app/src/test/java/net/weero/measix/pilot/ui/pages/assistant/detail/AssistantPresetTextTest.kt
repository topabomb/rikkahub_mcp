package net.weero.measix.pilot.ui.pages.assistant.detail

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class AssistantPresetTextTest {
    @Test fun `editing preset text retains media role identity and metadata`() {
        val image = UIMessagePart.Image("https://example.org/configuration.png")
        val original = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("before"), image, UIMessagePart.Text("tail")))
        assertEquals(original.copy(parts = listOf(UIMessagePart.Text("after"), image)), original.withPresetText("after"))
        val imageOnly = original.copy(parts = listOf(image))
        assertEquals(imageOnly.copy(parts = listOf(UIMessagePart.Text("added"), image)), imageOnly.withPresetText("added"))
    }
}
