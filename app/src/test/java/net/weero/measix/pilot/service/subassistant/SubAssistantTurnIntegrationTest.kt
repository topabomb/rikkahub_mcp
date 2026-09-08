package net.weero.measix.pilot.service.subassistant

import me.rerere.common.configuration.ConfigurationReference

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.StepOutcome
import me.rerere.ai.ui.ToolInteractionState
import me.rerere.ai.ui.ToolResultStatus
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.ai.attachments.AttachmentResolveResult
import net.weero.measix.pilot.data.ai.attachments.AttachmentResolver
import net.weero.measix.pilot.data.ai.mcp.TurnMcpCapabilitySnapshot
import net.weero.measix.pilot.data.ai.subassistant.getSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.tools.ToolOutputStore
import net.weero.measix.pilot.data.ai.tools.TurnToolSetFactory
import net.weero.measix.pilot.data.ai.tools.local.LocalToolOption
import net.weero.measix.pilot.data.ai.tools.local.buildAskUserTool
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.toEffectiveSettingsSnapshot
import net.weero.measix.pilot.data.db.entity.ToolExecutionStatus
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.toMessageNode
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.service.*
import net.weero.measix.pilot.service.ApplicationRecoveryGate
import net.weero.measix.pilot.service.runtime.ConversationCommandCoordinator
import net.weero.measix.pilot.service.runtime.ConversationOperationLocks
import net.weero.measix.pilot.service.runtime.ConversationRuntimeRegistry
import net.weero.measix.pilot.service.runtime.currentTurnPresentation
import net.weero.measix.pilot.service.runtime.ConversationWrite
import net.weero.measix.pilot.service.runtime.TurnExecutionOperation
import net.weero.measix.pilot.service.runtime.disclosureCandidate
import net.weero.measix.pilot.service.runtime.toSnapshot
import net.weero.measix.pilot.service.turn.TurnCommitter
import net.weero.measix.pilot.service.turn.TurnContextFactory
import net.weero.measix.pilot.service.turn.TurnFinalizer
import net.weero.measix.pilot.service.turn.TurnOutcome
import net.weero.measix.pilot.service.turn.TurnPipelineFactory
import net.weero.measix.pilot.service.turn.TurnRunner
import net.weero.measix.pilot.test.turnRunInputsFixture
import net.weero.measix.pilot.testkit.ProviderAttempt
import net.weero.measix.pilot.testkit.ScriptedProvider
import net.weero.measix.pilot.testkit.finishChunk
import net.weero.measix.pilot.testkit.scriptedProviderManager
import net.weero.measix.pilot.testkit.textDelta
import net.weero.measix.pilot.testkit.toolCallDelta
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import kotlin.uuid.Uuid

/** Real parent/child runners and command reducers; only provider and transaction IO are doubles. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class SubAssistantTurnIntegrationTest {
    @Test(timeout = 30_000)
    fun `child ask user resumes original turn and parent atomically retains complete execution link`() = runScenario(false)

    @Test(timeout = 30_000)
    fun `child terminal commit failure retains its pending runtime through actual run cleanup`() = runScenario(true)

    @Test(timeout = 30_000)
    fun `enterprise exit waits for child creation and leaves no unfinished execution`() = runScenario(false, ExitWindow.CREATE)

    @Test(timeout = 30_000)
    fun `enterprise exit waits for committed child link before completing`() = runScenario(false, ExitWindow.LINK)

    @Test(timeout = 30_000)
    fun `enterprise exit stops a started child before completing`() = runScenario(false, ExitWindow.CHILD_START)

    @Test(timeout = 30_000)
    fun `enterprise exit retains closing when child terminal commit fails and retries original workers`() = runScenario(true, ExitWindow.CHILD_START)

    @Test(timeout = 30_000)
    fun `in flight caller revocation persists the same stop reason in parent and child`() = runScenario(false, revokeDuringRequest = true)

    private enum class ExitWindow { CREATE, LINK, CHILD_START }

    private fun runScenario(failChildTerminal: Boolean, exitWindow: ExitWindow? = null, revokeDuringRequest: Boolean = false) = runBlocking {
        val appScope = AppScope(Dispatchers.Default)
        val root = java.nio.file.Files.createTempDirectory("child-exit-test").toFile()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            val sessions = EnterpriseSessionController(EnterpriseAppliedStore(root))
            val access = if (exitWindow == null) RealmAccess.Personal else {
                sessions.enrollFixture(exampleEnterprisePackage())
                sessions.captureSelectedRealmAccess()
            }
            val model = me.rerere.ai.provider.Model(modelId = "scripted-model")
            val providerSetting = me.rerere.ai.provider.ProviderSetting.OpenAI(models = listOf(model))
            val child = Assistant(name = "Child", allowAsSubAssistant = true, chatModelId = model.id, enableMemory = false)
            val parent = Assistant(
                name = "Parent", chatModelId = model.id, enableMemory = false,
                regexes = listOf(net.weero.measix.pilot.data.model.AssistantRegex(
                    id = ConfigurationReference.random(), findRegex = "foo", replaceString = "foo!",
                    affectingScope = setOf(net.weero.measix.pilot.data.model.AssistantAffectScope.ASSISTANT),
                )),
                localTools = listOf(LocalToolOption.AssistantDelegation), allowedSubAssistantIds = setOf(child.id),
            )
            val settings = Settings(
                assistantId = parent.id, chatModelId = model.id,
                assistants = listOf(parent, child), providers = listOf(providerSetting),
            )
            val settingsStore = mockk<SettingsStore>()
            val settingsFlow = MutableStateFlow(settings.toEffectiveSettingsSnapshot())
            every { settingsStore.effectiveSettings } returns settingsFlow
            net.weero.measix.pilot.test.installExecutionConfigurationFixture(settingsStore)
            val repository = mockk<ConversationRepository>(relaxed = true)
            val writes = Collections.synchronizedList(mutableListOf<ConversationWrite.Mutate>())
            val headers = java.util.concurrent.ConcurrentHashMap<Uuid, net.weero.measix.pilot.service.runtime.ConversationHeader>()
            coEvery { repository.insertConversation(any()) } coAnswers {
                val conversation = firstArg<Conversation>()
                headers[conversation.id] = conversation.toSnapshot().header
                if (exitWindow == ExitWindow.CREATE && conversation.parentConversationId != null) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            coEvery { repository.deleteConversation(any()) } coAnswers { headers.remove(firstArg<Uuid>()); Unit }
            coEvery { repository.getTurnExecutions(any()) } coAnswers {
                val id = firstArg<Uuid>().toString()
                synchronized(writes) { writes.mapNotNull { it.executionFacts?.turn }.associateBy { it.turnId }.values.filter { it.conversationId == id } }
            }
            coEvery { repository.getToolExecutions(any()) } coAnswers {
                val id = firstArg<String>()
                synchronized(writes) { writes.mapNotNull { it.executionFacts?.toolExecution }.associateBy { it.executionId }.values.filter { it.turnId == id } }
            }
            coEvery { repository.countUnfinishedTurns(any()) } coAnswers {
                val scope = firstArg<ConfigurationScope>()
                synchronized(writes) { writes.mapNotNull { it.executionFacts?.turn }.associateBy { it.turnId }.values.count {
                    headers[Uuid.parse(it.conversationId)]?.scope == scope && it.status in setOf(TurnExecutionStatus.RUNNING, TurnExecutionStatus.AWAITING_USER)
                } }
            }
            var masterId: Uuid? = null
            var failedChildId: Uuid? = null
            var rejectChildTerminal = failChildTerminal
            coEvery { repository.commit(any()) } coAnswers {
                (firstArg<ConversationWrite>() as? ConversationWrite.Mutate)?.let { write ->
                    val terminal = write.executionFacts?.turn?.status?.let {
                        it !in setOf(TurnExecutionStatus.RUNNING, TurnExecutionStatus.AWAITING_USER)
                    } == true
                    if (rejectChildTerminal && terminal && write.mutation.conversationId != masterId) {
                        failedChildId = write.mutation.conversationId
                        throw java.io.IOException("child terminal commit failed")
                    }
                    writes += write
                    if (exitWindow == ExitWindow.CHILD_START && write.mutation.conversationId != masterId &&
                        write.executionFacts?.turnOperation == TurnExecutionOperation.START) {
                        entered.complete(Unit)
                        release.await()
                    }
                }
                true
            }
            val locks = ConversationOperationLocks()
            val registry = ConversationRuntimeRegistry(appScope, repository, locks)
            val commands = ConversationCommandCoordinator(registry, repository, ApplicationRecoveryGate().apply { ready() }, locks)
            val finalizer = TurnFinalizer(repository, registry, commands, JsonInstant)
            val artifacts = mockk<ArtifactStore>(relaxed = true)
            val resolver = mockk<AttachmentResolver>(relaxed = true)
            coEvery { resolver.withImages<Any?>(any(), any()) } coAnswers {
                secondArg<suspend (AttachmentResolveResult) -> Any?>()(AttachmentResolveResult.Success(emptyList()))
            }
            val attempts = if (revokeDuringRequest) listOf(
                ProviderAttempt.Stream(listOf(toolCallDelta("parent-call", "assistant_call", "{}"), finishChunk("tool_calls"))),
                ProviderAttempt.Stream(emptyList(), beforeFirstOutput = release),
                ProviderAttempt.Stream(listOf(textDelta("Child stopped"), finishChunk())),
            ) else listOf(
                ProviderAttempt.Stream(listOf(textDelta("foo"), toolCallDelta("parent-call", "assistant_call", "{}"), finishChunk("tool_calls"))),
                ProviderAttempt.Stream(listOf(toolCallDelta("child-ask", "ask_user", """{"questions":[{"id":"q","question":"Which color?"}]}"""), finishChunk("tool_calls"))),
                ProviderAttempt.Stream(listOf(textDelta("Child chose blue"), finishChunk())),
                ProviderAttempt.Stream(listOf(textDelta("foo"), finishChunk())),
            )
            val provider = ScriptedProvider(attempts)
            val observedProvider = object : me.rerere.ai.provider.Provider<me.rerere.ai.provider.ProviderSetting.OpenAI> by provider {
                override suspend fun streamText(
                    providerSetting: me.rerere.ai.provider.ProviderSetting.OpenAI,
                    messages: List<me.rerere.ai.core.ModelRequestMessage>,
                    params: me.rerere.ai.provider.TextGenerationParams,
                ): kotlinx.coroutines.flow.Flow<me.rerere.ai.ui.MessageChunk> {
                    val stream = provider.streamText(providerSetting, messages, params)
                    if (revokeDuringRequest && provider.dispatches.size == 2) entered.complete(Unit)
                    return stream
                }
            }
            val runner = TurnRunner(mockk<Context>(relaxed = true), scriptedProviderManager(observedProvider), JsonInstant, resolver, ToolOutputStore(artifacts))
            val tools = mockk<TurnToolSetFactory>(relaxed = true)
            coEvery { tools.prepareMcpCapabilities(any()) } returns TurnMcpCapabilitySnapshot.EMPTY
            coEvery { tools.buildTools(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns listOf(buildAskUserTool())
            val pipeline = mockk<TurnPipelineFactory>()
            every { pipeline.input(any()) } returns emptyList()
            every { pipeline.output() } returns emptyList()
            val runGate = SubAssistantRunGate()
            val childRuns = SubAssistantRunCoordinator(
                turnRunner = runner, conversationRepo = repository, runtimeRegistry = registry,
                commandCoordinator = commands, toolSetFactory = tools, settingsStore = settingsStore,
                modelExecutions = net.weero.measix.pilot.test.testModelExecutionService(settingsStore, sessions, ApplicationRecoveryGate().apply { ready() }),
                memoryService = mockk<net.weero.measix.pilot.service.MemoryService> {
                    coEvery { captureExecution(any(), any()) } returns null
                }, turnPipelineFactory = pipeline,
                configurations = ConfigurationQueryService(settingsStore, sessions, ApplicationRecoveryGate().apply { ready() }),
                turnContextFactory = TurnContextFactory(mockk(relaxed = true)), artifactStore = artifacts,
                toolArtifactRewriter = mockk(relaxed = true), json = JsonInstant,
                attachmentResolver = resolver, context = mockk(relaxed = true), turnFinalizer = finalizer,
                runGate = runGate,
            )
            val runtime = commands.create(Conversation(assistantId = parent.id, scope = access.scope, messageNodes = listOf(UIMessage.user("Delegate the choice").toMessageNode())))
            masterId = runtime.id
            coEvery { repository.getConversationHeader(any()) } coAnswers { registry.findRuntime(firstArg())?.durable?.header }
            coEvery { repository.getTurnExecution(any()) } coAnswers {
                val id = firstArg<String>()
                synchronized(writes) { writes.mapNotNull { it.executionFacts?.turn }.lastOrNull { it.turnId == id } }
            }
            val parentTool = Tool(
                name = "assistant_call", description = "Delegate a choice", execute = { emptyList() },
                contextualExecute = {
                    childRuns.executeCall(parent.id, runtime.id, access, child.id, "Choose a color", this)
                },
            )
            val turnId = Uuid.random()
            var answered = false
            var pausedChildTurnId: Uuid? = null
            var pausedChildWorker: kotlinx.coroutines.Job? = null
            var pausedStepId: Uuid? = null
            var pausedLocalCallId: Uuid? = null
            val worker = appScope.async(start = CoroutineStart.LAZY) {
                val started = TurnCommitter.start(commands, runtime, turnId, disclosureCandidate(), finalizer)
                runner.run(turnRunInputsFixture(
                    conversationId = runtime.id, settings = settings, model = model,
                    mediaCapabilities = me.rerere.ai.provider.RequestMediaCapabilities.NONE,
                    messages = runtime.durable.currentMessages(), assistant = parent, tools = listOf(parentTool),
                    outputTransformers = listOf(net.weero.measix.pilot.data.ai.transformers.RegexOutputTransformer),
                    assistantMessageId = started.assistantMessageId, handle = started.handle,
                    onAssistantObserved = started.turnCommitter::observeAssistant,
                    onStreamDelta = { projected ->
                        assertFalse("tool phases must not transform already sampled text again",
                            projected.parts.filterIsInstance<UIMessagePart.Text>().any { it.text.contains("foo!!") })
                        started.turnCommitter.publishStream(projected)
                    },
                    onResult = started.turnCommitter::commitRunResult,
                    onCheckpoint = { checkpoint ->
                        started.turnCommitter.onCheckpoint(checkpoint)
                        if (exitWindow == ExitWindow.LINK && checkpoint is net.weero.measix.pilot.service.runtime.ToolExecutionUpdatedCheckpoint &&
                            checkpoint.toolExecution?.childTurnId != null) {
                            entered.complete(Unit)
                            release.await()
                        }
                        val metadata = runtime.durable.currentMessages().last().getTools().singleOrNull()?.getSubAssistantCallMetadata(JsonInstant)
                        val interaction = metadata?.userInteraction
                        if (interaction != null && !answered && exitWindow == null) {
                            answered = true
                            val childRuntime = requireNotNull(registry.findRuntime(Uuid.parse(requireNotNull(metadata.childConversationId))))
                            pausedChildTurnId = childRuntime.snapshot.value.stream!!.turnId
                            pausedChildWorker = childRuntime.currentWorker()
                            val ask = childRuntime.durable.currentMessages().last().getTools().single()
                            pausedStepId = ask.stepId
                            pausedLocalCallId = ask.localCallId
                            assertEquals(ToolInteractionState.AwaitingInput, ask.interactionState)
                            assertTrue(runGate.completeAnswer(runtime.id, net.weero.measix.pilot.data.enterprise.RealmAccess.Personal, metadata.runId, interaction.interactionId, "blue"))
                            assertFalse(runGate.completeAnswer(runtime.id, net.weero.measix.pilot.data.enterprise.RealmAccess.Personal, metadata.runId, interaction.interactionId, "red"))
                        }
                    },
                ))
            }
            registry.installAndStartTurnWorker(runtime.id, turnId, worker)
            if (revokeDuringRequest) {
                entered.await()
                settingsFlow.value = settings.copy(assistants = listOf(parent.copy(allowedSubAssistantIds = emptySet()), child)).toEffectiveSettingsSnapshot()
                assertTrue(worker.await() is TurnOutcome.Completed)
                val metadata = runtime.durable.currentMessages().flatMap { it.getTools() }.single().getSubAssistantCallMetadata(JsonInstant)!!
                assertEquals(net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallState.STOPPED, metadata.state)
                assertEquals("target_access_revoked", metadata.reason)
                val childTerminal = writes.mapNotNull { it.executionFacts?.turn }.last { it.conversationId != runtime.id.toString() }
                assertEquals(TurnExecutionStatus.CANCELLED, childTerminal.status)
                assertEquals(metadata.reason, childTerminal.reason)
                assertFalse(runGate.isBusy(SubAssistantRunKey(runtime.id, child.id)))
                return@runBlocking
            }
            if (exitWindow != null) {
                entered.await()
                val gate = ApplicationRecoveryGate().apply { ready() }
                val application = ConversationApplicationService(settingsStore, repository, mockk(), registry, commands, gate,
                    SubAssistantLifecycle(repository, registry, commands, JsonInstant), mockk(), artifacts, mockk(), finalizer,
                    JsonInstant, mockk(), ConversationTitleCoordinator(), sessions, runGate)
                val synchronization = mockk<EnterpriseSynchronizationService> { coEvery { cancelAndAwait(any()) } returns Unit }
                val exit = EnterpriseExitService(sessions, synchronization, application, gate, appScope, net.weero.measix.pilot.service.portal.PortalDocumentRegistry())
                val request = requireNotNull(exit.captureRequest())
                val pending = async { try { exit.exit(request); null } catch (error: Exception) { error } }
                sessions.state.first { it is EnterpriseState.Available && it.manifest.phase == EnterpriseSessionPhase.CLOSING }
                assertFalse(pending.isCompleted)
                release.complete(Unit)
                val failure = pending.await()
                if (failChildTerminal) {
                    assertTrue(failure is java.io.IOException)
                    assertEquals(EnterpriseSessionPhase.CLOSING, (sessions.state.value as EnterpriseState.Available).manifest.phase)
                    rejectChildTerminal = false
                    exit.retry(requireNotNull(sessions.pendingExit()))
                } else org.junit.Assert.assertNull(failure)
                assertEquals(EnterpriseSessionPhase.SIGNED_OUT, (sessions.state.value as EnterpriseState.Available).manifest.phase)
                worker.join()
                assertFalse(runGate.isBusy(SubAssistantRunKey(runtime.id, child.id)))
                assertEquals(0, repository.countUnfinishedTurns(access.scope))
                assertTrue(registry.activeRuntimes().none { it.snapshot.value.stream != null || it.currentWorker()?.isCompleted == false || it.hasAuxiliaryWork })
                val childStarts = synchronized(writes) { writes.filter { it.mutation.conversationId != masterId && it.executionFacts?.turnOperation == TurnExecutionOperation.START } }
                assertEquals(if (exitWindow == ExitWindow.CHILD_START) 1 else 0, childStarts.size)
                val linkedChildren = synchronized(writes) { writes.mapNotNull { it.executionFacts?.toolExecution?.childConversationId }.toSet() }
                assertTrue(headers.keys.filter { it != masterId }.all { it.toString() in linkedChildren })
                return@runBlocking
            }
            val result = runCatching { worker.await() }
            if (failChildTerminal) {
                assertFalse(result.getOrNull() is TurnOutcome.Completed)
                val failedRuntime = requireNotNull(registry.findRuntime(requireNotNull(failedChildId)))
                val stream = requireNotNull(failedRuntime.snapshot.value.stream)
                val retainedWorker = requireNotNull(failedRuntime.currentWorker())
                org.junit.Assert.assertSame(requireNotNull(pausedChildWorker), retainedWorker)
                assertTrue(retainedWorker.isCancelled)
                assertEquals(stream.turnId, failedRuntime.currentGenerationTurnId())
                assertFalse(failedRuntime.currentTurnPresentation().phase == net.weero.measix.pilot.service.runtime.TurnLivePhase.STOPPING)
                return@runBlocking
            }
            assertTrue(result.getOrThrow() is TurnOutcome.Completed)
            assertTrue(answered)
            assertEquals(4, provider.dispatches.size)
            assertEquals("each sampled text is transformed once and closed Step text stays unchanged",
                listOf("foo!", "foo!"), runtime.durable.currentMessages().last().parts.filterIsInstance<UIMessagePart.Text>().map { it.text })
            val parentResult = runtime.durable.currentMessages().last().getTools().single()
            assertEquals(ToolResultStatus.COMPLETED, parentResult.resultStatus)
            assertTrue(parentResult.output.filterIsInstance<UIMessagePart.Text>().any { it.text.contains("Child chose blue") })
            val metadata = requireNotNull(parentResult.getSubAssistantCallMetadata(JsonInstant))
            val childId = Uuid.parse(requireNotNull(metadata.childConversationId))
            val childRuntime = requireNotNull(registry.findRuntime(childId))
            val finalChild = childRuntime.durable.currentMessages().last()
            val ask = finalChild.getTools().single()
            assertEquals(pausedStepId, ask.stepId)
            assertEquals(pausedLocalCallId, ask.localCallId)
            assertEquals(ToolInteractionState.Answered("blue"), ask.interactionState)
            assertEquals(StepOutcome.Final, finalChild.parts.filterIsInstance<UIMessagePart.Step>().last().outcome)
            val childWrites = writes.filter { it.mutation.conversationId == childId }
            assertEquals(1, childWrites.count { it.executionFacts?.turnOperation == TurnExecutionOperation.START })
            assertEquals(setOf(pausedChildTurnId.toString()), childWrites.mapNotNull { it.executionFacts?.turn?.turnId }.toSet())
            assertTrue(childWrites.any { it.executionFacts?.turn?.status == TurnExecutionStatus.AWAITING_USER })
            assertEquals(TurnExecutionStatus.COMPLETED, childWrites.mapNotNull { it.executionFacts?.turn }.last().status)
            val linkedWrite = writes.first { it.executionFacts?.toolExecution?.childTurnId != null }
            val link = requireNotNull(linkedWrite.executionFacts?.toolExecution)
            assertEquals(pausedChildTurnId.toString(), link.childTurnId)
            assertEquals(metadata.runId, link.subAssistantRunId)
            assertEquals(childId.toString(), link.childConversationId)
            assertEquals(metadata.runId, linkedWrite.mutation.upsertedNodes.flatMap { it.messages }.flatMap { it.getTools() }.single().getSubAssistantCallMetadata(JsonInstant)?.runId)
            assertEquals(ToolExecutionStatus.COMPLETED, writes.mapNotNull { it.executionFacts?.toolExecution }.last().status)
        } finally {
            release.complete(Unit)
            appScope.cancel()
            withContext(NonCancellable) { appScope.coroutineContext[Job]?.join() }
            root.deleteRecursively()
        }
    }
}
