package net.weero.measix.pilot.service

import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.data.ai.subassistant.*
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.toMessageNode
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.service.runtime.*
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubAssistantDetailReaderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `navigation never serializes the borrowed capability and restored detail is unavailable`() = runTest {
        val f = fixture()
        val route = Screen.SubAssistantDetail("run", f.source)
        val json = Json.encodeToString(Screen.SubAssistantDetail.serializer(), route)
        assertFalse(json.contains("source"))
        assertFalse(json.contains(f.master.id.toString()))
        val restored = Json.decodeFromString(Screen.SubAssistantDetail.serializer(), json)
        assertNull(restored.source)
        assertEquals(SubAssistantDetailUiState.Unavailable, f.reader.observe(restored.source, restored.runId).first())
        verify(exactly = 0) { f.registry.observeRuntimeState(any()) }
        assertFalse(f.source.closed.value)
    }

    @Test fun `parent closure revokes detail and releases only child subscription`() = runTest {
        val f = fixture()
        val states = mutableListOf<SubAssistantDetailUiState>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.reader.observe(f.source, "run").toList(states) }
        runCurrent()
        assertTrue(states.last() is SubAssistantDetailUiState.Ready)
        f.source.close()
        runCurrent()
        assertEquals(SubAssistantDetailUiState.Unavailable, states.last())
        verify(exactly = 1) { f.childLease.close() }
        assertEquals(1, f.parentCloseCount)
        job.cancelAndJoin()
        verify(exactly = 1) { f.childLease.close() }
    }

    @Test fun `leave and return cannot revive original detail or acquire another child lease`() = runTest {
        val f = fixture()
        val states = mutableListOf<SubAssistantDetailUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.reader.observe(f.source, "run").toList(states) }
        runCurrent()
        assertTrue(states.last() is SubAssistantDetailUiState.Ready)
        f.sessions.selectPersonalFixture()
        f.sessions.selectEnterpriseFixture()
        runCurrent()
        assertEquals(SubAssistantDetailUiState.Unavailable, states.last())
        assertTrue(f.source.closed.value)
        assertEquals(SubAssistantDetailUiState.Unavailable, f.reader.observe(f.source, "run").first())
        coVerify(exactly = 1) { f.registry.acquireRegisteredRuntime(f.child.id, any()) }
        verify(exactly = 1) { f.childLease.close() }
    }

    @Test fun `foreign child is rejected before runtime load without closing parent`() = runTest {
        val f = fixture()
        coEvery { f.repository.getConversationHeader(f.child.id) } returns f.child.toSnapshot().header.copy(scope = ConfigurationScope.Personal)
        val states = mutableListOf<SubAssistantDetailUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.reader.observe(f.source, "run").toList(states) }
        runCurrent()
        assertEquals(SubAssistantDetailUiState.Unavailable, states.last())
        assertFalse(f.source.closed.value)
        coVerify(exactly = 0) { f.registry.loadRuntime(f.child.id) }
        coVerify(exactly = 0) { f.projector.project(any()) }
    }

    @Test fun `parent and child deletion invalidate detail without revoking an otherwise open parent`() = runTest {
        for (deleteParent in listOf(false, true)) {
            val f = fixture()
            val states = mutableListOf<SubAssistantDetailUiState>()
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.reader.observe(f.source, "run").toList(states) }
            runCurrent()
            assertTrue(states.last() is SubAssistantDetailUiState.Ready)
            (if (deleteParent) f.masterState else f.childState).value = ConversationRuntimeState.Missing
            runCurrent()
            assertEquals(SubAssistantDetailUiState.Unavailable, states.last())
            verify(exactly = 1) { f.childLease.close() }
            assertFalse(f.source.closed.value)
            job.cancelAndJoin()
        }
    }

    @Test fun `metadata arriving during initial preview is preserved without restarting child`() = runTest {
        val f = fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { f.projector.project(any()) } coAnswers { entered.complete(Unit); release.await(); emptyMap() }
        val states = mutableListOf<SubAssistantDetailUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.reader.observe(f.source, "run").toList(states) }
        entered.await()
        val changed = f.master.copy(messageNodes = listOf(f.call(SubAssistantCallState.FAILED).let {
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(it)).toMessageNode()
        }))
        f.masterSnapshots.value = ConversationRuntimeSnapshot(changed.toSnapshot(), null)
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertEquals(SubAssistantCallState.FAILED, (states.last() as SubAssistantDetailUiState.Ready).link.metadata.state)
        coVerify(exactly = 1) { f.registry.acquireRegisteredRuntime(f.child.id, any()) }
    }

    @Test fun `revocation during delayed preview prevents late ready publication`() = runTest {
        val f = fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { f.projector.project(any()) } coAnswers {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            mapOf("attachment:old" to "file:///old")
        }
        val states = mutableListOf<SubAssistantDetailUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.reader.observe(f.source, "run").toList(states) }
        entered.await()
        f.source.close()
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertEquals(SubAssistantDetailUiState.Unavailable, states.last())
        assertTrue(states.none { it is SubAssistantDetailUiState.Ready })
        verify(exactly = 1) { f.childLease.close() }
    }

    @Test fun `real registry retains observed child beyond idle timeout and still delivers deletion`() = runTest {
        val f = fixture()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appScope = AppScope(dispatcher)
        val locks = ConversationOperationLocks()
        val registry = ConversationRuntimeRegistry(appScope, f.repository, locks)
        val coordinator = ConversationCommandCoordinator(registry, f.repository, ApplicationRecoveryGate().apply { ready() }, locks)
        coEvery { f.repository.getConversationSnapshotById(f.master.id) } returns f.master.toSnapshot()
        var persistedChild: Conversation? = f.child
        coEvery { f.repository.getConversationSnapshotById(f.child.id) } answers { persistedChild?.toSnapshot() }
        coEvery { f.repository.getConversationHeader(f.child.id) } answers { persistedChild?.toSnapshot()?.header }
        coEvery { f.repository.deleteConversation(f.child.id) } coAnswers { persistedChild = null }
        val parent = registry.loadRuntime(f.master.id)
        val parentLease = registry.acquireRegisteredRuntime(f.master.id, parent)
        val source = ConversationViewLease(f.master.id, f.source.access, f.source.selectionRevision, parentLease::close)
        val reader = SubAssistantDetailReader(ConversationQueryService(f.repository, registry, mockk(), mockk(), f.projector,
            f.sessions, ApplicationRecoveryGate().apply { ready() }, mockk(), coordinator), dispatcher)
        val states = mutableListOf<SubAssistantDetailUiState>()
        try {
            val first = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { reader.observe(source, "run").toList(states) }
            runCurrent()
            val child = requireNotNull(registry.findRuntime(f.child.id))
            assertTrue(states.last() is SubAssistantDetailUiState.Ready)
            advanceTimeBy(6_000)
            runCurrent()
            assertSame(child, registry.findRuntime(f.child.id))
            first.cancelAndJoin()
            assertFalse(child.isInUse)
            assertTrue(parent.isInUse)
            assertFalse(source.closed.value)
            advanceTimeBy(6_000)
            runCurrent()
            assertNull(registry.findRuntime(f.child.id))
            assertSame(parent, registry.findRuntime(f.master.id))

            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { reader.observe(source, "run").toList(states) }
            runCurrent()
            advanceTimeBy(6_000)
            runCurrent()
            val reopenedChild = requireNotNull(registry.findRuntime(f.child.id))
            coordinator.deleteOrThrow(f.child.id)
            assertNull(persistedChild)
            runCurrent()
            assertEquals(SubAssistantDetailUiState.Unavailable, states.last())
            assertFalse(reopenedChild.isInUse)
            assertTrue(parent.isInUse)
            assertFalse(source.closed.value)
        } finally {
            source.close()
            appScope.cancel()
        }
    }

    private suspend fun TestScope.fixture(): Fixture {
        val sessions = EnterpriseSessionController(EnterpriseAppliedStore(temporary.newFolder()))
        sessions.enrollFixture(exampleEnterprisePackage())
        return Fixture(sessions, sessions.captureSelectedRealmAccess(), StandardTestDispatcher(testScheduler))
    }

    private class Fixture(val sessions: EnterpriseSessionController, private val access: RealmAccess, dispatcher: CoroutineDispatcher) {
        val repository = mockk<ConversationRepository>()
        val registry = mockk<ConversationRuntimeRegistry>()
        val projector = mockk<ConversationAttachmentPreviewProjector>()
        val masterId = Uuid.random()
        val childId = Uuid.random()
        val assistant = ConfigurationReference.random()
        val task = UIMessage.user("Review")
        var parentCloseCount = 0
        val source = ConversationViewLease(masterId, access, sessions.selectionRevision.value) { parentCloseCount++ }
        val master = Conversation(id = masterId, assistantId = assistant, scope = access.scope,
            messageNodes = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(call())).toMessageNode()))
        val child = Conversation(id = childId, assistantId = assistant, scope = access.scope, parentConversationId = masterId,
            messageNodes = listOf(task, UIMessage.assistant("Answer")).map { it.toMessageNode() })
        val masterSnapshots = MutableStateFlow(ConversationRuntimeSnapshot(master.toSnapshot(), null))
        private val childSnapshots = MutableStateFlow(ConversationRuntimeSnapshot(child.toSnapshot(), null))
        private val masterRuntime = mockk<ConversationRuntime>()
        private val childRuntime = mockk<ConversationRuntime>()
        val masterState = MutableStateFlow<ConversationRuntimeState>(ConversationRuntimeState.Ready(masterRuntime))
        val childState = MutableStateFlow<ConversationRuntimeState>(ConversationRuntimeState.Ready(childRuntime))
        val childLease = mockk<ConversationRuntimeLease>(relaxed = true)
        val coordinator = ConversationCommandCoordinator(registry, repository, ApplicationRecoveryGate().apply { ready() }, ConversationOperationLocks())
        val reader = SubAssistantDetailReader(ConversationQueryService(repository, registry, mockk(), mockk(), projector,
            sessions, ApplicationRecoveryGate().apply { ready() }, mockk(), coordinator), dispatcher)

        init {
            every { masterRuntime.snapshot } returns masterSnapshots
            every { childRuntime.snapshot } returns childSnapshots
            every { registry.observeRuntimeState(masterId) } returns masterState
            every { registry.observeRuntimeState(childId) } returns childState
            coEvery { repository.getConversationHeader(childId) } returns child.toSnapshot().header
            coEvery { registry.loadRuntime(childId) } returns childRuntime
            coEvery { registry.acquireRegisteredRuntime(childId, childRuntime) } returns childLease
            every { projector.lifecycleChanges() } returns MutableStateFlow(Unit)
            coEvery { projector.project(any()) } returns emptyMap()
        }

        fun call(state: SubAssistantCallState = SubAssistantCallState.RUNNING) = UIMessagePart.Tool(
            localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call", toolName = "assistant_call", input = "{}",
        ).mergeSubAssistantCallMetadata(JsonInstant, buildInitialSubAssistantCallMetadata("run", assistant, "Target").copy(
            childConversationId = childId.toString(), childTaskNodeId = task.id.toString(), state = state,
        ))
    }
}
