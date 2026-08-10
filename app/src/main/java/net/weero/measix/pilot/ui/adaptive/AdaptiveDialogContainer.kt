package net.weero.measix.pilot.ui.adaptive

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * Full-window dialog container whose visual surface remains bounded while taps on the surrounding
 * scrim dismiss it. Compose's platform outside-click handling cannot see those taps when a custom
 * dialog uses a full-size root, so the hit test is performed without consuming child gestures.
 *
 * The inner content Box uses [clipToBounds] to prevent child content (e.g. floating toolbars)
 * from overflowing the dialog's visual bounds.
 */
@Composable
internal fun AdaptiveDialogContainer(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    var contentBounds by remember { mutableStateOf<Rect?>(null) }
    val currentOnDismissRequest by rememberUpdatedState(onDismissRequest)

    Box(
        modifier = Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(
                    requireUnconsumed = false,
                    pass = PointerEventPass.Initial,
                )
                val up = waitForUpOrCancellation(pass = PointerEventPass.Initial)
                val bounds = contentBounds
                if (
                    up != null &&
                    bounds != null &&
                    !bounds.contains(down.position) &&
                    !bounds.contains(up.position)
                ) {
                    currentOnDismissRequest()
                }
            }
        }.then(modifier),
        contentAlignment = contentAlignment,
    ) {
        Box(
            modifier = Modifier
                .clipToBounds()
                .onGloballyPositioned {
                    contentBounds = it.boundsInParent()
                },
            content = content,
        )
    }
}
