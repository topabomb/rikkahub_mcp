package net.weero.measix.pilot.service

import net.weero.measix.pilot.service.runtime.inlineContextText
import net.weero.measix.pilot.service.runtime.disclosurePayload
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.service.subassistant.SubAssistantLifecycle
import net.weero.measix.pilot.service.turn.TurnFinalizer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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
import net.weero.measix.pilot.service.runtime.ConversationHeader
import net.weero.measix.pilot.service.runtime.ConversationRuntimeRegistry
import net.weero.measix.pilot.service.runtime.ConversationAggregateSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class ConversationForkContextTest {
    @get:org.junit.Rule val temporary = org.junit.rules.TemporaryFolder()
    @Test
    fun `fork passes committed folder and workspace cwd through createTree`() = runTest { verifyFork(false) }

    @Test
    fun `failed fork creation discards only its cloned artifact and retains the source`() = runTest { verifyFork(true) }

    private suspend fun kotlinx.coroutines.test.TestScope.verifyFork(failCreate: Boolean) {
        val sourceId = Uuid.random()
        val assistantId = ConfigurationReference.random()
        val folderId = Uuid.random()
        val sourceFile = temporary.newFile("source.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val sourceRef = net.weero.measix.pilot.data.files.LocalArtifactRef(relativePath = "upload/source.png", mimeType = "image/png")
        val anchor = UIMessage.user("fork here").let { message ->
            if (failCreate) message.copy(parts = message.parts + me.rerere.ai.ui.UIMessagePart.Image(android.net.Uri.fromFile(sourceFile).toString()))
            else message
        }
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
                    payload = disclosurePayload(copiedContent),
                ),
                ConversationModelContextEntry(
                    ownerNodeId = futureOwnerNode.id,
                    ownerMessageId = futureOwner.id,
                    anchorNodeId = futureAnchorNode.id,
                    anchorMessageId = futureAnchor.id,
                    payload = disclosurePayload(copiedContent),
                ),
            ),
        )
        val repository = mockk<ConversationRepository>()
        val appScope = net.weero.measix.pilot.AppScope(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        val locks = net.weero.measix.pilot.service.runtime.ConversationOperationLocks()
        val registry = ConversationRuntimeRegistry(appScope, repository, locks)
        val gate = ApplicationRecoveryGate().apply { ready() }
        val commandCoordinator = ConversationCommandCoordinator(registry, repository, gate, locks)
        val sessions = net.weero.measix.pilot.data.enterprise.EnterpriseSessionController(
            net.weero.measix.pilot.data.enterprise.enterpriseTestStore(temporary.newFolder()),
        )
        sessions.recover()
        registry.registerSnapshot(snapshot)
        val created = slot<ConversationAggregateSnapshot>()
        coEvery { repository.getChildConversationIds(sourceId) } returns emptyList()
        coEvery { repository.getChildConversationSnapshots(sourceId) } returns emptyList()
        coEvery { repository.existsConversationById(any()) } returns false
        coEvery { repository.getRootConversationTitles(snapshot.header.scope, assistantId) } returns listOf("Source", "Source(1)", "Source(3)")
        val createFailure = java.io.IOException("createTree failed")
        coEvery { repository.insertConversationTree(capture(created), any()) } coAnswers {
            if (failCreate) throw createFailure
        }
        coEvery { repository.getConversationHeader(match { it != sourceId }) } returns null
        val lifecycle = mockk<SubAssistantLifecycle>()
        coEvery { lifecycle.requireClosedRunsBeforeTreeMutation(snapshot) } returns snapshot
        val artifactStore = mockk<ArtifactStore>()
        val owned = mockk<net.weero.measix.pilot.data.files.OwnedArtifact>()
        io.mockk.every { owned.uri } returns android.net.Uri.fromFile(temporary.newFile("copied.png"))
        io.mockk.every { artifactStore.file(sourceRef) } returns sourceFile
        coEvery { artifactStore.resolveManagedReference(sourceFile) } returns sourceRef
        coEvery { artifactStore.copyFilePreservingOrigin(sourceFile, "image/png", sourceFile.name, any()) } returns owned
        coEvery { artifactStore.publishAllUnpublished(any()) } returns Unit
        coEvery { artifactStore.discardUnpublished(owned) } returns net.weero.measix.pilot.data.files.ArtifactDeleteResult.Completed(7)

        val service = ConversationApplicationService(
            settingsStore = mockk(relaxed = true),
            conversationRepo = repository,
            folderRepository = mockk<FolderRepository>(),
            runtimeRegistry = registry,
            commandCoordinator = commandCoordinator,
            recoveryGate = gate,
            subAssistantLifecycle = lifecycle,
            sideEffects = mockk<GenerationSideEffects>(),
            artifactStore = artifactStore,
            artifactUseCase = mockk<ArtifactUseCase>(),
            turnFinalizer = TurnFinalizer(repository, registry, commandCoordinator, Json),
            json = Json,
            toolArtifactRewriter = mockk<ToolArtifactRewriter>(),
            titleCoordinator = ConversationTitleCoordinator(),
            sessions = sessions,
            subAssistantRunGate = mockk(),
        )

        try {
            val selection = sessions.observeSelectedRealmSelection().first { it != null }!!
            val page = ConversationViewLease(sourceId, selection.access, selection.revision) {}
            if (failCreate) {
                try {
                    service.forkAtMessage(page.commandTarget, owner.id)
                    org.junit.Assert.fail("create failure must propagate")
                } catch (error: java.io.IOException) {
                    org.junit.Assert.assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it === createFailure })
                    assertEquals(createFailure.message, error.message)
                    assertEquals(0, error.suppressed.size)
                }
                coVerify(exactly = 1) { artifactStore.discardUnpublished(owned) }
                coVerify(exactly = 0) { artifactStore.publishAllUnpublished(any()) }
                org.junit.Assert.assertArrayEquals(byteArrayOf(1, 2, 3), sourceFile.readBytes())
                return
            }
            service.forkAtMessage(page.commandTarget, owner.id)

            val fork = created.captured
            assertEquals("Source(2)", fork.header.title)
            assertEquals(folderId, fork.header.folderId)
            assertEquals("src/main", fork.header.workspaceCwd)
            assertEquals(1, fork.modelContextEntries.size)
            val copiedEntry = fork.modelContextEntries.single()
            assertEquals(fork.nodes[0].id, copiedEntry.anchorNodeId)
            assertEquals(anchor.id, copiedEntry.anchorMessageId)
            assertEquals(fork.nodes[1].id, copiedEntry.ownerNodeId)
            assertEquals(owner.id, copiedEntry.ownerMessageId)
            assertEquals(copiedContent, copiedEntry.inlineContextText())
            coVerify(exactly = 1) { repository.insertConversationTree(any(), emptyList()) }
        } finally { appScope.cancel() }
    }

}
