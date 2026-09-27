package net.weero.measix.pilot.service

import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.model.*
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.service.runtime.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConversationContextQueryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val user = MessageNode.of(UIMessage.user("question"))
    private val assistant = MessageNode.of(UIMessage.assistant("answer"))
    private val entry = ConversationModelContextEntry(assistant.id, assistant.currentMessage.id, user.id, user.currentMessage.id,
        ConversationContextPayload(source = ConversationContextSource.Attachment(ContextMessageLocator(user.id, user.currentMessage.id),
            0, "original.txt", AttachmentContextInput.DOCUMENT_TEXT, "attachment:original"), body = ConversationContextBody.Artifact(42, "upload/context.txt")))
    private val original = Conversation.ofId(Uuid.random()).toSnapshot().copy(nodes = listOf(user, assistant), modelContextEntries = listOf(entry))
    private var snapshot = original
    private val repository = mockk<ConversationRepository>()
    private val registry = mockk<ConversationRuntimeRegistry>()
    private val artifacts = mockk<ArtifactStore>()
    private lateinit var lease: ConversationViewLease
    private suspend fun query(): ConversationQueryService {
        val sessions = EnterpriseSessionController(enterpriseTestStore(temporary.newFolder())).apply { recover() }
        lease = ConversationViewLease(original.conversationId, RealmAccess.Personal, sessions.selectionRevision.value) {}
        every { registry.findRuntime(any()) } returns null
        coEvery { repository.getConversationSnapshotById(any()) } answers { snapshot }
        return ConversationQueryService(repository, registry, mockk(), mockk(), mockk(), sessions,
            ApplicationRecoveryGate().apply { ready() }, mockk(), mockk(), artifacts)
    }

    @Test fun `discovery is lightweight and expanded body preserves original bytes and source`() = runTest {
        val query = query()
        val details = query.contextDetails(lease, original.conversationId, user.currentMessage.id)
        coVerify(exactly = 0) { artifacts.readContextText(any(), any()) }
        val text = "原文\r\n{{literal}} <untrusted>"
        coEvery { artifacts.readContextText(original.header.scope, ConversationContextBody.Artifact(42, "upload/context.txt")) } returns text
        val item = details.requests.single().items.single()
        val loaded = query.contextContent(lease, original.conversationId, user.currentMessage.id, null, item.key)
        assertEquals(text, loaded.text)
        assertTrue(loaded.source!!.contains("original.txt"))
    }

    @Test fun `closed lease and off branch message refuse body reads`() = runTest {
        val query = query()
        val item = query.contextDetails(lease, original.conversationId, user.currentMessage.id).requests.single().items.single()
        assertFailure<IllegalArgumentException> { query.contextContent(lease, original.conversationId, Uuid.random(), null, item.key) }
        lease.close()
        assertFailure<IllegalStateException> { query.contextContent(lease, original.conversationId, user.currentMessage.id, null, item.key) }
        coVerify(exactly = 0) { artifacts.readContextText(any(), any()) }
    }

    @Test fun `historical body remains authorized through its selected owner after editing the user anchor`() = runTest {
        val query = query()
        val editedUser = user.copy(messages = user.messages + UIMessage.user("edited question"), selectIndex = 1)
        snapshot = original.copy(nodes = listOf(editedUser, assistant))
        val text = "Saved attachment {{literal}}"
        coEvery { artifacts.readContextText(original.header.scope, ConversationContextBody.Artifact(42, "upload/context.txt")) } returns text
        val request = query.contextDetails(lease, original.conversationId, assistant.currentMessage.id).requests.single()
        assertNull(request.id)
        assertEquals(ConversationContextRequestState.HISTORICAL, request.state)
        val loaded = query.contextContent(lease, original.conversationId, assistant.currentMessage.id, null, request.items.single().key)
        assertEquals(text, loaded.text)
        val source = requireNotNull(loaded.source)
        assertTrue(source.contains(user.currentMessage.id.toString()))
        assertFalse(source.contains(editedUser.currentMessage.id.toString()))
        assertFailure<IllegalArgumentException> {
            query.contextDetails(lease, original.conversationId, user.currentMessage.id)
        }
        coVerify(exactly = 1) { artifacts.readContextText(any(), any()) }
    }

    @Test fun `preset and summary details read the exact immutable message variant without a payload copy`() = runTest {
        val query = query()
        val text = "Original summary\r\n{{literal}} <source>"
        val originalMessage = assistant.currentMessage.copy(parts = listOf(me.rerere.ai.ui.UIMessagePart.Text(text)))
        val other = UIMessage.assistant("Other variant")
        val node = assistant.copy(messages = listOf(originalMessage, other))
        for (source in listOf(ConversationContextSource.HistorySummary(null, "Original instruction"),
            ConversationContextSource.Preset(me.rerere.common.configuration.ConfigurationReference.random(), 0))) {
            snapshot = original.copy(nodes = listOf(user, node), modelContextEntries = listOf(entry.copy(
                anchorNodeId = node.id, anchorMessageId = originalMessage.id, payload =
                ConversationContextPayload(source = source, body = ConversationContextBody.MessageReference(
                    ContextMessageLocator(node.id, originalMessage.id))))))
            val request = query.contextDetails(lease, original.conversationId, user.currentMessage.id).requests.single()
            assertEquals(ConversationContextRequestState.SAVED_CONTENT, request.state)
            val item = request.items.single()
            val content = query.contextContent(lease, original.conversationId, user.currentMessage.id, null, item.key)
            assertEquals(text, content.text)
            assertFalse(content.text.contains("Other variant"))
        }
        coVerify(exactly = 0) { artifacts.readContextText(any(), any()) }
    }

    @Test fun `late body is rejected when page closes or selected variant changes during IO`() = runTest {
        var query = query()
        var item = query.contextDetails(lease, original.conversationId, user.currentMessage.id).requests.single().items.single()
        coEvery { artifacts.readContextText(any(), any()) } coAnswers { lease.close(); "late" }
        assertFailure<IllegalStateException> { query.contextContent(lease, original.conversationId, user.currentMessage.id, null, item.key) }
        query = query()
        item = query.contextDetails(lease, original.conversationId, user.currentMessage.id).requests.single().items.single()
        coEvery { artifacts.readContextText(any(), any()) } coAnswers {
            snapshot = original.copy(nodes = listOf(user, MessageNode.of(UIMessage.assistant("other variant"))))
            "late"
        }
        assertFailure<IllegalStateException> { query.contextContent(lease, original.conversationId, user.currentMessage.id, null, item.key) }
    }

    @Test fun `payload failures keep original diagnostic and cancellation propagates`() = runTest {
        val query = query()
        val item = query.contextDetails(lease, original.conversationId, user.currentMessage.id).requests.single().items.single()
        val failure = java.io.IOException("original path unavailable", IllegalStateException("payload missing"))
        coEvery { artifacts.readContextText(any(), any()) } throws failure
        try { query.contextContent(lease, original.conversationId, user.currentMessage.id, null, item.key); fail() }
        catch (error: java.io.IOException) { assertEquals(failure.message, error.message); assertEquals(failure.cause!!.message, error.cause!!.message) }
        coEvery { artifacts.readContextText(any(), any()) } throws CancellationException("closed")
        assertFailure<CancellationException> { query.contextContent(lease, original.conversationId, user.currentMessage.id, null, item.key) }
    }

    private suspend inline fun <reified T : Throwable> assertFailure(block: suspend () -> Unit) {
        try { block(); fail("Expected ${T::class.simpleName}") }
        catch (error: Throwable) { if (error !is T) throw error }
    }
}
