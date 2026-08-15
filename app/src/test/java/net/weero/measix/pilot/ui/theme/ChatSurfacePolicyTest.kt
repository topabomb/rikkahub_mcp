package net.weero.measix.pilot.ui.theme

import androidx.compose.ui.graphics.Color
import net.weero.measix.pilot.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSurfacePolicyTest {
    @Test
    fun `chrome stays at user opacity when there is no assistant background`() {
        assertEquals(1f, ChatSurfacePolicy.chromeAlpha(false, 1f))
        assertEquals(0.5f, ChatSurfacePolicy.chromeAlpha(false, 0.5f), 0.0001f)
    }

    @Test
    fun `chrome is capped when assistant background is visible and opacity is full`() {
        assertEquals(
            ChatSurfacePolicy.BACKGROUND_CHROME_MAX_ALPHA,
            ChatSurfacePolicy.chromeAlpha(true, 1f),
            0.0001f,
        )
    }

    @Test
    fun `user can still lower chrome below the background cap`() {
        assertEquals(0.4f, ChatSurfacePolicy.chromeAlpha(true, 0.4f), 0.0001f)
    }

    @Test
    fun `chrome opacity is clamped to the supported range`() {
        assertEquals(
            ChatSurfacePolicy.MIN_CHROME_ALPHA,
            ChatSurfacePolicy.chromeAlpha(false, 0f),
            0.0001f,
        )
        assertEquals(1f, ChatSurfacePolicy.chromeAlpha(false, 2f), 0.0001f)
    }

    @Test
    fun `page chrome only thins when a background is visible`() {
        assertEquals(1f, ChatSurfacePolicy.pageChromeAlpha(false))
        assertEquals(
            ChatSurfacePolicy.BACKGROUND_CHROME_MAX_ALPHA,
            ChatSurfacePolicy.pageChromeAlpha(true),
            0.0001f,
        )
    }

    @Test
    fun `artifacts stay fully opaque`() {
        assertEquals(1f, ChatSurfacePolicy.artifactAlpha())
    }

    @Test
    fun `overlay helper keeps full opacity colors unchanged`() {
        val color = Color(0xFF112233)
        assertEquals(color, color.withOverlayAlpha(1f))
        assertEquals(0.82f, color.withOverlayAlpha(0.82f).alpha, 0.005f)
    }

    @Test
    fun `visible background includes image and gradient`() {
        assertFalse(Assistant().hasVisibleChatBackground())
        assertFalse(Assistant(background = "   ").hasVisibleChatBackground())
        assertTrue(Assistant(background = "file://wall.png").hasVisibleChatBackground())
        assertTrue(Assistant(useGradientBackground = true).hasVisibleChatBackground())
    }
}
