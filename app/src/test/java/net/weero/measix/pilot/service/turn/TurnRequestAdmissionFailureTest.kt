package net.weero.measix.pilot.service.turn

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.rerere.ai.core.ModelRequestMessage
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import net.weero.measix.pilot.data.ai.request.RequestAssembler
import net.weero.measix.pilot.data.ai.request.RequestContextPlanner
import net.weero.measix.pilot.data.ai.tools.ToolCallRuntime
import net.weero.measix.pilot.data.ai.tools.ToolOutputCompactionPlanner
import net.weero.measix.pilot.data.configuration.ConfigurationResolver
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.UserSettingsDocument
import net.weero.measix.pilot.data.enterprise.EnterpriseState
import net.weero.measix.pilot.data.files.ArtifactReadLease
import net.weero.measix.pilot.data.files.ArtifactRetentionLease
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.model.*
import net.weero.measix.pilot.service.runtime.*
import net.weero.measix.pilot.test.testTurnContext
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import kotlin.uuid.Uuid

/** Request retry before Turn finalization; a terminalized Turn is never reopened by this fixture. */
class TurnRequestAdmissionFailureTest {
    @Test fun `admission command failure or rejection cancellation cannot reach provider or durable projection`() = runTest {
        for (failure in listOf(IllegalStateException("admission transaction rejected"), CancellationException("admission rejected"))) {
            val fixture = Fixture()
            val initial = fixture.snapshot
            fixture.admitFailure = failure
            val actual = failureOf { fixture.request() }
            assertEquals(failure::class, actual::class)
            assertEquals(failure.message, actual.message)
            assertEquals(initial, fixture.snapshot)
            assertEquals(1, fixture.admitCalls)
            assertEquals(0, fixture.requests.size)
            assertEquals(0, fixture.checkpoints)
            assertEquals(1, fixture.releases)
        }
    }

    @Test fun `cancellation while reading current state leaves no seal and does not send provider input`() = runTest {
        val fixture = Fixture()
        val initial = fixture.snapshot
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        fixture.beforeRead = { entered.complete(Unit); resume.await() }
        val request = async { fixture.request() }
        entered.await()
        request.cancelAndJoin()
        assertTrue(request.isCancelled)
        assertEquals(initial, fixture.snapshot)
        assertEquals(0, fixture.admitCalls)
        assertEquals(0, fixture.requests.size)
        assertEquals(0, fixture.checkpoints)
        assertEquals(1, fixture.releases)
    }

    @Test fun `failed provider leaves sealed input which retries unchanged and revoked model lease cannot send it`() = runTest {
        val fixture = Fixture()
        val failure = IOException("upstream disconnected", IllegalStateException("original transport detail"))
        fixture.providerFailure = failure
        val actual = failureOf { fixture.request() }
        assertTrue(actual is IOException)
        assertEquals(failure.message, actual.message)
        val causes = generateSequence(actual) { it.cause }.toList()
        assertTrue("Original transport exception remains in the cause chain", causes.any { it === failure })
        assertSame(failure.cause, causes.last())
        assertEquals("original transport detail", causes.last().message)
        val sealed = fixture.snapshot
        assertEquals(1, sealed.contextAdmissions.size)
        assertEquals(1, fixture.admitCalls)
        assertEquals(1, fixture.samples)
        val firstRequest = fixture.requests.single()
        fixture.currentMemory = "changed elsewhere after failed request"
        fixture.providerFailure = null
        assertEquals(StepExecutionResult.Final, fixture.request())
        assertEquals(firstRequest, fixture.requests.last())
        assertEquals(sealed, fixture.snapshot)
        assertEquals(1, fixture.admitCalls)
        assertEquals(1, fixture.samples)
        assertEquals(0, fixture.checkpoints)

        fixture.modelLease.release()
        val revoked = failureOf { fixture.request() }
        assertEquals("model_execution_lease_closed", revoked.message)
        assertEquals(2, fixture.requests.size)
        assertEquals(sealed, fixture.snapshot)
        assertEquals(1, fixture.samples)
        assertEquals(3, fixture.releases)
    }

    private suspend fun failureOf(action: suspend () -> Unit): Throwable {
        try { action() } catch (failure: Throwable) { return failure }
        error("Expected request failure")
    }

    @Test fun `sealed retry refuses a changed user anchor before another provider call`() = runTest {
        val fixture = Fixture()
        fixture.providerFailure = IOException("upstream disconnected")
        failureOf { fixture.request() }
        val old = fixture.snapshot.nodes.first()
        fixture.snapshot = fixture.snapshot.copy(nodes = listOf(old.copy(
            messages = old.messages + UIMessage.user("edited question"), selectIndex = 1)) + fixture.snapshot.nodes.drop(1))
        val changed = fixture.snapshot
        fixture.providerFailure = null
        val failure = failureOf { fixture.request() }
        assertTrue(failure.message.orEmpty().contains("admitted_request_application_content_changed"))
        assertEquals(1, fixture.requests.size)
        assertEquals(1, fixture.samples)
        assertEquals(changed, fixture.snapshot)
    }

    private class Fixture {
        val model = Model(modelId = "test")
        val setting = ProviderSetting.OpenAI(models = listOf(model))
        val assistant = Assistant(enableMemory = true, streamOutput = false)
        val settings = Settings(providers = listOf(setting), assistants = listOf(assistant))
        val android = mockk<Context>(relaxed = true)
        val artifacts = mockk<ArtifactStore>()
        val provider = mockk<Provider<ProviderSetting.OpenAI>>()
        val manager = mockk<ProviderManager>()
        val requests = mutableListOf<List<ModelRequestMessage>>()
        var currentMemory = "original state"
        var beforeRead: suspend () -> Unit = {}
        var admitFailure: Throwable? = null
        var providerFailure: Throwable? = null
        var admitCalls = 0
        var samples = 0
        var checkpoints = 0
        var releases = 0
        var snapshot = Conversation.ofId(Uuid.random(), assistant.id)
            .copy(messageNodes = listOf(MessageNode.of(UIMessage.user("question")))).toSnapshot()
        val start = TurnTransition.buildStartTurnCommand(snapshot, Uuid.random())
        val handle = TurnHandle(snapshot.conversationId, start.epoch, start.turnId, start.assistantMessageId)
        val modelLease = ModelExecutionLease { accept -> accept(ModelRequestTarget.Remote(setting)) }
        private val configuration = ConfigurationResolver.resolve(UserSettingsDocument.empty().withPersonalSettings(settings),
            ConfigurationScope.Personal, EnterpriseState.Loading)
        private val context = testTurnContext(settings, model, assistant).let { original -> original.copy(
            model = original.model.copy(requests = modelLease),
            disclosure = TurnDisclosureSource.capture(configuration, assistant, DisclosureNamespace(null, assistant.id),
                readConfiguration = { configuration }, readMemory = {
                    samples++
                    beforeRead()
                    listOf(AssistantMemory(1, currentMemory))
                })) }
        private val access = object : TurnRequestContextAccess {
            override suspend fun read() = TurnRequestHistory(snapshot, emptyMap())
            override suspend fun admit(entries: List<ConversationModelContextEntry>, admission: ConversationContextAdmission) {
                admitCalls++
                admitFailure?.let { throw it }
                snapshot = ConversationTransition.apply(snapshot, AdmitRequestContext(handle, entries, admission))
            }
        }
        private val runner = StepRunner(android, manager, RequestContextPlanner(), RequestAssembler(),
            ToolOutputCompactionPlanner(), mockk(relaxed = true), ToolCallRuntime(Json), artifacts)
        init {
            snapshot = ConversationTransition.apply(snapshot, start)
            every { manager.getProviderByType(setting) } returns provider
            coEvery { artifacts.retainForRequest(ConfigurationScope.Personal, any()) } answers {
                ArtifactReadLease(emptyMap(), { null }, ArtifactRetentionLease { releases++ })
            }
            coEvery { provider.generateText(setting, any(), any()) } coAnswers {
                requests += secondArg<List<ModelRequestMessage>>()
                providerFailure?.let { throw it }
                MessageChunk(id = "response", model = model.modelId, choices = listOf(UIMessageChoice(
                    index = 0, delta = null, message = UIMessage.assistant("done"), finishReason = "stop")))
            }
        }
        suspend fun request(): StepExecutionResult = runner.run(TurnRunState(TurnRunInputs(
            turnContext = context, handle = handle, messages = snapshot.currentMessages(), requestContext = access,
            assistantMessageId = handle.assistantMessageId,
            onCheckpoint = { checkpoints++; snapshot = ConversationTransition.apply(snapshot, it) },
            onStreamDelta = {}, onResult = { error("StepRunner must not finalize the Turn") },
        ), android))
    }
}
