package net.weero.measix.pilot.service.subassistant

import me.rerere.common.configuration.ConfigurationReference

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
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
import net.weero.measix.pilot.service.ApplicationRecoveryGate
import net.weero.measix.pilot.service.runtime.ConversationCommandCoordinator
import net.weero.measix.pilot.service.runtime.ConversationOperationLocks
import net.weero.measix.pilot.service.runtime.ConversationRuntimeRegistry
import net.weero.measix.pilot.service.runtime.currentTurnPresentation
import net.weero.measix.pilot.service.runtime.ConversationWrite
import net.weero.measix.pilot.service.runtime.TurnExecutionOperation
import net.weero.measix.pilot.service.runtime.disclosureCandidate
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
class SubAssistantTurnIntegrationTest {
    @Test(timeout = 30_000)
    fun `child ask user resumes original turn and parent atomically retains complete execution link`() = runScenario(false)

    @Test(timeout = 30_000)
    fun `child terminal commit failure retains its pending runtime through actual run cleanup`() = runScenario(true)

    private fun runScenario(failChildTerminal: Boolean) = runBlocking {
        val appScope = AppScope(Dispatchers.Default)
        try {
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
            every { settingsStore.effectiveSettings } returns MutableStateFlow(settings.toEffectiveSettingsSnapshot())
            val repository = mockk<ConversationRepository>(relaxed = true)
            val writes = Collections.synchronizedList(mutableListOf<ConversationWrite.Mutate>())
            var masterId: Uuid? = null
            var failedChildId: Uuid? = null
            coEvery { repository.commit(any()) } coAnswers {
                (firstArg<ConversationWrite>() as? ConversationWrite.Mutate)?.let { write ->
                    val terminal = write.executionFacts?.turn?.status?.let {
                        it !in setOf(TurnExecutionStatus.RUNNING, TurnExecutionStatus.AWAITING_USER)
                    } == true
                    if (failChildTerminal && terminal && write.mutation.conversationId != masterId) {
                        failedChildId = write.mutation.conversationId
                        throw java.io.IOException("child terminal commit failed")
                    }
                    writes += write
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
            val provider = ScriptedProvider(listOf(
                ProviderAttempt.Stream(listOf(textDelta("foo"), toolCallDelta("parent-call", "assistant_call", "{}"), finishChunk("tool_calls"))),
                ProviderAttempt.Stream(listOf(toolCallDelta("child-ask", "ask_user", """{"questions":[{"id":"q","question":"Which color?"}]}"""), finishChunk("tool_calls"))),
                ProviderAttempt.Stream(listOf(textDelta("Child chose blue"), finishChunk())),
                ProviderAttempt.Stream(listOf(textDelta("foo"), finishChunk())),
            ))
            val runner = TurnRunner(mockk<Context>(relaxed = true), scriptedProviderManager(provider), JsonInstant, resolver, ToolOutputStore(artifacts))
            val tools = mockk<TurnToolSetFactory>(relaxed = true)
            coEvery { tools.prepareMcpCapabilities(any()) } returns TurnMcpCapabilitySnapshot.EMPTY
            coEvery { tools.buildTools(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns listOf(buildAskUserTool())
            val pipeline = mockk<TurnPipelineFactory>()
            every { pipeline.input(any()) } returns emptyList()
            every { pipeline.output() } returns emptyList()
            val childRuns = SubAssistantRunCoordinator(
                turnRunner = runner, conversationRepo = repository, runtimeRegistry = registry,
                commandCoordinator = commands, toolSetFactory = tools, settingsStore = settingsStore,
                memoryService = mockk<net.weero.measix.pilot.service.MemoryService> {
                    coEvery { captureExecution(any(), any()) } returns null
                }, turnPipelineFactory = pipeline,
                configurations = mockk(relaxed = true),
                turnContextFactory = TurnContextFactory(mockk(relaxed = true)), artifactStore = artifacts,
                toolArtifactRewriter = mockk(relaxed = true), json = JsonInstant,
                attachmentResolver = resolver, context = mockk(relaxed = true), turnFinalizer = finalizer,
                runGate = SubAssistantRunGate(),
            )
            val runtime = commands.create(Conversation(assistantId = parent.id, messageNodes = listOf(UIMessage.user("Delegate the choice").toMessageNode())))
            masterId = runtime.id
            coEvery { repository.getConversationHeader(any()) } coAnswers { registry.findRuntime(firstArg())?.durable?.header }
            coEvery { repository.getTurnExecution(any()) } coAnswers {
                val id = firstArg<String>()
                synchronized(writes) { writes.mapNotNull { it.executionFacts?.turn }.lastOrNull { it.turnId == id } }
            }
            val parentTool = Tool(
                name = "assistant_call", description = "Delegate a choice", execute = { emptyList() },
                contextualExecute = {
                    childRuns.executeCall(parent.id, runtime.id, net.weero.measix.pilot.data.enterprise.RealmAccess.Personal, child.id, "Choose a color", this)
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
                        val metadata = runtime.durable.currentMessages().last().getTools().singleOrNull()?.getSubAssistantCallMetadata(JsonInstant)
                        val interaction = metadata?.userInteraction
                        if (interaction != null && !answered) {
                            answered = true
                            val childRuntime = requireNotNull(registry.findRuntime(Uuid.parse(requireNotNull(metadata.childConversationId))))
                            pausedChildTurnId = childRuntime.snapshot.value.stream!!.turnId
                            pausedChildWorker = childRuntime.currentWorker()
                            val ask = childRuntime.durable.currentMessages().last().getTools().single()
                            pausedStepId = ask.stepId
                            pausedLocalCallId = ask.localCallId
                            assertEquals(ToolInteractionState.AwaitingInput, ask.interactionState)
                            assertTrue(childRuns.answerUserInteraction(metadata.runId, interaction.interactionId, "blue"))
                            assertFalse(childRuns.answerUserInteraction(metadata.runId, interaction.interactionId, "red"))
                        }
                    },
                ))
            }
            registry.installAndStartTurnWorker(runtime.id, turnId, worker)
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
            appScope.cancel()
        }
    }
}
