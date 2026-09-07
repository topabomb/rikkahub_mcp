package net.weero.measix.pilot.service.subassistant

import net.weero.measix.pilot.data.ai.subassistant.normalizeSubAssistantCancellationReason
import net.weero.measix.pilot.data.ai.subassistant.preprocessSubAssistantTask
import net.weero.measix.pilot.data.model.AssistantAffectScope
import net.weero.measix.pilot.data.model.AssistantRegex
import me.rerere.ai.ui.ToolInteractionState
import net.weero.measix.pilot.service.turn.TurnFinalizer

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ToolAttachmentResolution
import me.rerere.ai.core.ToolExecutionContext
import me.rerere.ai.core.ToolMetadataDelivery
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.service.turn.TurnRunner
import net.weero.measix.pilot.data.ai.mcp.TurnMcpCapabilitySnapshot
import net.weero.measix.pilot.data.ai.attachments.AttachmentFailureReasons
import net.weero.measix.pilot.data.ai.attachments.AttachmentResolveResult
import net.weero.measix.pilot.data.ai.attachments.AttachmentResolver
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallState
import net.weero.measix.pilot.data.ai.subassistant.mergeSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.tools.TurnToolSetFactory
import net.weero.measix.pilot.data.ai.tools.local.LocalToolOption
import net.weero.measix.pilot.service.turn.TurnPipelineFactory
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.toEffectiveSettingsSnapshot
import net.weero.measix.pilot.data.files.ArtifactDeleteResult
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.OwnedArtifact
import net.weero.measix.pilot.data.files.LocalArtifactRef
import net.weero.measix.pilot.data.files.ToolArtifactRewriter
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.toMessageNode
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.data.repository.MemoryRepository
import net.weero.measix.pilot.data.repository.WorkspaceRepository
import net.weero.measix.pilot.service.runtime.ConversationCommandCoordinator
import net.weero.measix.pilot.service.runtime.ConversationAggregateSnapshot
import net.weero.measix.pilot.service.runtime.ConversationRuntime
import net.weero.measix.pilot.service.runtime.ConversationRuntimeRegistry
import net.weero.measix.pilot.service.runtime.ConversationRuntimeSnapshot
import net.weero.measix.pilot.service.runtime.toSnapshot
import net.weero.measix.pilot.service.runtime.StartTurn
import net.weero.measix.pilot.service.runtime.TurnHandle
import net.weero.measix.pilot.service.turn.TurnRunInputs
import net.weero.measix.pilot.service.turn.TurnOutcome
import net.weero.measix.pilot.data.ai.tools.TurnInteractionCapability
import me.rerere.ai.provider.RequestMediaCapabilities
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SubAssistantRunCoordinatorTest {
    private val callerId = Uuid.random()
    private val targetId = Uuid.random()
    private val masterId = Uuid.random()
    private val currentMessageId = Uuid.random()
    private val currentToolStepId = Uuid.random()
    private val currentToolLocalCallId = Uuid.random()

    @Test
    fun `text target materializes durable image parts and link failure compensates the exact child`() = runTest {
        val image = UIMessagePart.Image(url = "file:///tmp/a.png")
        val harness = harness(AttachmentResolveResult.Success(listOf(image)))
        val created = slot<Conversation>()
        coEvery { harness.commandCoordinator.create(capture(created)) } returns mockk<ConversationRuntime>()
        coEvery { harness.commandCoordinator.deleteOrThrow(any()) } just Runs
        val patches = mutableListOf<JsonObject>()
        val context = executionContext(
            reportMetadata = { patch, _ -> patches += patch },
            reportChild = { throw IllegalStateException("link checkpoint failed") },
        )

        harness.coordinator.executeCall(
            callerAssistantId = callerId,
            masterConversationId = masterId,
            targetAssistantId = targetId,
            task = "Describe the image",
            execContext = context,
            attachments = listOf("/upload/abc123.png"),
        )

        val child = created.captured
        val user = child.currentMessages.single { it.role == MessageRole.USER }
        val initialMetadata = patches.map { patch ->
            JsonInstant.decodeFromJsonElement(
                SubAssistantCallMetadata.serializer(),
                patch.getValue("sub_assistant_call"),
            )
        }.first { it.childConversationId == child.id.toString() }
        assertEquals("Describe the image", (user.parts.first() as UIMessagePart.Text).text)
        assertTrue(user.parts.any { it is UIMessagePart.Image && it.url == image.url })
        assertEquals(user.id.toString(), initialMetadata.childTaskNodeId)
        assertEquals(child.id.toString(), initialMetadata.childConversationId)
        coVerify(exactly = 1) { harness.commandCoordinator.deleteOrThrow(child.id) }
    }

    @Test
    fun `resolver failure never creates a child`() = runTest {
        val harness = harness(AttachmentResolveResult.Failure(AttachmentFailureReasons.ATTACHMENT_NOT_FOUND))

        val result = harness.coordinator.executeCall(
            callerAssistantId = callerId,
            masterConversationId = masterId,
            targetAssistantId = targetId,
            task = "Describe the image",
            execContext = executionContext(),
            attachments = listOf("/upload/abc123.png"),
        )

        assertTrue(result.isNotEmpty())
        coVerify(exactly = 0) { harness.commandCoordinator.create(any()) }
    }

    @Test
    fun `publication failure after child link commit retains linked child`() = runTest {
        val owned = mockk<OwnedArtifact>(relaxed = true)
        every { owned.uri.toString() } returns "file:///tmp/copied.png"
        val harness = harness(AttachmentResolveResult.Success(emptyList()), cloneArtifact = owned)
        var linkCommitted = false
        coEvery { harness.artifactStore.publishAllUnpublished(listOf(owned)) } coAnswers {
            assertTrue(linkCommitted)
            error("publication failed")
        }
        val patches = mutableListOf<JsonObject>()
        val result = harness.coordinator.executeCall(
            callerAssistantId = callerId, masterConversationId = masterId, targetAssistantId = targetId,
            task = "Describe the image",
            execContext = executionContext(
                reportMetadata = { patch, delivery ->
                    assertEquals(ToolMetadataDelivery.DEFERRED, delivery)
                    patches += patch
                },
                reportChild = { linkCommitted = true },
            ),
            attachments = emptyList(),
        )

        val failureText = result.filterIsInstance<UIMessagePart.Text>().single().text
        assertTrue(failureText, failureText.contains("publication failed"))
        assertEquals(1, patches.size)
        coVerify(exactly = 1) { harness.artifactStore.copyFilePreservingOrigin(any(), any(), any(), any()) }
        coVerify(exactly = 1) { harness.artifactStore.publishAllUnpublished(listOf(owned)) }
        coVerify(exactly = 0) { harness.commandCoordinator.deleteOrThrow(any()) }
        coVerify(exactly = 0) { harness.artifactStore.discardUnpublished(any()) }
    }

    @Test
    fun `revocation while Child preparation is suspended prevents START admission`() = runTest {
        val preparationEntered = CompletableDeferred<Unit>()
        val resumePreparation = CompletableDeferred<Unit>()
        val harness = harness(
            resolveResult = AttachmentResolveResult.Success(emptyList()),
            preparationGate = preparationEntered to resumePreparation,
        )
        val childRuntime = mockk<ConversationRuntime>(relaxed = true)
        val created = slot<Conversation>()
        coEvery { harness.commandCoordinator.create(capture(created)) } coAnswers {
            every { childRuntime.id } returns created.captured.id
            every { childRuntime.snapshot } returns MutableStateFlow(
                ConversationRuntimeSnapshot(durable = created.captured.toSnapshot(), stream = null),
            )
            childRuntime
        }
        coEvery { harness.commandCoordinator.load(any()) } returns childRuntime

        val execution = async {
            harness.coordinator.executeCall(
                callerAssistantId = callerId,
                masterConversationId = masterId,
                targetAssistantId = targetId,
                task = "prepare then revoke",
                execContext = executionContext(),
                attachments = emptyList(),
            )
        }
        preparationEntered.await()
        val current = harness.settingsFlow.value.settings
        val caller = current.assistants.single { it.id == callerId }
        harness.settingsFlow.value = current.copy(
            assistants = current.assistants.map {
                if (it.id == callerId) caller.copy(allowedSubAssistantIds = emptySet()) else it
            },
        ).toEffectiveSettingsSnapshot()
        resumePreparation.complete(Unit)

        val result = execution.await()
        assertTrue(result.filterIsInstance<UIMessagePart.Text>().single().text.contains("target_access_revoked"))
        coVerify(exactly = 0) { childRuntime.bindTurnContext(any(), any(), any()) }
        coVerify(exactly = 0) { childRuntime.bindModelContextProjection(any(), any(), any()) }
        coVerify(exactly = 0) { harness.commandCoordinator.startTurn(any(), any()) }
    }

    @Test
    fun `child creation failure releases read scope without deleting existing input`() = runTest {
        val harness = harness(AttachmentResolveResult.Success(listOf(UIMessagePart.Image("file:///tmp/a.png"))))
        coEvery { harness.commandCoordinator.create(any()) } throws IllegalStateException("db down")
        coEvery { harness.commandCoordinator.deleteOrThrow(any()) } just Runs

        val result = harness.coordinator.executeCall(
            callerAssistantId = callerId,
            masterConversationId = masterId,
            targetAssistantId = targetId,
            task = "look at this",
            execContext = executionContext(),
            attachments = listOf("/upload/abc123.png"),
        )

        assertTrue(result.isNotEmpty())
        assertEquals(false, harness.readHeld.get())
        coVerify(exactly = 0) { harness.artifactStore.discardUnpublished(any()) }
    }

    @Test
    fun `child generation runs through the shared TurnRunner and its committed answer becomes the parent tool result`() = runTest {
        // The Child does not own a bespoke generation loop: executeCall drives the SAME
        // shared TurnRunner seam with Child request semantics, and the answer the runner commits to
        // the Child durable is surfaced as the Parent tool result. (The runner turning provider
        // output into durable facts is TurnRunnerTest's coverage; here we prove the coordination.)
        val childAnswer = UIMessage(
            id = Uuid.random(),
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("child final answer")),
        )
        val inputsSlot = slot<TurnRunInputs>()
        val runner = mockk<TurnRunner>()
        every { runner.resolveRequestMediaCapabilities(any(), any()) } returns RequestMediaCapabilities.NONE
        coEvery { runner.run(capture(inputsSlot)) } returns TurnOutcome.Completed(assistantMessage = childAnswer)

        val harness = harness(AttachmentResolveResult.Success(emptyList()), turnRunner = runner)
        val childRuntime = mockk<ConversationRuntime>(relaxed = true)
        val created = slot<Conversation>()
        coEvery { harness.commandCoordinator.create(capture(created)) } coAnswers {
            val child = created.captured
            val withAnswer = child.copy(messageNodes = child.messageNodes + childAnswer.toMessageNode())
            val durable = withAnswer.toSnapshot()
            every { childRuntime.id } returns child.id
            every { childRuntime.durable } returns durable
            every { childRuntime.snapshot } returns MutableStateFlow(
                ConversationRuntimeSnapshot(durable = durable, stream = null),
            )
            childRuntime
        }
        coEvery { harness.commandCoordinator.load(any()) } returns childRuntime
        coEvery { harness.commandCoordinator.startTurn(any(), any()) } answers {
            val command = secondArg<StartTurn>()
            TurnHandle(firstArg(), 1L, command.turnId, command.assistantMessageId)
        }
        coEvery { harness.commandCoordinator.executeOrThrow(any(), any()) } just Runs

        val result = harness.coordinator.executeCall(
            callerAssistantId = callerId,
            masterConversationId = masterId,
            targetAssistantId = targetId,
            task = "summarize",
            execContext = executionContext(),
            attachments = emptyList(),
        )

        // Child delegates to the shared runner exactly once, with Child-only interaction capability.
        coVerify(exactly = 1) { runner.run(any()) }
        assertEquals(TurnInteractionCapability.USER_INPUT_ONLY, inputsSlot.captured.interactionAvailability)
        // The committed Child answer surfaces as the Parent tool result.
        val parentText = result.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        assertTrue(parentText, parentText.contains("child final answer"))
    }

    private fun executionContext(
        reportMetadata: suspend (JsonObject, ToolMetadataDelivery) -> Unit = { _, _ -> },
        reportChild: suspend (String) -> Unit = { },
    ) = ToolExecutionContext(
        locator = ToolCallLocator(currentMessageId, currentToolStepId, currentToolLocalCallId), providerCallId = "call",
        reportMetadata = reportMetadata,
        resolveAttachments = { ToolAttachmentResolution() },
        reportChildConversation = reportChild,
        registerUnpublishedResource = { },
    )

    private fun harness(
        resolveResult: AttachmentResolveResult,
        cloneArtifact: OwnedArtifact? = null,
        preparationGate: Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>? = null,
        turnRunner: TurnRunner = mockk(relaxed = true),
    ): Harness {
        val modelId = Uuid.random()
        val model = Model(
            id = modelId,
            modelId = "target-model",
            type = ModelType.CHAT,
            inputModalities = listOf(Modality.TEXT),
        )
        val caller = Assistant(
            id = callerId,
            localTools = listOf(LocalToolOption.AssistantDelegation),
            allowedSubAssistantIds = setOf(targetId),
            chatModelId = modelId,
        )
        val target = Assistant(
            id = targetId,
            name = "Target",
            allowAsSubAssistant = true,
            chatModelId = modelId,
        )
        val settingsStore = mockk<SettingsStore>()
        val settingsFlow = MutableStateFlow(
            Settings(
                assistants = listOf(caller, target),
                assistantId = callerId,
                chatModelId = modelId,
                providers = listOf(ProviderSetting.OpenAI(models = listOf(model))),
            ).toEffectiveSettingsSnapshot(),
        )
        every { settingsStore.effectiveSettings } returns settingsFlow
        val resolver = mockk<AttachmentResolver>()
        val readHeld = java.util.concurrent.atomic.AtomicBoolean(false)
        coEvery { resolver.withImages<Any?>(any(), any()) } coAnswers {
            readHeld.set(true)
            try {
                secondArg<suspend (AttachmentResolveResult) -> Any?>()(resolveResult)
            } finally {
                readHeld.set(false)
            }
        }
        val runtimeRegistry = mockk<ConversationRuntimeRegistry>(relaxed = true)
        every { runtimeRegistry.findRuntime(any()) } returns null
        val commandCoordinator = mockk<ConversationCommandCoordinator>()
        val artifactStore = mockk<ArtifactStore>(relaxed = true)
        val conversationRepo = mockk<ConversationRepository>(relaxed = true)
        if (cloneArtifact != null) {
            val sourceFile = java.io.File("D:/tmp/source.png")
            val originalTask = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Image("file:///D:/tmp/source.png")))
            val source = Conversation(
                assistantId = targetId,
                parentConversationId = masterId,
                messageNodes = listOf(originalTask.toMessageNode(), UIMessage.user("later task").toMessageNode()),
            )
            val previous = UIMessagePart.Tool(
                toolName = "assistant_call", localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "previous", input = "{}",
            ).mergeSubAssistantCallMetadata(JsonInstant, SubAssistantCallMetadata(
                    runId = "previous-run", targetAssistantId = targetId.toString(), targetNameSnapshot = "Target",
                    state = SubAssistantCallState.COMPLETED, childConversationId = source.id.toString(),
                    childTaskNodeId = originalTask.id.toString(),
            ))
            val master = Conversation(
                id = masterId, assistantId = callerId,
                messageNodes = listOf(
                    UIMessage(role = MessageRole.ASSISTANT, parts = listOf(previous)).toMessageNode(),
                    UIMessage(id = currentMessageId, role = MessageRole.ASSISTANT, parts = listOf(
                        UIMessagePart.Tool(toolName = "assistant_call", localCallId = currentToolLocalCallId, stepId = currentToolStepId, providerCallId = "call", input = "{}"),
                    )).toMessageNode(),
                ),
            )
            val runtime = mockk<ConversationRuntime>()
            every { runtime.snapshot } returns MutableStateFlow(
                ConversationRuntimeSnapshot(durable = master.toSnapshot(), stream = null),
            )
            every { runtimeRegistry.findRuntime(masterId) } returns runtime
            coEvery { conversationRepo.getConversationById(source.id) } returns source
            coEvery { conversationRepo.getConversationSnapshotById(source.id) } returns source.toSnapshot()
            coEvery { commandCoordinator.createSnapshot(any()) } returns mockk<ConversationRuntime>()
            coEvery { commandCoordinator.create(any()) } returns mockk<ConversationRuntime>(relaxed = true)
            val originalRef = LocalArtifactRef(relativePath = "upload/source.png", mimeType = "image/png")
            coEvery { artifactStore.resolveManagedReference(any()) } returns originalRef
            every { artifactStore.file(originalRef) } returns sourceFile
            coEvery { artifactStore.copyFilePreservingOrigin(any(), any(), any(), any()) } returns cloneArtifact
        }
        val turnFinalizer = mockk<TurnFinalizer>(relaxed = true)
        val toolSetFactory = mockk<TurnToolSetFactory>(relaxed = true)
        if (preparationGate != null) {
            coEvery { toolSetFactory.prepareMcpCapabilities(any()) } coAnswers {
                preparationGate.first.complete(Unit)
                preparationGate.second.await()
                TurnMcpCapabilitySnapshot.EMPTY
            }
        }
        val coordinator = SubAssistantRunCoordinator(
            turnRunner = turnRunner,
            conversationRepo = conversationRepo,
            runtimeRegistry = runtimeRegistry,
            commandCoordinator = commandCoordinator,
            toolSetFactory = toolSetFactory,
            settingsStore = settingsStore,
            memoryRepository = mockk<MemoryRepository>(relaxed = true),
            turnPipelineFactory = mockk<TurnPipelineFactory>(relaxed = true),
            turnContextFactory = mockk(relaxed = true),
            artifactStore = artifactStore,
            toolArtifactRewriter = mockk<ToolArtifactRewriter>(relaxed = true),
            json = JsonInstant,
            attachmentResolver = resolver,
            context = mockk(relaxed = true),
            turnFinalizer = turnFinalizer,
            runGate = SubAssistantRunGate(),
        )
        return Harness(coordinator, commandCoordinator, artifactStore, turnFinalizer, readHeld, settingsFlow)
    }

    private data class Harness(
        val coordinator: SubAssistantRunCoordinator,
        val commandCoordinator: ConversationCommandCoordinator,
        val artifactStore: ArtifactStore,
        val turnFinalizer: TurnFinalizer,
        val readHeld: java.util.concurrent.atomic.AtomicBoolean,
        val settingsFlow: MutableStateFlow<net.weero.measix.pilot.data.datastore.EffectiveSettingsSnapshot>,
    )

    // ── ask_user 桥接与中断收口（原 SubAssistantAskUserBridgeTest）──

    @Test
    fun `restart makes pending and in-flight child tools protocol complete and read-only`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                pendingAsk("question"),
                UIMessagePart.Tool(
                    localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "running",
                    toolName = "get_time_info",
                    input = "{}",
                ),
            ),
        )

        val recovered = message.finalizeSubAssistantToolsAfterInterruption("app_restarted")
        val tools = recovered.getTools()

        assertTrue(tools.all { it.hasReplayResult })
        assertEquals(ToolInteractionState.Denied("app_restarted"), tools[0].interactionState)
        assertEquals(ToolInteractionState.NotRequired, tools[1].interactionState)
        assertTrue((tools[0].output.single() as UIMessagePart.Text).text.contains("app_restarted"))
    }

    @Test
    fun `user stop makes child tools protocol complete for safe lineage reuse`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(pendingAsk("question")),
        )

        val recovered = message.finalizeSubAssistantToolsAfterInterruption("user_cancelled")
        val tool = recovered.getTools().single()

        assertTrue(tool.hasReplayResult)
        assertEquals(ToolInteractionState.Denied("user_cancelled"), tool.interactionState)
        assertTrue((tool.output.single() as UIMessagePart.Text).text.contains("user_cancelled"))
    }

    @Test
    fun `unknown coroutine cancellation text is normalized to user cancelled`() {
        assertEquals("user_cancelled", normalizeSubAssistantCancellationReason("StandaloneCoroutine was cancelled"))
        assertEquals("target_removed", normalizeSubAssistantCancellationReason("assistant_removed"))
        assertEquals("target_access_revoked", normalizeSubAssistantCancellationReason("target_access_revoked"))
    }

    @Test
    fun `target user regex preprocessing is applied before child persistence`() {
        val target = Assistant(
            regexes = listOf(
                AssistantRegex(
                    id = Uuid.random(),
                    findRegex = "secret",
                    replaceString = "redacted",
                    affectingScope = setOf(AssistantAffectScope.USER),
                )
            )
        )

        assertEquals("redacted task", preprocessSubAssistantTask("secret task", target))
    }

    private fun pendingAsk(id: String) = UIMessagePart.Tool(
        localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = id,
        toolName = "ask_user",
        input = "{}",
        interactionState = ToolInteractionState.AwaitingApproval,
    )
}
