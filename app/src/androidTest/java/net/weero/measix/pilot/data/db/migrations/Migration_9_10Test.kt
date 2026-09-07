package net.weero.measix.pilot.data.db.migrations

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.db.dao.ModelContextConflictException
import net.weero.measix.pilot.data.db.entity.ConversationEntity
import net.weero.measix.pilot.data.db.entity.ConversationModelContextEntity
import net.weero.measix.pilot.data.db.entity.MessageNodeEntity
import net.weero.measix.pilot.data.db.fts.MessageFtsManager
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.AssistantMemory
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.service.ConversationDisclosureSnapshotService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

/**
 * Room migration 验收。
 *
 * v9 -> v10 必须纯 additive：历史行逐字保留、新表为空、fresh 与 migrated schema 同构，
 * 且 owner / anchor node 与整个 Conversation 的删除都能真正收口 context 行。
 * insert-once 语义在 Room 真实生成的 v10 schema 上验证：同 key + 同 row 幂等、
 * 同 key + 不同 row 冲突且绝不覆盖已提交历史。key 是 (owner_node_id, owner_message_id)：
 * Fork / Child clone 保留 message id、只重建 node id，唯一性必须以 owner node 为作用域。
 */
@RunWith(AndroidJUnit4::class)
class Migration_9_10Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    // ---- additive migration ----

    @Test
    fun migrationPreservesHistoryAndLeavesTheNewTableEmpty() {
        val name = "migration-v9-v10-data"
        val v9 = helper.createDatabase(name, 9)
        seedV9Rows(v9)
        val beforeTables = tableNames(v9)
        val beforeRows = v9Tables(v9).associateWith { rows(v9, "SELECT * FROM `$it` ORDER BY rowid") }
        v9.close()

        val migrated = helper.runMigrationsAndValidate(name, 10, true, Migration_9_10)
        assertEquals((beforeTables + CONTEXT_TABLE).sorted(), tableNames(migrated))
        assertEquals(
            beforeRows,
            v9Tables(migrated).associateWith { rows(migrated, "SELECT * FROM `$it` ORDER BY rowid") },
        )
        assertEquals(emptyList<List<String?>>(), rows(migrated, "SELECT * FROM " + CONTEXT_TABLE))
        assertTrue(rows(migrated, "PRAGMA foreign_key_check").isEmpty())
        migrated.close()
    }

    @Test
    fun historicalChainReachesFreshV10Schema() {
        val name = "migration-v1-v10-schema"
        helper.createDatabase(name, 1).close()
        val migrated = helper.runMigrationsAndValidate(
            name,
            10,
            true,
            Migration_1_2,
            Migration_2_3,
            Migration_3_4,
            Migration_4_5,
            Migration_5_6,
            Migration_6_7,
            Migration_7_8,
            Migration_8_9,
            Migration_9_10,
        )
        val fresh = helper.createDatabase("migration-v10-fresh-schema", 10)
        assertEquals(tableNames(fresh), tableNames(migrated))
        tableNames(fresh).forEach { table ->
            fun columns(db: SupportSQLiteDatabase) = rows(db, "PRAGMA table_info(`$table`)")
                .associate { column -> requireNotNull(column[1]) to column.drop(2) }
            assertEquals(columns(fresh), columns(migrated))
            assertEquals(indexInfo(fresh, table), indexInfo(migrated, table))
            assertEquals(foreignKeyInfo(fresh, table), foreignKeyInfo(migrated, table))
        }
        migrated.close()
        fresh.close()
    }

    @Test
    fun newTableDeclaresOwnerAnchorAndContentWithoutAConversationColumn() {
        val name = "migration-v9-v10-shape"
        helper.createDatabase(name, 9).close()
        val db = helper.runMigrationsAndValidate(name, 10, true, Migration_9_10)

        val columns = rows(db, "PRAGMA table_info(`" + CONTEXT_TABLE + "`)")
        assertEquals(
            listOf("owner_node_id", "owner_message_id", "anchor_node_id", "anchor_message_id", "content"),
            columns.map { it[1] },
        )
        assertTrue("every column must be NOT NULL", columns.all { it[3] == "1" })
        // 复合主键 (owner_node_id, owner_message_id)：一个 Assistant request variant 最多一份
        // 聚合 Snapshot，且唯一性以 owner node 为作用域（克隆保留 message id）。
        assertEquals(
            listOf("owner_node_id", "owner_message_id"),
            columns.filter { it[5] != "0" }.map { it[1] },
        )
        // 归属只由 owner node 推导，不重复保存一个可能冲突的 Conversation 事实源。
        assertTrue("conversation_id must not be duplicated here", "conversation_id" !in columns.map { it[1]!! })
        assertEquals(
            setOf("index_" + CONTEXT_TABLE + "_anchor_node_id"),
            // 只断言显式声明的索引；owner 前缀查找与唯一性都由主键 autoindex 承担。
            explicitIndexNames(db, CONTEXT_TABLE),
        )
        assertEquals(2, foreignKeyInfo(db, CONTEXT_TABLE).size)
        assertTrue(foreignKeyInfo(db, CONTEXT_TABLE).values.all { it == "message_node:CASCADE" })
        db.close()
    }
    // ---- lifecycle on the Room-generated schema ----

    @Test
    fun deletingOwnerNodeCascadesTheEntry() = runBlocking {
        withModelContextDatabase { db ->
            seedNodes(db)
            db.conversationModelContextDao().insertOnce(listOf(entry()))
            assertEquals(1, countEntries(db))
            deleteRow(db, "message_node", "node-assistant")
            assertEquals(0, countEntries(db))
        }
    }

    @Test
    fun deletingAnchorNodeCascadesTheEntry() = runBlocking {
        withModelContextDatabase { db ->
            seedNodes(db)
            db.conversationModelContextDao().insertOnce(listOf(entry()))
            assertEquals(1, countEntries(db))
            deleteRow(db, "message_node", "node-user")
            assertEquals(0, countEntries(db))
        }
    }

    @Test
    fun deletingTheConversationClosesEntriesThroughTheNodeChain() = runBlocking {
        withModelContextDatabase { db ->
            seedNodes(db)
            db.conversationModelContextDao().insertOnce(listOf(entry()))
            assertEquals(1, countEntries(db))
            deleteRow(db, "ConversationEntity", CONVERSATION_ID)
            assertEquals(0, countEntries(db))
            assertEquals(0, scalar(db, "SELECT COUNT(*) FROM message_node"))
        }
    }

    @Test
    fun aContextRowCannotPointAtANodeThatDoesNotExist() = runBlocking {
        withModelContextDatabase { db ->
            seedNodes(db)
            val failure = runCatching {
                db.conversationModelContextDao().insertOnce(listOf(entry(ownerNodeId = "node-never-created")))
            }.exceptionOrNull()
            assertTrue("expected an FK failure, got " + failure, failure != null)
            assertEquals(0, countEntries(db))
        }
    }

    @Test
    fun sameOwnerWithSameContentReplaysIdempotently() = runBlocking {
        withModelContextDatabase { db ->
            seedNodes(db)
            val dao = db.conversationModelContextDao()
            val content = canonicalContent("note A")
            dao.insertOnce(listOf(entry(content)))
            dao.insertOnce(listOf(entry(content)))
            dao.insertOnce(
                listOf(
                    entry(content),
                    entry(content, ownerNodeId = "node-assistant-later", ownerMessageId = LATER_ASSISTANT),
                ),
            )
            assertEquals(2, countEntries(db))
            assertEquals(content, onlyContent(db, OWNER_ASSISTANT))
        }
    }

    @Test
    fun sameOwnerWithDifferentContentFailsClosedAndNeverOverwrites() = runBlocking {
        withModelContextDatabase { db ->
            seedNodes(db)
            val dao = db.conversationModelContextDao()
            val committed = canonicalContent("note A")
            dao.insertOnce(listOf(entry(committed)))

            val failure = runCatching {
                dao.insertOnce(listOf(entry(canonicalContent("note B"))))
            }.exceptionOrNull()
            assertTrue(
                "expected ModelContextConflictException, got " + failure,
                failure is ModelContextConflictException,
            )
            assertEquals("history must never be rewritten", committed, onlyContent(db, OWNER_ASSISTANT))
            assertEquals(1, countEntries(db))
        }
    }

    @Test
    fun thePrimaryKeyRejectsASecondRowForTheSameOwnerNodeAndMessage() = runBlocking {
        withModelContextDatabase { db ->
            seedNodes(db)
            db.conversationModelContextDao().insertOnce(listOf(entry()))
            // 绕过 insertOnce 的幂等判定，证明唯一性来自数据库约束而不是 Kotlin 分支。
            val failure = runCatching {
                db.openHelper.writableDatabase.execSQL(
                    "INSERT INTO " + CONTEXT_TABLE + " (owner_node_id, owner_message_id, anchor_node_id, " +
                        "anchor_message_id, content) VALUES (?,?,?,?,?)",
                    arrayOf<Any?>(
                        "node-assistant",
                        OWNER_ASSISTANT,
                        "node-user",
                        ANCHOR_USER,
                        canonicalContent("note B"),
                    ),
                )
            }.exceptionOrNull()
            assertTrue("expected a UNIQUE failure, got " + failure, failure != null)
            assertEquals(1, countEntries(db))
        }
    }

    /**
     * Fork / Child clone 保留 message id、只重建 node id：同一 owner message id 在另一个
     * owner node（另一条克隆分支）下是合法的新 entry，绝不能被当作幂等重放静默跳过。
     */
    @Test
    fun theSameOwnerMessageIdUnderADifferentOwnerNodeIsANewEntry() = runBlocking {
        withModelContextDatabase { db ->
            seedNodes(db)
            val dao = db.conversationModelContextDao()
            val content = canonicalContent("note A")
            dao.insertOnce(listOf(entry(content)))
            dao.insertOnce(listOf(entry(content, ownerNodeId = "node-assistant-later")))
            assertEquals(2, countEntries(db))
            // 每条克隆分支各自拥有自己的 row：同 owner message id、不同 owner node。
            assertEquals(content, dao.findByOwner("node-assistant", OWNER_ASSISTANT)?.content)
            assertEquals(content, dao.findByOwner("node-assistant-later", OWNER_ASSISTANT)?.content)
        }
    }

    @Test
    fun storedContentIsExactlyWhatTheCanonicalRendererProduced() = runBlocking {
        withModelContextDatabase { db ->
            seedNodes(db)
            val content = ConversationDisclosureSnapshotService.render(
                ConversationDisclosureSnapshotService.Candidate(
                    assistant = Assistant(id = Uuid.random(), name = "Master", enableMemory = true),
                    allAssistants = emptyList(),
                    memories = listOf(
                        AssistantMemory(3, "用户偏好深色主题"),
                        AssistantMemory(8, "当前项目使用 Kotlin"),
                    ),
                ),
            )
            db.conversationModelContextDao().insertOnce(listOf(entry(content)))
            val stored = onlyContent(db, OWNER_ASSISTANT)
            assertEquals("the durable row holds exact canonical bytes", content, stored)
            assertEquals(1, ConversationDisclosureSnapshotService.requireCanonical(stored))
        }
    }

    /**
     * 装载查询是归属规则的唯一实现：Conversation 由 owner node 反推，顺序来自消息树。
     * 这里同时证明 anchor 指向别的 Conversation 不会把该条目算进那个 Conversation。
     */
    @Test
    fun entriesAreLoadedByOwnerNodeWithTreeOrderAndNeverByAnchor() = runBlocking {
        withModelContextDatabase { db ->
            seedNodes(db)
            db.conversationDao().insert(
                ConversationEntity(
                    id = OTHER_CONVERSATION_ID,
                    assistantId = "assistant-other",
                    title = "other",
                    createAt = 10L,
                    updateAt = 20L,
                    chatSuggestions = "[]",
                    isPinned = false,
                ),
            )
            // 插入顺序与树顺序刻意相反，用于证明装载顺序来自消息树。
            db.messageNodeDao().insertAll(
                listOf(
                    node("node-other-late", 2, "assistant-3", OTHER_CONVERSATION_ID),
                    node("node-other-early", 1, "assistant-4", OTHER_CONVERSATION_ID),
                    node("node-other-root", 0, "user-9", OTHER_CONVERSATION_ID),
                ),
            )

            db.conversationModelContextDao().insertOnce(
                listOf(
                    entry(ownerNodeId = "node-other-late", ownerMessageId = "assistant-3"),
                    entry(ownerNodeId = "node-other-early", ownerMessageId = "assistant-4"),
                    // conv-1 的条目，anchor 故意落在另一个 Conversation 的 node 上。
                    entry(ownerNodeId = "node-assistant", ownerMessageId = OWNER_ASSISTANT)
                        .copy(anchorNodeId = "node-other-root", anchorMessageId = "user-9"),
                ),
            )

            val dao = db.conversationModelContextDao()
            val owned = dao.getEntriesOfConversation(CONVERSATION_ID)
            assertEquals(
                "anchor must not move an entry into another conversation",
                listOf(OWNER_ASSISTANT),
                owned.map { it.ownerMessageId },
            )
            assertEquals(
                "load order follows the message tree, not insertion order",
                listOf("assistant-4", "assistant-3"),
                dao.getEntriesOfConversation(OTHER_CONVERSATION_ID).map { it.ownerMessageId },
            )
        }
    }

    /**
     * 要求 fail-closed 的是**装载**而不是 DAO 约束：迁移后的 v10 上，跨 Conversation 的
     * anchor 与角色错误的 owner 都必须让 Repository 装载抛错，而不是静默把 context 当作不存在。
     */
    @Test
    fun repositoryLoadFailsClosedOnCrossConversationAnchorAndWrongOwnerRole() = runBlocking {
        withMigratedToCurrentSchemaDatabase { db ->
            seedNodes(db)
            db.conversationDao().insert(
                ConversationEntity(
                    id = OTHER_CONVERSATION_ID,
                    assistantId = "assistant-other",
                    title = "other",
                    createAt = 10L,
                    updateAt = 20L,
                    chatSuggestions = "[]",
                    isPinned = false,
                ),
            )
            db.messageNodeDao().insertAll(
                listOf(node("node-elsewhere", 0, "user-elsewhere", OTHER_CONVERSATION_ID, role = "user")),
            )
            val crossAnchor = entry(ownerNodeId = "node-assistant", ownerMessageId = OWNER_ASSISTANT)
                .copy(anchorNodeId = "node-elsewhere", anchorMessageId = "user-elsewhere")
            db.conversationModelContextDao().insertOnce(listOf(crossAnchor))

            val anchorFailure = runCatching {
                repository(db).getConversationSnapshotById(Uuid.parse(CONVERSATION_ID))
            }.exceptionOrNull()
            assertNotNull("cross-conversation anchor must fail the load", anchorFailure)

            db.conversationModelContextDao().deleteByPrimaryKeys(listOf(crossAnchor))
            db.messageNodeDao().deleteByIds(listOf("node-assistant"))
            db.messageNodeDao().insertAll(listOf(node("node-assistant", 1, OWNER_ASSISTANT, role = "user")))
            db.conversationModelContextDao().insertOnce(listOf(entry()))

            val roleFailure = runCatching {
                repository(db).getConversationSnapshotById(Uuid.parse(CONVERSATION_ID))
            }.exceptionOrNull()
            assertNotNull("owner node holding a non-assistant message must fail the load", roleFailure)
        }
    }

    private fun repository(db: AppDatabase): ConversationRepository = ConversationRepository(
        conversationDAO = db.conversationDao(),
        messageNodeDAO = db.messageNodeDao(),
        favoriteDAO = db.favoriteDao(),
        database = db,
        messageFtsManager = mockk<MessageFtsManager>(relaxed = true),
        turnExecutionDAO = db.turnExecutionDao(),
        toolExecutionDAO = db.toolExecutionDao(),
        modelContextDAO = db.conversationModelContextDao(),
        artifactStore = mockk<ArtifactStore>(relaxed = true),
    )

    /** 真实 v9 → v10 → v11 迁移产物，再按生产前提启用 FK，用于验证当前 schema 上的装载语义。 */
    private suspend fun withMigratedToCurrentSchemaDatabase(block: suspend (AppDatabase) -> Unit) {
        val name = "migration-v9-v11-repository-load"
        context.deleteDatabase(name)
        helper.createDatabase(name, 9).close()
        helper.runMigrationsAndValidate(name, 10, true, Migration_9_10).close()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(Migration_9_10, Migration_10_11)
            .allowMainThreadQueries()
            .build()
        try {
            db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = ON")
            assertEquals(11, db.openHelper.readableDatabase.version)
            block(db)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    // ---- fixtures ----

    private suspend fun seedNodes(db: AppDatabase) {
        db.conversationDao().insert(
            ConversationEntity(
                id = CONVERSATION_ID,
                assistantId = "assistant-owner",
                title = "history",
                createAt = 10L,
                updateAt = 20L,
                chatSuggestions = "[]",
                isPinned = false,
            ),
        )
        db.messageNodeDao().insertAll(
            listOf(
                node("node-user", 0, ANCHOR_USER, role = "user"),
                node("node-assistant", 1, OWNER_ASSISTANT),
                node("node-assistant-later", 2, LATER_ASSISTANT),
            ),
        )
    }

    private fun node(
        id: String,
        index: Int,
        messageId: String,
        conversationId: String = CONVERSATION_ID,
        role: String = "assistant",
    ) = MessageNodeEntity(
        id = id,
        conversationId = conversationId,
        nodeIndex = index,
        // 变体内容不参与 context DAO 的约束；message 归属与角色校验由 Repository mapper 负责。
        messages = "[{\"id\":\"" + messageId + "\",\"role\":\"" + role + "\",\"parts\":[]}]",
        selectIndex = 0,
    )

    private fun entry(
        content: String = canonicalContent("note A"),
        ownerNodeId: String = "node-assistant",
        ownerMessageId: String = OWNER_ASSISTANT,
    ) = ConversationModelContextEntity(
        ownerMessageId = ownerMessageId,
        ownerNodeId = ownerNodeId,
        anchorNodeId = "node-user",
        anchorMessageId = ANCHOR_USER,
        content = content,
    )

    private fun canonicalContent(memory: String) = ConversationDisclosureSnapshotService.render(
        ConversationDisclosureSnapshotService.Candidate(
            assistant = Assistant(id = Uuid.random(), name = "Master", enableMemory = true),
            allAssistants = emptyList(),
            memories = listOf(AssistantMemory(1, memory)),
        ),
    )

    /** FK 约束按连接启用，与 DataSourceModule 的 onOpen 回调同一前提。 */
    private suspend fun withModelContextDatabase(block: suspend (AppDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = ON")
            block(db)
        } finally {
            db.close()
        }
    }

    private fun deleteRow(db: AppDatabase, table: String, id: String) {
        db.openHelper.writableDatabase.delete(table, "id = ?", arrayOf(id))
    }

    private fun countEntries(db: AppDatabase): Int = scalar(db, "SELECT COUNT(*) FROM " + CONTEXT_TABLE)

    private fun onlyContent(db: AppDatabase, ownerMessageId: String): String =
        db.openHelper.readableDatabase.query(
            "SELECT content FROM " + CONTEXT_TABLE + " WHERE owner_message_id = ?",
            arrayOf(ownerMessageId),
        ).use { cursor ->
            assertTrue("no entry owned by " + ownerMessageId, cursor.moveToFirst())
            val content = cursor.getString(0)
            assertTrue("more than one entry for " + ownerMessageId, !cursor.moveToNext())
            content
        }

    private fun scalar(db: AppDatabase, sql: String): Int =
        db.openHelper.readableDatabase.query(sql).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun seedV9Rows(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO ConversationEntity(id, assistant_id, title, create_at, update_at, suggestions, " +
                "is_pinned, custom_system_prompt, mode_injection_ids, workspace_cwd, tags, folder_id, " +
                "parent_conversation_id) VALUES ('conv-1','assistant-1','old title',10,20,'[]',0,'','[]','','','',NULL)",
        )
        db.execSQL(
            "INSERT INTO message_node(id, conversation_id, node_index, messages, select_index) VALUES " +
                "('node-user','conv-1',0,'[]',0), ('node-assistant','conv-1',1,'[]',0)",
        )
        db.execSQL(
            "INSERT INTO MemoryEntity(id, assistant_id, content) VALUES " +
                "(1,'assistant-1','first'), (2,'assistant-1','second')",
        )
    }

    private fun v9Tables(db: SupportSQLiteDatabase): List<String> = tableNames(db).filter { it != CONTEXT_TABLE }

    private fun tableNames(db: SupportSQLiteDatabase): List<String> =
        rows(
            db,
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
                "AND name != 'room_master_table' ORDER BY name",
        ).map { requireNotNull(it.single()) }

    private fun explicitIndexNames(db: SupportSQLiteDatabase, table: String): Set<String> =
        indexInfo(db, table).keys.map { it.substringBefore(":") }.filterNot { it.startsWith("sqlite_autoindex_") }.toSet()

    private fun indexInfo(db: SupportSQLiteDatabase, table: String): Map<String, List<List<String?>>> = buildMap {
        db.query("PRAGMA index_list(`" + table + "`)").use { cursor ->
            while (cursor.moveToNext()) {
                val indexName = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                val unique = cursor.getInt(cursor.getColumnIndexOrThrow("unique"))
                put(indexName + ":" + unique, rows(db, "PRAGMA index_info(`" + indexName + "`)").map { column ->
                    listOf(column[0], column[2])
                })
            }
        }
    }

    private fun foreignKeyInfo(db: SupportSQLiteDatabase, table: String): Map<String, String> = buildMap {
        db.query("PRAGMA foreign_key_list(`" + table + "`)").use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow("id")
            val targetColumn = cursor.getColumnIndexOrThrow("table")
            val fromColumn = cursor.getColumnIndexOrThrow("from")
            val onDelete = cursor.getColumnIndexOrThrow("on_delete")
            while (cursor.moveToNext()) {
                val key = cursor.getInt(idColumn).toString() + ":" + cursor.getString(fromColumn)
                put(key, cursor.getString(targetColumn) + ":" + cursor.getString(onDelete))
            }
        }
    }

    private fun rows(db: SupportSQLiteDatabase, sql: String): List<List<String?>> = buildList {
        db.query(sql).use { cursor ->
            while (cursor.moveToNext()) {
                add((0 until cursor.columnCount).map { column ->
                    if (cursor.isNull(column)) null else cursor.getString(column)
                })
            }
        }
    }

    private companion object {
        const val CONTEXT_TABLE = "conversation_model_context"
        const val CONVERSATION_ID = "conv-1"
        const val ANCHOR_USER = "user-1"
        const val OWNER_ASSISTANT = "assistant-1"
        const val LATER_ASSISTANT = "assistant-2"
        const val OTHER_CONVERSATION_ID = "conv-2"
    }
}
