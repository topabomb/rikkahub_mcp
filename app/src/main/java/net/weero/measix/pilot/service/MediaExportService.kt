package net.weero.measix.pilot.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.net.toFile
import androidx.core.net.toUri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.weero.measix.pilot.data.ai.attachments.RemoteMediaFetchResult
import net.weero.measix.pilot.data.ai.attachments.SafeRemoteMediaFetcher
import net.weero.measix.pilot.data.imggen.GeneratedMediaStore
import net.weero.measix.pilot.utils.ImageExportResult
import net.weero.measix.pilot.utils.exportImage
import net.weero.measix.pilot.utils.exportImageFile
import net.weero.measix.pilot.utils.getActivity
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal const val IMAGE_SAVE_PERMISSION_REQUIRED = "permission_required"

/** UI-facing export port for copying media outside app storage; it owns no artifact lifecycle. */
class MediaExportService(
    private val remoteMediaFetcher: SafeRemoteMediaFetcher,
) {
    @OptIn(ExperimentalEncodingApi::class)
    suspend fun saveImage(context: Context, image: String): String = withContext(Dispatchers.IO) {
        val activity = requireNotNull(context.getActivity()) { "Activity not found" }
        val fileName = "MeasixPilot_${System.currentTimeMillis()}.png"
        when {
            image.startsWith("data:image") -> {
                val payload = image.substringAfter("base64,", missingDelimiterValue = "")
                require(payload.isNotEmpty() && payload.length <= MAX_BASE64_IMAGE_CHARS) {
                    "Image payload exceeds the size limit"
                }
                val bytes = Base64.decode(payload.toByteArray())
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: error("Failed to decode image")
                try {
                    requireExportSuccess(context.exportImage(activity, bitmap, fileName))
                } finally {
                    bitmap.recycle()
                }
            }

            image.startsWith("file:") ->
                requireExportSuccess(context.exportImageFile(activity, image.toUri().toFile(), fileName))

            image.startsWith("/") ->
                requireExportSuccess(context.exportImageFile(activity, File(image), fileName))

            image.startsWith("http") -> exportRemoteImage(context, activity, image, fileName)

            else -> error("Invalid image format")
        }
        fileName
    }

    suspend fun saveBitmap(context: Context, bitmap: Bitmap, fileName: String) = withContext(Dispatchers.IO) {
        val activity = requireNotNull(context.getActivity()) { "Activity not found" }
        requireExportSuccess(context.exportImage(activity, bitmap, fileName))
    }

    private fun exportRemoteImage(
        context: Context,
        activity: android.app.Activity,
        image: String,
        fileName: String,
    ) {
        val fetched = when (val result = remoteMediaFetcher.fetch(image)) {
            is RemoteMediaFetchResult.Success -> result
            is RemoteMediaFetchResult.Failure -> error("Remote image could not be fetched: ${result.reason}")
        }
        val bitmap = BitmapFactory.decodeByteArray(fetched.bytes, 0, fetched.bytes.size)
            ?: error("Failed to decode image")
        try {
            requireExportSuccess(context.exportImage(activity, bitmap, fileName))
        } finally {
            bitmap.recycle()
        }
    }

    private fun requireExportSuccess(result: ImageExportResult) {
        when (result) {
            ImageExportResult.Success -> Unit
            ImageExportResult.PermissionRequired -> error(IMAGE_SAVE_PERMISSION_REQUIRED)
            ImageExportResult.Failed -> error("Failed to save image")
        }
    }

    private companion object {
        const val MAX_BASE64_IMAGE_CHARS =
            (GeneratedMediaStore.MAX_IMAGE_BYTES * 4 / 3) + 16
    }
}
