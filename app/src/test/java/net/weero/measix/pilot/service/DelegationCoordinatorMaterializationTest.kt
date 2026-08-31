package net.weero.measix.pilot.service

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
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
import net.weero.measix.pilot.data.ai.GenerationLoop
import net.weero.measix.pilot.data.ai.attachments.AttachmentFailureReasons
import net.weero.measix.pilot.data.ai.attachments.AttachmentResolveResult
import net.weero.measix.pilot.data.ai.attachments.AttachmentResolver
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallState
import net.weero.measix.pilot.data.ai.subassistant.mergeSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.tools.GenerationToolSetFactory
import net.weero.measix.pilot.data.ai.tools.local.LocalToolOption
import net.weero.measix.pilot.data.ai.transformers.TemplateTransformer
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
import net.weero.measix.pilot.service.runtime.ConversationRuntime
import net.weero.measix.pilot.service.runtime.ConversationRuntimeRegistry
import net.weero.measix.pilot.service.runtime.DelegationCoordinator
import net.weero.measix.pilot.service.runtime.toSnapshot
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class DelegationCoordinatorMaterializationTest {
    private val callerId = Uuid.random()
    private val targetId = Uuid.random()
    private val masterId = Uuid.random()
    private val currentMessageId = Uuid.random()

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
    fun `publication failure after child link commit retains child and linked terminal metadata`() = runTest {
        val owned = mockk<OwnedArtifact>(relaxed = true)
        every { owned.uri.toString() } returns "file:///tmp/copied.png"
        val harness = harness(AttachmentResolveResult.Success(emptyList()), cloneArtifact = owned)
        val created = slot<Conversation>()
        coEvery { harness.commandCoordinator.create(capture(created)) } returns mockk<ConversationRuntime>()
        var linkCommitted = false
        coEvery { harness.artifactStore.publishAllUnpublished(listOf(owned)) } coAnswers {
            assertTrue(linkCommitted)
            error("publication failed")
        }
        val terminalMetadata = slot<SubAssistantCallMetadata>()
        coEvery { harness.turnFinalization.finalizeSubAssistantRun(any(), any(), any(), capture(terminalMetadata)) } just Runs
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

        assertTrue(result.filterIsInstance<UIMessagePart.Text>().single().text.contains("failed"))
        assertEquals(1, patches.size)
        assertEquals(created.captured.id.toString(), terminalMetadata.captured.childConversationId)
        assertEquals(SubAssistantCallState.FAILED, terminalMetadata.captured.state)
        coVerify(exactly = 1) { harness.artifactStore.publishAllUnpublished(listOf(owned)) }
        coVerify(exactly = 0) { harness.commandCoordinator.deleteOrThrow(any()) }
        coVerify(exactly = 0) { harness.artifactStore.discardUnpublished(any()) }
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

    private fun executionContext(
        reportMetadata: suspend (JsonObject, ToolMetadataDelivery) -> Unit = { _, _ -> },
        reportChild: suspend (String) -> Unit = { },
    ) = ToolExecutionContext(
        messageId = currentMessageId,
        toolOrdinal = 0,
        toolCallId = "call",
        reportMetadata = reportMetadata,
        resolveAttachments = { ToolAttachmentResolution() },
        reportChildConversation = reportChild,
        registerUnpublishedResource = { },
    )

    private fun harness(resolveResult: AttachmentResolveResult, cloneArtifact: OwnedArtifact? = null): Harness {
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
        every { settingsStore.effectiveSettings } returns MutableStateFlow(
            Settings(
                assistants = listOf(caller, target),
                assistantId = callerId,
                chatModelId = modelId,
                providers = listOf(ProviderSetting.OpenAI(models = listOf(model))),
            ).toEffectiveSettingsSnapshot(),
        )
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
                toolName = "assistant_call", toolCallId = "previous", input = "{}",
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
                        UIMessagePart.Tool(toolName = "assistant_call", toolCallId = "call", input = "{}"),
                    )).toMessageNode(),
                ),
            )
            val runtime = mockk<ConversationRuntime>()
            every { runtime.snapshot } returns MutableStateFlow(master.toSnapshot())
            every { runtimeRegistry.findRuntime(masterId) } returns runtime
            coEvery { conversationRepo.getConversationById(source.id) } returns source
            val originalRef = LocalArtifactRef(relativePath = "upload/source.png", mimeType = "image/png")
            coEvery { artifactStore.resolveManagedReference(any()) } returns originalRef
            every { artifactStore.file(originalRef) } returns sourceFile
            coEvery { artifactStore.copyFilePreservingOrigin(any(), any(), any(), any()) } returns cloneArtifact
        }
        val turnFinalization = mockk<TurnFinalization>(relaxed = true)
        val coordinator = DelegationCoordinator(
            generationLoop = mockk<GenerationLoop>(relaxed = true),
            conversationRepo = conversationRepo,
            runtimeRegistry = runtimeRegistry,
            commandCoordinator = commandCoordinator,
            toolSetFactory = mockk<GenerationToolSetFactory>(relaxed = true),
            settingsStore = settingsStore,
            memoryRepository = mockk<MemoryRepository>(relaxed = true),
            templateTransformer = mockk<TemplateTransformer>(relaxed = true),
            workspaceRepository = mockk<WorkspaceRepository>(relaxed = true),
            artifactStore = artifactStore,
            toolArtifactRewriter = mockk<ToolArtifactRewriter>(relaxed = true),
            json = JsonInstant,
            attachmentResolver = resolver,
            context = mockk(relaxed = true),
            turnFinalization = turnFinalization,
            runGate = SubAssistantRunGate(),
        )
        return Harness(coordinator, commandCoordinator, artifactStore, turnFinalization, readHeld)
    }

    private data class Harness(
        val coordinator: DelegationCoordinator,
        val commandCoordinator: ConversationCommandCoordinator,
        val artifactStore: ArtifactStore,
        val turnFinalization: TurnFinalization,
        val readHeld: java.util.concurrent.atomic.AtomicBoolean,
    )
}
