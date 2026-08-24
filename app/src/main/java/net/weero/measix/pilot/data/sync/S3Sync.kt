package net.weero.measix.pilot.data.sync

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.weero.measix.pilot.data.sync.s3.S3Client
import net.weero.measix.pilot.data.sync.s3.S3Config
import net.weero.measix.pilot.utils.fileSizeToString
import java.io.File
import java.time.Instant

private const val TAG = "S3Sync"

class S3Sync(
    private val context: Context,
    private val httpClient: HttpClient,
    private val archiveService: BackupArchiveService,
) {
    private fun getS3Client(config: S3Config): S3Client {
        return S3Client(config, httpClient)
    }

    suspend fun testS3(config: S3Config) = withContext(Dispatchers.IO) {
        val client = getS3Client(config)
        // Test by listing objects with max 1 result
        client.listObjects(maxKeys = 1).getOrThrow()
        Log.i(TAG, "testS3: Connection successful")
    }

    suspend fun backupToS3(config: S3Config) = withContext(Dispatchers.IO) {
        val file = prepareBackupFile(config)
        try {
            val client = getS3Client(config)
            val key = "measix_pilot_backups/${file.name}"
            client.putObject(key = key, file = file, contentType = "application/zip").getOrThrow()
            Log.i(TAG, "backupToS3: Uploaded ${file.name} (${file.length().fileSizeToString()})")
        } finally {
            if (file.exists() && !file.delete()) Log.w(TAG, "backupToS3: Failed to delete ${file.name}")
        }
    }

    suspend fun listBackupFiles(config: S3Config): List<S3BackupItem> = withContext(Dispatchers.IO) {
        val client = getS3Client(config)
        val result = client.listObjects(
            prefix = "measix_pilot_backups/",
            maxKeys = 1000
        ).getOrThrow()

        result.objects
            .filter { it.key.startsWith("measix_pilot_backups/backup_") && it.key.endsWith(".zip") }
            .map { obj ->
                S3BackupItem(
                    key = obj.key,
                    displayName = obj.key.substringAfterLast("/"),
                    size = obj.size,
                    lastModified = obj.lastModified ?: Instant.EPOCH
                )
            }
            .sortedByDescending { it.lastModified }
    }

    suspend fun restoreFromS3(config: S3Config, item: S3BackupItem) = withContext(Dispatchers.IO) {
        val client = getS3Client(config)
        val backupFile = File(context.cacheDir, item.displayName)

        try {
            // Download backup file directly to file to avoid OOM
            Log.i(TAG, "restoreFromS3: Downloading ${item.displayName}")
            client.downloadObjectToFile(item.key, backupFile).getOrThrow()

            Log.i(TAG, "restoreFromS3: Downloaded ${backupFile.length().fileSizeToString()}")

            // Restore from backup file
            archiveService.stageRestore(backupFile, config.backupSelection())
        } finally {
            // Clean up temp file
            if (backupFile.exists()) {
                backupFile.delete()
                Log.i(TAG, "restoreFromS3: Cleaned up temporary backup file")
            }
        }
    }

    suspend fun deleteS3BackupFile(config: S3Config, item: S3BackupItem) = withContext(Dispatchers.IO) {
        val client = getS3Client(config)
        client.deleteObject(item.key).getOrThrow()
        Log.i(TAG, "deleteS3BackupFile: Deleted ${item.key}")
    }

    suspend fun prepareBackupFile(config: S3Config): File =
        archiveService.prepare(config.backupSelection())
}

private fun S3Config.backupSelection(): BackupSelection {
    val includeAggregate = items.any { it == S3Config.BackupItem.DATABASE || it == S3Config.BackupItem.FILES }
    return BackupSelection(includeAggregate, includeAggregate)
}

data class S3BackupItem(
    val key: String,
    val displayName: String,
    val size: Long,
    val lastModified: Instant,
)
