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
        when (result.resultCode) {
            android.app.Activity.RESULT_OK -> {
                // 输出文件所有权移交消费方：消费方在协程中复制完成后自行删除。
                // 此处绝不同步删除——消费是异步的（suspend 文件登记），
                // 同步删除会与消费协程竞态，导致消费方读到已删除的文件。
                cropOutputUri?.let { croppedUri ->
                    onCroppedImageReady(croppedUri)
                }
            }

            UCrop.RESULT_ERROR -> {
                val error = result.data?.let { UCrop.getError(it) }
                Logging.log(
                    "CropLauncher",
                    "crop failed: ${error?.message} | ${error?.stackTraceToString()}"
                )
                toaster.show(
                    cropFailedMessage,
                    type = ToastType.Error
                )
            }

            else -> Unit
        }
        if (result.resultCode != android.app.Activity.RESULT_OK) {
            // 失败/取消：输出文件无消费方，此处清理（uCrop 失败时通常未写入，删除幂等）
            cropOutputUri?.toFile()?.delete()
        }
        cropOutputUri = null
        onCleanup?.invoke()
    }

    val launchCrop: (Uri) -> Unit = { sourceUri ->
        val outputFile = File(context.appTempFolder, "crop_output_${System.currentTimeMillis()}.jpg")
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
