package net.weero.measix.pilot.ui.adaptive

import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.separatingVerticalHingeBounds
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * Window width tiers used by the app's own content policies.
 * Thresholds match Material 3 canonical window size classes.
 */
enum class AdaptiveWidthClass {
    Compact,
    Medium,
    Expanded,
    Large,
    ExtraLarge,
}

/**
 * Chat layout mode: single-pane (modal drawer) or list-detail (permanent sidebar + chat).
 */
enum class ChatLayoutMode {
    SinglePane,
    ListDetail,
}

/**
 * App-level adaptive information. Calculated once at the root and shared by every screen so
 * pages cannot drift into different device-orientation heuristics.
 */
@Immutable
data class AdaptiveLayoutInfo(
    val windowSize: DpSize,
    val windowAdaptiveInfo: WindowAdaptiveInfo,
) {
    val widthClass: AdaptiveWidthClass = AdaptiveLayoutPolicy.widthClass(windowSize.width.value)

    /**
     * True when the window reports a vertical separating hinge (a physical fold gap).
     *
     * On devices/emulators without folding support, [WindowAdaptiveInfo.windowPosture] carries no
     * hinges and this falls back to `false` — i.e. a regular flat window. Emulators never report a
     * hinge, so hinge-driven behavior (e.g. keeping the dialog on the detail-side display area)
     * cannot be exercised there; that is a platform capability gap, not a policy bug.
     */
    val hasSeparatingVerticalHinge: Boolean =
        windowAdaptiveInfo.windowPosture.separatingVerticalHingeBounds.isNotEmpty()

    /**
     * True when the device is half-opened into a book/tabletop posture with a horizontal hinge.
     *
     * Posture is reported by the window manager: only a real half-opened horizontal hinge sets
     * this. Flat windows, non-folding devices and emulators report `false`, so all size-only
     * policy decisions (layout mode, expanded modal, sidebar collapse) keep working unchanged.
     */
    val isTabletop: Boolean = windowAdaptiveInfo.windowPosture.isTabletop
    val chatLayoutMode: ChatLayoutMode = AdaptiveLayoutPolicy.chatLayoutMode(
        widthDp = windowSize.width.value,
        heightDp = windowSize.height.value,
        isTabletop = isTabletop,
    )
    val useExpandedModal: Boolean = AdaptiveLayoutPolicy.useExpandedModal(
        widthDp = windowSize.width.value,
        heightDp = windowSize.height.value,
        isTabletop = isTabletop,
    )
    val canCollapseChatSidebar: Boolean = AdaptiveLayoutPolicy.canCollapseChatSidebar(
        widthDp = windowSize.width.value,
        heightDp = windowSize.height.value,
        hasSeparatingVerticalHinge = hasSeparatingVerticalHinge,
        isTabletop = isTabletop,
    )
    val useCompactChatInput: Boolean =
        AdaptiveLayoutPolicy.useCompactChatInput(windowSize.height.value)
    val listPaneWidth: Dp = when (widthClass) {
        AdaptiveWidthClass.Large, AdaptiveWidthClass.ExtraLarge ->
            AdaptiveLayoutDefaults.WideListPaneWidth
        else -> AdaptiveLayoutDefaults.ListPaneWidth
    }
}

/**
 * Pure-function adaptive layout policies. Every threshold and decision is here so it can be
 * unit-tested without a Compose runtime.
 *
 * Threshold hierarchy (all in dp):
 * - [MediumWidthBreakpoint] (600): minimum width for dual-pane chat, expanded modal, and the
 *   boundary between Compact and Medium width classes.
 * - [MinimumDualPaneHeight] (480): minimum height for dual-pane chat, expanded modal, and the
 *   boundary for compact chat input. All three share this threshold for consistency.
 * - [ExpandedWidthBreakpoint] (840), [LargeWidthBreakpoint] (1200), [ExtraLargeWidthBreakpoint]
 *   (1600): only used for [widthClass] classification.
 */
object AdaptiveLayoutPolicy {
    const val MediumWidthBreakpoint = 600f
    const val ExpandedWidthBreakpoint = 840f
    const val LargeWidthBreakpoint = 1200f
    const val ExtraLargeWidthBreakpoint = 1600f
    const val MinimumDualPaneHeight = 480f

    fun widthClass(widthDp: Float): AdaptiveWidthClass = when {
        widthDp < MediumWidthBreakpoint -> AdaptiveWidthClass.Compact
        widthDp < ExpandedWidthBreakpoint -> AdaptiveWidthClass.Medium
        widthDp < LargeWidthBreakpoint -> AdaptiveWidthClass.Expanded
        widthDp < ExtraLargeWidthBreakpoint -> AdaptiveWidthClass.Large
        else -> AdaptiveWidthClass.ExtraLarge
    }

    /**
     * Chat uses list-detail as soon as the window is at least medium width (600dp) and tall
     * enough for two panes (480dp). Domestic foldables unfolded (~916-962dp) fit this tier,
     * so their unfolded main screen shows list + detail instead of a phone-like single pane.
     * Phone portrait and short landscape windows are protected by the width and height guards.
     * Tabletop posture deliberately remains single-pane.
     */
    fun chatLayoutMode(
        widthDp: Float,
        heightDp: Float,
        isTabletop: Boolean,
    ): ChatLayoutMode {
        if (isTabletop || heightDp < MinimumDualPaneHeight) return ChatLayoutMode.SinglePane
        return if (widthDp >= MediumWidthBreakpoint) ChatLayoutMode.ListDetail
        else ChatLayoutMode.SinglePane
    }

    /**
     * Short-lived modal tools use a centered panel instead of a bottom sheet whenever the
     * window is large enough for dual-pane chat (>= 600dp wide, >= 480dp tall, non-tabletop).
     * This keeps popup behavior consistent with the chat layout: dual-pane chat = centered
     * dialogs; single-pane chat = bottom sheets. Phone portrait and short landscape windows
     * stay with ModalBottomSheet.
     */
    fun useExpandedModal(
        widthDp: Float,
        heightDp: Float,
        isTabletop: Boolean,
    ): Boolean =
        widthDp >= MediumWidthBreakpoint &&
            heightDp >= MinimumDualPaneHeight &&
            !isTabletop

    /**
     * Keeps every input action available without letting the composer dominate short windows.
     */
    fun useCompactChatInput(heightDp: Float): Boolean = heightDp < MinimumDualPaneHeight

    /**
     * A flat wide window may hide its conversation list. A separating hinge keeps both panes
     * visible so the chat never expands through an unusable physical gap.
     */
    fun canCollapseChatSidebar(
        widthDp: Float,
        heightDp: Float,
        hasSeparatingVerticalHinge: Boolean,
        isTabletop: Boolean,
    ): Boolean =
        chatLayoutMode(
            widthDp = widthDp,
            heightDp = heightDp,
            isTabletop = isTabletop,
        ) == ChatLayoutMode.ListDetail && !hasSeparatingVerticalHinge
}

val LocalAdaptiveLayoutInfo = staticCompositionLocalOf<AdaptiveLayoutInfo> {
    error("AdaptiveLayoutInfo has not been provided")
}

@Composable
fun rememberAdaptiveLayoutInfo(): AdaptiveLayoutInfo {
    val windowSize = LocalWindowInfo.current.containerDpSize
    val windowAdaptiveInfo = currentWindowAdaptiveInfoV2()
    return remember(windowSize, windowAdaptiveInfo) {
        AdaptiveLayoutInfo(
            windowSize = windowSize,
            windowAdaptiveInfo = windowAdaptiveInfo,
        )
    }
}

object AdaptiveLayoutDefaults {
    /** Max width for readable content (chat messages, input). */
    val ReadableContentMaxWidth = 840.dp

    /** Default max width for centered dialog sheets. */
    val SheetMaxWidth = 640.dp

    /** Default max height for centered dialog sheets. */
    val SheetMaxHeight = 760.dp

    /** Conversation list pane width on Medium/Expanded windows (matches ModalDrawerSheet width). */
    val ListPaneWidth = 300.dp

    /** Conversation list pane width on Large/ExtraLarge windows. */
    val WideListPaneWidth = 360.dp

    /** Minimum pane width when a hinge splits the dialog into two halves. */
    val HingePaneMinWidth = 320.dp

    /** Padding around the centered dialog content. */
    val DialogPadding = 24.dp
}
