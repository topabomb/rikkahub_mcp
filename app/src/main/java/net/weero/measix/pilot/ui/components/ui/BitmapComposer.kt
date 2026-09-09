package net.weero.measix.pilot.ui.components.ui

import android.app.Activity
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.drawToBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val MAX_HEIGHT = 10000.dp
private val MAX_WIDTH = 10000.dp
val LocalExportContext = staticCompositionLocalOf { false }

/** The calling export owns the temporary Compose tree until capture or cancellation. */
class BitmapComposer {
    suspend fun composableToBitmap(
        activity: Activity,
        width: Dp? = null,
        height: Dp? = null,
        screenDensity: Density,
        content: @Composable () -> Unit,
    ): Bitmap {
        var captured: Bitmap? = null
        try {
            return withContext(Dispatchers.Main.immediate) {
                val widthPixels = (screenDensity.density * (width ?: MAX_WIDTH).value).roundToInt()
                val heightPixels = (screenDensity.density * (height ?: MAX_HEIGHT).value).roundToInt()
                val container = FrameLayout(activity).apply {
                    layoutParams = ViewGroup.LayoutParams(widthPixels, heightPixels)
                    visibility = View.INVISIBLE
                }
                val compose = ComposeView(activity).apply {
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                    setContent { CompositionLocalProvider(LocalExportContext provides true, content = content) }
                }
                val decor = activity.window.decorView as ViewGroup
                var failure: Throwable? = null
                try {
                    container.addView(compose)
                    decor.addView(container)
                    fun layout() {
                        container.measure(
                            View.MeasureSpec.makeMeasureSpec(widthPixels, if (width == null) View.MeasureSpec.AT_MOST else View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(heightPixels, if (height == null) View.MeasureSpec.AT_MOST else View.MeasureSpec.EXACTLY),
                        )
                        container.layout(0, 0, container.measuredWidth, container.measuredHeight)
                    }
                    layout()
                    delay(100)
                    layout()
                    compose.drawToBitmap().also { captured = it }
                } catch (error: Throwable) {
                    failure = error
                    throw error
                } finally {
                    var cleanupFailure: Throwable? = null
                    for (cleanup in listOf({ compose.disposeComposition() }, { decor.removeView(container) })) {
                        try { cleanup() }
                        catch (error: Throwable) {
                            val prior = failure ?: cleanupFailure
                            if (prior != null) { if (error !== prior) prior.addSuppressed(error) }
                            else cleanupFailure = error
                        }
                    }
                    if (failure == null) cleanupFailure?.let { throw it }
                }
            }
        } catch (error: Throwable) {
            captured?.recycle()
            throw error
        }
    }
}
