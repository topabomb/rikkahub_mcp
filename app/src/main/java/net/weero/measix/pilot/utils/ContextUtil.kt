package net.weero.measix.pilot.utils

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap

import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

private const val TAG = "ContextUtil"

/**
 * Read clipboard data as text
 */
fun Context.readClipboardText(): String {
    val clipboardManager =
        getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    val clip = clipboardManager.primaryClip ?: return ""
    val item = clip.getItemAt(0) ?: return ""
    return item.text.toString()
}

/**
 * 发起添加群流程
 *
 * @param key 由官网生成的key
 * @return 返回true表示呼起手Q成功，返回false表示呼起失败
 */
fun Context.joinQQGroup(key: String?): Boolean {
    val intent = Intent(Intent.ACTION_VIEW)
    intent.setData(("mqqopensdkapi://bizAgent/qm/qr?url=http%3A%2F%2Fqm.qq.com%2Fcgi-bin%2Fqm%2Fqr%3Ffrom%3Dapp%26p%3Dandroid%26jump_from%3Dwebapi%26k%3D$key").toUri())
    // 此Flag可根据具体产品需要自定义，如设置，则在加群界面按返回，返回手Q主界面，不设置，按返回会返回到呼起产品界面    //intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(intent)
        return true
    } catch (e: java.lang.Exception) {
        // 未安装手Q或安装的版本不支持
        return false
    }
}

/**
 * Write text into clipboard
 */
fun Context.writeClipboardText(text: String) {
    val clipboardManager =
        getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    runCatching {
        clipboardManager.setPrimaryClip(android.content.ClipData.newPlainText("text", text))
        Log.i(TAG, "writeClipboardText: $text")
    }.onFailure {
        Log.e(TAG, "writeClipboardText: $text", it)
        Toast.makeText(this, "Failed to write text into clipboard", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Open a url
 */
fun Context.openUrl(url: String) {
    Log.i(TAG, "openUrl: $url")
    runCatching {
        val intent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
        intent.launchUrl(this, url.toUri())
    }.onFailure {
        it.printStackTrace()
        Toast.makeText(this, "Failed to open URL: $url", Toast.LENGTH_SHORT).show()
    }
}

fun Context.getActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) {
            return context
        }
        context = context.baseContext
    }
    return null
}

fun Context.getComponentActivity(): ComponentActivity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is ComponentActivity) {
            return context
        }
        context = context.baseContext
    }
    return null
}

enum class ImageExportResult {
    Success,
    PermissionRequired,
    Failed,
}

fun Context.exportImage(
    activity: Activity,
    bitmap: Bitmap,
    fileName: String = "MeasixPilot_${System.currentTimeMillis()}.png"
): ImageExportResult {
    requestLegacyWritePermission(activity)?.let { return it }

    var outputStream: OutputStream? = null
    var insertedUri: Uri? = null
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: return ImageExportResult.Failed
            insertedUri = uri
            val stream = contentResolver.openOutputStream(uri)
                ?: return discardInsertedImage(uri)
            outputStream = stream
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                return discardInsertedImage(uri)
            }
        } else {
            val imagesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val image = File(imagesDir, fileName)
            outputStream = FileOutputStream(image)
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)) {
                return ImageExportResult.Failed
            }

            @Suppress("DEPRECATION")
            val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            mediaScanIntent.data = Uri.fromFile(image)
            sendBroadcast(mediaScanIntent)
        }
        Log.i(TAG, "Image saved successfully: $fileName")
        ImageExportResult.Success
    } catch (e: Exception) {
        Log.e(TAG, "Failed to save image", e)
        insertedUri?.let { runCatching { contentResolver.delete(it, null, null) } }
        ImageExportResult.Failed
    } finally {
        outputStream?.close()
    }
}

suspend fun Context.exportImageBytes(
    activity: Activity,
    bytes: ByteArray,
    mimeType: String,
    fileName: String,
    beforePublish: suspend () -> Unit,
): ImageExportResult {
    requestLegacyWritePermission(activity)?.let { return it }
    var insertedUri: Uri? = null
    var legacyFile: File? = null
    var published = false
    var failure: Throwable? = null
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Unable to create image export")
            insertedUri = uri
            val output = contentResolver.openOutputStream(uri) ?: error("Unable to create image export")
            output.use { it.write(bytes) }
            beforePublish()
            check(contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null) == 1) { "Image publication failed" }
        } else {
            beforePublish()
            val imagesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val image = File(imagesDir, fileName)
            legacyFile = image
            image.outputStream().use { it.write(bytes) }
            beforePublish()
            @Suppress("DEPRECATION")
            val scan = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply { data = Uri.fromFile(image) }
            sendBroadcast(scan)
        }
        published = true
        ImageExportResult.Success
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        failure = cancelled
        throw cancelled
    } catch (error: Exception) {
        failure = error
        Log.e(TAG, "Failed to save image", error)
        ImageExportResult.Failed
    } finally {
        if (!published) {
            try {
                insertedUri?.let { check(contentResolver.delete(it, null, null) == 1) { "Unable to remove unpublished image" } }
                legacyFile?.let { check(!it.exists() || it.delete()) { "Unable to remove unpublished image" } }
            } catch (cleanup: Throwable) {
                val original = failure
                if (original == null) throw cleanup
                original.addSuppressed(cleanup)
                if (original !is kotlinx.coroutines.CancellationException) throw original
            }
        }
    }
}

private fun Context.requestLegacyWritePermission(activity: Activity): ImageExportResult? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return null
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        == PackageManager.PERMISSION_GRANTED
    ) {
        return null
    }
    ActivityCompat.requestPermissions(
        activity,
        arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
        1
    )
    return ImageExportResult.PermissionRequired
}

private fun Context.discardInsertedImage(uri: Uri): ImageExportResult {
    runCatching { contentResolver.delete(uri, null, null) }
    return ImageExportResult.Failed
}
