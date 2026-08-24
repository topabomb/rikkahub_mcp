package net.weero.measix.pilot.ui.components.ai

import android.app.Activity
import com.yalantis.ucrop.UCrop
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class CropResultDispositionTest {
    @Test
    fun `crop output extension matches PNG compression`() {
        assertEquals("crop_output_42.png", cropOutputFile(File("cache"), 42L).name)
    }

    @Test
    fun `success transfers output without showing an error or deleting it`() {
        assertEquals(
            CropResultDisposition(
                deliverOutput = true,
                showError = false,
                deleteOutput = false,
            ),
            cropResultDisposition(Activity.RESULT_OK),
        )
    }

    @Test
    fun `crop error shows a localized error and deletes the unowned output`() {
        assertEquals(
            CropResultDisposition(
                deliverOutput = false,
                showError = true,
                deleteOutput = true,
            ),
            cropResultDisposition(UCrop.RESULT_ERROR),
        )
    }

    @Test
    fun `cancellation silently deletes the unowned output`() {
        assertEquals(
            CropResultDisposition(
                deliverOutput = false,
                showError = false,
                deleteOutput = true,
            ),
            cropResultDisposition(Activity.RESULT_CANCELED),
        )
    }
}
