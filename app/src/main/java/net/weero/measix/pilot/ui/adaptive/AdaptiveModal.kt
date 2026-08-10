package net.weero.measix.pilot.ui.adaptive

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Presents short-lived chat tools as a bottom sheet on phones and as a bounded centered dialog
 * on windows large enough for dual-pane chat (>= 600dp wide, >= 480dp tall, non-tabletop).
 * This keeps popup behavior consistent with the chat layout: dual-pane chat = centered
 * dialogs; single-pane chat = bottom sheets.
 *
 * A vertical hinge keeps the dialog on the detail-side display area, constrained to the
 * right half of the window so it never straddles the physical gap.
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
        val hingePaneMaxWidth = (adaptiveInfo.windowSize.width / 2 - AdaptiveLayoutDefaults.DialogPadding)
            .coerceAtLeast(AdaptiveLayoutDefaults.HingePaneMinWidth)
        val effectiveMaxWidth = if (adaptiveInfo.hasSeparatingVerticalHinge) {
            minOf(dialogMaxWidth, hingePaneMaxWidth)
        } else {
            dialogMaxWidth
        }

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
                contentAlignment = if (adaptiveInfo.hasSeparatingVerticalHinge) {
                    Alignment.CenterEnd
                } else {
                    Alignment.Center
                },
            ) {
                Surface(
                    modifier = Modifier
                        .widthIn(max = effectiveMaxWidth)
                        .fillMaxWidth()
                        .heightIn(max = dialogMaxHeight),
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
