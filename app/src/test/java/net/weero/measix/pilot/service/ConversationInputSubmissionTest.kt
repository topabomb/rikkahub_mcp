package net.weero.measix.pilot.service

import android.app.Application
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.configuration.ResolvedConfiguration
import net.weero.measix.pilot.data.datastore.ExecutionConfigurationSnapshot
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.service.runtime.*
import net.weero.measix.pilot.service.subassistant.SubAssistantLifecycle
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConversationInputSubmissionTest {
    @Test fun `edit resend receipt waits for one atomic edit transaction and excludes generation completion`() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        val entered = CompletableDeferred<Unit>()
        val commit = CompletableDeferred<Unit>()
        fixture.beforeCommit = { entered.complete(Unit); commit.await() }
        try {
            fixture.initialize()
            val before = fixture.runtime.durable
            val sending = async { fixture.resend() }
            entered.await()

            assertFalse(sending.isCompleted)
            assertSame(before, fixture.runtime.durable)
            assertTrue(fixture.writes.isEmpty())
            commit.complete(Unit)
            val receipt = requireNotNull(sending.await())

            assertEquals(fixture.runtime.id, receipt.conversationId)
            assertEquals(receipt.userMessageId, fixture.runtime.durable.nodes.single().currentMessage.id)
            assertEquals(listOf(fixture.original.currentMessage, fixture.runtime.durable.nodes.single().currentMessage),
                fixture.runtime.durable.nodes.single().messages)
            val mutation = (fixture.writes.single() as ConversationWrite.Mutate).mutation
            assertEquals(listOf(fixture.original.id), mutation.upsertedNodes.map { it.id })
            assertEquals(listOf(fixture.trailing.id), mutation.deletedNodeIds)
            assertFalse(requireNotNull(fixture.runtime.currentWorker()).isCompleted)
            coVerify(exactly = 0) { fixture.draft.returnToDraft(fixture.submission) }
        } finally { fixture.close() }
    }

    @Test fun `preprocessing or transaction rejection returns the attachment owner before the failure receipt`() = runTest {
        for (preparationFailure in listOf(true, false)) {
            val fixture = Fixture(StandardTestDispatcher(testScheduler))
            val failure = IOException(if (preparationFailure) "configuration read failed" else "atomic edit rejected")
            if (preparationFailure) fixture.beforeRead = { throw failure }
            else fixture.beforeCommit = { throw failure }
            try {
                fixture.initialize()
                val before = fixture.runtime.durable

                assertNull(fixture.resend())
                assertSame(before, fixture.runtime.durable)
                assertEquals(listOf(fixture.original, fixture.trailing), fixture.runtime.durable.nodes)
                assertTrue(fixture.writes.isEmpty())
                coVerify(exactly = 1) { fixture.draft.returnToDraft(fixture.submission) }
                val reported = fixture.errors.errors.value.single()
                assertEquals("Message send failed", reported.title)
                assertEquals("IOException: ${failure.message}", reported.detail.lineSequence().first())
                if (!preparationFailure) assertTrue(reported.detail.contains("Caused by: IOException: ${failure.message}"))
                assertEquals(fixture.runtime.id, reported.conversationId)
            } finally { fixture.close() }
        }
    }

    @Test fun `accepted edit cancellation before commit returns ownership without reporting failure`() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        val entered = CompletableDeferred<Unit>()
        fixture.beforeRead = { entered.complete(Unit); awaitCancellation() }
        try {
            fixture.initialize()
            val before = fixture.runtime.durable
            val sending = async { fixture.resend() }
            entered.await()
            requireNotNull(fixture.runtime.currentWorker()).cancel(CancellationException("user stopped preparing edit"))

            assertNull(sending.await())
            assertSame(before, fixture.runtime.durable)
            coVerify(exactly = 1) { fixture.draft.returnToDraft(fixture.submission) }
            assertTrue(fixture.errors.errors.value.isEmpty())
        } finally { fixture.close() }
    }

    @Test fun `cancellation after committed edit returns success and cannot reclaim committed attachments`() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        try {
            fixture.initialize()
            fixture.beforeCommit = {
                requireNotNull(fixture.runtime.currentWorker()).cancel(CancellationException("cancel after transaction"))
            }

            val receipt = requireNotNull(fixture.resend())
            runCurrent()

            assertEquals(receipt.userMessageId, fixture.runtime.durable.nodes.single().currentMessage.id)
            assertEquals(1, fixture.writes.size)
            coVerify(exactly = 0) { fixture.draft.returnToDraft(fixture.submission) }
            assertTrue(fixture.errors.errors.value.isEmpty())
        } finally { fixture.close() }
    }

    private class Fixture(dispatcher: TestDispatcher) {
        // JVM resource loading is outside this fixture; keep the request's diagnostic path executable.
        private val application = mockk<Application> {
            every { getString(R.string.error_title_send_message) } returns "Message send failed"
        }
        private val appScope = AppScope(dispatcher)
        private val assistant = Assistant()
        val original = MessageNode.of(UIMessage.user("original question"))
        val trailing = MessageNode.of(UIMessage.assistant("original answer"))
        private val conversation = Conversation.ofId(Uuid.random(), assistant.id)
            .copy(messageNodes = listOf(original, trailing))
        private val repository = mockk<ConversationRepository>()
        private val locks = ConversationOperationLocks()
        private val gate = ApplicationRecoveryGate().apply { ready() }
        private val registry = ConversationRuntimeRegistry(appScope, repository, locks)
        private val commands = ConversationCommandCoordinator(registry, repository, gate, locks)
        private val sessions = mockk<EnterpriseSessionController>()
        private val models = mockk<ModelExecutionService>()
        private val lifecycle = mockk<SubAssistantLifecycle>()
        val errors = ChatErrorStore()
        val draft = mockk<ArtifactDraftScope>(relaxed = true)
        val submission = mockk<ArtifactSubmission>(relaxed = true)
        val writes = mutableListOf<ConversationWrite>()
        var beforeRead: suspend () -> Unit = {}
        var beforeCommit: suspend () -> Unit = {}
        lateinit var runtime: ConversationRuntime
        private lateinit var retained: ConversationRuntimeLease
        private lateinit var view: ConversationViewLease
        private lateinit var turns: ConversationTurnService

        suspend fun initialize() {
            coEvery { repository.getChildConversationIds(any()) } returns emptyList()
            coEvery { repository.commit(any()) } coAnswers {
                beforeCommit()
                writes += firstArg<ConversationWrite>()
                true
            }
            coEvery { sessions.withSelectedRealmSelection<Any?>(any(), any()) } coAnswers {
                secondArg<suspend () -> Any?>()()
            }
            coEvery { sessions.withRealmAccess<Any?>(any(), any()) } coAnswers {
                secondArg<suspend () -> Any?>()()
            }
            val configuration = mockk<ResolvedConfiguration>()
            every { configuration.assistants } returns mapOf(assistant.id to assistant)
            coEvery { models.read(any()) } coAnswers {
                beforeRead()
                ExecutionConfigurationSnapshot(Settings.dummy(), configuration, "fixture")
            }
            coEvery { lifecycle.requireClosedRunsBeforeTreeMutation(any()) } coAnswers { firstArg<ConversationAggregateSnapshot>() }
            // Hold the accepted worker after its edit commits: the receipt must not wait for START or Provider IO.
            coEvery { lifecycle.applyRetentionAfterTreeMutation(any()) } coAnswers { awaitCancellation() }
            coEvery { draft.claimSubmission(any(), any()) } returns submission
            runtime = registry.registerRuntime(conversation)
            retained = registry.acquireRegisteredRuntime(runtime.id, runtime)
            view = ConversationViewLease(runtime.id, RealmAccess.Personal, 0) {}
            turns = ConversationTurnService(application, appScope, mockk(relaxed = true), mockk<SettingsStore>(),
                models, mockk(), mockk(), sessions, mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(),
                mockk(relaxed = true), lifecycle, registry, commands, gate, errors, mockk(relaxed = true), mockk(),
                ConversationTitleCoordinator())
        }

        suspend fun resend() = turns.editAndResend(view.commandTarget, original.currentMessage.id,
            listOf(UIMessagePart.Text("replacement question")), draft)

        suspend fun close() {
            if (::view.isInitialized) view.close()
            if (::retained.isInitialized) retained.close()
            appScope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }
}
