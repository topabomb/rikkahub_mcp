package net.weero.measix.pilot.data.sync.webdav

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.weero.measix.pilot.data.datastore.WebDavConfig
import net.weero.measix.pilot.data.sync.BackupArchiveService
import net.weero.measix.pilot.data.sync.BackupRestoreInProgressException
import net.weero.measix.pilot.data.sync.BackupRestorePendingRestartException
import net.weero.measix.pilot.data.sync.BackupSelection
import net.weero.measix.pilot.utils.fileSizeToString
import java.io.File
import java.time.Instant

private const val TAG = "WebDavSync"

class WebDavSync(
    private val context: Context,
    private val httpClient: HttpClient,
    private val archiveService: BackupArchiveService,
) {
    private fun getClient(config: WebDavConfig): WebDavClient {
        return WebDavClient(config, httpClient)
    }

    suspend fun testConnection(config: WebDavConfig) = withContext(Dispatchers.IO) {
        val client = getClient(config)
        // Test by listing the root directory
        client.propfind(depth = 0).getOrThrow()
        Log.i(TAG, "testConnection: Connection successful")
    }

    suspend fun backup(config: WebDavConfig) = withContext(Dispatchers.IO) {
        val file = prepareBackupFile(config)
        try {
            val client = getClient(config)
            client.ensureCollectionExists().getOrThrow()
            client.put(path = file.name, file = file, contentType = "application/zip").getOrThrow()
            Log.i(TAG, "backup: Uploaded ${file.name} (${file.length().fileSizeToString()})")
        } finally {
            if (file.exists() && !file.delete()) Log.w(TAG, "backup: Failed to delete ${file.name}")
        }
    }

    suspend fun listBackupFiles(config: WebDavConfig): List<WebDavBackupItem> = withContext(Dispatchers.IO) {
        val client = getClient(config)

        // Ensure the backup directory exists
        client.ensureCollectionExists().getOrThrow()

        val resources = client.list().getOrThrow()

        resources
            .filter { !it.isCollection && it.displayName.startsWith("backup_") && it.displayName.endsWith(".zip") }
            .map { resource ->
                WebDavBackupItem(
                    href = resource.href,
                    displayName = resource.displayName,
                    size = resource.contentLength,
                    lastModified = resource.lastModified ?: Instant.EPOCH
                )
            }
            .sortedByDescending { it.lastModified }
    }

    suspend fun restore(config: WebDavConfig, item: WebDavBackupItem) = withContext(Dispatchers.IO) {
        val client = getClient(config)
        val backupFile = File(context.cacheDir, item.displayName)

        try {
            // Download backup file directly to file to avoid OOM
            Log.i(TAG, "restore: Downloading ${item.displayName}")
            client.downloadToFile(item.displayName, backupFile).getOrThrow()

            Log.i(TAG, "restore: Downloaded ${backupFile.length().fileSizeToString()}")

            // Restore from backup file
            archiveService.stageRestore(backupFile, config.backupSelection())
        } finally {
            // Clean up temp file
            if (backupFile.exists()) {
                backupFile.delete()
                Log.i(TAG, "restore: Cleaned up temporary backup file")
            }
        }
    }

    suspend fun deleteBackupFile(config: WebDavConfig, item: WebDavBackupItem) = withContext(Dispatchers.IO) {
        val client = getClient(config)
        client.delete(item.displayName).getOrThrow()
        Log.i(TAG, "deleteBackupFile: Deleted ${item.displayName}")
    }

    suspend fun restoreFromLocalFile(file: File, config: WebDavConfig) = withContext(Dispatchers.IO) {
        Log.i(TAG, "restoreFromLocalFile: Starting restore from ${file.absolutePath}")

        if (!file.exists()) {
            throw Exception("Backup file does not exist")
        }

        if (!file.canRead()) {
            throw Exception("Cannot read backup file")
        }

        try {
            archiveService.stageRestore(file, config.backupSelection())
            Log.i(TAG, "restoreFromLocalFile: Restore completed successfully")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (conflict: BackupRestoreInProgressException) {
            throw conflict
        } catch (conflict: BackupRestorePendingRestartException) {
            throw conflict
        } catch (error: Exception) {
            Log.e(TAG, "restoreFromLocalFile: Failed to restore from local file", error)
            throw IllegalStateException("Restore failed: ${error.message}", error)
        }
    }

    suspend fun prepareBackupFile(config: WebDavConfig): File =
        archiveService.prepare(config.backupSelection())
}

private fun WebDavConfig.backupSelection(): BackupSelection {
    val includeAggregate = items.any { it == WebDavConfig.BackupItem.DATABASE || it == WebDavConfig.BackupItem.FILES }
    return BackupSelection(includeAggregate, includeAggregate)
}

data class WebDavBackupItem(
    val href: String,
    val displayName: String,
    val size: Long,
    val lastModified: Instant,
)
