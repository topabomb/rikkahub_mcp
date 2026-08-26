package net.weero.measix.pilot.service

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.weero.measix.pilot.data.datastore.ChatFontFamily
import net.weero.measix.pilot.data.datastore.DisplaySetting
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.files.BackupSnapshotBarrier
import net.weero.measix.pilot.data.files.FileFolders
import net.weero.measix.pilot.data.files.FileUtils

/** Owns the custom chat-font file domain and its Settings reference. */
class CustomChatFontService(
    context: Context,
    private val settingsStore: SettingsStore,
) {
    private val appContext = context.applicationContext
    private val filesDir = appContext.filesDir
    private val fontDir = File(filesDir, FileFolders.FONTS)

    /** Recovers interrupted imports and removes file roots that no longer have a Settings owner. */
    suspend fun reconcile() = BackupSnapshotBarrier.withLock {
        withContext(NonCancellable) {
            val committed = settingsStore.updateLocal { current ->
                val display = current.displaySetting
                val retained = resolveManagedFont(display.chatCustomFontPath)?.takeIf(File::isFile)
                val hasBrokenReference = display.chatCustomFontPath.isNotBlank() && retained == null
                if (hasBrokenReference ||
                    display.chatFontFamily == ChatFontFamily.CUSTOM && retained == null
                ) {
                    current.copy(
                        displaySetting = display.copy(
                            chatFontFamily = ChatFontFamily.DEFAULT,
                            chatCustomFontPath = "",
                            chatCustomFontName = "",
                        )
                    )
                } else {
                    current
                }
            }
            withContext(Dispatchers.IO) {
                cleanupObsoleteFonts(committed.displaySetting.chatCustomFontPath)
            }
        }
    }

    suspend fun import(uri: Uri): DisplaySetting {
        var importFiles: FontImportFiles? = null
        var settingsCommitted = false
        try {
            return BackupSnapshotBarrier.withLock {
                val displayName = withContext(Dispatchers.IO) { resolveDisplayName(uri) }
                val files = newImportFiles(displayName).also { importFiles = it }
                withContext(Dispatchers.IO) {
                    stageImport(uri, files)
                }

                withContext(NonCancellable) {
                    val committed = settingsStore.updateLocal { current ->
                        current.copy(
                            displaySetting = current.displaySetting.copy(
                                chatFontFamily = ChatFontFamily.CUSTOM,
                                chatCustomFontPath = files.relativeTargetPath,
                                chatCustomFontName = files.displayName,
                            )
                        )
                    }
                    settingsCommitted = true
                    withContext(Dispatchers.IO) {
                        cleanupObsoleteFonts(retainedRelativePath = committed.displaySetting.chatCustomFontPath)
                    }
                    committed.displaySetting
                }
            }
        } catch (failure: Throwable) {
            if (!settingsCommitted) {
                withContext(NonCancellable + Dispatchers.IO) {
                    importFiles?.let(::discardImportFiles)
                }
            }
            throw failure
        }
    }

    /**
     * Clears only the reference the caller observed. A queued removal cannot erase a newer import.
     */
    suspend fun remove(expectedRelativePath: String): DisplaySetting = BackupSnapshotBarrier.withLock {
        withContext(NonCancellable) {
            val committed = settingsStore.updateLocal { current ->
                val display = current.displaySetting
                if (display.chatCustomFontPath != expectedRelativePath) {
                    current
                } else {
                    current.copy(
                        displaySetting = display.copy(
                            chatFontFamily = ChatFontFamily.DEFAULT,
                            chatCustomFontPath = "",
                            chatCustomFontName = "",
                        )
                    )
                }
            }
            withContext(Dispatchers.IO) {
                cleanupObsoleteFonts(retainedRelativePath = committed.displaySetting.chatCustomFontPath)
            }
            committed.displaySetting
        }
    }

    private fun resolveDisplayName(uri: Uri): String =
        FileUtils.getFileNameFromUri(appContext, uri)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.take(MAX_DISPLAY_NAME_LENGTH)
            ?: DEFAULT_DISPLAY_NAME

    private fun newImportFiles(displayName: String): FontImportFiles {
        val extension = displayName.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
            .takeIf(ALLOWED_EXTENSIONS::contains)
            ?: DEFAULT_EXTENSION
        val token = UUID.randomUUID().toString()
        val target = File(fontDir, "$FONT_FILE_PREFIX$token.$extension")
        val temporary = File(fontDir, "$FONT_IMPORT_PREFIX$token.tmp")
        return FontImportFiles(
            temporary = temporary,
            target = target,
            relativeTargetPath = "${FileFolders.FONTS}/${target.name}",
            displayName = displayName,
        )
    }

    private suspend fun stageImport(uri: Uri, files: FontImportFiles) {
        check(fontDir.mkdirs() || fontDir.isDirectory) { "Unable to create the custom font directory" }
        check(!files.temporary.exists() && !files.target.exists()) { "Custom font staging path already exists" }

        try {
            val input = appContext.contentResolver.openInputStream(uri)
                ?: error("Unable to open the selected font")
            input.use { source ->
                FileOutputStream(files.temporary).use { destination ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    var byteCount = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = source.read(buffer)
                        if (read < 0) break
                        byteCount += read
                        require(byteCount <= MAX_FONT_BYTES) { "The selected font exceeds the size limit" }
                        destination.write(buffer, 0, read)
                    }
                    require(byteCount > 0L) { "The selected font is empty" }
                    destination.flush()
                    destination.fd.sync()
                }
            }

            try {
                Typeface.createFromFile(files.temporary)
            } catch (failure: RuntimeException) {
                throw IllegalArgumentException("The selected file is not a valid font", failure)
            }

            check(files.temporary.renameTo(files.target)) { "Unable to save the selected font" }
        } catch (failure: Throwable) {
            discardImportFiles(files)
            throw failure
        }
    }

    private fun cleanupObsoleteFonts(retainedRelativePath: String) {
        val retained = resolveManagedFont(retainedRelativePath)
        fontDir.listFiles(File::isFile)
            ?.filterNot { file -> retained != null && canonicalOrNull(file) == retained }
            ?.forEach { obsolete ->
                if (!obsolete.delete()) {
                    Log.w(TAG, "Unable to delete obsolete custom font: $obsolete")
                }
            }
    }

    private fun discardImportFiles(files: FontImportFiles) {
        listOf(files.temporary, files.target).forEach { file ->
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "Unable to discard staged custom font: $file")
            }
        }
    }

    private fun resolveManagedFont(relativePath: String): File? {
        if (relativePath.isBlank()) return null
        val canonicalRoot = canonicalOrNull(fontDir) ?: return null
        val candidate = canonicalOrNull(File(filesDir, relativePath)) ?: return null
        return candidate.takeIf { it.parentFile == canonicalRoot }
    }

    private fun canonicalOrNull(file: File): File? = runCatching { file.canonicalFile }.getOrNull()

    private data class FontImportFiles(
        val temporary: File,
        val target: File,
        val relativeTargetPath: String,
        val displayName: String,
    )

    private companion object {
        const val TAG = "CustomChatFontService"
        const val FONT_FILE_PREFIX = "chat_font."
        const val FONT_IMPORT_PREFIX = "chat_font.import."
        const val DEFAULT_DISPLAY_NAME = "custom_font"
        const val DEFAULT_EXTENSION = "ttf"
        const val MAX_DISPLAY_NAME_LENGTH = 200
        const val MAX_FONT_BYTES = 32L * 1024L * 1024L
        const val COPY_BUFFER_SIZE = 16 * 1024
        val ALLOWED_EXTENSIONS = setOf("ttf", "otf", "ttc")
    }
}
