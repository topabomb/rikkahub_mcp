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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.data.repository.ConversationPayloadException
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.service.ApplicationRecoveryGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val mutation = slot<ConversationMutation>()
        coEvery { repository.applyMutation(capture(mutation), null) } returns true
        val coordinator = coordinator(registry, repository, now = 42L)

        coordinator.executeOrThrow(id, UpdateHeader(title = "new"))

        assertEquals("new", mutation.captured.headerPatch?.title)
        assertTrue(mutation.captured.upsertedNodes.isEmpty())
        coVerify(exactly = 0) { repository.getConversationById(any()) }
        coVerify(exactly = 0) { registry.loadRuntime(any()) }
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
        coEvery { repository.applyMutation(any(), any()) } throws IllegalStateException("transaction failed")
        val coordinator = coordinator(registry, repository)
        val before = runtime.snapshot.value

        val result = coordinator.execute(id, AppendUserMessage(UIMessage.user("not committed")))

        assertTrue(result is ConversationCommandResult.Failure)
        assertEquals(before, runtime.snapshot.value)
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

        coordinator.executeOrThrow(
            id,
            UpdateToolApproval(Uuid.random(), 0, me.rerere.ai.ui.ToolApprovalState.Approved),
        )

        assertSame(before, runtime.snapshot.value)
        coVerify(exactly = 0) { repository.applyMutation(any(), any()) }
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
        coEvery { repository.applyMutation(any(), null) } coAnswers {
            val patch = firstArg<ConversationMutation>().headerPatch
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
        coEvery { repository.getConversationById(id) } coAnswers {
            val captured = durable.get()
            loadCaptured.complete(Unit)
            releaseLoad.await()
            captured
        }
        coEvery { repository.applyMutation(any(), any()) } coAnswers {
            val patch = firstArg<ConversationMutation>().headerPatch
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
        assertEquals("new", runtime.snapshot.value.header.title)
        appScope.cancel()
    }

    @Test
    fun `draft header edits remain in memory and first user message materializes the complete aggregate`() = runTest {
        val id = Uuid.random()
        val updatedAssistantId = Uuid.random()
        val repository = mockk<ConversationRepository>()
        val inserted = slot<Conversation>()
        coEvery { repository.existsConversationById(id) } returns false
        coEvery { repository.insertConversation(capture(inserted)) } returns Unit
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
        assertEquals("configured draft", draft.snapshot.value.header.title)
        assertEquals(updatedAssistantId, draft.snapshot.value.header.assistantId)

        coordinator.executeOrThrow(id, AppendUserMessage(UIMessage.user("first")))

        coVerify(exactly = 1) { repository.insertConversation(any()) }
        assertFalse(registry.isDraft(id))
        assertEquals("configured draft", inserted.captured.title)
        assertEquals(updatedAssistantId, inserted.captured.assistantId)
        assertEquals("first", inserted.captured.messageNodes.single().currentMessage.toText())
        assertEquals("first", draft.snapshot.value.nodes.single().currentMessage.toText())
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
        coVerify(exactly = 0) { repository.applyMutation(any(), any()) }
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
                Conversation.ofId(masterId),
                listOf(Conversation.ofId(childId).copy(parentConversationId = masterId)),
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
        coEvery { repository.getConversationById(id) } throws
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
        val runtime = ConversationRuntime(id, Conversation.ofId(id).toSnapshot(), scope, {})
        val registry = mockk<ConversationRuntimeRegistry>()
        val repository = mockk<ConversationRepository>()
        coEvery { registry.loadRuntime(id) } returns runtime
        every { registry.isDraft(id) } returns false
        val mutation = slot<ConversationMutation>()
        val facts = slot<ExecutionFacts>()
        coEvery { repository.applyMutation(capture(mutation), capture(facts)) } returns true
        val coordinator = coordinator(registry, repository, now = 123L)
        val turnId = Uuid.random()
        val assistantId = Uuid.random()

        val handle = coordinator.startTurn(id, turnId, assistantId, resume = false)

        assertEquals(turnId, handle.turnId)
        assertEquals(1, mutation.captured.upsertedNodes.size)
        assertEquals(TurnExecutionStatus.RUNNING, facts.captured.turn?.status)
        assertEquals(123L, facts.captured.turn?.createdAt)
        coVerify(exactly = 1) { repository.applyMutation(any(), any()) }
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
        coEvery { repository.getConversationById(rootId) } returns root
        coEvery { repository.getConversationHeader(rootId) } returns root.toSnapshot().header
        coEvery { repository.getChildConversationIds(rootId) } returns listOf(childId)
        coEvery { repository.getChildConversations(rootId) } returns listOf(child)
        coEvery { repository.deleteConversation(rootId) } returns Unit
        val coordinator = coordinator(registry, repository)

        val deleted = coordinator.deleteCapturingTree(rootId)

        assertEquals(root, deleted.root)
        assertEquals(listOf(child), deleted.children)
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
        coEvery { repository.applyMutation(any(), any()) } coAnswers {
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
        assertEquals(100, runtime.snapshot.value.nodes.size)
        val text = runtime.snapshot.value.nodes.map { it.currentMessage.toText() }.toSet()
        assertFalse((0 until 100).any { "message-$it" !in text })
        scope.cancel()
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
