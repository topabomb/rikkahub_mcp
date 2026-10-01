package net.weero.measix.pilot.service

import io.mockk.*
import androidx.lifecycle.ViewModelStore
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
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.toMessageNode
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.service.runtime.*
import net.weero.measix.pilot.utils.JsonInstant
import net.weero.measix.pilot.utils.userVisibleDiagnostic
import net.weero.measix.pilot.ui.pages.subassistant.SubAssistantDetailVM
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
        coVerify(exactly = 0) { f.projector.project(any(), any()) }
    }

    @Test fun `child header read failure retains its diagnostic without closing parent`() = runTest {
        val f = fixture()
        val failure = java.io.IOException("child header unreadable", IllegalStateException("database detail"))
        coEvery { f.repository.getConversationHeader(f.child.id) } throws failure
        val states = mutableListOf<SubAssistantDetailUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.reader.observe(f.source, "run").toList(states) }
        runCurrent()
        assertFailurePreserved(failure, (states.last() as SubAssistantDetailUiState.Failed).error)
        assertFalse(f.source.closed.value)
        assertFailurePreserved(failure, requireNotNull(org.robolectric.shadows.ShadowLog.getLogsForTag("SubAssistantDetail").last().throwable))
        coVerify(exactly = 0) { f.registry.loadRuntime(f.child.id) }
    }

    @Test fun `parent and child failed reads retain their original diagnostic locally`() = runTest {
        for (failParent in listOf(true, false)) {
            val f = fixture()
            val failure = java.io.IOException("stored ${if (failParent) "parent" else "child"} unreadable",
                IllegalArgumentException("invalid message encoding")).apply {
                    addSuppressed(IllegalStateException("cleanup detail"))
                }
            val states = mutableListOf<SubAssistantDetailUiState>()
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.reader.observe(f.source, "run").toList(states)
            }
            runCurrent()
            assertTrue(states.last() is SubAssistantDetailUiState.Ready)
            (if (failParent) f.masterState else f.childState).value = ConversationRuntimeState.Failed(failure)
            runCurrent()
            assertFailurePreserved(failure, (states.last() as SubAssistantDetailUiState.Failed).error)
            assertFalse(f.source.closed.value)
            assertEquals(0, f.parentCloseCount)
            verify(exactly = 1) { f.childLease.close() }
            job.cancelAndJoin()
        }
    }

    @Test fun `detail retry keeps its borrowed parent and cannot revive it after realm reselection`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val f = fixture()
            val failure = java.io.IOException("detail index unreadable", IllegalStateException("index detail"))
            coEvery { f.repository.getConversationHeader(f.child.id) } throws failure
            val settings = mockk<SettingsStore>()
            every { settings.userSettings } returns MutableStateFlow(Settings.dummy())
            val vm = SubAssistantDetailVM(f.source, "run", f.reader, settings)
            store.put("detail", vm)
            runCurrent()
            assertFailurePreserved(failure, (vm.uiState.value as SubAssistantDetailUiState.Failed).error)
            assertFalse(f.source.closed.value)
            coVerify(exactly = 0) { f.registry.acquireRegisteredRuntime(any(), any()) }

            coEvery { f.repository.getConversationHeader(f.child.id) } returns f.child.toSnapshot().header
            vm.retry()
            runCurrent()
            assertTrue(vm.uiState.value is SubAssistantDetailUiState.Ready)
            assertFalse(f.source.closed.value)
            assertEquals(0, f.parentCloseCount)
            coVerify(exactly = 1) { f.registry.acquireRegisteredRuntime(f.child.id, any()) }
            coVerify(exactly = 0) { f.registry.acquireRegisteredRuntime(f.master.id, any()) }

            f.sessions.selectPersonalFixture()
            f.sessions.selectEnterpriseFixture()
            vm.retry()
            runCurrent()
            assertEquals(SubAssistantDetailUiState.Unavailable, vm.uiState.value)
            assertTrue(f.source.closed.value)
            coVerify(exactly = 1) { f.registry.acquireRegisteredRuntime(f.child.id, any()) }
            verify(exactly = 1) { f.childLease.close() }
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `collector cancellation completes child cleanup without publishing failure or closing parent`() = runTest {
        val f = fixture()
        val entered = CompletableDeferred<Unit>()
        val exited = CompletableDeferred<Unit>()
        coEvery { f.projector.project(any(), any()) } coAnswers {
            entered.complete(Unit)
            try { awaitCancellation() } finally { exited.complete(Unit) }
        }
        val states = mutableListOf<SubAssistantDetailUiState>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.reader.observe(f.source, "run").toList(states)
        }
        entered.await()
        job.cancelAndJoin()
        exited.await()
        assertTrue(job.isCancelled)
        assertTrue(states.none { it is SubAssistantDetailUiState.Failed || it is SubAssistantDetailUiState.Unavailable })
        assertFalse(f.source.closed.value)
        verify(exactly = 1) { f.childLease.close() }
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

    @Test fun `publication or newer child during first preview invalidates the pending projection`() = runTest {
        for (publish in listOf(true, false)) {
            val f = fixture()
            val changes = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }
            every { f.projector.lifecycleChanges() } returns changes
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var reads = 0
            coEvery { f.projector.project(any(), any()) } coAnswers {
                if (++reads == 1) {
                    entered.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    emptyMap()
                } else mapOf("attachment:published" to AttachmentPreview("file:///published", null))
            }
            val states = mutableListOf<SubAssistantDetailUiState>()
            val observing = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.reader.observe(f.source, "run").toList(states)
            }
            entered.await()
            if (publish) changes.emit(Unit) else {
                f.childSnapshots.value = ConversationRuntimeSnapshot(f.child.copy(
                    messageNodes = f.child.messageNodes + UIMessage.assistant("new output").toMessageNode(),
                ).toSnapshot(), null)
            }
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertTrue(states.last() is SubAssistantDetailUiState.Ready)
            states.filterIsInstance<SubAssistantDetailUiState.Ready>().forEach { ready ->
                assertEquals(mapOf("attachment:published" to AttachmentPreview("file:///published", null)), ready.attachmentPreviews)
                if (!publish) assertEquals(f.childSnapshots.value.durable.nodes.size, ready.child.nodes.size)
            }
            observing.cancelAndJoin()
        }
    }

    @Test fun `metadata arriving during initial preview is preserved without restarting child`() = runTest {
        val f = fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { f.projector.project(any(), any()) } coAnswers { entered.complete(Unit); release.await(); emptyMap() }
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
        coEvery { f.projector.project(any(), any()) } coAnswers {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            mapOf("attachment:old" to AttachmentPreview("file:///old", null))
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
        val source = ConversationViewLease(f.master.id, f.source.access, f.source.selectionRevision, closeAction = parentLease::close)
        val reader = SubAssistantDetailReader(ConversationQueryService(f.repository, registry, mockk(), mockk(), f.projector,
            f.sessions, ApplicationRecoveryGate().apply { ready() }, mockk(), coordinator, mockk()), dispatcher)
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

    private fun assertFailurePreserved(expected: Throwable, actual: Throwable) {
        assertEquals(expected.javaClass, actual.javaClass)
        assertEquals(expected.message, actual.message)
        expected.cause?.let { cause ->
            assertTrue(generateSequence(actual.cause) { it.cause }.any {
                it.javaClass == cause.javaClass && it.message == cause.message
            })
        }
        expected.suppressed.forEach { expectedSuppressed ->
            assertTrue(generateSequence(actual) { it.cause }.flatMap { it.suppressed.asSequence() }.any {
                it.javaClass == expectedSuppressed.javaClass && it.message == expectedSuppressed.message
            })
        }
        assertTrue(actual.userVisibleDiagnostic().contains(expected.userVisibleDiagnostic()))
    }

    private suspend fun TestScope.fixture(): Fixture {
        val sessions = EnterpriseSessionController(net.weero.measix.pilot.data.enterprise.enterpriseTestStore(temporary.newFolder()))
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
        val childSnapshots = MutableStateFlow(ConversationRuntimeSnapshot(child.toSnapshot(), null))
        private val masterRuntime = mockk<ConversationRuntime>()
        private val childRuntime = mockk<ConversationRuntime>()
        val masterState = MutableStateFlow<ConversationRuntimeState>(ConversationRuntimeState.Ready(masterRuntime))
        val childState = MutableStateFlow<ConversationRuntimeState>(ConversationRuntimeState.Ready(childRuntime))
        val childLease = mockk<ConversationRuntimeLease>(relaxed = true)
        val coordinator = ConversationCommandCoordinator(registry, repository, ApplicationRecoveryGate().apply { ready() }, ConversationOperationLocks())
        val reader = SubAssistantDetailReader(ConversationQueryService(repository, registry, mockk(), mockk(), projector,
            sessions, ApplicationRecoveryGate().apply { ready() }, mockk(), coordinator, mockk()), dispatcher)

        init {
            every { masterRuntime.snapshot } returns masterSnapshots
            every { childRuntime.snapshot } returns childSnapshots
            every { registry.observeRuntimeState(masterId) } returns masterState
            every { registry.observeRuntimeState(childId) } returns childState
            coEvery { repository.getConversationHeader(childId) } returns child.toSnapshot().header
            coEvery { registry.loadRuntime(childId) } returns childRuntime
            coEvery { registry.acquireRegisteredRuntime(childId, childRuntime) } returns childLease
            every { projector.lifecycleChanges() } returns MutableStateFlow(Unit)
            coEvery { projector.project(any(), any()) } returns emptyMap()
        }

        fun call(state: SubAssistantCallState = SubAssistantCallState.RUNNING) = UIMessagePart.Tool(
            localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call", toolName = "assistant_call", input = "{}",
        ).mergeSubAssistantCallMetadata(JsonInstant, buildInitialSubAssistantCallMetadata("run", assistant, "Target").copy(
            childConversationId = childId.toString(), childTaskNodeId = task.id.toString(), state = state,
        ))
    }
}
