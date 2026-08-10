package net.weero.measix.pilot.ui.adaptive

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Full-window dialog container whose visual surface remains bounded while taps on the surrounding
 * scrim dismiss it.
 *
 * Implemented with an explicit full-window scrim layer placed underneath the content instead of a
 * manual hit test against measured content bounds:
 *
 * - The scrim layer fills the whole window and dismisses on any tap that reaches it.
 * - The content layer sits on top, so taps inside the dialog surface (including child controls
 *   near the bottom edge) are consumed by the content layer and never reach the scrim.
 *
 * Relying on measured content bounds for the outside-click test was fragile: content that uses
 * `fillMaxHeight(...)` / `weight(...)` could render its bottom action row outside the measured
 * bounds (depending on window size, insets and density), causing taps on real buttons (Save /
 * Confirm / a filter text field) to be treated as scrim taps and dismiss the dialog instead of
 * acting. The scrim layer approach removes that coupling entirely.
 *
 * The content layer still uses [clipToBounds] to prevent child content (e.g. floating toolbars)
 * from overflowing the dialog's visual bounds.
 */
@Composable
internal fun AdaptiveDialogContainer(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val currentOnDismissRequest by rememberUpdatedState(onDismissRequest)
    val scrimInteractionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier,
        contentAlignment = contentAlignment,
    ) {
        // Scrim layer: fills the window below the content; any tap that reaches it dismisses.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = scrimInteractionSource,
                    indication = null,
                ) {
                    currentOnDismissRequest()
                },
        )
        // Content layer: sized to the dialog surface. Taps inside the surface must not fall
        // through to the scrim, so consume them here. Child controls (buttons, text fields,
        // segmented buttons, scrollables) consume their own gestures first and are unaffected;
        // this only catches taps on the dialog's blank padding/background.
        Box(
            modifier = Modifier
                .clipToBounds()
                .pointerInput(Unit) {
                    detectTapGestures { }
                },
            content = content,
        )
    }
}
