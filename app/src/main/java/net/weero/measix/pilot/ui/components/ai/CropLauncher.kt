package net.weero.measix.pilot.ui.components.ai

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toFile
import com.dokar.sonner.ToastType
import com.yalantis.ucrop.UCrop
import com.yalantis.ucrop.UCropActivity
import me.rerere.common.android.Logging
import me.rerere.common.android.appTempFolder
import net.weero.measix.pilot.R
import net.weero.measix.pilot.ui.context.LocalToaster
import java.io.File

internal data class CropResultDisposition(
    val deliverOutput: Boolean,
    val showError: Boolean,
    val deleteOutput: Boolean,
)

internal fun cropResultDisposition(resultCode: Int): CropResultDisposition = when (resultCode) {
    android.app.Activity.RESULT_OK -> CropResultDisposition(
        deliverOutput = true,
        showError = false,
        deleteOutput = false,
    )

    UCrop.RESULT_ERROR -> CropResultDisposition(
        deliverOutput = false,
        showError = true,
        deleteOutput = true,
    )

    else -> CropResultDisposition(
        deliverOutput = false,
        showError = false,
        deleteOutput = true,
    )
}

internal fun cropOutputFile(folder: File, timestampMillis: Long): File =
    File(folder, "crop_output_$timestampMillis.png")

@Composable
internal fun useCropLauncher(
    onCroppedImageReady: (Uri) -> Unit,
    onCleanup: (() -> Unit)? = null,
    aspectRatio: Pair<Float, Float>? = null,
    freeStyleCropEnabled: Boolean = true
): Pair<ActivityResultLauncher<Intent>, (Uri) -> Unit> {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val cropFailedMessage = stringResource(R.string.image_crop_failed)
    var cropOutputUri by remember { mutableStateOf<Uri?>(null) }

    val cropActivityLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val disposition = cropResultDisposition(result.resultCode)
        if (disposition.deliverOutput) {
            // 成功后输出文件所有权移交消费方；消费方完成 suspend 文件登记后负责删除，
            // 避免回调返回时同步删除与异步读取竞态。
            cropOutputUri?.let(onCroppedImageReady)
        }
        if (disposition.showError) {
            val error = result.data?.let { UCrop.getError(it) }
            Logging.log(
                "CropLauncher",
                "crop failed: ${error?.message} | ${error?.stackTraceToString()}"
            )
            toaster.show(cropFailedMessage, type = ToastType.Error)
        }
        if (disposition.deleteOutput) {
            cropOutputUri?.let { uri -> uri.toFile().delete() }
        }
        cropOutputUri = null
        onCleanup?.invoke()
    }

    val launchCrop: (Uri) -> Unit = { sourceUri ->
        val outputFile = cropOutputFile(context.appTempFolder, System.currentTimeMillis())
        cropOutputUri = Uri.fromFile(outputFile)

        var crop = UCrop.of(sourceUri, cropOutputUri!!).withOptions(UCrop.Options().apply {
            setFreeStyleCropEnabled(freeStyleCropEnabled)
            setAllowedGestures(
                UCropActivity.SCALE, UCropActivity.ROTATE, UCropActivity.NONE
            )
            setCompressionFormat(Bitmap.CompressFormat.PNG)
        }).withMaxResultSize(4096, 4096)
        aspectRatio?.let { (x, y) ->
            crop = crop.withAspectRatio(x, y)
        }
        val cropIntent = crop.getIntent(context)

        cropActivityLauncher.launch(cropIntent)
    }

    return Pair(cropActivityLauncher, launchCrop)
}
