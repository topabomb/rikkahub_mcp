package net.weero.measix.pilot.ui.components.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ImagePreviewDialogProgressTest {
    private val height = 1000f

    @Test
    fun `progress is zero without drag or without container`() {
        assertEquals(0f, dragProgress(0f, height))
        assertEquals(0f, dragProgress(500f, 0f))
        assertEquals(0f, dragProgress(500f, -1f))
    }

    @Test
    fun `progress is direction agnostic and not upper clamped`() {
        assertEquals(0.25f, dragProgress(250f, height))
        assertEquals(0.25f, dragProgress(-250f, height))
        assertEquals(1.2f, dragProgress(height * 1.2f, height))
    }

    @Test
    fun `scrim alpha stays within bounds and never clamps above one`() {
        assertEquals(1f, scrimAlpha(0f, height))
        assertEquals(0.25f, scrimAlpha(height, height))
        // 退出动画终点(1.2 倍高)仍有余量下限 0.1(浮点容差)
        assertEquals(0.1f, scrimAlpha(height * 1.2f, height), 1e-6f)
    }

    @Test
    fun `overlay alpha hides at half progress and clamps to zero beyond`() {
        assertEquals(1f, overlayAlpha(0f, height))
        assertEquals(0f, overlayAlpha(height * 0.5f, height))
        assertEquals(0f, overlayAlpha(height, height))
    }

    @Test
    fun `save error maps permission separately from generic failures`() {
        assertEquals(
            "grant",
            imageSaveErrorMessage(
                message = net.weero.measix.pilot.data.files.IMAGE_SAVE_PERMISSION_REQUIRED,
                permissionText = "grant",
                failedFormat = "failed: %s",
            ),
        )
        assertEquals(
            "failed: HTTP 404",
            imageSaveErrorMessage(
                message = "HTTP 404",
                permissionText = "grant",
                failedFormat = "failed: %s",
            ),
        )
    }
}
