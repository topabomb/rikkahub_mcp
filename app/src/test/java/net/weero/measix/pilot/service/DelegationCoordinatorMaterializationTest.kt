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
import me.rerere.ai.core.ToolResourceLease
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.GenerationHandler
import net.weero.measix.pilot.data.ai.attachments.AttachmentFailureReasons
import net.weero.measix.pilot.data.ai.attachments.AttachmentResolveResult
import net.weero.measix.pilot.data.ai.attachments.AttachmentResolver
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.tools.GenerationToolSetFactory
import net.weero.measix.pilot.data.ai.tools.local.LocalToolOption
import net.weero.measix.pilot.data.ai.transformers.TemplateTransformer
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.files.ArtifactDeleteResult
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.OwnedArtifact
import net.weero.measix.pilot.data.files.ToolArtifactRewriter
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.data.repository.MemoryRepository
import net.weero.measix.pilot.data.repository.WorkspaceRepository
import net.weero.measix.pilot.service.runtime.ConversationCommandCoordinator
import net.weero.measix.pilot.service.runtime.ConversationRuntime
import net.weero.measix.pilot.service.runtime.ConversationRuntimeRegistry
import net.weero.measix.pilot.service.runtime.DelegationCoordinator
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class DelegationCoordinatorMaterializationTest {
    private val callerId = Uuid.random()
    private val targetId = Uuid.random()
    private val masterId = Uuid.random()

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
            attachments = listOf("attachment:11111111-1111-1111-1111-111111111111"),
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
            attachments = listOf("attachment:11111111-1111-1111-1111-111111111111"),
        )

        assertTrue(result.isNotEmpty())
        coVerify(exactly = 0) { harness.commandCoordinator.create(any()) }
    }

    @Test
    fun `child creation failure rolls back resolver artifacts`() = runTest {
        val owned = mockk<OwnedArtifact>()
        val harness = harness(AttachmentResolveResult.Success(listOf(UIMessagePart.Image("file:///tmp/a.png")), listOf(owned)))
        coEvery { harness.commandCoordinator.create(any()) } throws IllegalStateException("db down")
        coEvery { harness.commandCoordinator.deleteOrThrow(any()) } just Runs
        coEvery { harness.artifactStore.discardUnpublished(owned) } returns ArtifactDeleteResult.Completed(42L)

        val result = harness.coordinator.executeCall(
            callerAssistantId = callerId,
            masterConversationId = masterId,
            targetAssistantId = targetId,
            task = "look at this",
            execContext = executionContext(),
            attachments = listOf("attachment:11111111-1111-1111-1111-111111111111"),
        )

        assertTrue(result.isNotEmpty())
        coVerify(exactly = 1) { harness.artifactStore.discardUnpublished(owned) }
    }

    private fun executionContext(
        reportMetadata: suspend (JsonObject, Boolean) -> Unit = { _, _ -> },
        reportChild: suspend (String) -> Unit = { },
    ) = ToolExecutionContext(
        messageId = Uuid.random(),
        toolOrdinal = 0,
        toolCallId = "call",
        reportMetadata = reportMetadata,
        resolveAttachments = { ToolAttachmentResolution() },
        reportChildConversation = reportChild,
        registerUnpublishedResource = { },
    )

    private fun harness(resolveResult: AttachmentResolveResult): Harness {
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
        every { settingsStore.settingsFlow } returns MutableStateFlow(
            Settings(
                assistants = listOf(caller, target),
                assistantId = callerId,
                chatModelId = modelId,
                providers = listOf(ProviderSetting.OpenAI(models = listOf(model))),
            )
        )
        val resolver = mockk<AttachmentResolver>()
        coEvery { resolver.resolve(any(), any()) } returns resolveResult
        val runtimeRegistry = mockk<ConversationRuntimeRegistry>(relaxed = true)
        every { runtimeRegistry.findRuntime(masterId) } returns null
        val commandCoordinator = mockk<ConversationCommandCoordinator>()
        val artifactStore = mockk<ArtifactStore>(relaxed = true)
        every { artifactStore.unpublishedLease(any()) } returns ToolResourceLease({}, {})
        val coordinator = DelegationCoordinator(
            generationHandler = mockk<GenerationHandler>(relaxed = true),
            conversationRepo = mockk<ConversationRepository>(relaxed = true),
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
            turnFinalization = mockk<TurnFinalization>(relaxed = true),
            runGate = SubAssistantRunGate(),
        )
        return Harness(coordinator, commandCoordinator, artifactStore)
    }

    private data class Harness(
        val coordinator: DelegationCoordinator,
        val commandCoordinator: ConversationCommandCoordinator,
        val artifactStore: ArtifactStore,
    )
}
