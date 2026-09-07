package net.weero.measix.pilot.service

import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.service.subassistant.SubAssistantLifecycle
import net.weero.measix.pilot.service.turn.TurnFinalizer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.data.model.ConversationModelContextEntry
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.ToolArtifactRewriter
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.data.repository.FolderRepository
import net.weero.measix.pilot.service.runtime.ConversationCommandCoordinator
import net.weero.measix.pilot.service.runtime.ConversationRuntime
import net.weero.measix.pilot.service.runtime.ConversationHeader
import net.weero.measix.pilot.service.runtime.ConversationRuntimeRegistry
import net.weero.measix.pilot.service.runtime.ConversationRuntimeSnapshot
import net.weero.measix.pilot.service.runtime.ConversationAggregateSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationForkContextTest {
    @Test
    fun `fork passes committed folder and workspace cwd through createTree`() = runTest {
        val sourceId = Uuid.random()
        val assistantId = ConfigurationReference.random()
        val folderId = Uuid.random()
        val anchor = UIMessage.user("fork here")
        val owner = UIMessage.assistant("answer")
        val futureAnchor = UIMessage.user("future")
        val futureOwner = UIMessage.assistant("future answer")
        val anchorNode = MessageNode.of(anchor)
        val ownerNode = MessageNode.of(owner)
        val futureAnchorNode = MessageNode.of(futureAnchor)
        val futureOwnerNode = MessageNode.of(futureOwner)
        val copiedContent = ConversationDisclosureSnapshotService.render(
            ConversationDisclosureSnapshotService.Candidate(
                assistant = Assistant(id = assistantId),
                allAssistants = emptyList(),
                memories = emptyList(),
            ),
        )
        val snapshot = ConversationAggregateSnapshot(
            conversationId = sourceId,
            header = ConversationHeader(
                id = sourceId,
                title = "Source",
                assistantId = assistantId,
                folderId = folderId,
                isPinned = false,
                chatSuggestions = emptyList(),
                customSystemPrompt = null,
                modeInjectionIds = emptySet(),
                workspaceCwd = "src/main",
                parentConversationId = null,
                newConversation = false,
                createAt = 1,
                updateAt = 1,
            ),
            nodes = listOf(anchorNode, ownerNode, futureAnchorNode, futureOwnerNode),
            modelContextEntries = listOf(
                ConversationModelContextEntry(
                    ownerNodeId = ownerNode.id,
                    ownerMessageId = owner.id,
                    anchorNodeId = anchorNode.id,
                    anchorMessageId = anchor.id,
                    content = copiedContent,
                ),
                ConversationModelContextEntry(
                    ownerNodeId = futureOwnerNode.id,
                    ownerMessageId = futureOwner.id,
                    anchorNodeId = futureAnchorNode.id,
                    anchorMessageId = futureAnchor.id,
                    content = copiedContent,
                ),
            ),
        )
        val runtime = mockk<ConversationRuntime>()
        every { runtime.snapshot } returns MutableStateFlow(
            ConversationRuntimeSnapshot(durable = snapshot, stream = null),
        )
        every { runtime.durable } returns snapshot
        val commandCoordinator = mockk<ConversationCommandCoordinator>()
        coEvery { commandCoordinator.load(sourceId) } returns runtime
        val created = slot<ConversationAggregateSnapshot>()
        coEvery { commandCoordinator.createTree(capture(created), any()) } returns runtime
        val lifecycle = mockk<SubAssistantLifecycle>()
        coEvery { lifecycle.requireClosedRunsBeforeTreeMutation(snapshot) } returns snapshot
        val repository = mockk<ConversationRepository>()
        coEvery { repository.getChildConversationSnapshots(sourceId) } returns emptyList()
        val artifactStore = mockk<ArtifactStore>(relaxed = true)

        val service = ConversationApplicationService(
            settingsStore = mockk(relaxed = true),
            conversationRepo = repository,
            folderRepository = mockk<FolderRepository>(),
            runtimeRegistry = mockk<ConversationRuntimeRegistry>(),
            commandCoordinator = commandCoordinator,
            recoveryGate = mockk<ApplicationRecoveryGate>(),
            subAssistantLifecycle = lifecycle,
            sideEffects = mockk<GenerationSideEffects>(),
            artifactStore = artifactStore,
            artifactUseCase = mockk<ArtifactUseCase>(),
            turnFinalizer = mockk<TurnFinalizer>(relaxed = true),
            json = Json,
            toolArtifactRewriter = mockk<ToolArtifactRewriter>(),
            titleCoordinator = mockk<ConversationTitleCoordinator>(),
            sessions = mockk(),
        )

        service.forkAtMessage(sourceId, owner.id)

        val fork = created.captured
        assertEquals(folderId, fork.header.folderId)
        assertEquals("src/main", fork.header.workspaceCwd)
        assertEquals(1, fork.modelContextEntries.size)
        val copiedEntry = fork.modelContextEntries.single()
        assertEquals(fork.nodes[0].id, copiedEntry.anchorNodeId)
        assertEquals(anchor.id, copiedEntry.anchorMessageId)
        assertEquals(fork.nodes[1].id, copiedEntry.ownerNodeId)
        assertEquals(owner.id, copiedEntry.ownerMessageId)
        assertEquals(copiedContent, copiedEntry.content)
        coVerify(exactly = 1) { commandCoordinator.createTree(any(), emptyList()) }
    }

}
