package net.weero.measix.pilot.benchmark

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.os.Trace
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ToolOutputPolicy
import me.rerere.ai.ui.*
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.ai.tools.ToolOutputStore
import net.weero.measix.pilot.data.ai.tools.ToolOutputReadResult
import net.weero.measix.pilot.data.ai.tools.ToolOutputGrepResult
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.db.*
import net.weero.measix.pilot.data.db.entity.ArtifactOrigin
import net.weero.measix.pilot.data.db.fts.MessageFtsManager
import net.weero.measix.pilot.data.files.*
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.toMessageNode
import net.weero.measix.pilot.data.repository.ConversationRepository
import kotlin.uuid.Uuid

/** Isolated fixture storage; measured operations always use the production Room and Artifact owners. */
internal class TurnPersistenceWorkloads(application: Context) : AutoCloseable {
    private val root = Files.createTempDirectory(application.cacheDir.toPath(), "turn-benchmark-").toFile()
    private val context = object : ContextWrapper(application) {
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        override fun getApplicationContext(): Context = this
    }

    fun migrateLargeRoom() {
        val file = File(root, "legacy.db")
        val schema = context.assets.open("net.weero.measix.pilot.data.db.AppDatabase/10.json").bufferedReader().use {
            Json.parseToJsonElement(it.readText()).jsonObject.getValue("database").jsonObject
        }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { legacy ->
            schema.getValue("entities").jsonArray.forEach { raw ->
                val entity = raw.jsonObject
                val table = entity.getValue("tableName").jsonPrimitive.content
                fun execute(template: String) = legacy.execSQL(template.replace("\${TABLE_NAME}", table))
                execute(entity.getValue("createSql").jsonPrimitive.content)
                entity["indices"]?.jsonArray?.forEach { execute(it.jsonObject.getValue("createSql").jsonPrimitive.content) }
            }
            schema["views"]?.jsonArray?.forEach { legacy.execSQL(it.jsonObject.getValue("createSql").jsonPrimitive.content) }
            schema.getValue("setupQueries").jsonArray.forEach { legacy.execSQL(it.jsonPrimitive.content) }
            legacy.execSQL("INSERT INTO ConversationEntity(id,title,create_at,update_at) VALUES('benchmark','history',1,1)")
            val output = "result ".repeat(8_192)
            legacy.beginTransaction()
            try {
                repeat(1_000) { index ->
                    val message = """[{"id":"${Uuid.random()}","role":"assistant","parts":[{"type":"tool","toolCallId":"call_$index","toolName":"read_file","input":"{}","output":[{"type":"text","text":"$output"}],"approvalState":{"type":"auto"}},{"type":"text","text":"Final answer $index"}]}]"""
                    legacy.execSQL("INSERT INTO message_node(id,conversation_id,node_index,messages,select_index) VALUES(?,?,?,?,0)",
                        arrayOf<Any>(Uuid.random().toString(), "benchmark", index, message))
                }
                legacy.setTransactionSuccessful()
            } finally { legacy.endTransaction() }
            legacy.version = 10
        }
        // Opening forces the same migration chain and schema validation as app startup and backup restore.
        val database = createAppDatabase(context, file.absolutePath)
        try {
            val migrated = measureWorkload("turn_room_migration_1000") { database.openHelper.writableDatabase }
            val migratedRows = migrated.query("SELECT COUNT(*) FROM message_node WHERE transcript_schema=3").use {
                check(it.moveToFirst())
                it.getInt(0).also { count -> check(count == 1_000) }
            }
            migrated.query("PRAGMA foreign_key_check").use { check(!it.moveToFirst()) }
            Trace.setCounter("turn_migration_rows", migratedRows.toLong())
        } finally { database.close() }
    }

    fun readAndGrepHundredMegabytes() = runBlocking {
        val source = File(root, "source.txt")
        val line = ("x".repeat(1_023) + "\n").toByteArray()
        val count = 100 * 1_024
        source.outputStream().buffered().use { stream ->
            repeat(count - 1) { stream.write(line) }
            stream.write(("needle" + "x".repeat(1_017) + "\n").toByteArray())
        }
        check(source.length() == 100L * 1_024 * 1_024)
        val database = createAppDatabase(context, File(root, "artifacts.db").absolutePath)
        val scope = AppScope()
        try {
            val artifacts = ArtifactStore(
                ArtifactPayloadStore(context), database.artifactDao(), database.artifactReferenceDao(),
                database.systemMetaDao(), database.conversationDao(), database.messageNodeDao(),
                ArtifactSettingsCoordinator(SettingsStore(context, scope)), RoomDatabaseTransactionRunner(database),
            )
            val owned = artifacts.copyFile(source, "text/plain", "tool-output.txt", FileFolders.TOOL_OUTPUTS, ArtifactOrigin.SYSTEM)
            val archive = ToolOutputArchive(owned.entity.id, ToolOutputArchiveRef(owned.entity.relativePath, "text/plain"), source.length(), count)
            fun completedStep(ordinal: Int, outcome: StepOutcome) =
                net.weero.measix.pilot.service.runtime.TurnTransition.openStep(ordinal).copy(
                    outcome = outcome, finishedAt = kotlin.time.Clock.System.now(),
                    modelResult = StepModelResult(
                        finishReason = if (outcome == StepOutcome.Continue) "tool_calls" else "stop",
                        usage = StepUsage(), providerRequestCount = 1,
                        timeToFirstOutputMillis = null, requestDurationMillis = null,
                        usageCompleteness = me.rerere.ai.core.UsageCompleteness.NONE, providerMetadata = null,
                    ),
                )
            val step = completedStep(0, StepOutcome.Continue)
            val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(step, UIMessagePart.Tool(
                localCallId = Uuid.random(), stepId = step.stepId, providerCallId = "call", toolName = "read_file", input = "{}",
                resultStatus = ToolResultStatus.COMPLETED,
                output = listOf(UIMessagePart.Text("[Archived tool result: ref=${archive.ref}]")),
                runtimeState = ToolRuntimeState(ToolOutputPolicy.ARCHIVABLE_TEXT, archive),
            ), completedStep(1, StepOutcome.Final), UIMessagePart.Text("Archived output is available.")))
            val conversation = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(message.toMessageNode()))
            val repository = ConversationRepository(
                database.conversationDao(), database.messageNodeDao(), database.favoriteDao(), database,
                MessageFtsManager(database), database.turnExecutionDao(), database.toolExecutionDao(),
                database.conversationModelContextDao(), artifacts,
            )
            repository.insertConversation(conversation)
            artifacts.publishAllUnpublished(listOf(owned))
            val store = ToolOutputStore(artifacts)
            // Both bounded APIs scan the archive stream; fixture IO and publication are outside these slices.
            val read = measureWorkload("turn_output_read_100mb") { store.read(conversation.id, archive.ref, count - 10, 10) }
            check(read is ToolOutputReadResult.Success && read.totalLines >= count && read.lines.size == 10)
            val grep = measureWorkload("turn_output_grep_100mb") { store.grep(conversation.id, archive.ref, "needle", false, 1, 10) }
            check(grep is ToolOutputGrepResult.Success && grep.matchCount == 1 && grep.blocks.isNotEmpty())
            Trace.setCounter("turn_output_input_bytes", source.length())
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
            database.close()
        }
    }

    override fun close() {
        check(root.name.startsWith("turn-benchmark-") && root.canonicalFile.parentFile == context.baseContext.cacheDir.canonicalFile)
        check(root.deleteRecursively())
    }
}
