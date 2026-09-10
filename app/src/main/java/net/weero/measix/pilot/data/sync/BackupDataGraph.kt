package net.weero.measix.pilot.data.sync

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.configuration.configurationScopeFromStorageKey
import net.weero.measix.pilot.data.configuration.storageKey
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.UserSettingsDocument
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.db.APP_DATABASE_VERSION
import net.weero.measix.pilot.data.db.createAppDatabase
import net.weero.measix.pilot.data.db.fts.MessageFtsManager
import net.weero.measix.pilot.data.db.transcript.V3TranscriptValidator
import net.weero.measix.pilot.data.db.transcript.readTranscriptPayload
import net.weero.measix.pilot.data.files.ArtifactPayloadStore
import net.weero.measix.pilot.data.files.ArtifactReferencePolicy
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.FileFolders
import net.weero.measix.pilot.data.imggen.GeneratedMediaStore
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.model.NodeFavoriteRef
import net.weero.measix.pilot.data.model.collectArtifactReferences
import net.weero.measix.pilot.utils.JsonInstant
import kotlin.uuid.Uuid

/** Detached archive graphs only. Runtime database and file writes remain with their existing owners. */
internal class BackupDataGraph(private val context: Context) {
    private val payloads = ArtifactPayloadStore(context)

    suspend fun exportPersonal(source: File, target: File, settings: Settings,
        files: File = context.filesDir, rejectEnterprise: Boolean = false): Set<String> =
        build(target, listOf(source)) { room, db ->
            if (rejectEnterprise) assertPersonal(db, "source0")
            copyGraph(db, "source0", "scope = 'personal'")
            copyTable(db, "source0", "workspaces")
            val roots = mapOf<ConfigurationScope, Set<String>>(ConfigurationScope.Personal to ArtifactReferencePolicy.roots(settings))
            rebuildAndValidate(room, roots)
            payloadPaths(db, files, allowRecovery = true) + sharedConfigurationFiles(files, settings)
        }

    suspend fun validatePersonalArchive(directory: File, settings: Settings, legacy: Boolean): Settings {
        val original = File(directory, BackupArchiveService.DATABASE_ENTRY)
        val checked = File(directory, "checked-personal.sqlite")
        try {
            val normalized = build(checked, listOf(original)) { room, db ->
                assertPersonal(db, "source0")
                copyGraph(db, "source0", "scope = 'personal'")
                copyTable(db, "source0", "workspaces")
                val candidate = if (legacy) normalizeLegacyPersonalRoots(db, directory, settings) else settings
                rebuildAndValidate(room, mapOf(ConfigurationScope.Personal to ArtifactReferencePolicy.roots(candidate)))
                payloadPaths(db, directory, allowRecovery = true)
                candidate
            }
            check(original.delete() && checked.renameTo(original)) { "Unable to publish checked personal graph" }
            return normalized
        } finally { checked.delete() }
    }

    private fun normalizeLegacyPersonalRoots(db: SupportSQLiteDatabase, files: File, settings: Settings): Settings {
        val unavailable = ArtifactReferencePolicy.roots(settings).filterTo(hashSetOf()) { token ->
            val path = payloads.relativePathForToken(token) ?: return@filterTo false
            db.query("SELECT scope FROM artifact WHERE relative_path=? AND state='ACTIVE'", arrayOf(path)).use {
                if (!it.moveToFirst() || !inside(files, path).isFile) true
                else {
                    check(it.getString(0) == ConfigurationScope.Personal.storageKey()) { "Configuration artifact is outside its scope" }
                    false
                }
            }
        }
        return ArtifactReferencePolicy.detach(settings, unavailable)
    }

    /** A fresh publication is built from the untouched personal input and the latest cold-start graph. */
    suspend fun mergeRestore(
        personal: File,
        latest: File?,
        current: UserSettingsDocument,
        output: File,
    ): Settings {
        val restored = JsonInstant.decodeFromString<Settings>(File(personal, BackupArchiveService.SETTINGS_ENTRY).readText())
        val manifest = File(personal, BackupArchiveService.MANIFEST_ENTRY)
        val legacy = !manifest.isFile || JsonInstant.decodeFromString<DurableBackupManifest>(manifest.readText()).version != BackupArchiveService.MANIFEST_VERSION
        val retainedRoots = ArtifactReferencePolicy.scopedRoots(current).filterKeys { it != ConfigurationScope.Personal }
        val retainedPaths = retainedRoots.values.flatten().mapNotNullTo(hashSetOf(), payloads::relativePathForToken)
        val personalDatabase = migratePendingInput(File(personal, BackupArchiveService.DATABASE_ENTRY), output)
        val sourceFiles = listOfNotNull(personalDatabase, latest)
        val destination = File(output, BackupArchiveService.DATABASE_ENTRY)
        val (files, restoredSettings) = build(destination, sourceFiles) { room, db ->
            assertPersonal(db, "source0")
            copyGraph(db, "source0", "scope = 'personal'")
            val candidate = if (legacy) normalizeLegacyPersonalRoots(db, personal, restored) else restored
            if (latest != null) {
                db.execSQL("CREATE TEMP TABLE retained_artifact_path(path TEXT PRIMARY KEY)")
                retainedPaths.forEach { db.execSQL("INSERT INTO retained_artifact_path VALUES (?)", arrayOf(it)) }
                copyGraph(db, "source1", "scope <> 'personal'", retainSharedAssets = true)
                copyTable(db, "source1", "workspaces")
            } else copyTable(db, "source0", "workspaces")
            rebuildAndValidate(room, ArtifactReferencePolicy.scopedRoots(current.withPersonalSettings(candidate)))
            val personalPaths = payloadPaths(db, personal, allowRecovery = true, source = "source0")
            val retained = if (latest == null) emptySet() else payloadPaths(
                db, context.filesDir, allowRecovery = true, source = "source1",
                predicate = "scope <> 'personal' OR relative_path IN (SELECT path FROM retained_artifact_path)",
                mediaPredicate = "scope <> 'personal'",
            )
            (personalPaths.map { it to inside(personal, it) } +
                retained.map { it to inside(context.filesDir, it) } +
                sharedConfigurationFiles(personal, candidate).map { it to inside(personal, it) }) to candidate
        }
        files.forEach { (path, source) -> copyPayload(source, inside(output, path)) }
        // These filenames are recovery receipts owned by GeneratedMediaStore, including committed row deletions.
        val images = File(context.filesDir, FileFolders.IMAGES)
        val receipts = if (!images.exists()) emptyArray() else {
            check(images.isDirectory) { "Live media recovery directory is not a directory" }
            checkNotNull(images.listFiles()) { "Cannot enumerate live media recovery receipts" }
        }
        receipts
            .filter { it.name.endsWith(GeneratedMediaStore.DELETING_SUFFIX) || it.name.endsWith(GeneratedMediaStore.PENDING_SUFFIX) }
            .forEach { receipt ->
                check(receipt.isFile) { "Live media recovery receipt is not a file" }
                val relative = FileFolders.IMAGES + "/" + receipt.name
                val canonical = inside(output, relative.removeSuffix(GeneratedMediaStore.DELETING_SUFFIX).removeSuffix(GeneratedMediaStore.PENDING_SUFFIX))
                val fromArchive = inside(personal, relative.removeSuffix(GeneratedMediaStore.DELETING_SUFFIX).removeSuffix(GeneratedMediaStore.PENDING_SUFFIX))
                check(!canonical.exists() || !fromArchive.exists()) { "Restore payload conflicts with a live media recovery receipt: $relative" }
                copyPayload(receipt, inside(output, relative))
            }
        BackupArchiveService.DURABLE_DIRECTORIES.forEach { check(File(output, it).isDirectory || File(output, it).mkdirs()) }
        return restoredSettings
    }

    /** An app upgrade can encounter a prepared archive from the previously installed Room version. */
    private fun migratePendingInput(original: File, output: File): File {
        val version = SQLiteDatabase.openDatabase(original.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { it.version }
        require(version in 1..APP_DATABASE_VERSION) { "Unsupported pending restore database version" }
        if (version == APP_DATABASE_VERSION) return original
        val copy = File(output, "personal-source.sqlite")
        original.copyTo(copy)
        val database = createAppDatabase(context, copy.absolutePath)
        try {
            val db = database.openHelper.writableDatabase
            check(db.version == APP_DATABASE_VERSION) { "Pending restore migration did not reach the current schema" }
            db.disableWriteAheadLogging()
        } finally { database.close() }
        return copy
    }

    private suspend fun <T> build(target: File, sources: List<File>, block: suspend (AppDatabase, SupportSQLiteDatabase) -> T): T {
        check(!target.exists()) { "Backup graph destination already exists" }
        target.parentFile?.mkdirs()
        val room = createAppDatabase(context, target.absolutePath)
        try {
            val db = room.openHelper.writableDatabase
            // ATTACH belongs to a connection. Detached construction uses one connection, including reads.
            db.disableWriteAheadLogging()
            sources.forEachIndexed { index, file ->
                check(file.isFile) { "Backup graph source is missing" }
                db.execSQL("ATTACH DATABASE ? AS source$index", arrayOf(file.absolutePath))
            }
            db.beginTransaction()
            val result = try {
                db.execSQL("PRAGMA defer_foreign_keys = ON")
                val value = block(room, db)
                db.query("PRAGMA foreign_key_check").use { check(!it.moveToFirst()) { "Backup graph has broken foreign keys" } }
                db.setTransactionSuccessful()
                value
            } finally { db.endTransaction() }
            sources.indices.forEach { db.execSQL("DETACH DATABASE source$it") }
            db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { check(it.moveToFirst() && it.getInt(0) == 0) }
            return result
        } finally { room.close() }
    }

    private fun copyGraph(db: SupportSQLiteDatabase, source: String, predicate: String, retainSharedAssets: Boolean = false) {
        listOf("ConversationEntity", "MemoryEntity", "GenMediaEntity", "conversation_folder", "favorites")
            .forEach { copyTable(db, source, it, predicate) }
        val artifacts = if (retainSharedAssets) "$predicate OR relative_path IN (SELECT path FROM retained_artifact_path)" else predicate
        if (retainSharedAssets) {
            // Identical shared assets can occur in both inputs; an identity with changed metadata is a conflict.
            val columns = columns(db, "artifact")
            requireEmpty(db, "SELECT $columns FROM $source.artifact WHERE ($artifacts) AND id IN (SELECT id FROM main.artifact) EXCEPT SELECT $columns FROM main.artifact",
                "Shared artifact metadata changed since the personal backup")
        }
        copyTable(db, source, "artifact", "($artifacts)" + if (retainSharedAssets) " AND id NOT IN (SELECT id FROM main.artifact)" else "")
        copyTable(db, source, "message_node", "conversation_id IN (SELECT id FROM $source.ConversationEntity WHERE $predicate)")
        copyTable(db, source, "turn_execution", "conversation_id IN (SELECT id FROM $source.ConversationEntity WHERE $predicate)")
        copyTable(db, source, "tool_execution", "turn_id IN (SELECT turn_id FROM $source.turn_execution WHERE conversation_id IN (SELECT id FROM $source.ConversationEntity WHERE $predicate))")
        copyTable(db, source, "conversation_model_context", "owner_node_id IN (SELECT id FROM $source.message_node WHERE conversation_id IN (SELECT id FROM $source.ConversationEntity WHERE $predicate))")
    }

    private fun copyTable(db: SupportSQLiteDatabase, source: String, table: String, predicate: String = "1") {
        val columns = columns(db, table)
        db.execSQL("INSERT INTO main.$table ($columns) SELECT $columns FROM $source.$table WHERE $predicate")
        // Deleted IDs remain reserved, including when a restore retains only part of a source graph.
        db.query("SELECT seq FROM $source.sqlite_sequence WHERE name=?", arrayOf(table)).use { cursor ->
            if (cursor.moveToFirst()) {
                val sequence = cursor.getLong(0)
                db.execSQL("INSERT INTO main.sqlite_sequence(name,seq) SELECT ?,? WHERE NOT EXISTS (SELECT 1 FROM main.sqlite_sequence WHERE name=?)", arrayOf<Any>(table, sequence, table))
                db.execSQL("UPDATE main.sqlite_sequence SET seq=MAX(seq,?) WHERE name=?", arrayOf<Any>(sequence, table))
            }
        }
    }

    private fun columns(db: SupportSQLiteDatabase, table: String): String = buildList {
        db.query("PRAGMA main.table_info(`$table`)").use { cursor ->
            while (cursor.moveToNext()) add("`${cursor.getString(1)}`")
        }
    }.joinToString(",")

    private fun assertPersonal(db: SupportSQLiteDatabase, source: String) {
        listOf("ConversationEntity", "MemoryEntity", "GenMediaEntity", "conversation_folder", "favorites", "artifact").forEach {
            requireEmpty(db, "SELECT 1 FROM $source.$it WHERE scope <> 'personal'", "Personal backup contains enterprise records")
        }
    }

    private suspend fun rebuildAndValidate(room: AppDatabase, roots: Map<ConfigurationScope, Set<String>>) {
        val db = room.openHelper.writableDatabase
        requireEmpty(db, "SELECT 1 FROM ConversationEntity c JOIN ConversationEntity p ON p.id=c.parent_conversation_id WHERE c.scope<>p.scope", "Cross-scope child conversation")
        requireEmpty(db, "SELECT 1 FROM ConversationEntity c LEFT JOIN conversation_folder f ON f.id=c.folder_id WHERE c.folder_id<>'' AND (f.id IS NULL OR f.scope<>c.scope OR f.assistant_id<>c.assistant_id)", "Invalid conversation folder")
        requireEmpty(db, "SELECT 1 FROM conversation_model_context x JOIN message_node o ON o.id=x.owner_node_id JOIN message_node a ON a.id=x.anchor_node_id WHERE o.conversation_id<>a.conversation_id", "Cross-conversation model context")
        requireEmpty(db, "SELECT 1 FROM tool_execution e JOIN turn_execution t ON t.turn_id=e.turn_id JOIN ConversationEntity p ON p.id=t.conversation_id LEFT JOIN ConversationEntity c ON c.id=e.child_conversation_id LEFT JOIN turn_execution ct ON ct.turn_id=e.child_turn_id WHERE (e.child_conversation_id IS NOT NULL AND (c.id IS NULL OR c.scope<>p.scope OR c.parent_conversation_id IS NULL OR c.parent_conversation_id<>p.id)) OR (e.child_turn_id IS NOT NULL AND (c.id IS NULL OR ct.turn_id IS NULL OR ct.conversation_id<>c.id))", "Invalid child execution lineage")
        requireEmpty(db, "SELECT path FROM (SELECT relative_path AS path FROM artifact UNION ALL SELECT path FROM GenMediaEntity) GROUP BY path HAVING COUNT(*) > 1", "Independent file owners share a payload path")
        db.query("SELECT scope, ref_json FROM favorites WHERE type='node'").use { cursor ->
            while (cursor.moveToNext()) {
                val ref = JsonInstant.decodeFromString<NodeFavoriteRef>(cursor.getString(1))
                db.query("SELECT c.scope FROM message_node n JOIN ConversationEntity c ON c.id=n.conversation_id WHERE n.id=? AND c.id=?", arrayOf(ref.nodeId.toString(), ref.conversationId.toString())).use {
                    check(it.moveToFirst() && it.getString(0) == cursor.getString(0)) { "Favorite is outside its original conversation scope" }
                }
            }
        }
        val nonTerminal = strings(db, "SELECT assistant_message_id FROM turn_execution WHERE status IN ('RUNNING','AWAITING_USER') AND assistant_message_id IS NOT NULL").toSet()
        val fts = MessageFtsManager(room)
        db.query("SELECT n.id,n.select_index,n.transcript_schema,c.id,c.scope,c.title,c.update_at FROM message_node n JOIN ConversationEntity c ON c.id=n.conversation_id").use { cursor ->
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                val id = cursor.getString(0)
                check(cursor.getInt(2) == 3)
                val raw = readTranscriptPayload(db, id)
                V3TranscriptValidator.validateNode(raw, JsonInstant, nonTerminal)
                val messages = JsonInstant.decodeFromString<List<UIMessage>>(raw)
                check(cursor.getInt(1) in messages.indices) { "Selected message variant is missing" }
                val scope = configurationScopeFromStorageKey(cursor.getString(4))
                val references = messages.collectArtifactReferences()
                val seen = hashSetOf<Pair<Long, String>>()
                references.forEach { reference ->
                    val path = payloads.relativePathForToken(reference.token) ?: return@forEach
                    db.query("SELECT id,scope,state FROM artifact WHERE relative_path=?", arrayOf(path)).use { artifact ->
                        // User deletion preserves the transcript; only live, identity-matching assets have a projection.
                        if (!artifact.moveToFirst() || artifact.getString(2) != "ACTIVE") return@use
                        val artifactId = artifact.getLong(0)
                        if (reference.expectedArtifactId != null && reference.expectedArtifactId != artifactId) return@use
                        check(artifact.getString(1) == scope.storageKey()) { "Message artifact is outside its scope: $path" }
                        if (seen.add(artifactId to reference.type.name)) db.execSQL("INSERT INTO artifact_reference(artifact_id,node_id,reference_type) VALUES (?,?,?)", arrayOf(artifactId, id, reference.type.name))
                    }
                }
                fts.reindexNodesInTransaction(cursor.getString(3), cursor.getString(5), cursor.getLong(6), listOf(MessageNode(Uuid.parse(id), messages, cursor.getInt(1))))
            }
        }
        roots.forEach { (scope, tokens) -> tokens.forEach tokenLoop@ { token ->
            val path = payloads.relativePathForToken(token) ?: return@tokenLoop
            db.query("SELECT scope,state FROM artifact WHERE relative_path=?", arrayOf(path)).use {
                check(it.moveToFirst() && it.getString(1) in setOf("ACTIVE", "DELETING")) { "Configuration artifact is missing: $path" }
                val owner = configurationScopeFromStorageKey(it.getString(0))
                check(owner == ConfigurationScope.Personal || owner == scope) { "Configuration artifact is outside its scope: $path" }
            }
        } }
        fts.markProjectionCurrentInTransaction()
        db.execSQL("INSERT INTO system_meta(`key`,value) VALUES (?,?)", arrayOf(ArtifactStore.REFERENCE_PROJECTION_VERSION_KEY,"true"))
    }

    private fun payloadPaths(db: SupportSQLiteDatabase, root: File, allowRecovery: Boolean, source: String = "main",
        predicate: String = "1", mediaPredicate: String = "1"): Set<String> = buildSet {
        db.query("SELECT relative_path,state,payload_token FROM $source.artifact WHERE $predicate").use { cursor ->
            while (cursor.moveToNext()) {
                val path = cursor.getString(0)
                require(path.substringBefore('/') in setOf(FileFolders.UPLOAD, FileFolders.IMAGES, FileFolders.TOOL_OUTPUTS)) { "Artifact payload is outside managed directories" }
                val file = inside(root, path)
                if (cursor.getString(1) == "ACTIVE") check(file.isFile) { "Missing artifact payload: $path" }
                if (file.isFile) add(path)
                if (!cursor.isNull(2)) {
                    val token = cursor.getString(2)
                    require(File(token).name == token && '/' !in token && '\\' !in token)
                    val staged = ArtifactPayloadStore.STAGING_FOLDER + "/" + token
                    if (inside(root, staged).isFile) add(staged)
                }
            }
        }
        strings(db, "SELECT path FROM $source.GenMediaEntity WHERE $mediaPredicate").forEach { path ->
            require(path.startsWith(FileFolders.IMAGES + "/"))
            if (inside(root, path).isFile) add(path)
            else {
                val receipt = path + GeneratedMediaStore.DELETING_SUFFIX
                check(allowRecovery && inside(root, receipt).isFile) { "Missing generated media: $path" }
                add(receipt)
            }
        }
    }

    private fun sharedConfigurationFiles(root: File, settings: Settings): Set<String> = buildSet {
        File(root, FileFolders.SKILLS).takeIf(File::isDirectory)?.walkTopDown()?.filter(File::isFile)?.forEach {
            add(it.relativeTo(root).invariantSeparatorsPath)
        }
        settings.displaySetting.chatCustomFontPath.takeIf(String::isNotBlank)?.let {
            require(it.startsWith(FileFolders.FONTS + "/"))
            check(inside(root, it).isFile) { "Missing custom font" }
            add(it)
        }
    }

    private suspend fun copyPayload(source: File, target: File) {
        currentCoroutineContext().ensureActive()
        check(source.isFile) { "Missing backup payload: $source" }
        if (target.exists()) {
            check(source.length() == target.length() && source.inputStream().use { left -> java.io.DataInputStream(target.inputStream()).use { right ->
                val a = ByteArray(8192); val b = ByteArray(8192)
                var same = true
                while (true) { val n = left.read(a); if (n < 0) break; right.readFully(b,0,n); if (!a.copyOf(n).contentEquals(b.copyOf(n))) { same = false; break } }
                same
            } }) { "Restore payload conflicts with retained enterprise content: $target" }
        } else {
            target.parentFile?.mkdirs()
            source.inputStream().use { input -> target.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            } }
        }
    }

    private fun strings(db: SupportSQLiteDatabase, sql: String): List<String> = buildList {
        db.query(sql).use { cursor -> while (cursor.moveToNext()) add(cursor.getString(0)) }
    }
    private fun requireEmpty(db: SupportSQLiteDatabase, sql: String, detail: String) =
        db.query(sql).use { check(!it.moveToFirst()) { detail } }
    private fun inside(root: File, path: String): File {
        val file = File(root, path).canonicalFile
        require(file.path.startsWith(root.canonicalPath + File.separator)) { "Backup payload escapes its directory" }
        require(file.relativeTo(root.canonicalFile).invariantSeparatorsPath == path) { "Backup payload path is not canonical" }
        return file
    }
}
