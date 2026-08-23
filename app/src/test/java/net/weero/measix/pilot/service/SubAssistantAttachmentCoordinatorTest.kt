package net.weero.measix.pilot.service

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ToolExecutionContext
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.FinishedReason
import net.weero.measix.pilot.service.runtime.AppendUserMessage
import net.weero.measix.pilot.service.runtime.BeginTurn
import net.weero.measix.pilot.service.runtime.ConversationCommand
import net.weero.measix.pilot.service.TurnRecovery
import net.weero.measix.pilot.service.runtime.ConversationRuntime
import net.weero.measix.pilot.service.runtime.ConversationRuntimeRegistry
import net.weero.measix.pilot.service.runtime.DelegationCoordinator
import net.weero.measix.pilot.service.runtime.toSnapshot
import net.weero.measix.pilot.data.ai.GenerationChunk
import net.weero.measix.pilot.data.ai.GenerationHandler
import net.weero.measix.pilot.data.ai.attachments.AttachmentFailureReasons
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.data.ai.attachments.AttachmentResolveResult
import net.weero.measix.pilot.data.ai.attachments.AttachmentResolver
import net.weero.measix.pilot.data.ai.tools.GenerationToolSetFactory
import net.weero.measix.pilot.data.ai.tools.local.LocalToolOption
import net.weero.measix.pilot.data.ai.transformers.TemplateTransformer
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.files.FilesManager
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.ai.subassistant.buildInitialSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.findPreviousCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.mergeSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.resolveLineage
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.toMessageNode
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.data.repository.MemoryRepository
import net.weero.measix.pilot.data.repository.WorkspaceRepository
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SubAssistantAttachmentCoordinatorTest {
    private val callerId = Uuid.random()
    private val targetId = Uuid.random()
    private val masterId = Uuid.random()
    private val modelId = Uuid.random()

    @Test
    fun `vision target writes child user image and keeps task message id`() = runTest {
        val image = UIMessagePart.Image(
            url = "file:///tmp/a.png",
            metadata = kotlinx.serialization.json.buildJsonObject {
                put(
                    AttachmentRefs.METADATA_KEY,
                    kotlinx.serialization.json.JsonPrimitive(AttachmentRefs.format(Uuid.random())),
                )
            },
        )
        val harness = harness(
            modelModalities = listOf(Modality.TEXT, Modality.IMAGE),
            resolveResult = AttachmentResolveResult.Success(listOf(image)),
        )
        val inserted = slot<Conversation>()
        coEvery { harness.conversationRepo.insertConversation(capture(inserted)) } returns Unit

        harness.coordinator.executeCall(
            callerAssistantId = callerId,
            masterConversationId = masterId,
            targetAssistantId = targetId,
            task = "Describe the image",
            execContext = execContext(),
            attachments = listOf(AttachmentRefs.getRef(image)!!),
        )

        val child = inserted.captured
        val user = child.currentMessages.single { it.role == MessageRole.USER }
        assertEquals("Describe the image", (user.parts.first() as UIMessagePart.Text).text)
        assertTrue(user.parts.any { it is UIMessagePart.Image && it.url == image.url })
        assertTrue(user.id.toString().isNotBlank())
        coVerify {
            harness.childSession.submit(match { it is BeginTurn })
        }
    }

    @Test
    fun `text target still creates child with durable image parts`() = runTest {
        // 能力不足不是附件失败：Target 模型不接收 IMAGE 时 Child 照常创建，
        // durable 消息保留 Image parts，视觉投影交给 Target run 的 AttachmentProjectionTransformer。
        val image = UIMessagePart.Image(url = "file:///tmp/a.png")
        val harness = harness(
            modelModalities = listOf(Modality.TEXT),
            resolveResult = AttachmentResolveResult.Success(listOf(image)),
        )
        val inserted = slot<Conversation>()
        coEvery { harness.conversationRepo.insertConversation(capture(inserted)) } returns Unit

        harness.coordinator.executeCall(
            callerAssistantId = callerId,
            masterConversationId = masterId,
            targetAssistantId = targetId,
            task = "Describe the image",
            execContext = execContext(),
            attachments = listOf("attachment:11111111-1111-1111-1111-111111111111"),
        )

        val child = inserted.captured
        val user = child.currentMessages.single { it.role == MessageRole.USER }
        assertTrue(user.parts.any { it is UIMessagePart.Image && it.url == image.url })
    }

    @Test
    fun `reuse appends one user message with images and keeps that id as task id`() = runTest {
        val image = UIMessagePart.Image(
            url = "file:///tmp/a.png",
            metadata = kotlinx.serialization.json.buildJsonObject {
                put(
                    AttachmentRefs.METADATA_KEY,
                    kotlinx.serialization.json.JsonPrimitive(AttachmentRefs.format(Uuid.random())),
                )
            },
        )
        val childId = Uuid.random()
        val previousTask = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("old")))
        val child = Conversation(
            id = childId,
            assistantId = targetId,
            messageNodes = listOf(previousTask.toMessageNode()),
            parentConversationId = masterId,
        )
        val previousTool = UIMessagePart.Tool(
            toolCallId = "prev",
            toolName = "assistant_call",
            input = "{}",
            output = listOf(UIMessagePart.Text("done")),
        ).mergeSubAssistantCallMetadata(
            JsonInstant,
            buildInitialSubAssistantCallMetadata(
                runId = "run-prev",
                targetAssistantId = targetId,
                targetNameSnapshot = "Target",
            ).copy(
                childConversationId = childId.toString(),
                childTaskNodeId = previousTask.id.toString(),
                state = net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallState.COMPLETED,
            ),
        )
        val previousAssistant = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(previousTool))
        val currentAssistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(toolCallId = "cur", toolName = "assistant_call", input = "{}"),
            ),
        )
        val updates = mutableListOf<Conversation>()
        val inserts = mutableListOf<Conversation>()
        val submittedCommands = mutableListOf<ConversationCommand>()
        val harness = harness(
            modelModalities = listOf(Modality.TEXT, Modality.IMAGE),
            resolveResult = AttachmentResolveResult.Success(listOf(image)),
            masterMessages = listOf(previousAssistant, currentAssistant),
            existingChild = child,
        )
        io.mockk.coEvery { harness.conversationRepo.getConversationById(childId) } returns child
        io.mockk.coEvery { harness.conversationRepo.updateConversation(any()) } answers {
            updates += invocation.args[0] as Conversation
        }
        io.mockk.coEvery { harness.conversationRepo.insertConversation(any()) } answers {
            inserts += invocation.args[0] as Conversation
        }
        // reuseChild 走 AppendUserMessage 命令（经 session.submit 唯一提交通道）
        io.mockk.coEvery { harness.childSession.submit(any()) } answers {
            submittedCommands += invocation.args[0] as ConversationCommand
            child.toSnapshot()
        }
        val previous = findPreviousCallMetadata(
            masterMessages = listOf(previousAssistant, currentAssistant),
            currentMessageId = currentAssistant.id,
            currentToolOrdinal = 0,
            targetAssistantId = targetId,
            json = JsonInstant,
        )
        assertEquals(childId.toString(), previous?.childConversationId)
        assertTrue(
            resolveLineage(previous, child, masterId, targetId)
                is net.weero.measix.pilot.data.ai.subassistant.LineageDecision.ReuseChild,
        )

        harness.coordinator.executeCall(
            callerAssistantId = callerId,
            masterConversationId = masterId,
            targetAssistantId = targetId,
            task = "Look again",
            execContext = ToolExecutionContext(
                messageId = currentAssistant.id,
                toolOrdinal = 0,
                toolCallId = "cur",
                reportMetadata = { _, _ -> },
            ),
            attachments = listOf(AttachmentRefs.getRef(image)!!),
        )

        // reuseChild 追加任务消息走 AppendUserMessage 命令（session.submit 唯一提交通道）
        val appendCommand = submittedCommands.filterIsInstance<AppendUserMessage>().singleOrNull()
            ?: error("no AppendUserMessage submitted; commands=${submittedCommands.size} updates=${updates.size}")
        val task = appendCommand.message
        assertEquals(MessageRole.USER, task.role)
        assertEquals("Look again", (task.parts.first() as UIMessagePart.Text).text)
        assertTrue(task.parts.any { it is UIMessagePart.Image && it.url == image.url })
        assertTrue(task.id.toString().isNotBlank())
        // 不再走整对象回写（单一写者不变式）
        assertTrue(updates.isEmpty())
        assertTrue(inserts.isEmpty())
    }

    @Test
    fun `resolver failure does not create a child`() = runTest {
        val harness = harness(
            modelModalities = listOf(Modality.TEXT, Modality.IMAGE),
            resolveResult = AttachmentResolveResult.Failure(AttachmentFailureReasons.ATTACHMENT_NOT_FOUND),
        )
        val result = harness.coordinator.executeCall(
            callerAssistantId = callerId,
            masterConversationId = masterId,
            targetAssistantId = targetId,
            task = "Describe the image",
            execContext = execContext(),
            attachments = listOf("attachment:11111111-1111-1111-1111-111111111111"),
        )
        val payload = JsonInstant.parseToJsonElement((result.single() as UIMessagePart.Text).text)
            .let { it as kotlinx.serialization.json.JsonObject }
        assertEquals(
            AttachmentFailureReasons.ATTACHMENT_NOT_FOUND,
            payload["reason"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content },
        )
        coVerify(exactly = 0) { harness.conversationRepo.insertConversation(any()) }
    }

    @Test
    fun `resolver created files are rolled back when child creation fails`() = runTest {
        val image = UIMessagePart.Image(url = "file:///tmp/a.png")
        val harness = harness(
            modelModalities = listOf(Modality.TEXT, Modality.IMAGE),
            resolveResult = AttachmentResolveResult.Success(
                parts = listOf(image),
                createdManagedFileIds = listOf(42L),
            ),
        )
        coEvery { harness.conversationRepo.insertConversation(any()) } throws IllegalStateException("db down")

        val result = harness.coordinator.executeCall(
            callerAssistantId = callerId,
            masterConversationId = masterId,
            targetAssistantId = targetId,
            task = "look at this",
            execContext = execContext(),
            attachments = listOf("attachment:11111111-1111-1111-1111-111111111111"),
        )

        // Child 写入失败：返回失败结果，同时本批新落地的远程文件被清理
        assertTrue(result.isNotEmpty())
        coVerify(exactly = 1) { harness.filesManager.deleteManagedFilePermanently(42L, deleteFromDisk = true) }
    }

    private fun execContext() = ToolExecutionContext(
        messageId = Uuid.random(),
        toolOrdinal = 0,
        toolCallId = "call",
        reportMetadata = { _, _ -> },
    )

    private fun harness(
        modelModalities: List<Modality>,
        resolveResult: AttachmentResolveResult,
        masterMessages: List<UIMessage> = emptyList(),
        existingChild: Conversation? = null,
    ): Harness {
        val model = Model(
            id = modelId,
            modelId = "m",
            displayName = "M",
            type = ModelType.CHAT,
            inputModalities = modelModalities,
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
        val settings = Settings(
            assistants = listOf(caller, target),
            assistantId = callerId,
            chatModelId = modelId,
            providers = listOf(ProviderSetting.OpenAI(models = listOf(model))),
        )
        val settingsStore = mockk<SettingsStore>()
        every { settingsStore.settingsFlow } returns MutableStateFlow(settings)
        val conversationRepo = mockk<ConversationRepository>(relaxed = true)
        if (existingChild != null) {
            coEvery { conversationRepo.getConversationById(existingChild.id) } returns existingChild
        }
        val sessionRegistry = mockk<ConversationRuntimeRegistry>(relaxed = true)
        val masterSession = mockk<ConversationRuntime>(relaxed = true)
        every { masterSession.snapshot } returns MutableStateFlow(
            Conversation.ofId(
                id = masterId,
                assistantId = callerId,
                messages = masterMessages.map { it.toMessageNode() },
            ).toSnapshot(),
        )
        every { sessionRegistry.getSession(any()) } returns masterSession
        val childSession = mockk<ConversationRuntime>(relaxed = true)
        every { childSession.snapshot } returns MutableStateFlow(
            Conversation.ofId(Uuid.random(), assistantId = targetId).toSnapshot(),
        )
        every { childSession.processingStatus } returns MutableStateFlow(null)
        every { sessionRegistry.getOrCreateSessionWithConversation(any(), any()) } returns childSession
        every { sessionRegistry.getOrCreateSession(any()) } returns childSession
        val generationHandler = mockk<GenerationHandler>(relaxed = true)
        every {
            generationHandler.generateText(
                settings = any(),
                model = any(),
                messages = any(),
                assistant = any(),
            )
        } returns flowOf(GenerationChunk.Finished(FinishedReason.COMPLETED))
        val resolver = mockk<AttachmentResolver>()
        coEvery { resolver.resolve(any(), any()) } returns resolveResult
        val filesManager = mockk<FilesManager>(relaxed = true)
        val coordinator = DelegationCoordinator(
            generationHandler = generationHandler,
            conversationRepo = conversationRepo,
            sessionRegistry = sessionRegistry,
            toolSetFactory = mockk<GenerationToolSetFactory>(relaxed = true),
            settingsStore = settingsStore,
            memoryRepository = mockk<MemoryRepository>(relaxed = true),
            templateTransformer = mockk<TemplateTransformer>(relaxed = true),
            workspaceRepository = mockk<WorkspaceRepository>(relaxed = true),
            filesManager = filesManager,
            json = JsonInstant,
            attachmentResolver = resolver,
            context = mockk(relaxed = true),
            turnRecovery = TurnRecovery(
                conversationRepo = conversationRepo,
                sessionRegistry = sessionRegistry,
                settingsStore = settingsStore,
                json = JsonInstant,
            ),
            runGate = SubAssistantRunGate(),
        )
        return Harness(coordinator, conversationRepo, filesManager, childSession)
    }

    private class Harness(
        val coordinator: DelegationCoordinator,
        val conversationRepo: ConversationRepository,
        val filesManager: FilesManager,
        val childSession: ConversationRuntime,
    )
}
