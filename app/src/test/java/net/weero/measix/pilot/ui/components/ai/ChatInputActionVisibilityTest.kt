package net.weero.measix.pilot.ui.components.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatInputActionVisibilityTest {
    @Test
    fun `IME keeps exactly one reachable terminal action`() {
        assertEquals(
            ChatInputActionVisibility(
                showActionRow = false,
                showTrailingSend = true,
                showTrailingAsr = false,
            ),
            chatInputActionVisibility(imeTargetVisible = true, isAsrRecording = false),
        )
        assertEquals(
            ChatInputActionVisibility(
                showActionRow = false,
                showTrailingSend = false,
                showTrailingAsr = true,
            ),
            chatInputActionVisibility(imeTargetVisible = true, isAsrRecording = true),
        )
        assertEquals(
            ChatInputActionVisibility(
                showActionRow = true,
                showTrailingSend = false,
                showTrailingAsr = false,
            ),
            chatInputActionVisibility(imeTargetVisible = false, isAsrRecording = true),
        )
    }
}
