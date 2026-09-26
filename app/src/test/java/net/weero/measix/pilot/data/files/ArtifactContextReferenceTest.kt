package net.weero.measix.pilot.data.files

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.configuration.EnterpriseAuthority
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.db.entity.ArtifactReferenceType
import net.weero.measix.pilot.data.db.dao.ModelContextConflictException
import net.weero.measix.pilot.data.model.*
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.service.runtime.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
internal class ArtifactContextReferenceTest : ArtifactStoreLifecycleTestBase() {
    private fun repository() = ConversationRepository(database.conversationDao(), database.messageNodeDao(),
        database.favoriteDao(), database, mockk(relaxed = true), database.turnExecutionDao(),
        database.toolExecutionDao(), database.conversationModelContextDao(), store)

    private fun snapshot(): ConversationAggregateSnapshot {
        val user = MessageNode.of(UIMessage.user("question"))
        val owner = MessageNode.of(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("answer"))))
        return Conversation.ofId(Uuid.random()).copy(newConversation = false, messageNodes = listOf(user, owner)).toSnapshot()
    }

    private fun entry(snapshot: ConversationAggregateSnapshot, owned: OwnedArtifact, occurrence: Int = 0) =
        ConversationModelContextEntry(snapshot.nodes[1].id, snapshot.nodes[1].currentMessage.id,
            snapshot.nodes[0].id, snapshot.nodes[0].currentMessage.id,
            ConversationContextPayload(source = ConversationContextSource.System(emptyList()),
                body = ConversationContextBody.Artifact(owned.entity.id, owned.entity.relativePath)), occurrence = occurrence)

    private fun mutation(snapshot: ConversationAggregateSnapshot,
        insert: List<ConversationModelContextEntry> = emptyList(),
        delete: List<ConversationModelContextEntry> = emptyList(),
    ) = ConversationMutation(snapshot.conversationId, null, emptyList(), emptyList(), 1, emptyList(),
        indexForSearch = false, insertedModelContextEntries = insert, deletedModelContextEntries = delete)

    private suspend fun text(value: String, scope: ConfigurationScope = ConfigurationScope.Personal): OwnedArtifact {
        folders += FileFolders.UPLOAD
        return store.createText(scope, value, displayName = "context.txt")
    }

    @Test fun `context only commit preserves roots across message updates and fork until final entry deletion`() = runTest {
        val repo = repository()
        val original = snapshot()
        repo.insertConversationSnapshot(original)
        val owned = text("exact immutable context")
        val source = entry(original, owned)
        repo.applyMutation(mutation(original, insert = listOf(source)))
        store.publishUnpublished(owned)
        assertTrue(database.artifactReferenceDao().existsInConversation(owned.entity.id,
            original.conversationId.toString(), ArtifactReferenceType.CONTEXT.name))
        assertEquals("exact immutable context", store.readContextText(original.header.scope,
            source.payload.body as ConversationContextBody.Artifact))

        val changed = original.nodes[1].copy(messages = listOf(original.nodes[1].currentMessage.copy(
            parts = listOf(UIMessagePart.Text("edited answer")))))
        repo.applyMutation(mutation(original).copy(upsertedNodes = listOf(changed), upsertedNodeIndices = listOf(1)))
        assertTrue(database.artifactReferenceDao().existsByArtifactId(owned.entity.id))

        val fork = snapshot()
        val copied = entry(fork, owned)
        repo.insertConversationSnapshot(fork.copy(modelContextEntries = listOf(copied)))
        repo.applyMutation(mutation(original, delete = listOf(source)))
        assertFalse(database.artifactReferenceDao().existsInConversation(owned.entity.id,
            original.conversationId.toString(), ArtifactReferenceType.CONTEXT.name))
        assertTrue(database.artifactReferenceDao().existsInConversation(owned.entity.id,
            fork.conversationId.toString(), ArtifactReferenceType.CONTEXT.name))
        store.ensureReferenceProjection()
        assertTrue(store.collectGarbage(protectionWindowMillis = 0).isEmpty())

        repo.applyMutation(mutation(fork, delete = listOf(copied)))
        assertEquals(listOf(owned.entity.id), store.collectGarbage(protectionWindowMillis = 0).map { it.id })
        assertNull(database.artifactDao().getById(owned.entity.id))
        assertFalse(store.file(owned.entity).exists())
    }

    @Test fun `conflicting context commit rolls back reference replacement and keeps candidate ownership`() = runTest {
        val repo = repository()
        val original = snapshot()
        repo.insertConversationSnapshot(original)
        val first = text("committed")
        val source = entry(original, first)
        repo.applyMutation(mutation(original, insert = listOf(source)))
        store.publishUnpublished(first)
        val candidate = text("must not replace committed body")
        val conflict = entry(original, candidate)
        val failure = runCatching { repo.applyMutation(mutation(original, insert = listOf(conflict))) }.exceptionOrNull()
        assertTrue(failure.toString(), failure is ModelContextConflictException)
        assertEquals(source, repo.getConversationSnapshotById(original.conversationId)!!.modelContextEntries.single())
        assertTrue(database.artifactReferenceDao().existsByArtifactId(first.entity.id))
        assertFalse(database.artifactReferenceDao().existsByArtifactId(candidate.entity.id))
        assertTrue(store.discardUnpublished(candidate) is ArtifactDeleteResult.Completed)
        assertEquals("committed", store.readContextText(original.header.scope, source.payload.body as ConversationContextBody.Artifact))
    }

    @Test fun `context scope is validated before commit`() = runTest {
        val repo = repository()
        val original = snapshot()
        repo.insertConversationSnapshot(original)
        val foreign = text("enterprise only", ConfigurationScope.Enterprise(EnterpriseAuthority("other"), "user"))
        val source = entry(original, foreign)
        val failure = runCatching { repo.applyMutation(mutation(original, insert = listOf(source))) }.exceptionOrNull()
        assertTrue(failure.toString(), failure is ArtifactProjectionException)
        assertTrue(repo.getConversationSnapshotById(original.conversationId)!!.modelContextEntries.isEmpty())
        assertFalse(database.artifactReferenceDao().existsByArtifactId(foreign.entity.id))
        assertTrue(store.discardUnpublished(foreign) is ArtifactDeleteResult.Completed)
    }

    @Test fun `same path with another identity never satisfies saved context or recreates its reference`() = runTest {
        val repo = repository()
        val original = snapshot()
        repo.insertConversationSnapshot(original)
        val owned = text("replacement bytes")
        val wrongIdentity = entry(original, owned).let { it.copy(payload = it.payload.copy(
            body = ConversationContextBody.Artifact(owned.entity.id + 1000, owned.entity.relativePath))) }
        repo.applyMutation(mutation(original, insert = listOf(wrongIdentity)))
        store.ensureReferenceProjection()
        assertFalse(database.artifactReferenceDao().existsByArtifactId(owned.entity.id))
        val failure = runCatching { store.readContextText(original.header.scope,
            wrongIdentity.payload.body as ConversationContextBody.Artifact) }.exceptionOrNull()
        assertEquals("artifact_media_unavailable", failure?.message)
        assertTrue(store.discardUnpublished(owned) is ArtifactDeleteResult.Completed)
    }
}
