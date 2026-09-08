package net.weero.measix.pilot.service

import me.rerere.common.android.appTempFolder
import kotlinx.coroutines.NonCancellable
import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.weero.measix.pilot.utils.ImageExportResult
import net.weero.measix.pilot.utils.exportImageBytes
import net.weero.measix.pilot.utils.getActivity

internal const val IMAGE_SAVE_PERMISSION_REQUIRED = "permission_required"

/** UI-facing export port for copying media outside app storage; it owns no artifact lifecycle. */
class MediaExportService {
    suspend fun saveImage(context: Context, image: ImageSource): String = withContext(Dispatchers.IO) {
        val activity = requireNotNull(context.getActivity()) { "Activity not found" }
        val bytes = image.readBytes()
        val mime = requireNotNull(net.weero.measix.pilot.data.ai.attachments.ImageMime.sniff(bytes)) { "Invalid image format" }
        val extension = when (mime) {
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/heic", "image/heif" -> "heic"
            else -> "png"
        }
        val fileName = "MeasixPilot_${java.util.UUID.randomUUID()}.$extension"
        requireExportSuccess(context.exportImageBytes(activity, bytes, mime, fileName, image::requireAccess))
        fileName
    }

    suspend fun saveAndShareBitmap(context: Context, bitmap: Bitmap, fileName: String, verifyAccess: suspend () -> Unit) {
        val bytes = withContext(Dispatchers.IO) {
            verifyAccess()
            java.io.ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 90, output)) { "Unable to encode image export" }
                output.toByteArray()
            }
        }
        withContext(Dispatchers.IO) {
            val activity = requireNotNull(context.getActivity()) { "Activity not found" }
            requireExportSuccess(context.exportImageBytes(activity, bytes, "image/png", fileName, verifyAccess))
        }
        shareBytes(context, bytes, fileName, "image/png", verifyAccess)
    }

    suspend fun shareText(context: Context, text: String, fileName: String, verifyAccess: suspend () -> Unit) =
        shareBytes(context, text.toByteArray(Charsets.UTF_8), fileName, "text/markdown", verifyAccess)

    private suspend fun shareBytes(context: Context, bytes: ByteArray, fileName: String, mimeType: String, verifyAccess: suspend () -> Unit) {
        var candidate: java.io.File? = null
        var shared = false
        var failure: Throwable? = null
        try {
            verifyAccess()
            val file = withContext(Dispatchers.IO) {
                java.io.File(context.appTempFolder, "${java.util.UUID.randomUUID()}_$fileName").also {
                    candidate = it
                    it.outputStream().use { output -> output.write(bytes) }
                }
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            withContext(Dispatchers.Main.immediate) {
                verifyAccess()
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(android.content.Intent.createChooser(intent,
                    context.getString(net.weero.measix.pilot.R.string.chat_page_export_share_via)))
                shared = true
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            if (!shared) try {
                withContext(NonCancellable + Dispatchers.IO) {
                    candidate?.let { check(!it.exists() || it.delete()) { "Unable to remove unpublished export" } }
                }
            } catch (cleanup: Throwable) {
                failure?.let { if (cleanup !== it) it.addSuppressed(cleanup) } ?: throw cleanup
            }
        }
    }

    private fun requireExportSuccess(result: ImageExportResult) {
        when (result) {
            ImageExportResult.Success -> Unit
            ImageExportResult.PermissionRequired -> error(IMAGE_SAVE_PERMISSION_REQUIRED)
            ImageExportResult.Failed -> error("Failed to save image")
        }
    }

}
