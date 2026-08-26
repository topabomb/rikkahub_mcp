package net.weero.measix.pilot.service

import android.content.Context
import android.net.Uri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import net.weero.measix.pilot.data.datastore.WebDavConfig
import net.weero.measix.pilot.data.sync.S3BackupItem
import net.weero.measix.pilot.data.sync.S3Sync
import net.weero.measix.pilot.data.sync.s3.S3Config
import net.weero.measix.pilot.data.sync.webdav.WebDavBackupItem
import net.weero.measix.pilot.data.sync.webdav.WebDavSync
import net.weero.measix.pilot.data.sync.BackupRestorePendingRestartException
import net.weero.measix.pilot.data.sync.BackupRestoreInProgressException

/** Serializes the complete confirmed restore operation, including remote download and staging. */
class BackupRestoreApplicationService(
    context: Context,
    private val webDavSync: WebDavSync,
    private val s3Sync: S3Sync,
) {
    private val context = context.applicationContext
    private val restoreMutex = Mutex()

    suspend fun restoreWebDav(config: WebDavConfig, item: WebDavBackupItem) = exclusively {
        webDavSync.restore(config, item)
    }

    suspend fun restoreS3(config: S3Config, item: S3BackupItem) = exclusively {
        s3Sync.restoreFromS3(config, item)
    }

    suspend fun restoreLocal(uri: Uri, config: WebDavConfig) = exclusively {
        val file = withContext(NonCancellable + Dispatchers.IO) {
            File.createTempFile("restore_", ".zip", context.cacheDir)
        }
        try {
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use(input::copyTo)
                } ?: error("Unable to open the selected backup archive")
            }
            webDavSync.restoreFromLocalFile(file, config)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { file.delete() }
        }
    }

    private suspend fun exclusively(block: suspend () -> Unit) {
        if (!restoreMutex.tryLock()) throw BackupRestoreOperationInProgressException()
        try {
            block()
        } catch (error: BackupRestorePendingRestartException) {
            throw BackupRestorePendingRestartConflictException()
        } catch (error: BackupRestoreInProgressException) {
            throw BackupRestoreOperationInProgressException()
        } finally {
            restoreMutex.unlock()
        }
    }
}

class BackupRestoreOperationInProgressException :
    IllegalStateException("Another backup restore operation is already running")

class BackupRestorePendingRestartConflictException :
    IllegalStateException("A backup restore is already staged and waiting for restart")
