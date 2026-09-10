package net.weero.measix.pilot.service

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.spyk
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import me.rerere.ai.provider.*
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.datastore.*
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.service.runtime.*
import net.weero.measix.pilot.service.subassistant.SubAssistantLifecycle
import net.weero.measix.pilot.service.turn.TurnFinalizer
import net.weero.measix.pilot.utils.JsonInstant
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
class AuxiliaryGenerationOwnershipTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `cancelled manual summary waits for provider cleanup and never replaces history`() = runTest {
        fixture { f ->
            val initial = f.runtime.durable
            val operation = launch { f.application.compress(f.page.commandTarget, "", 100, 0) }
            f.started.await()
            operation.cancel()
            runCurrent()
            assertFalse(operation.isCompleted)
            assertTrue(f.runtime.hasAuxiliaryWork)
            f.reply.complete("late summary")
            operation.join()
            assertEquals(initial, f.runtime.durable)
            assertFalse(f.runtime.hasAuxiliaryWork)
            coVerify(exactly = 0) { f.repository.commit(any()) }
        }
    }

    @Test fun `title and suggestion reject replies from a replaced enterprise session`() = runTest {
        fixture { f ->
            val title = f.effects.launchTitle(f.runtime, f.page.access, true)
            val suggestion = f.effects.launchSuggestion(f.runtime, f.page.access)
            runCurrent()
            val exit = f.sessions.beginInvalidation(f.page.access as RealmAccess.Enterprise, EnterpriseExitReason.AUTHORIZATION_REVOKED)
            f.reply.complete("obsolete result")
            joinAll(title, suggestion)
            f.sessions.finishExit(exit)
            f.sessions.enrollFixture(exampleEnterprisePackage())
            assertTrue(title.isCancelled)
            assertTrue(suggestion.isCancelled)
            assertEquals("", f.runtime.durable.header.title)
            assertTrue(f.runtime.durable.header.chatSuggestions.isEmpty())
        }
    }

    @Test fun `selection switch preserves original session title but manual title wins its late reply`() = runTest {
        fixture { f ->
            val title = f.effects.launchTitle(f.runtime, f.page.access, true)
            f.started.await()
            f.application.updateTitle(f.page.commandTarget, "manual")
            f.sessions.selectPersonalFixture()
            f.reply.complete("generated")
            title.await()
            assertEquals("manual", f.runtime.durable.header.title)
        }
        fixture { f ->
            val title = f.effects.launchTitle(f.runtime, f.page.access, true)
            f.started.await()
            f.sessions.selectPersonalFixture()
            f.reply.complete("generated")
            title.await()
            assertEquals("generated", f.runtime.durable.header.title)
        }
    }

    @Test fun `summary rejects a tree edited while provider was running`() = runTest {
        fixture { f ->
            val summary = async { f.application.compress(f.page.commandTarget, "", 100, 0) }
            f.started.await()
            f.coordinator.executeOrThrow(f.runtime.id, AppendUserMessage(UIMessage.user("new input")))
            val edited = f.runtime.durable
            f.reply.complete("stale summary")
            assertTrue(summary.await().isFailure)
            assertEquals(edited, f.runtime.durable)
        }
    }

    @Test fun `cancellation after summary commit admission still publishes the complete committed snapshot`() = runTest {
        fixture { f ->
            val operation = launch { f.application.compress(f.page.commandTarget, "", 100, 0) }
            f.started.await()
            f.onCommit = { write -> if (write is ConversationWrite.MutateTree) operation.cancel() }
            f.reply.complete("committed summary")
            operation.join()
            assertTrue(operation.isCancelled)
            assertEquals("committed summary", f.runtime.durable.currentMessages().single().toText())
            assertTrue(f.runtime.durable.header.chatSuggestions.isEmpty())
            assertFalse(f.runtime.hasAuxiliaryWork)
        }
    }

    @Test fun `assistant generation cancellation times out without losing auxiliary cleanup ownership`() = runTest {
        fixture { f ->
            val title = f.effects.launchTitle(f.runtime, f.page.access, true)
            f.started.await()
            val stopped = async { withTimeoutOrNull(100) {
                f.application.cancelGenerationsForAssistant(DEFAULT_ASSISTANT_ID, "assistant_removed")
                true
            } }
            advanceTimeBy(100)
            runCurrent()
            assertNull(stopped.await())
            assertTrue(f.runtime.hasAuxiliaryWork)
            assertFalse(title.isCompleted)
            coVerify(exactly = 0) { f.repository.deleteConversation(any()) }
            f.reply.complete("late title")
            f.application.cancelGenerationsForAssistant(DEFAULT_ASSISTANT_ID, "assistant_removed")
            assertFalse(f.runtime.hasAuxiliaryWork)
            assertEquals("", f.runtime.durable.header.title)
        }
    }

    @Test fun `assistant cleanup retains completed auxiliary owner and retries only failed releases`() = runTest {
        fixture { f ->
            val worker = Job()
            var attempts = 0
            var releasedSibling = 0
            val failure = IllegalStateException("release unavailable")
            val failed = ModelExecutionLease(releaseOwner = { if (++attempts == 1) throw failure }) { error("unused") }
            val sibling = ModelExecutionLease(releaseOwner = { releasedSibling++ }) { error("unused") }
            f.runtime.registerAuxiliaryWorker(f.page.access, worker)
            f.runtime.bindAuxiliaryModelExecution(f.page.access, worker, DEFAULT_ASSISTANT_ID, failed)
            f.runtime.bindAuxiliaryModelExecution(f.page.access, worker, DEFAULT_ASSISTANT_ID, sibling)
            worker.complete()
            assertTrue(f.runtime.hasAuxiliaryWork)
            try { f.application.cancelGenerationsForAssistant(DEFAULT_ASSISTANT_ID, "assistant_removed"); fail("cleanup failure lost") }
            catch (error: IllegalStateException) { assertEquals(failure.message, error.message) }
            assertTrue(f.runtime.hasAuxiliaryWork)
            assertEquals(1, releasedSibling)
            f.application.cancelGenerationsForAssistant(DEFAULT_ASSISTANT_ID, "assistant_removed")
            assertEquals(2, attempts)
            assertEquals(1, releasedSibling)
            assertFalse(f.runtime.hasAuxiliaryWork)
        }
    }

    @Test fun `assistant cleanup follows original auxiliary identity after moving the conversation`() = runTest {
        fixture { f ->
            val title = f.effects.launchTitle(f.runtime, f.page.access, true)
            f.started.await()
            f.coordinator.executeOrThrow(f.runtime.id, MoveToAssistant(me.rerere.common.configuration.ConfigurationReference.random()))
            val newerWorker = Job()
            val newerTurn = Uuid.random()
            f.runtime.installTurnWorker(newerTurn, newerWorker)
            try {
                val stopped = async { f.application.cancelGenerationsForAssistant(DEFAULT_ASSISTANT_ID, "assistant_removed") }
                runCurrent()
                assertFalse(stopped.isCompleted)
                assertTrue(newerWorker.isActive)
                f.reply.complete("obsolete title")
                stopped.await()
                assertTrue(title.isCancelled)
                assertEquals("", f.runtime.durable.header.title)
                assertFalse(f.runtime.hasAuxiliaryWork)
                assertTrue(newerWorker.isActive)
            } finally {
                newerWorker.cancel()
                f.runtime.releaseTurnWorker(newerTurn, newerWorker, false)
            }
        }
    }

    @Test fun `summary releases model resources before committing replacement history`() = runTest {
        fixture { f ->
            val initial = f.runtime.durable
            var releases = 0
            coEvery { f.models.captureAuxiliary(any(), any(), any(), any(), any()) } coAnswers {
                val captured = f.actualModels.captureAuxiliary(firstArg(), secondArg(), thirdArg(), arg(3), arg(4))
                val release = ModelExecutionLease(releaseOwner = {
                    if (++releases == 1) throw IllegalStateException("release unavailable")
                }) { error("unused") }
                secondArg<ConversationRuntime>().bindAuxiliaryModelExecution(firstArg(), thirdArg(), arg(3), release)
                captured
            }
            val summary = async { f.application.compress(f.page.commandTarget, "", 100, 0) }
            f.started.await()
            f.reply.complete("replacement summary")
            assertTrue(summary.await().isFailure)
            assertEquals(initial, f.runtime.durable)
            assertEquals(2, releases)
            assertFalse(f.runtime.hasAuxiliaryWork)
            coVerify(exactly = 0) { f.repository.commit(any()) }
        }
    }

    @Test fun `late suggestion capture cannot clear the new assistants suggestions`() = runTest {
        fixture { f ->
            val captured = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            coEvery { f.models.captureAuxiliary(any(), any(), any(), any(), any()) } coAnswers {
                val result = f.actualModels.captureAuxiliary(firstArg(), secondArg(), thirdArg(), arg(3), arg(4))
                captured.complete(Unit)
                resume.await()
                result
            }
            val suggestion = f.effects.launchSuggestion(f.runtime, f.page.access)
            captured.await()
            f.coordinator.executeOrThrow(f.runtime.id, MoveToAssistant(me.rerere.common.configuration.ConfigurationReference.random()))
            f.coordinator.executeOrThrow(f.runtime.id, UpdateHeader(suggestions = listOf("new assistant suggestion")))
            resume.complete(Unit)
            suggestion.await()
            assertEquals(listOf("new assistant suggestion"), f.runtime.durable.header.chatSuggestions)
            coVerify(exactly = 0) { f.provider.generateText(any(), any(), any()) }
        }
    }

    @Test fun `local auxiliary requests commit title suggestions and an explicit simulated summary through original owners`() = runTest {
        fixture(local = true) { f ->
            f.effects.launchTitle(f.runtime, f.page.access, true).await()
            val title = f.runtime.durable.header.title
            assertTrue(title.isNotBlank() && title.length <= 10)
            assertFalse(title.contains("已收到"))
            f.effects.launchSuggestion(f.runtime, f.page.access).await()
            val suggestions = f.runtime.durable.header.chatSuggestions
            assertTrue(suggestions.size in 3..5)
            assertTrue(suggestions.all { it.isNotBlank() && it.length <= 10 })
            f.coordinator.executeOrThrow(f.runtime.id, AppendUserMessage(UIMessage.user("long context "+ "x".repeat(1000))))
            assertTrue(f.application.compress(f.page.commandTarget, "", 100, 0).isSuccess)
            val summary = f.runtime.durable.currentMessages().single().toText()
            assertTrue(summary.contains("本地模拟摘要"))
            assertTrue(summary.contains("original"))
            assertTrue(summary.contains("其余输入已省略"))
            assertTrue(summary.length < 600)
            assertFalse(summary.contains("You are a conversation compression assistant"))
            assertEquals(title, f.runtime.durable.header.title)
            assertTrue(f.runtime.durable.header.chatSuggestions.isEmpty())
            assertFalse(f.runtime.hasAuxiliaryWork)
            coVerify(exactly = 0) { f.provider.generateText(any(), any(), any()) }
        }
    }

    private suspend fun TestScope.fixture(local: Boolean = false, block: suspend (Fixture) -> Unit) {
        val f = Fixture(this, local)
        try { f.initialize(); block(f) }
        finally { f.reply.complete("cleanup"); f.appScope.cancel(); f.appScope.coroutineContext[Job]?.join() }
    }

    private inner class Fixture(test: TestScope, local: Boolean) {
        val appScope = AppScope(StandardTestDispatcher(test.testScheduler))
        val sessions = EnterpriseSessionController(EnterpriseAppliedStore(temporary.newFolder()))
        val repository = mockk<ConversationRepository>()
        val settings = mockk<SettingsStore>()
        val artifacts = mockk<ArtifactStore>()
        val locks = ConversationOperationLocks()
        val registry = ConversationRuntimeRegistry(appScope, repository, locks)
        val gate = ApplicationRecoveryGate().apply { ready() }
        val coordinator = ConversationCommandCoordinator(registry, repository, gate, locks)
        val titles = ConversationTitleCoordinator()
        val provider = mockk<Provider<ProviderSetting>>()
        val manager = mockk<ProviderManager>()
        val context = mockk<Context>()
        val started = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<String>()
        var onCommit: (ConversationWrite) -> Unit = {}
        val actualModels = ModelExecutionService(settings, sessions, gate, manager)
        val models = spyk(actualModels)
        val effects = GenerationSideEffects(context, appScope, models, manager, registry,
            coordinator, mockk(), JsonInstant, ChatErrorStore(), titles, sessions)
        val application = ConversationApplicationService(settings, repository, mockk(), registry, coordinator, gate,
            SubAssistantLifecycle(repository, registry, coordinator, JsonInstant), effects, artifacts, mockk(),
            TurnFinalizer(repository, registry, coordinator, JsonInstant), JsonInstant, mockk(), titles, sessions, mockk())
        val initial = Conversation.ofId(Uuid.random(), DEFAULT_ASSISTANT_ID)
            .copy(scope = exampleEnterprisePackage().identity.scope)
            .updateCurrentMessages(listOf(UIMessage.user("original"))).toSnapshot()
        lateinit var runtime: ConversationRuntime
        lateinit var page: ConversationViewLease

        init {
            val model = Model(modelId = "test")
            val configuration = Settings(providers = listOf(ProviderSetting.OpenAI(models = listOf(model))),
                chatModelId = model.id, titleModelId = model.id, suggestionModelId = model.id,
                compressModelId = model.id, enableSuggestion = true).let {
                if (local) it.copy(titlePrompt = "custom 查看企业公告 {content}", suggestionPrompt = "custom 查看企业公告 {content}") else it
            }
            every { settings.effectiveSettings } returns MutableStateFlow(EffectiveSettingsSnapshot(
                configuration, SettingsAccessIndex(), 0, ManagedConfigurationState.ABSENT))
            net.weero.measix.pilot.test.installExecutionConfigurationFixture(settings)
            coEvery { settings.withExecutionConfiguration<Any?>(any(), any(), any()) } coAnswers {
                val scope = firstArg<net.weero.measix.pilot.data.configuration.ConfigurationScope>()
                val document = UserSettingsDocument.empty().withPersonalSettings(configuration).let { document ->
                    document.copy(preferences = document.preferences.withSelections(scope,
                        if (local) ResourceSelections() else document.preferences.forScope(net.weero.measix.pilot.data.configuration.ConfigurationScope.Personal)))
                }
                thirdArg<suspend (ExecutionConfigurationSnapshot) -> Any?>()(ExecutionConfigurationSnapshot(
                    configuration, net.weero.measix.pilot.data.configuration.ConfigurationResolver.resolve(document, scope, secondArg()), "fixture"))
            }
            every { provider.requestMediaCapabilities(any(), any()) } returns RequestMediaCapabilities.NONE
            every { manager.getProviderByType(any<ProviderSetting>()) } returns provider
            every { context.getString(any()) } returns "operation failed"
            coEvery { provider.generateText(any(), any(), any()) } coAnswers {
                started.complete(Unit)
                val text = withContext(NonCancellable) { reply.await() }
                MessageChunk("reply", "test", listOf(UIMessageChoice(0, null, UIMessage.assistant(text), "stop")))
            }
            coEvery { repository.getConversationHeader(any()) } returns initial.header
            coEvery { repository.getConversationSnapshotById(any()) } returns initial
            coEvery { repository.getChildConversationIds(any()) } returns emptyList()
            coEvery { repository.getChildConversationSnapshots(any()) } returns emptyList()
            coEvery { repository.getTurnExecutions(any()) } returns emptyList()
            coEvery { repository.commit(any()) } answers { onCommit(firstArg()); true }
            coEvery { artifacts.collectGarbage() } returns emptyList()
        }

        suspend fun initialize() {
            sessions.enrollFixture(exampleEnterprisePackage())
            runtime = registry.registerSnapshot(initial)
            page = ConversationViewLease(runtime.id, sessions.captureSelectedRealmAccess(), sessions.selectionRevision.value) {}
        }
    }
}
