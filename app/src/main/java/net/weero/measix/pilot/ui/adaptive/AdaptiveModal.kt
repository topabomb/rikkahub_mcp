package net.weero.measix.pilot.ui.adaptive

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Presents short-lived chat tools as a bottom sheet on ordinary phone windows and as a bounded
 * dialog on windows large enough for dual-pane chat. Tabletop posture also uses a bounded dialog
 * in the top display area so modal content cannot expand through the horizontal hinge.
 *
 * A vertical hinge keeps the dialog on the detail-side display area, constrained from the hinge's
 * actual right edge rather than assuming that every fold is exactly centered.
 */
@Composable
fun AdaptiveModal(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    dialogMaxWidth: Dp = AdaptiveLayoutDefaults.SheetMaxWidth,
    dialogMaxHeight: Dp = AdaptiveLayoutDefaults.SheetMaxHeight,
    sheetState: SheetState? = null,
    sheetGesturesEnabled: Boolean = true,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    content: @Composable ColumnScope.() -> Unit,
) {
    val adaptiveInfo = LocalAdaptiveLayoutInfo.current

    if (adaptiveInfo.useExpandedModal) {
        val safeDrawingPadding = WindowInsets.safeDrawing.asPaddingValues()
        val layoutDirection = LocalLayoutDirection.current
        val verticalHinge = adaptiveInfo.primaryVerticalHingeBounds
        val horizontalHinge = adaptiveInfo.primaryHorizontalHingeBounds
        val effectiveMaxWidth = if (verticalHinge != null) {
            val detailPaneAvailableWidth = (
                adaptiveInfo.windowSize.width -
                    verticalHinge.rightDp.dp -
                    safeDrawingPadding.calculateEndPadding(layoutDirection) -
                    AdaptiveLayoutDefaults.DialogPadding * 2
                ).coerceAtLeast(1.dp)
            minOf(dialogMaxWidth, detailPaneAvailableWidth)
        } else {
            dialogMaxWidth
        }
        val effectiveMaxHeight = if (adaptiveInfo.isTabletop && horizontalHinge != null) {
            val topPaneAvailableHeight = (
                horizontalHinge.topDp.dp -
                    safeDrawingPadding.calculateTopPadding() -
                    AdaptiveLayoutDefaults.DialogPadding * 2
                ).coerceAtLeast(1.dp)
            minOf(dialogMaxHeight, topPaneAvailableHeight)
        } else {
            dialogMaxHeight
        }

        // Dialog path composes no sheet. A caller-supplied sheetState (if any) is intentionally
        // left untouched here: without a sheet to drive it, its currentValue stays at whatever it
        // was, and hide()/show() complete immediately since no sheet is mounted. Dismissal is
        // handled by Dialog's outside-click (scrim) path. Callers that need to know "is it shown"
        // must track their own dialog state (e.g. useEditState) rather than reading sheetState.
        Dialog(
            onDismissRequest = onDismissRequest,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            AdaptiveDialogContainer(
                onDismissRequest = onDismissRequest,
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(AdaptiveLayoutDefaults.DialogPadding),
                contentAlignment = when {
                    adaptiveInfo.isTabletop && horizontalHinge != null && verticalHinge != null ->
                        Alignment.TopEnd
                    adaptiveInfo.isTabletop && horizontalHinge != null -> Alignment.TopCenter
                    verticalHinge != null -> Alignment.CenterEnd
                    else -> Alignment.Center
                },
            ) {
                Surface(
                    modifier = Modifier
                        .widthIn(max = effectiveMaxWidth)
                        .fillMaxWidth()
                        .heightIn(max = effectiveMaxHeight),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 6.dp,
                    shadowElevation = 12.dp,
                ) {
                    Column(
                        modifier = modifier.fillMaxWidth(),
                        content = content,
                    )
                }
            }
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            sheetState = sheetState ?: rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            ),
            sheetGesturesEnabled = sheetGesturesEnabled,
            dragHandle = dragHandle,
        ) {
            Column(
                modifier = modifier.fillMaxWidth(),
                content = content,
            )
        }
    }
}
