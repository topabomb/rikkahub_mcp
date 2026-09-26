package net.weero.measix.pilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.StepOutcome
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.request.resolveUsesAt
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.db.fts.MessageFtsManager
import net.weero.measix.pilot.data.files.ArtifactReferenceDelta
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.model.*
import net.weero.measix.pilot.service.runtime.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Row/query budgets are deterministic; this test deliberately makes no wall-clock performance claim. */
@RunWith(AndroidJUnit4::class)
class ConversationContextQueryCostTest {
    @Test fun unchangedSealsKeepBodiesAndLightQueriesConstantWithLongHistory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val queries = Collections.synchronizedList(mutableListOf<ObservedQuery>())
        // Real Room tables/DAOs and command writes. FTS is outside these query paths.
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setQueryCallback(object : RoomDatabase.QueryCallback {
                override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                    queries += ObservedQuery(sqlQuery, bindArgs.toList())
                }
            }, java.util.concurrent.Executor { it.run() })
            .build()
        try {
            val repository = repository(database)
            val small = fixture(historyCount = 2, entryCount = 4, longBodies = false)
            var large = fixture(historyCount = 1000, entryCount = 32, longBodies = true)
            repository.insertConversationSnapshot(small)
            repository.insertConversationSnapshot(large)
            val dao = database.conversationModelContextDao()
            val ownerNode = large.nodes.last()
            val owner = ContextMessageLocator(ownerNode.id, ownerNode.currentMessage.id)
            val steps = ownerNode.currentMessage.parts.filterIsInstance<UIMessagePart.Step>().toMutableList()
            val handle = TurnHandle(large.conversationId, 1, Uuid.random(), owner.messageId)
            val originalBodies = large.modelContextEntries.map { it.payload }
            val originalUses = large.contextAdmissions.single().uses
            val window = large.contextAdmissions.single().windowStart
            val sealQueries = mutableListOf<ObservedQuery>()
            for (ordinal in 1..100) {
                val previous = large.nodes.last()
                val parts = previous.currentMessage.parts.map { part ->
                    if (part is UIMessagePart.Step && part.outcome == null)
                        part.copy(outcome = StepOutcome.Continue, finishedAt = part.startedAt) else part
                }
                val step = TurnTransition.openStep(ordinal)
                steps += step
                val advanced = previous.copy(messages = listOf(previous.currentMessage.copy(parts = parts + step)))
                val advance = ConversationTransition.plan(large,
                    ReplaceMessageTree(large.nodes.dropLast(1) + advanced), large.header.updateAt) as ConversationChange.Durable
                repository.applyMutation((advance.write as ConversationWrite.Mutate).mutation)
                large = advance.snapshot
                queries.clear()
                val admission = ConversationContextAdmission(owner, step.stepId, window, null, emptyList())
                val change = ConversationTransition.plan(large, AdmitRequestContext(handle, emptyList(), admission),
                    large.header.updateAt) as ConversationChange.Durable
                repository.applyMutation((change.write as ConversationWrite.Mutate).mutation)
                large = change.snapshot
                sealQueries += queries.toList()
            }
            val writes = sealQueries.filter { it.sql.trimStart().startsWith("INSERT", ignoreCase = true) }
            assertEquals(100, writes.count { it.sql.contains("conversation_context_admission") })
            assertTrue(writes.none { it.sql.contains("conversation_model_context") || it.sql.contains("conversation_context_use") })
            assertTrue(sealQueries.none { it.sql.contains("substr(payload", ignoreCase = true) })
            assertEquals(101, dao.getAdmissionsOfConversation(large.conversationId.toString()).size)
            assertEquals(32, dao.getEntryHeadersOfConversation(large.conversationId.toString()).size)
            assertEquals(32, dao.getUsesOfConversation(large.conversationId.toString()).size)
            val recovered = requireNotNull(repository.getConversationSnapshotById(large.conversationId))
            assertEquals(1000, recovered.nodes.size)
            assertEquals(originalBodies, recovered.modelContextEntries.map { it.payload })
            assertEquals(1, recovered.contextAdmissions.count { it.selection != null })
            assertEquals(originalUses, resolveUsesAt(recovered.contextAdmissions, owner, steps.last().stepId, steps).uses)

            suspend fun lightQueries(snapshot: ConversationAggregateSnapshot): List<ObservedQuery> {
                queries.clear()
                assertEquals(listOf(snapshot.conversationId), repository.getRecentConversationRecords(
                    ConfigurationScope.Personal, snapshot.header.assistantId).map { it.id })
                val headers = dao.getEntryHeadersOfConversation(snapshot.conversationId.toString())
                assertEquals(snapshot.modelContextEntries.size, headers.size)
                assertTrue(headers.all { it.payload.isEmpty() })
                assertEquals(snapshot.contextAdmissions.size, dao.getAdmissionsOfConversation(snapshot.conversationId.toString()).size)
                assertEquals(snapshot.modelContextEntries.size, dao.getUsesOfConversation(snapshot.conversationId.toString()).size)
                return queries.toList().filter { it.sql.trimStart().startsWith("SELECT", ignoreCase = true) &&
                    (it.sql.contains("FROM conversationentity", ignoreCase = true) ||
                        it.sql.contains("conversation_model_context") || it.sql.contains("conversation_context_")) }
            }
            val smallReads = lightQueries(small)
            val largeReads = lightQueries(large)
            assertEquals(4, smallReads.size)
            assertEquals(smallReads.size, largeReads.size)
            assertEquals(smallReads.map { it.sql }, largeReads.map { it.sql })
            largeReads.forEach { observed ->
                val sql = observed.sql.replace("'' AS payload", "", ignoreCase = true)
                assertFalse("Light query reads context body: $sql", Regex("\\bpayload\\b", RegexOption.IGNORE_CASE).containsMatchIn(sql))
                assertFalse("Light query reads transcript: $sql", sql.contains("messages", ignoreCase = true))
            }
            // Explain the actual SQL and bound arguments emitted by Room, not a hand-maintained query copy.
            fun explain(query: ObservedQuery): String = database.openHelper.readableDatabase
                .query("EXPLAIN QUERY PLAN ${query.sql}", query.args.toTypedArray()).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("detail"))) }.joinToString("\n")
                }
            val recentPlan = explain(largeReads[0])
            assertTrue(recentPlan, recentPlan.contains("index_ConversationEntity_scope_assistant_id_parent_conversation_id_is_pinned_update_at", ignoreCase = true))
            val headersPlan = explain(largeReads[1])
            assertTrue(headersPlan, headersPlan.contains("index_message_node_conversation_id_node_index"))
            assertTrue(headersPlan, headersPlan.contains("index_conversation_model_context_owner_node_id_owner_message_id_occurrence"))
            val admissionsPlan = explain(largeReads[2])
            assertTrue(admissionsPlan, admissionsPlan.contains("index_conversation_context_admission_owner_node_id_owner_message_id_step_id"))
            val usesPlan = explain(largeReads[3])
            assertTrue(usesPlan, usesPlan.contains("index_conversation_context_admission_owner_node_id_owner_message_id_step_id"))
            assertTrue(usesPlan, usesPlan.contains("sqlite_autoindex_conversation_context_use"))
            database.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally { database.close() }
    }

    private data class ObservedQuery(val sql: String, val args: List<Any?>)

    private fun fixture(historyCount: Int, entryCount: Int, longBodies: Boolean): ConversationAggregateSnapshot {
        val started = Instant.parse("2026-09-26T10:00:00Z")
        val history = (0 until historyCount - 2).map { index ->
            MessageNode.of(if (index % 2 == 0) UIMessage.user("history $index") else UIMessage(
                role = MessageRole.ASSISTANT, parts = listOf(
                    UIMessagePart.Step(Uuid.random(), 0, started, outcome = StepOutcome.Final), UIMessagePart.Text("answer $index"))))
        }
        val user = MessageNode.of(UIMessage.user("latest question"))
        val steps = listOf(UIMessagePart.Step(Uuid.random(), 0, started))
        val response = MessageNode.of(UIMessage(role = MessageRole.ASSISTANT, parts = steps))
        val owner = ContextMessageLocator(response.id, response.currentMessage.id)
        val anchor = ContextMessageLocator(user.id, user.currentMessage.id)
        val entries = (0 until entryCount).map { index ->
            val source = if (index == 0) ConversationContextSource.System(emptyList()) else
                ConversationContextSource.PromptRule(PromptInjection.ModeInjection(content = "rule $index"), emptyMap())
            // Three multi-byte original bodies each exceed a normal Android CursorWindow row.
            val body = if (longBodies && index in 1..3) "上下文".repeat(300_000) + index else "context $index"
            ConversationModelContextEntry(response.id, response.currentMessage.id, user.id, user.currentMessage.id,
                ConversationContextPayload(source = source, body = ConversationContextBody.Inline(body)),
                occurrence = index, stepId = steps.first().stepId)
        }
        val selection = TurnContextSelection(systemEntryId = entries.first().id, ruleEntryIds = entries.drop(1).map { it.id },
            timeReminderEnabled = false, timeZoneId = "UTC")
        val initial = ConversationContextAdmission(owner, steps.first().stepId, anchor, selection, entries.map { entry ->
            if (entry.payload.source is ConversationContextSource.System)
                ConversationContextUse(entry.id, MessageRole.SYSTEM, ContextPlacement.System)
            else ConversationContextUse(entry.id, MessageRole.USER, ContextPlacement.BeforeStep(steps.first().stepId))
        })
        return Conversation.ofId(Uuid.random(), me.rerere.common.configuration.ConfigurationReference.random())
            .copy(newConversation = false, messageNodes = history + user + response).toSnapshot()
            .copy(modelContextEntries = entries, contextAdmissions = listOf(initial))
    }

    private fun repository(database: AppDatabase): ConversationRepository {
        val artifacts = mockk<ArtifactStore>()
        coEvery { artifacts.withLifecycleLock<Any>(any()) } coAnswers { firstArg<suspend () -> Any>().invoke() }
        coEvery { artifacts.prepareReferenceDelta(any(), any(), any(), any()) } returns ArtifactReferenceDelta(emptyList(), emptyList(), emptyList())
        coJustRun { artifacts.applyReferenceDeltaInTransaction(any()) }
        return ConversationRepository(database.conversationDao(), database.messageNodeDao(), database.favoriteDao(), database,
            mockk<MessageFtsManager>(relaxed = true), database.turnExecutionDao(), database.toolExecutionDao(),
            database.conversationModelContextDao(), artifacts)
    }
}
