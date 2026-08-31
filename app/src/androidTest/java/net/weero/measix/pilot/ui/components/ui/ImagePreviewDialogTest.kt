package net.weero.measix.pilot.ui.components.ui

import android.graphics.Bitmap
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import net.weero.measix.pilot.R

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class ImagePreviewDialogTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val images = mutableListOf<File>()
    private lateinit var viewerView: View
    private var visible by mutableStateOf(true)

    @After
    fun cleanImages() {
        images.forEach(File::delete)
    }

    @Test
    fun viewerFromThumbnailFillsWindow() {
        openViewer()
        assertFullWindow()
    }

    @Test
    fun viewerFromBoundedDialogFillsWindow() {
        openViewer(nested = true)
        assertFullWindow()
    }

    @Test
    fun horizontalSwipeChangesPageAndVerticalDragDismisses() {
        openViewer()
        compose.onNodeWithText("1 / 2").assertExists()
        compose.onNode(isDialog()).performTouchInput {
            swipe(Offset(width * 0.8f, height * 0.5f), Offset(width * 0.2f, height * 0.5f), 400)
        }
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("2 / 2"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(isDialog()).performTouchInput {
            swipe(Offset(width * 0.5f, height * 0.3f), Offset(width * 0.5f, height * 0.8f), 600)
        }
        compose.waitUntil(5_000) { !visible }
        assertFalse(visible)
    }

    @Test
    fun bottomActionsStayInsideSystemBarAndCutoutInsets() {
        openViewer()
        val safeInsets = compose.runOnIdle {
            requireNotNull(ViewCompat.getRootWindowInsets(viewerView)).getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
        }
        listOf(R.string.image_viewer_info_content_description, R.string.image_viewer_save_content_description)
            .forEach { label ->
                val bounds = compose.onNodeWithContentDescription(compose.activity.getString(label))
                    .fetchSemanticsNode().boundsInRoot
                assertTrue("action left", bounds.left >= safeInsets.left)
                assertTrue("action top", bounds.top >= safeInsets.top)
                assertTrue("action right", bounds.right <= viewerView.width - safeInsets.right)
                assertTrue("action bottom", bounds.bottom <= viewerView.height - safeInsets.bottom)
            }
    }

    @Test
    fun doubleTapZoomsAndVerticalPanDoesNotDismiss() {
        openViewer()
        compose.waitUntil(5_000) { redImageCoverage() > 0.1f }
        val initialCoverage = redImageCoverage()
        compose.onNode(isDialog()).performTouchInput { doubleClick(center) }
        compose.waitUntil(5_000) { redImageCoverage() > initialCoverage + 0.05f }
        compose.onNode(isDialog()).performTouchInput {
            swipe(Offset(width * 0.5f, height * 0.3f), Offset(width * 0.5f, height * 0.8f), 600)
        }
        compose.waitForIdle()
        assertTrue("A zoomed image must pan instead of closing the viewer", visible)
        compose.onNodeWithText("1 / 2").assertExists()
    }

    private fun redImageCoverage(): Float {
        val pixels = compose.onNode(isDialog()).captureToImage().toPixelMap()
        var redSamples = 0
        val samples = 20
        repeat(samples) { x ->
            repeat(samples) { y ->
                val pixel = pixels[(x * pixels.width / samples), (y * pixels.height / samples)]
                if (pixel.red > 0.8f && pixel.green < 0.2f && pixel.blue < 0.2f) redSamples++
            }
        }
        return redSamples.toFloat() / (samples * samples)
    }

    private fun openViewer(nested: Boolean = false) {
        val urls = listOf(android.graphics.Color.RED, android.graphics.Color.BLUE).map { color ->
            val file = File.createTempFile("viewer-test-", ".png", compose.activity.cacheDir)
            images += file
            val bitmap = Bitmap.createBitmap(900, 300, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            file.toURI().toString()
        }
        compose.setContent {
            MaterialTheme {
                val content: @androidx.compose.runtime.Composable () -> Unit = {
                    Box(Modifier.size(72.dp)) {
                        if (visible) {
                            ImagePreviewDialog(
                                images = urls,
                                onDismissRequest = { visible = false },
                                overlay = {
                                    val view = LocalView.current
                                    SideEffect {
                                        viewerView = view
                                    }
                                },
                            )
                        }
                    }
                }
                if (nested) Dialog(onDismissRequest = {}) { content() } else content()
            }
        }
        compose.waitForIdle()
    }

    private fun assertFullWindow() {
        compose.runOnIdle {
            val bounds = compose.activity.windowManager.currentWindowMetrics.bounds
            val location = IntArray(2)
            viewerView.getLocationOnScreen(location)
            assertEquals("left", bounds.left, location[0])
            assertEquals("top", bounds.top, location[1])
            assertEquals("width", bounds.width(), viewerView.width)
            assertEquals("height", bounds.height(), viewerView.height)
        }
    }
}
