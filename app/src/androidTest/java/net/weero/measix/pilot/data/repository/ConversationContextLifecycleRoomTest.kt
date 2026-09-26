package net.weero.measix.pilot.data.repository

import android.content.Context
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
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.db.createAppDatabase
import net.weero.measix.pilot.data.db.fts.MessageFtsManager
import net.weero.measix.pilot.data.files.ArtifactReferenceDelta
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.model.*
import net.weero.measix.pilot.service.runtime.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Instant
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class ConversationContextLifecycleRoomTest {
    @Test fun executionHistoryDistinguishesTrackedRejectionFromForkWithoutCreatingExecutions() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "context-tool-history-${Uuid.random()}"
        val database = createAppDatabase(context, name)
        try {
            val repository = repository(database)
            val rejectedOwner = UIMessage.assistant("rejected before execution")
            val completedOwner = UIMessage.assistant("completed")
            val source = Conversation.ofId(Uuid.random()).copy(newConversation = false,
                messageNodes = listOf(MessageNode.of(rejectedOwner), MessageNode.of(completedOwner))).toSnapshot()
            repository.insertConversationSnapshot(source)
            val rejectedTurn = Uuid.random().toString()
            val executedTurn = Uuid.random().toString()
            for ((turn, owner) in listOf(rejectedTurn to rejectedOwner.id, executedTurn to completedOwner.id)) {
                database.turnExecutionDao().insert(net.weero.measix.pilot.data.db.entity.TurnExecutionEntity(
                    turn, source.conversationId.toString(), owner.toString(),
                    net.weero.measix.pilot.data.db.entity.TurnExecutionStatus.RUNNING, null, 1, 1))
            }
            val execution = Uuid.random().toString()
            val step = Uuid.random()
            val call = Uuid.random()
            database.toolExecutionDao().insertStartedIfTurnActive(execution, executedTurn, step.toString(), call.toString(),
                null, null, null, null, 1, 1)
            database.toolExecutionDao().transition(execution, executedTurn, call.toString(),
                listOf(net.weero.measix.pilot.data.db.entity.ToolExecutionStatus.STARTED),
                net.weero.measix.pilot.data.db.entity.ToolExecutionStatus.COMPLETED, null, null, null, null, 2)
            val facts = repository.getContextToolHistory(source.conversationId)
            assertEquals(setOf(rejectedOwner.id, completedOwner.id), facts.trackedAssistantMessageIds)
            assertEquals(mapOf(me.rerere.ai.core.ToolCallLocator(completedOwner.id, step, call) to
                net.weero.measix.pilot.data.db.entity.ToolExecutionStatus.COMPLETED), facts.outcomes)
            val forkId = Uuid.random()
            val fork = source.copy(conversationId = forkId, header = source.header.copy(id = forkId),
                nodes = source.nodes.map { it.copy(id = Uuid.random()) })
            repository.insertConversationTree(fork, emptyList())
            val copied = repository.getContextToolHistory(forkId)
            assertTrue(copied.trackedAssistantMessageIds.isEmpty())
            assertTrue(copied.outcomes.isEmpty())
            assertEquals(facts, repository.getContextToolHistory(source.conversationId))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
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

    @Test fun retiringCreationStepPreservesSelectionAndPlacementBaselineAcrossTransactionAndReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "context-lifecycle-${Uuid.random()}"
        val first = UIMessagePart.Step(Uuid.random(), 0, Instant.parse("2026-09-26T10:00:00Z"), outcome = StepOutcome.Continue)
        val second = first.copy(stepId = Uuid.random(), ordinal = 1, outcome = StepOutcome.Final)
        val user = MessageNode.of(UIMessage.user("question"))
        val assistant = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(first, UIMessagePart.Text("earlier"), second, UIMessagePart.Text("answer")))
        val node = MessageNode.of(assistant)
        val owner = ContextMessageLocator(node.id, assistant.id)
        val anchor = ContextMessageLocator(user.id, user.currentMessage.id)
        val system = ConversationModelContextEntry(node.id, assistant.id, user.id, user.currentMessage.id,
            ConversationContextPayload(source = ConversationContextSource.System(emptyList()), body = ConversationContextBody.Inline("stable system")), stepId = first.stepId)
        val rule = system.copy(id = contextEntryIdentity(node.id, assistant.id, 1), occurrence = 1,
            payload = ConversationContextPayload(source = ConversationContextSource.PromptRule(PromptInjection.ModeInjection(content = "rule"), emptyMap()),
                body = ConversationContextBody.Inline("original rule {{literal}}")))
        val selection = TurnContextSelection(systemEntryId = system.id, ruleEntryIds = listOf(rule.id), timeReminderEnabled = false, timeZoneId = "UTC")
        val firstAdmission = ConversationContextAdmission(owner, first.stepId, anchor, selection, listOf(
            ConversationContextUse(system.id, MessageRole.SYSTEM, ContextPlacement.System),
            ConversationContextUse(rule.id, MessageRole.USER, ContextPlacement.BeforeStep(first.stepId))))
        val later = ConversationContextAdmission(owner, second.stepId, anchor, null, emptyList())
        val snapshot = Conversation.ofId(Uuid.random()).copy(newConversation = false, messageNodes = listOf(user, node)).toSnapshot()
            .copy(modelContextEntries = listOf(system, rule), contextAdmissions = listOf(firstAdmission, later))
        val retainedNode = node.copy(messages = listOf(assistant.copy(parts = listOf(second.copy(ordinal = 0), UIMessagePart.Text("answer")))))
        val command = ReplaceMessageTree(listOf(user, retainedNode))
        val expected = ConversationTransition.apply(snapshot, command)
        try {
            val database = createAppDatabase(context, name)
            try {
                val repository = repository(database)
                repository.insertConversationSnapshot(snapshot)
                val change = ConversationTransition.plan(snapshot, command, snapshot.header.updateAt) as ConversationChange.Durable
                repository.applyMutation((change.write as ConversationWrite.Mutate).mutation)
                assertEquals(expected.modelContextEntries, repository.getConversationSnapshotById(snapshot.conversationId)!!.modelContextEntries)
                database.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            } finally { database.close() }
            val reopened = createAppDatabase(context, name)
            try {
                val actual = requireNotNull(repository(reopened).getConversationSnapshotById(snapshot.conversationId))
                assertEquals(expected.contextAdmissions, actual.contextAdmissions)
                assertEquals(listOf(system.payload, rule.payload), actual.modelContextEntries.map { it.payload })
                assertTrue(actual.modelContextEntries.none { it.id == system.id || it.id == rule.id })
                val resolved = resolveUsesAt(actual.contextAdmissions, owner, second.stepId,
                    retainedNode.currentMessage.parts.filterIsInstance<UIMessagePart.Step>())
                assertNotNull(resolved.selection)
                assertEquals(setOf(ContextPlacement.System, ContextPlacement.BeforeStep(second.stepId)), resolved.uses.map { it.placement }.toSet())
                assertEquals("answer", actual.currentMessages().last().parts.filterIsInstance<UIMessagePart.Text>().single().text)
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }
}
