package net.weero.measix.pilot.data.sync

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.weero.measix.pilot.data.datastore.readUserSettingsForBackupRestore
import net.weero.measix.pilot.data.db.createAppDatabase
import kotlinx.serialization.json.Json
import net.weero.measix.pilot.data.ai.mcp.decodePersonalMcpCatalogImport
import net.weero.measix.pilot.data.ai.mcp.McpCatalogStore
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.datastore.migrateLegacySettingsJson

/** Cold-start owner for installing a validated backup before Room can open the live database. */
object PendingBackupRestore {
    private const val ROOT = "backup_restore"
    private const val PENDING = "pending"
    private const val STAGING = "staging"
    private const val ROLLBACK = "rollback"
    private const val PUBLICATION = "publication"
    private const val RESOLVED_SETTINGS = ".resolved_settings.json"
    private const val COMPLETED = "completed"
    private const val APPLY_STARTED = ".apply_started"
    private const val APPLY_COMPLETE = ".apply_complete"
    private const val BOOTSTRAP_FAILURE = "bootstrap_failure"

    internal fun stagingDir(context: Context) = File(rootDir(context), STAGING)
    internal fun pendingDir(context: Context) = File(rootDir(context), PENDING)

    /** Applies the physical aggregate swap and records any failure for application recovery. */
    fun bootstrapBeforeDatabaseOpen(context: Context) {
        val failure = File(rootDir(context), BOOTSTRAP_FAILURE)
        try {
            applyBeforeDatabaseOpen(context)
            failure.delete()
        } catch (error: Exception) {
            failure.writeText(error.stackTraceToString(), Charsets.UTF_8)
        }
    }

    internal fun applyBeforeDatabaseOpen(
        context: Context,
        readSettings: suspend () -> net.weero.measix.pilot.data.datastore.UserSettingsDocument = { context.readUserSettingsForBackupRestore() },
        afterSwap: (String) -> Unit = {},
    ) {
        val pending = pendingDir(context)
        if (!File(pending, BackupArchiveService.PREPARED_MARKER).isFile) return
        if (File(pending, APPLY_COMPLETE).isFile) return

        val rollback = rollbackDir(context)
        val publication = File(rootDir(context), PUBLICATION)
        if (File(pending, APPLY_STARTED).exists()) {
            // Published personal restores swapped directly from pending before publication was introduced.
            rollbackInterrupted(context, pending, if (publication.exists()) publication else pending, rollback)
        }
        if (File(pending, BackupArchiveService.AGGREGATE_MARKER).isFile) {
            check(!publication.exists() || publication.deleteRecursively()) { "Unable to clear previous restore publication" }
            check(publication.mkdirs()) { "Unable to create restore publication" }
            runBlocking(Dispatchers.IO) {
                val latest = File(publication, "latest-source.sqlite")
                try {
                    val live = context.getDatabasePath(DATABASE_NAME)
                    if (live.isFile) {
                        val source = createAppDatabase(context, live.absolutePath)
                        try {
                            val quoted = latest.absolutePath.replace("'", "''")
                            source.openHelper.writableDatabase.execSQL("VACUUM INTO '$quoted'")
                        } finally { source.close() }
                    }
                    val restored = BackupDataGraph(context).mergeRestore(pending, latest.takeIf(File::isFile),
                        readSettings(), publication)
                    File(pending, RESOLVED_SETTINGS).writeText(net.weero.measix.pilot.utils.JsonInstant.encodeToString(restored), Charsets.UTF_8)
                } finally { latest.delete() }
            }
        }
        check(!rollback.exists() || rollback.deleteRecursively()) { "Unable to clear restore rollback directory" }
        check(rollback.mkdirs()) { "Unable to create restore rollback directory" }
        File(pending, APPLY_STARTED).writeText("1", Charsets.UTF_8)
        try {
            if (File(pending, BackupArchiveService.AGGREGATE_MARKER).isFile) {
                retainDatabaseSidecars(context, rollback)
                swapComponent(
                    staged = File(publication, BackupArchiveService.DATABASE_ENTRY),
                    live = context.getDatabasePath(DATABASE_NAME),
                    rollback = rollback,
                )
                afterSwap(BackupArchiveService.DATABASE_ENTRY)
                BackupArchiveService.DURABLE_DIRECTORIES.forEach { folder ->
                    swapComponent(File(publication, folder), File(context.filesDir, folder), rollback)
                    afterSwap(folder)
                }
            }
            File(pending, APPLY_COMPLETE).writeText("1", Charsets.UTF_8)
        } catch (error: Exception) {
            rollbackInterrupted(context, pending, publication, rollback)
            throw error
        }
    }

    suspend fun restoreSettingsIfPending(
        context: Context,
        store: ArtifactStore,
        catalogStore: McpCatalogStore,
        json: Json,
    ) {
        val bootstrapFailure = File(rootDir(context), BOOTSTRAP_FAILURE)
        if (bootstrapFailure.isFile) {
            throw BackupRestoreBootstrapException(bootstrapFailure.readText(Charsets.UTF_8))
        }
        val pending = pendingDir(context)
        if (!File(pending, APPLY_COMPLETE).isFile) return
        val publication = File(rootDir(context), PUBLICATION)
        val resolved = File(pending, RESOLVED_SETTINGS)
        val aggregate = File(pending, BackupArchiveService.AGGREGATE_MARKER).isFile
        val settingsFile = if (aggregate && resolved.isFile) resolved else {
            if (aggregate) {
                val manifest = File(pending, BackupArchiveService.MANIFEST_ENTRY)
                val legacy = !manifest.isFile || json.decodeFromString<DurableBackupManifest>(manifest.readText()).version != BackupArchiveService.MANIFEST_VERSION
                check(legacy && !publication.exists()) { "Completed backup restore is missing its resolved settings" }
            }
            File(pending, BackupArchiveService.SETTINGS_ENTRY)
        }
        val decoded = json.decodeFromString<Settings>(
            migrateLegacySettingsJson(settingsFile.readText(Charsets.UTF_8)),
        )
        val settings = if (File(pending, BackupArchiveService.AGGREGATE_MARKER).isFile) {
            decoded
        } else {
            BackupSettingsPolicy.withoutLocalPayloadReferences(decoded)
        }
        store.restoreSettingsReferences(settings)
        val catalogs = decodePersonalMcpCatalogImport(
            File(pending, BackupArchiveService.MCP_CATALOGS_ENTRY).readText(Charsets.UTF_8)
        )
        catalogStore.restorePersonalCatalogs(catalogs, settings.mcpServers)
    }

    internal fun complete(context: Context, afterRetire: () -> Unit = {}) {
        val pending = pendingDir(context)
        val completed = File(rootDir(context), COMPLETED)
        if (File(pending, APPLY_COMPLETE).isFile) {
            check(!completed.exists() && pending.renameTo(completed)) { "Unable to retire completed restore" }
            afterRetire()
        }
        if (!completed.exists()) return
        val rollback = rollbackDir(context)
        check(!rollback.exists() || rollback.deleteRecursively()) { "Unable to remove restore rollback data" }
        val publication = File(rootDir(context), PUBLICATION)
        check(!publication.exists() || publication.deleteRecursively()) { "Unable to remove restore publication" }
        check(completed.deleteRecursively()) { "Unable to finalize completed restore" }
    }

    private fun rollbackDir(context: Context) = File(rootDir(context), ROLLBACK)

    private fun rootDir(context: Context) = File(context.noBackupFilesDir, ROOT).apply { mkdirs() }

    private fun swapComponent(staged: File, live: File, rollback: File) {
        val saved = File(rollback, live.name)
        val absent = File(rollback, "${live.name}.absent")
        live.parentFile?.mkdirs()
        if (live.exists()) {
            check(live.renameTo(saved)) { "Unable to retain live restore component: ${live.name}" }
        } else {
            absent.writeText("1", Charsets.UTF_8)
        }
        check(staged.renameTo(live)) { "Unable to install staged restore component: ${staged.name}" }
    }

    private fun rollbackInterrupted(context: Context, pending: File, publication: File, rollback: File) {
        restoreComponents(context, publication).asReversed().forEach { (staged, live) ->
            val saved = File(rollback, live.name)
            val absent = File(rollback, "${live.name}.absent")
            if ((saved.exists() || absent.exists()) && !staged.exists() && live.exists()) {
                check(live.renameTo(staged)) { "Unable to restage ${live.name}" }
            }
            if (saved.exists()) {
                check(!live.exists() || live.deleteRecursively()) { "Unable to remove interrupted ${live.name}" }
                check(saved.renameTo(live)) { "Unable to restore ${live.name}" }
            } else if (absent.exists()) {
                check(!live.exists() || live.deleteRecursively()) { "Unable to remove newly restored ${live.name}" }
            }
        }
        restoreDatabaseSidecars(context, rollback)
        File(pending, APPLY_STARTED).delete()
        File(pending, APPLY_COMPLETE).delete()
        rollback.deleteRecursively()
    }

    private fun restoreComponents(context: Context, pending: File): List<Pair<File, File>> = buildList {
        add(File(pending, BackupArchiveService.DATABASE_ENTRY) to context.getDatabasePath(DATABASE_NAME))
        BackupArchiveService.DURABLE_DIRECTORIES.forEach { folder ->
            add(File(pending, folder) to File(context.filesDir, folder))
        }
    }

    private fun retainDatabaseSidecars(context: Context, rollback: File) {
        databaseSidecars(context).forEach { live ->
            if (live.exists()) {
                check(live.renameTo(File(rollback, live.name))) { "Unable to retain ${live.name}" }
            } else {
                File(rollback, "${live.name}.absent").writeText("1", Charsets.UTF_8)
            }
        }
    }

    private fun restoreDatabaseSidecars(context: Context, rollback: File) {
        databaseSidecars(context).forEach { live ->
            val saved = File(rollback, live.name)
            val absent = File(rollback, "${live.name}.absent")
            if (saved.exists()) {
                check(!live.exists() || live.delete()) { "Unable to remove interrupted ${live.name}" }
                check(saved.renameTo(live)) { "Unable to restore ${live.name}" }
            } else if (absent.exists()) {
                check(!live.exists() || live.delete()) { "Unable to remove newly restored ${live.name}" }
            }
        }
    }

    private fun databaseSidecars(context: Context): List<File> {
        val parent = context.getDatabasePath(DATABASE_NAME).parentFile
        return listOf(File(parent, "$DATABASE_NAME-wal"), File(parent, "$DATABASE_NAME-shm"))
    }

    private const val DATABASE_NAME = "measix_pilot"
}

class BackupRestoreBootstrapException(detail: String) :
    IllegalStateException("Pending backup restore could not be applied before database open: $detail")
