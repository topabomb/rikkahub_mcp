package net.weero.measix.pilot.ui.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveLayoutPolicyTest {
    @Test
    fun `width class uses Material 3 large and extra-large breakpoints`() {
        assertEquals(AdaptiveWidthClass.Compact, AdaptiveLayoutPolicy.widthClass(599f))
        assertEquals(AdaptiveWidthClass.Medium, AdaptiveLayoutPolicy.widthClass(600f))
        assertEquals(AdaptiveWidthClass.Expanded, AdaptiveLayoutPolicy.widthClass(840f))
        assertEquals(AdaptiveWidthClass.Large, AdaptiveLayoutPolicy.widthClass(1200f))
        assertEquals(AdaptiveWidthClass.ExtraLarge, AdaptiveLayoutPolicy.widthClass(1600f))
    }

    @Test
    fun `phone portrait uses a modal drawer and single chat pane`() {
        assertEquals(
            ChatLayoutMode.SinglePane,
            layoutMode(widthDp = 390f, heightDp = 844f),
        )
    }

    @Test
    fun `short phone landscape stays single pane even above expanded width`() {
        assertEquals(
            ChatLayoutMode.SinglePane,
            layoutMode(widthDp = 844f, heightDp = 390f),
        )
        assertTrue(AdaptiveLayoutPolicy.useCompactChatInput(heightDp = 390f))
        assertFalse(AdaptiveLayoutPolicy.useCompactChatInput(heightDp = 844f))
    }

    @Test
    fun `tall medium-width window like an unfolded foldable uses two panes`() {
        assertEquals(
            ChatLayoutMode.ListDetail,
            layoutMode(widthDp = 700f, heightDp = 900f),
        )
    }

    @Test
    fun `unfolded foldable main screen uses list and detail`() {
        // vivo X Fold3 Pro unfolded ~962dp wide, ~854dp tall
        assertEquals(
            ChatLayoutMode.ListDetail,
            layoutMode(widthDp = 962f, heightDp = 854f),
        )
        // Samsung Galaxy Z Fold6 unfolded ~924dp wide
        assertEquals(
            ChatLayoutMode.ListDetail,
            layoutMode(widthDp = 924f, heightDp = 854f),
        )
    }

    @Test
    fun `phone landscape keeps single pane because height is too short`() {
        assertEquals(
            ChatLayoutMode.SinglePane,
            layoutMode(widthDp = 844f, heightDp = 390f),
        )
    }

    @Test
    fun `expanded tablet uses conversation list and chat detail`() {
        assertEquals(
            ChatLayoutMode.ListDetail,
            layoutMode(widthDp = 1000f, heightDp = 800f),
        )
    }

    @Test
    fun `large window remains two pane by policy`() {
        assertEquals(
            ChatLayoutMode.ListDetail,
            layoutMode(widthDp = 1800f, heightDp = 1000f),
        )
    }

    @Test
    fun `hinge prevents sidebar collapse but does not affect layout mode`() {
        // Hinge only affects canCollapseChatSidebar, not chatLayoutMode
        assertEquals(
            ChatLayoutMode.ListDetail,
            layoutMode(widthDp = 1000f, heightDp = 800f),
        )
        assertFalse(
            AdaptiveLayoutPolicy.canCollapseChatSidebar(
                widthDp = 1000f,
                heightDp = 800f,
                hasSeparatingVerticalHinge = true,
                isTabletop = false,
            )
        )
        assertTrue(
            AdaptiveLayoutPolicy.canCollapseChatSidebar(
                widthDp = 1000f,
                heightDp = 800f,
                hasSeparatingVerticalHinge = false,
                isTabletop = false,
            )
        )
    }

    @Test
    fun `tabletop posture does not split conversation from input`() {
        assertEquals(
            ChatLayoutMode.SinglePane,
            layoutMode(widthDp = 1000f, heightDp = 800f, isTabletop = true),
        )
    }

    @Test
    fun `expanded modal is consistent with dual-pane chat layout`() {
        // Any window large enough for ListDetail should also use centered dialogs
        assertTrue(
            AdaptiveLayoutPolicy.useExpandedModal(
                widthDp = 1000f,
                heightDp = 800f,
                isTabletop = false,
            )
        )
        assertTrue(
            AdaptiveLayoutPolicy.useExpandedModal(
                widthDp = 700f,
                heightDp = 900f,
                isTabletop = false,
            )
        )
        // Tabletop: single pane chat, so bottom sheets
        assertFalse(
            AdaptiveLayoutPolicy.useExpandedModal(
                widthDp = 1000f,
                heightDp = 800f,
                isTabletop = true,
            )
        )
        // Phone portrait: too narrow
        assertFalse(
            AdaptiveLayoutPolicy.useExpandedModal(
                widthDp = 390f,
                heightDp = 844f,
                isTabletop = false,
            )
        )
        // Phone landscape: too short
        assertFalse(
            AdaptiveLayoutPolicy.useExpandedModal(
                widthDp = 844f,
                heightDp = 390f,
                isTabletop = false,
            )
        )
    }

    @Test
    fun `expanded modal boundary matches chat layout boundary`() {
        // Width boundary: 599 = false, 600 = true
        assertFalse(
            AdaptiveLayoutPolicy.useExpandedModal(
                widthDp = 599f,
                heightDp = 900f,
                isTabletop = false,
            )
        )
        assertTrue(
            AdaptiveLayoutPolicy.useExpandedModal(
                widthDp = 600f,
                heightDp = 900f,
                isTabletop = false,
            )
        )
        // Height boundary: 479 = false, 480 = true
        assertFalse(
            AdaptiveLayoutPolicy.useExpandedModal(
                widthDp = 600f,
                heightDp = 479f,
                isTabletop = false,
            )
        )
        assertTrue(
            AdaptiveLayoutPolicy.useExpandedModal(
                widthDp = 600f,
                heightDp = 480f,
                isTabletop = false,
            )
        )
    }

    @Test
    fun `conversation sidebar collapses only on flat wide windows`() {
        assertTrue(
            AdaptiveLayoutPolicy.canCollapseChatSidebar(
                widthDp = 1000f,
                heightDp = 800f,
                hasSeparatingVerticalHinge = false,
                isTabletop = false,
            )
        )
        assertFalse(
            AdaptiveLayoutPolicy.canCollapseChatSidebar(
                widthDp = 1000f,
                heightDp = 800f,
                hasSeparatingVerticalHinge = true,
                isTabletop = false,
            )
        )
        assertFalse(
            AdaptiveLayoutPolicy.canCollapseChatSidebar(
                widthDp = 390f,
                heightDp = 844f,
                hasSeparatingVerticalHinge = false,
                isTabletop = false,
            )
        )
    }

    @Test
    fun `medium width boundary is exactly 600dp`() {
        assertEquals(
            ChatLayoutMode.SinglePane,
            layoutMode(widthDp = 599f, heightDp = 900f),
        )
        assertEquals(
            ChatLayoutMode.ListDetail,
            layoutMode(widthDp = 600f, heightDp = 900f),
        )
    }

    @Test
    fun `minimum dual pane height boundary is exactly 480dp`() {
        assertEquals(
            ChatLayoutMode.SinglePane,
            layoutMode(widthDp = 600f, heightDp = 479f),
        )
        assertEquals(
            ChatLayoutMode.ListDetail,
            layoutMode(widthDp = 600f, heightDp = 480f),
        )
    }

    private fun layoutMode(
        widthDp: Float,
        heightDp: Float,
        isTabletop: Boolean = false,
    ): ChatLayoutMode = AdaptiveLayoutPolicy.chatLayoutMode(
        widthDp = widthDp,
        heightDp = heightDp,
        isTabletop = isTabletop,
    )
}
