package net.weero.measix.pilot.service.runtime

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.TurnTerminalReasons
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolInteractionState
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.data.repository.ConversationPayloadException
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.service.ApplicationRecoveryGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationCommandCoordinatorTest {
    @Test
    fun `non-resident header command never loads message nodes`() = runTest {
        val id = Uuid.random()
        val registry = mockk<ConversationRuntimeRegistry>()
        val repository = mockk<ConversationRepository>()
        every { registry.findRuntime(id) } returns null
        coEvery { repository.getConversationHeader(id) } returns
            Conversation.ofId(id).copy(title = "old").toSnapshot().header
        val write = slot<ConversationWrite>()
        coEvery { repository.commit(capture(write)) } returns true
        val coordinator = coordinator(registry, repository, now = 42L)

        coordinator.executeOrThrow(id, UpdateHeader(title = "new"))

        val mutation = (write.captured as ConversationWrite.Mutate).mutation
        assertEquals("new", mutation.headerPatch?.title)
        assertTrue(mutation.upsertedNodes.isEmpty())
        coVerify(exactly = 0) { repository.getConversationById(any()) }
        coVerify(exactly = 0) { registry.loadRuntime(any()) }
    }

    @Test
    fun `non-resident title CAS reports exact result without loading message nodes`() = runTest {
        val id = Uuid.random()
        val registry = mockk<ConversationRuntimeRegistry>()
        val repository = mockk<ConversationRepository>()
        every { registry.findRuntime(id) } returns null
        coEvery { repository.getConversationHeader(id) } returns
            Conversation.ofId(id).copy(title = "Manual").toSnapshot().header
        val write = slot<ConversationWrite>()
        coEvery { repository.commit(capture(write)) } returns true
        val coordinator = coordinator(registry, repository)

        assertFalse(coordinator.updateTitleIfCurrent(id, expectedTitle = "Local", title = "Model"))
        assertTrue(coordinator.updateTitleIfCurrent(id, expectedTitle = "Manual", title = "Manual"))
        assertTrue(coordinator.updateTitleIfCurrent(id, expectedTitle = "Manual", title = "Model"))

        assertEquals("Model", (write.captured as ConversationWrite.Mutate).mutation.headerPatch?.title)
        coVerify(exactly = 1) { repository.commit(any()) }
        coVerify(exactly = 0) { repository.getConversationById(any()) }
        coVerify(exactly = 0) { registry.loadRuntime(any()) }
    }

    @Test
    fun `resident title CAS reports a same-value match without a database write`() = runTest {
        val id = Uuid.random()
        val scope = CoroutineScope(Job())
        val runtime = ConversationRuntime(
            id,
            Conversation.ofId(id).copy(title = "Local").toSnapshot(),
            scope,
            {},
        )
        val registry = mockk<ConversationRuntimeRegistry>()
        val repository = mockk<ConversationRepository>()
        every { registry.findRuntime(id) } returns runtime
        every { registry.isDraft(id) } returns false
        val coordinator = coordinator(registry, repository)

        assertTrue(coordinator.updateTitleIfCurrent(id, expectedTitle = "Local", title = "Local"))

        assertEquals("Local", runtime.snapshot.value.durable.header.title)
        coVerify(exactly = 0) { repository.commit(any()) }
        scope.cancel()
    }

    @Test
    fun `resident persistence failure does not publish`() = runTest {
        val id = Uuid.random()
        val scope = CoroutineScope(Job())
        val runtime = ConversationRuntime(id, Conversation.ofId(id).toSnapshot(), scope, {})
        val registry = mockk<ConversationRuntimeRegistry>()
        val repository = mockk<ConversationRepository>()
        every { registry.findRuntime(id) } returns runtime
        every { registry.isDraft(id) } returns false
        coEvery { repository.commit(any()) } throws IllegalStateException("transaction failed")
        val coordinator = coordinator(registry, repository)
        val before = runtime.snapshot.value

        val result = coordinator.execute(id, AppendUserMessage(UIMessage.user("not committed")))

        assertTrue(result is ConversationCommandResult.Failure)
        assertEquals(before, runtime.snapshot.value)
        scope.cancel()
    }

    @Test
    fun `identity conflict is Conflict not Failure`() = runTest {
        val conversation = Conversation.ofId(Uuid.random())
        val scope = CoroutineScope(Job())
        val runtime = ConversationRuntime(conversation.id, conversation.toSnapshot(), scope, {})
        val registry = mockk<ConversationRuntimeRegistry>()
        val repository = mockk<ConversationRepository>()
        every { registry.findRuntime(conversation.id) } returns runtime
        every { registry.isDraft(conversation.id) } returns false
        val coordinator = coordinator(registry, repository)
        val before = runtime.snapshot.value
        runtime.publishCommitted(
            StartTurn(turnId = Uuid.random(), assistantNodeId = Uuid.random(), assistantMessageId = Uuid.random(), anchorNodeId = Uuid.random(), anchorMessageId = Uuid.random(), expectedSelectedPrefixMessageIds = emptyList(), modelContextCandidate = "", epoch = 1L),
            before.durable,
        )

        val result = coordinator.execute(conversation.id, AppendUserMessage(UIMessage.user("blocked")))

        assertTrue(result is ConversationCommandResult.Conflict)
        coVerify(exactly = 0) { repository.commit(any()) }
        scope.cancel()
    }

    @Test
    fun `stale tool approval is an identity preserving no-op without a database write`() = runTest {
        val id = Uuid.random()
        val scope = CoroutineScope(Job())
        val runtime = ConversationRuntime(id, Conversation.ofId(id).toSnapshot(), scope, {})
        val registry = mockk<ConversationRuntimeRegistry>()
        val repository = mockk<ConversationRepository>()
        every { registry.findRuntime(id) } returns runtime
        every { registry.isDraft(id) } returns false
        val coordinator = coordinator(registry, repository)
        val before = runtime.snapshot.value
        val failure = runCatching {
            coordinator.executeOrThrow(
                id,
                ResolveToolInteraction(
                    Uuid.random(),
                    Uuid.random(),
                    Uuid.random(),
                    net.weero.measix.pilot.service.runtime.ToolInteractionDecision.Approve,
                    TurnHandle(id, 1, Uuid.random(), Uuid.random()),
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is ConversationCommandConflictException)
        assertSame(before, runtime.snapshot.value)
        coVerify(exactly = 0) { repository.commit(any()) }
        scope.cancel()
    }

    @Test
    fun `concurrent non-resident toggles are serialized read modify writes`() = runTest {
        val id = Uuid.random()
        val registry = mockk<ConversationRuntimeRegistry>()
        val repository = mockk<ConversationRepository>()
        val header = AtomicReference(Conversation.ofId(id).toSnapshot().header)
        every { registry.findRuntime(id) } returns null
        coEvery { repository.getConversationHeader(id) } coAnswers { header.get() }
        coEvery { repository.commit(any()) } coAnswers {
            val patch = (args.first() as ConversationWrite.Mutate).mutation.headerPatch
            header.updateAndGet { current -> current.copy(isPinned = requireNotNull(patch?.isPinned)) }
            true
        }
        val coordinator = coordinator(registry, repository)

        (0 until 100).map {
            async(Dispatchers.Default) { coordinator.executeOrThrow(id, TogglePinned) }
        }.awaitAll()

        assertFalse(header.get().isPinned)
        coVerify(exactly = 0) { registry.loadRuntime(any()) }
    }

    @Test
    fun `runtime load and header command cannot install a stale aggregate`() = runTest {
        val id = Uuid.random()
        val durable = AtomicReference(Conversation.ofId(id).copy(title = "old"))
        val loadCaptured = CompletableDeferred<Unit>()
        val releaseLoad = CompletableDeferred<Unit>()
        val repository = mockk<ConversationRepository>()
        coEvery { repository.getConversationSnapshotById(id) } coAnswers {
            val captured = durable.get().toSnapshot()
            loadCaptured.complete(Unit)
            releaseLoad.await()
            captured
        }
        coEvery { repository.commit(any()) } coAnswers {
            val patch = (args.first() as ConversationWrite.Mutate).mutation.headerPatch
            if (patch?.title != null) durable.updateAndGet { current -> current.copy(title = patch.title) }
            true
        }
        val locks = ConversationOperationLocks()
        val appScope = AppScope(Dispatchers.Default)
        val registry = ConversationRuntimeRegistry(appScope, repository, locks)
        val gate = ApplicationRecoveryGate().apply { ready() }
        val coordinator = ConversationCommandCoordinator(registry, repository, gate, locks)

        val loading = async(Dispatchers.Default) { registry.loadRuntime(id) }
        loadCaptured.await()
        val updating = async(Dispatchers.Default) {
            coordinator.executeOrThrow(id, UpdateHeader(title = "new"))
        }
        releaseLoad.complete(Unit)

        val runtime = loading.await()
        updating.await()
        assertEquals("new", durable.get().title)
        assertEquals("new", runtime.snapshot.value.durable.header.title)
        appScope.cancel()
    }

    @Test
    fun `draft header edits remain in memory and first user message materializes the complete aggregate`() = runTest {
        val id = Uuid.random()
        val updatedAssistantId = Uuid.random()
        val repository = mockk<ConversationRepository>()
        val write = slot<ConversationWrite>()
        coEvery { repository.existsConversationById(id) } returns false
        coEvery { repository.commit(capture(write)) } returns true
        val locks = ConversationOperationLocks()
        val appScope = AppScope(Dispatchers.Default)
        val registry = ConversationRuntimeRegistry(appScope, repository, locks)
        val gate = ApplicationRecoveryGate().apply { ready() }
        val coordinator = ConversationCommandCoordinator(registry, repository, gate, locks)
        val draft = coordinator.loadOrRegisterDraft(Conversation.ofId(id, newConversation = true))

        coVerify(exactly = 0) { repository.insertConversation(any()) }
        assertTrue(registry.isDraft(id))

        coordinator.executeOrThrow(
            id,
            UpdateHeader(title = "configured draft"),
        )
        coordinator.executeOrThrow(id, MoveToAssistant(updatedAssistantId))

        coVerify(exactly = 0) { repository.insertConversation(any()) }
        assertTrue(registry.isDraft(id))
        assertEquals("configured draft", draft.snapshot.value.durable.header.title)
        assertEquals(updatedAssistantId, draft.snapshot.value.durable.header.assistantId)

        coordinator.executeOrThrow(id, AppendUserMessage(UIMessage.user("first")))

        coVerify(exactly = 1) { repository.commit(any()) }
        assertFalse(registry.isDraft(id))
        val inserted = (write.captured as ConversationWrite.MaterializeDraft).conversation
        assertEquals("configured draft", inserted.title)
        assertEquals(updatedAssistantId, inserted.assistantId)
        assertEquals("first", inserted.messageNodes.single().currentMessage.toText())
        assertEquals("first", draft.snapshot.value.durable.nodes.single().currentMessage.toText())
        appScope.cancel()
    }

    @Test
    fun `draft rejects structural commands without persistence or promotion`() = runTest {
        val id = Uuid.random()
        val repository = mockk<ConversationRepository>()
        coEvery { repository.existsConversationById(id) } returns false
        val locks = ConversationOperationLocks()
        val appScope = AppScope(Dispatchers.Default)
        val registry = ConversationRuntimeRegistry(appScope, repository, locks)
        val coordinator = ConversationCommandCoordinator(
            registry,
            repository,
            ApplicationRecoveryGate().apply { ready() },
            locks,
        )
        coordinator.loadOrRegisterDraft(Conversation.ofId(id, newConversation = true))

        val result = coordinator.execute(id, TogglePinned)

        assertTrue(result is ConversationCommandResult.Conflict)
        assertTrue(registry.isDraft(id))
        coVerify(exactly = 0) { repository.insertConversation(any()) }
        coVerify(exactly = 0) { repository.commit(any()) }
        appScope.cancel()
    }

    @Test
    fun `durable create cannot replace a resident draft`() = runTest {
        val id = Uuid.random()
        val repository = mockk<ConversationRepository>()
        coEvery { repository.existsConversationById(id) } returns false
        val locks = ConversationOperationLocks()
        val appScope = AppScope(Dispatchers.Default)
        val registry = ConversationRuntimeRegistry(appScope, repository, locks)
        val coordinator = ConversationCommandCoordinator(
            registry,
            repository,
            ApplicationRecoveryGate().apply { ready() },
            locks,
        )
        val draft = coordinator.loadOrRegisterDraft(Conversation.ofId(id, newConversation = true))

        val failure = runCatching { coordinator.create(Conversation.ofId(id)) }.exceptionOrNull()

        assertTrue(failure is ConversationCommandConflictException)
        assertSame(draft, registry.findRuntime(id))
        assertTrue(registry.isDraft(id))
        coVerify(exactly = 0) { repository.insertConversation(any()) }
        appScope.cancel()
    }

    @Test
    fun `tree create rejects a resident child draft before persistence`() = runTest {
        val masterId = Uuid.random()
        val childId = Uuid.random()
        val repository = mockk<ConversationRepository>()
        coEvery { repository.existsConversationById(any()) } returns false
        val locks = ConversationOperationLocks()
        val appScope = AppScope(Dispatchers.Default)
        val registry = ConversationRuntimeRegistry(appScope, repository, locks)
        val coordinator = ConversationCommandCoordinator(
            registry,
            repository,
            ApplicationRecoveryGate().apply { ready() },
            locks,
        )
        coordinator.loadOrRegisterDraft(Conversation.ofId(childId, newConversation = true))

        val failure = runCatching {
            coordinator.createTree(
                Conversation.ofId(masterId).toSnapshot(),
                listOf(Conversation.ofId(childId).copy(parentConversationId = masterId).toSnapshot()),
            )
        }.exceptionOrNull()

        assertTrue(failure is ConversationCommandConflictException)
        assertTrue(registry.isDraft(childId))
        coVerify(exactly = 0) { repository.insertConversationTree(any(), any()) }
        appScope.cancel()
    }

    @Test
    fun `eviction publishes missing to existing runtime observers`() = runTest {
        val id = Uuid.random()
        val repository = mockk<ConversationRepository>()
        val locks = ConversationOperationLocks()
        val appScope = AppScope(Dispatchers.Default)
        val registry = ConversationRuntimeRegistry(appScope, repository, locks)
        registry.installDraft(Conversation.ofId(id, newConversation = true))
        val state = registry.observeRuntimeState(id)

        registry.evictRuntime(id)

        assertTrue(state.value is ConversationRuntimeState.Missing)
        assertEquals(null, registry.findRuntime(id))
        appScope.cancel()
    }

    @Test
    fun `corrupt aggregate load publishes Failed and never Ready`() = runTest {
        val id = Uuid.random()
        val repository = mockk<ConversationRepository>()
        coEvery { repository.getConversationSnapshotById(id) } throws
            ConversationPayloadException("invalid message payload")
        val appScope = AppScope(Dispatchers.Default)
        val registry = ConversationRuntimeRegistry(appScope, repository, ConversationOperationLocks())

        val failure = runCatching { registry.loadRuntime(id) }.exceptionOrNull()
        val state = registry.observeRuntimeState(id).value

        assertTrue(failure is ConversationPayloadException)
        assertTrue(state is ConversationRuntimeState.Failed)
        assertFalse(state is ConversationRuntimeState.Ready)
        appScope.cancel()
    }

    @Test
    fun `start turn persists slot and running fact in one transaction call`() = runTest {
        val id = Uuid.random()
        val scope = CoroutineScope(Job())
        val conversation = Conversation.ofId(id).copy(
            messageNodes = listOf(MessageNode.of(UIMessage.user("question"))),
        )
        val runtime = ConversationRuntime(id, conversation.toSnapshot(), scope, {})
        val registry = mockk<ConversationRuntimeRegistry>()
        val repository = mockk<ConversationRepository>()
        coEvery { registry.loadRuntime(id) } returns runtime
        every { registry.isDraft(id) } returns false
        val write = slot<ConversationWrite>()
        coEvery { repository.commit(capture(write)) } returns true
        val coordinator = coordinator(registry, repository, now = 123L)
        val turnId = Uuid.random()
        val assistantId = Uuid.random()
        runtime.installTurnWorker(turnId, Job())

        val handle = coordinator.startTurn(
            id,
            TurnTransition.buildStartTurnCommand(
                current = runtime.durable,
                turnId = turnId,
                modelContextCandidate = disclosureCandidate(),
                assistantMessageId = assistantId,
            ),
        )

        assertEquals(turnId, handle.turnId)
        val mutate = write.captured as ConversationWrite.Mutate
        assertEquals(1, mutate.mutation.upsertedNodes.size)
        assertEquals(assistantId, mutate.mutation.upsertedNodes.single().currentMessage.id)
        assertEquals(TurnExecutionStatus.RUNNING, mutate.executionFacts?.turn?.status)
        assertEquals(assistantId.toString(), mutate.executionFacts?.turn?.assistantMessageId)
        assertEquals(123L, mutate.executionFacts?.turn?.createdAt)
        // 首次 START 的目标分支没有历史 entry：candidate 必然构成新 baseline，随同一事务插入。
        assertEquals(
            listOf(assistantId),
            mutate.mutation.insertedModelContextEntries.map { it.ownerMessageId },
        )
        coVerify(exactly = 1) { repository.commit(any()) }
        scope.cancel()
    }

    /**
     * entry 写失败不得发布 StartTurn Runtime snapshot；已提交 USER 保留：
     * durable commit 抛错时，Runtime 仍停留在 USER-only 树，没有流式投影，也没有 Assistant slot。
     */
    @Test
    fun `failed START commit publishes nothing and keeps the committed user message`() = runTest {
        val id = Uuid.random()
        val scope = CoroutineScope(Job())
        val userNode = MessageNode.of(UIMessage.user("question"))
        val conversation = Conversation.ofId(id).copy(messageNodes = listOf(userNode))
        val runtime = ConversationRuntime(id, conversation.toSnapshot(), scope, {})
        val registry = mockk<ConversationRuntimeRegistry>()
        val repository = mockk<ConversationRepository>()
        coEvery { registry.loadRuntime(id) } returns runtime
        every { registry.isDraft(id) } returns false
        coEvery { repository.commit(any()) } throws IllegalStateException("model context row conflicts")
        val coordinator = coordinator(registry, repository)
        val turnId = Uuid.random()
        runtime.installTurnWorker(turnId, Job())
        val before = runtime.snapshot.value

        val failure = runCatching {
            coordinator.startTurn(
                id,
                TurnTransition.buildStartTurnCommand(
                    current = before.durable,
                    turnId = turnId,
                    modelContextCandidate = disclosureCandidate(),
                    assistantMessageId = Uuid.random(),
                ),
            )
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(before, runtime.snapshot.value)
        assertNull(runtime.snapshot.value.stream)
        assertEquals(listOf(userNode.id), runtime.snapshot.value.durable.nodes.map { it.id })
        coVerify(exactly = 1) { repository.commit(any()) }
        scope.cancel()
    }

    @Test
    fun `rejected start turn does not consume epoch`() = runTest {
        val id = Uuid.random()
        val scope = CoroutineScope(Job())
        val conversation = Conversation.ofId(id).copy(
            messageNodes = listOf(MessageNode.of(UIMessage.user("question"))),
        )
        val runtime = ConversationRuntime(id, conversation.toSnapshot(), scope, {})
        val registry = mockk<ConversationRuntimeRegistry>()
        val repository = mockk<ConversationRepository>()
        coEvery { registry.loadRuntime(id) } returns runtime
        every { registry.isDraft(id) } returns false
        coEvery { repository.commit(any()) } returns true
        val coordinator = coordinator(registry, repository)
        val turnId = Uuid.random()
        val assistantId = Uuid.random()
        val command = TurnTransition.buildStartTurnCommand(
            current = runtime.durable,
            turnId = turnId,
            modelContextCandidate = disclosureCandidate(),
            assistantMessageId = assistantId,
        )

        val rejected = runCatching {
            coordinator.startTurn(id, command)
        }.exceptionOrNull()

        assertTrue(rejected is ConversationCommandConflictException)
        coVerify(exactly = 0) { repository.commit(any()) }

        runtime.installTurnWorker(turnId, Job())
        val handle = coordinator.startTurn(id, command)

        assertEquals(1L, handle.epoch)
        // 发布的流式投影必须携带同一 epoch：否则首个 checkpoint 的 owner 校验
        // 就会拒绝，turn 永远无法提交。
        assertEquals(1L, runtime.snapshot.value.stream?.epoch)
        scope.cancel()
    }

    @Test
    fun `create collision cannot overwrite durable state`() = runTest {
        val id = Uuid.random()
        val registry = mockk<ConversationRuntimeRegistry>()
        val repository = mockk<ConversationRepository>()
        every { registry.findRuntime(id) } returns null
        coEvery { repository.existsConversationById(id) } returns true
        val coordinator = coordinator(registry, repository)

        val failure = runCatching { coordinator.create(Conversation.ofId(id)) }.exceptionOrNull()

        assertTrue(failure is ConversationCommandConflictException)
        coVerify(exactly = 0) { repository.insertConversation(any()) }
        coVerify(exactly = 0) { registry.registerRuntime(any()) }
    }

    @Test
    fun `undo deletion captures complete child lineage before cascade`() = runTest {
        val rootId = Uuid.random()
        val childId = Uuid.random()
        val root = Conversation.ofId(rootId)
        val child = Conversation.ofId(childId).copy(parentConversationId = rootId)
        val registry = mockk<ConversationRuntimeRegistry>()
        val repository = mockk<ConversationRepository>()
        every { registry.findRuntime(rootId) } returns null
        every { registry.findRuntime(childId) } returns null
        coEvery { registry.evictRuntime(any()) } returns Unit
        coEvery { repository.getConversationSnapshotById(rootId) } returns root.toSnapshot()
        coEvery { repository.getConversationHeader(rootId) } returns root.toSnapshot().header
        coEvery { repository.getChildConversationIds(rootId) } returns listOf(childId)
        coEvery { repository.getChildConversationSnapshots(rootId) } returns listOf(child.toSnapshot())
        coEvery { repository.deleteConversation(rootId) } returns Unit
        val coordinator = coordinator(registry, repository)

        val deleted = coordinator.deleteCapturingTree(rootId)

        assertEquals(root.toSnapshot(), deleted.root)
        assertEquals(listOf(child.toSnapshot()), deleted.children)
        coVerify(exactly = 1) { repository.deleteConversation(rootId) }
        coVerify(exactly = 1) { registry.evictRuntime(rootId) }
        coVerify(exactly = 1) { registry.evictRuntime(childId) }
    }

    @Test
    fun `one hundred commands for one id are serialized without loss`() = runTest {
        val id = Uuid.random()
        val scope = CoroutineScope(Dispatchers.Default)
        val runtime = ConversationRuntime(id, Conversation.ofId(id).toSnapshot(), scope, {})
        val registry = mockk<ConversationRuntimeRegistry>()
        val repository = mockk<ConversationRepository>()
        every { registry.findRuntime(id) } returns runtime
        every { registry.isDraft(id) } returns false
        val inFlight = AtomicInteger()
        val maximum = AtomicInteger()
        coEvery { repository.commit(any()) } coAnswers {
            val current = inFlight.incrementAndGet()
            maximum.updateAndGet { maxOf(it, current) }
            delay(1)
            inFlight.decrementAndGet()
            true
        }
        val coordinator = coordinator(registry, repository)

        (0 until 100).map { index ->
            async(Dispatchers.Default) {
                coordinator.executeOrThrow(id, AppendUserMessage(UIMessage.user("message-$index")))
            }
        }.awaitAll()

        assertEquals(1, maximum.get())
        assertEquals(100, runtime.snapshot.value.durable.nodes.size)
        val text = runtime.snapshot.value.durable.nodes.map { it.currentMessage.toText() }.toSet()
        assertFalse((0 until 100).any { "message-$it" !in text })
        scope.cancel()
    }

    @Test
    fun `stale approval continuation cannot cancel a newer active turn`() = runTest {
        val conversation = Conversation.ofId(Uuid.random())
        val assistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "approval",
                    toolName = "approval_tool",
                    input = "{}",
                    interactionState = ToolInteractionState.AwaitingApproval,
                ),
            ),
        )
        val snapshot = conversation.copy(messageNodes = listOf(MessageNode.of(assistant))).toSnapshot()
        val repository = mockk<ConversationRepository>(relaxed = true)
        val locks = ConversationOperationLocks()
        val appScope = AppScope(Dispatchers.Unconfined)
        val registry = ConversationRuntimeRegistry(appScope, repository, locks)
        val runtime = registry.registerRuntime(conversation.copy(messageNodes = snapshot.nodes))
        val oldTurnId = Uuid.random()
        val oldJob = appScope.launch(start = CoroutineStart.LAZY) { awaitCancellation() }
        registry.installAndStartTurnWorker(runtime.id, oldTurnId, oldJob)
        val handle = TurnHandle(runtime.id, 1L, oldTurnId, assistant.id)
        runtime.publishCommitted(
            StartTurn(
                turnId = oldTurnId,
                assistantNodeId = Uuid.random(),
                assistantMessageId = assistant.id,
                anchorNodeId = Uuid.random(),
                anchorMessageId = Uuid.random(),
                expectedSelectedPrefixMessageIds = emptyList(),
                modelContextCandidate = "",
                epoch = 1L,
            ),
            runtime.durable,
        )
        runtime.retainAwaitingUser(handle)

        val newTurnId = Uuid.random()
        val newJob = appScope.launch(start = CoroutineStart.LAZY) { awaitCancellation() }
        registry.installAndStartTurnWorker(
            conversationId = runtime.id,
            turnId = newTurnId,
            worker = newJob,
            supersedeReason = TurnTerminalReasons.SUPERSEDED_BY_NEW_TURN,
        )
        assertEquals(newTurnId, runtime.currentGenerationTurnId())
        assertTrue(newJob.isActive)

        val staleJob = appScope.launch(start = CoroutineStart.LAZY) { awaitCancellation() }
        val rejected = runCatching {
            registry.installAndStartUserInteractionContinuation(runtime.id, handle, staleJob)
        }.exceptionOrNull()

        assertTrue(rejected is ConversationCommandConflictException)
        assertFalse(staleJob.isActive)
        staleJob.cancel()
        assertEquals(newTurnId, runtime.currentGenerationTurnId())
        assertTrue(newJob.isActive)
        assertFalse(newJob.isCancelled)
        appScope.cancel()
    }

    private fun coordinator(
        registry: ConversationRuntimeRegistry,
        repository: ConversationRepository,
        now: Long = 1L,
    ): ConversationCommandCoordinator {
        val gate = ApplicationRecoveryGate().apply { ready() }
        return ConversationCommandCoordinator(registry, repository, gate, ConversationOperationLocks()) { now }
    }
}
