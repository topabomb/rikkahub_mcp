package net.weero.measix.pilot.ui.hooks

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity

@Composable
fun ImeLazyListAutoScroller(
    lazyListState: LazyListState,
) {
    val ime = WindowInsets.ime
    val localDensity = LocalDensity.current
    LaunchedEffect(Unit) {
        var previousImeBottom = 0
        snapshotFlow {
            ime.getBottom(localDensity)
        }.collect { currentImeBottom ->
            imeScrollDelta(previousImeBottom, currentImeBottom)
                .takeIf { it > 0 }
                ?.let { delta -> lazyListState.scrollBy(delta.toFloat()) }
            // Keep the baseline in sync while the IME hides as well. Otherwise the
            // next open at the same height produces a zero delta and misses the
            // viewport change entirely.
            previousImeBottom = currentImeBottom
        }
    }
}

/**
 * The chat viewport only needs to follow IME expansion. IME dismissal is already
 * represented by imePadding changing the viewport and must not issue a reverse
 * list scroll; it still updates the baseline for the next opening.
 */
internal fun imeScrollDelta(previousImeBottom: Int, currentImeBottom: Int): Int =
    if (currentImeBottom > previousImeBottom && currentImeBottom > 0) {
        currentImeBottom - previousImeBottom
    } else {
        0
    }
