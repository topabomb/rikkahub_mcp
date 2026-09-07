package net.weero.measix.pilot.service

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.configuration.ConfigurationResolver
import net.weero.measix.pilot.data.configuration.ResolvedConfiguration
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.datastore.DEFAULT_ASSISTANT_ID
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.UserSettingsDocument
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.data.files.ArtifactRetentionLease
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.service.runtime.*
import net.weero.measix.pilot.service.subassistant.SubAssistantLifecycle
import net.weero.measix.pilot.service.turn.TurnFinalizer
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
class ConversationCommandAccessTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `old page commands reject a conflated selection round trip before reading or writing data`() = runTest {
        fixture { f ->
            val target = f.page.commandTarget
            f.sessions.switchToPersonal()
            f.sessions.switchToEnterprise()
            val commands: List<suspend () -> Unit> = listOf(
                { f.application.updateTitle(target, "changed") },
                { f.application.updateCustomSystemPrompt(target, "changed") },
                { f.application.updateModeInjectionIds(target, emptySet()) },
                { f.application.updateWorkspaceCwd(target, "changed") },
                { f.application.togglePin(target) },
                { f.application.selectNode(target, Uuid.random(), 0) },
                { f.application.editMessage(target, Uuid.random(), listOf(UIMessagePart.Text("changed"))) },
                { f.application.moveToAssistant(target, DEFAULT_ASSISTANT_ID, false) },
                { f.application.stopGeneration(target) },
                { f.application.delete(target) },
                { f.application.deleteForUndo(target) },
                { f.application.deleteMessage(target, UIMessage.user("changed")) },
                { f.application.forkAtMessage(target, Uuid.random()) },
            )
            commands.forEach { rejects<EnterpriseConfigurationException>(it) }
            coVerify(exactly = 0) { f.repository.getConversationHeader(any()) }
            coVerify(exactly = 0) { f.repository.getConversationSnapshotById(any()) }
            coVerify(exactly = 0) { f.repository.commit(any()) }
            assertEquals(1, f.rows.size)
        }
    }

    @Test fun `manual header and message edits use the authorized root and close with their page`() = runTest {
        fixture { f ->
            val target = f.page.commandTarget
            f.application.updateTitle(target, "renamed")
            f.application.updateCustomSystemPrompt(target, "system")
            f.application.updateWorkspaceCwd(target, "workspace")
            f.application.togglePin(target)
            val message = f.runtime.durable.nodes.single().currentMessage
            f.application.editMessage(target, message.id, listOf(UIMessagePart.Text("edited")))
            assertEquals("renamed", f.runtime.durable.header.title)
            assertEquals("system", f.runtime.durable.header.customSystemPrompt)
            assertEquals("workspace", f.runtime.durable.header.workspaceCwd)
            assertTrue(f.runtime.durable.header.isPinned)
            assertEquals("edited", f.runtime.durable.currentMessages().single().toText())
            f.page.close()
            rejects<IllegalStateException> { f.application.togglePin(target) }
            assertTrue(f.runtime.durable.header.isPinned)
        }
    }

    @Test fun `page closure while queued on the conversation lock prevents writes and stop capture`() = runTest {
        for (stopCommand in listOf(false, true)) fixture { f ->
            val held = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val holder = launch {
                f.locks.withLock(f.rootId) { held.complete(Unit); release.await() }
            }
            held.await()
            val worker = Job()
            f.runtime.installTurnWorker(Uuid.random(), worker)
            try {
                val command = async {
                    rejects<IllegalStateException> {
                        if (stopCommand) f.application.stopGeneration(f.page.commandTarget)
                        else f.application.updateTitle(f.page.commandTarget, "closed")
                    }
                }
                runCurrent()
                f.page.close()
                release.complete(Unit)
                command.await()
                assertTrue(worker.isActive)
                coVerify(exactly = 0) { f.repository.commit(any()) }
                assertEquals(f.rows.getValue(f.rootId).header.title, f.runtime.durable.header.title)
            } finally { release.complete(Unit); worker.cancel(); holder.join() }
        }
    }

    @Test fun `foreign and child headers cannot be used as ordinary roots`() = runTest {
        fixture { f ->
            val foreign = f.put(ConfigurationScope.Personal)
            val child = f.put(f.scope, f.rootId)
            for (id in listOf(foreign, child)) {
                val target = ConversationCommandTarget(id, f.page.commandTarget.selection) {}
                rejects<IllegalStateException> { f.application.updateTitle(target, "changed") }
                rejects<IllegalStateException> { f.application.delete(target) }
            }
            coVerify(exactly = 0) { f.repository.getConversationSnapshotById(any()) }
            coVerify(exactly = 0) { f.repository.commit(any()) }
        }
    }

    @Test fun `managed prompt and unavailable assistant changes are rejected by the command owner`() = runTest {
        fixture { f ->
            val header = f.runtime.durable.header.copy(assistantId = ConfigurationReference.Enterprise(f.scope.authority, "ast_fixed"))
            f.registry.evictRuntime(f.rootId)
            f.rows[f.rootId] = f.rows.getValue(f.rootId).copy(header = header)
            f.runtime = f.registry.registerSnapshot(f.rows.getValue(f.rootId))
            rejects<IllegalStateException> { f.application.updateCustomSystemPrompt(f.page.commandTarget, "override") }
            rejects<IllegalStateException> { f.application.moveToAssistant(f.page.commandTarget, ConfigurationReference.random(), false) }
            coVerify(exactly = 0) { f.repository.commit(any()) }
        }
    }

    @Test fun `cancelled stop releases authorization locks while its captured worker finishes`() = runTest {
        fixture { f ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val worker = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { entered.complete(Unit); release.await() } }
            }
            f.runtime.installTurnWorker(Uuid.random(), worker)
            val stop = launch { f.application.stopGeneration(f.page.commandTarget) }
            entered.await()
            f.sessions.switchToPersonal()
            stop.cancel()
            runCurrent()
            assertFalse(stop.isCompleted)
            release.complete(Unit)
            stop.join()
            assertTrue(stop.isCancelled)
            assertNull(f.runtime.currentWorker())
        }
    }

    @Test fun `delete rechecks original authorization after joining and leaves the conversation when revoked`() = runTest {
        fixture { f ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val worker = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { entered.complete(Unit); release.await() } }
            }
            f.runtime.installTurnWorker(Uuid.random(), worker)
            val deleting = async { rejects<EnterpriseConfigurationException> { f.application.delete(f.page.commandTarget) } }
            entered.await()
            f.sessions.switchToPersonal()
            release.complete(Unit)
            deleting.await()
            assertTrue(f.rows.containsKey(f.rootId))
            assertNull(f.runtime.currentWorker())
            coVerify(exactly = 0) { f.repository.deleteConversation(any()) }
        }
    }

    @Test fun `tree authorization rejects foreign lineage before reading its message payload`() = runTest {
        fixture { f ->
            val child = f.put(ConfigurationScope.Personal, f.rootId)
            rejects<IllegalStateException> { f.application.deleteForUndo(f.page.commandTarget) }
            rejects<IllegalStateException> { f.application.forkAtMessage(f.page.commandTarget, f.runtime.durable.nodes.single().currentMessage.id) }
            coVerify(exactly = 0) { f.repository.getChildConversationSnapshots(any()) }
            coVerify(exactly = 0) { f.repository.getConversationSnapshotById(child) }
            coVerify(exactly = 0) { f.repository.deleteConversation(any()) }
        }
    }

    @Test fun `committed deletion still evicts the original runtime when its caller cancels`() = runTest {
        fixture { f ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            coEvery { f.repository.deleteConversation(f.rootId) } coAnswers {
                entered.complete(Unit)
                release.await()
                f.rows.remove(f.rootId)
                Unit
            }
            val deleting = launch { f.application.delete(f.page.commandTarget) }
            entered.await()
            deleting.cancel()
            release.complete(Unit)
            deleting.join()
            assertTrue(deleting.isCancelled)
            assertFalse(f.rows.containsKey(f.rootId))
            assertNull(f.registry.findRuntime(f.rootId))
        }
    }

    @Test fun `undo restores complete lineage once and concurrent discard cannot release its claimed retention`() = runTest {
        fixture { f ->
            val child = f.put(f.scope, f.rootId)
            val expected = f.rows.toMap()
            val token = f.application.deleteForUndo(f.page.commandTarget)
            f.page.close()
            assertTrue(f.rows.isEmpty())
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            coEvery { f.repository.insertConversationTree(any(), any()) } coAnswers {
                entered.complete(Unit)
                release.await()
                val root = firstArg<ConversationAggregateSnapshot>()
                f.rows[root.conversationId] = root
                secondArg<List<ConversationAggregateSnapshot>>().forEach { f.rows[it.conversationId] = it }
            }
            val restoring = launch { f.application.restore(token) }
            entered.await()
            f.application.discardRestoreToken(token)
            assertEquals(0, f.retentionReleased)
            rejects<IllegalStateException> { f.application.restore(token) }
            release.complete(Unit)
            restoring.join()
            assertEquals(expected, f.rows)
            assertEquals(f.rootId, f.rows.getValue(child).header.parentConversationId)
            assertEquals(1, f.retentionReleased)
            token.close()
            rejects<IllegalStateException> { f.application.restore(token) }
            assertEquals(1, f.retentionReleased)
        }
    }

    @Test fun `revoked undo cannot rebind and releases its retained artifacts on failure`() = runTest {
        fixture { f ->
            val token = f.application.deleteForUndo(f.page.commandTarget)
            f.sessions.finishExit(requireNotNull(f.sessions.beginExit()))
            f.sessions.enrollFixture(exampleEnterprisePackage())
            rejects<EnterpriseConfigurationException> { f.application.restore(token) }
            assertTrue(f.rows.isEmpty())
            assertEquals(1, f.retentionReleased)
            rejects<IllegalStateException> { f.application.restore(token) }
        }
    }

    @Test fun `undo rejects a replacement conversation instead of overwriting its identifier`() = runTest {
        fixture { f ->
            val token = f.application.deleteForUndo(f.page.commandTarget)
            val replacement = token.root.copy(header = token.root.header.copy(title = "replacement"))
            f.rows[f.rootId] = replacement
            rejects<ConversationCommandConflictException> { f.application.restore(token) }
            assertEquals(replacement, f.rows[f.rootId])
            assertEquals(1, f.retentionReleased)
        }
    }

    private suspend fun TestScope.fixture(action: suspend (Fixture) -> Unit) {
        val f = Fixture(this)
        try { f.initialize(); action(f) }
        finally { f.appScope.cancel() }
    }

    private inner class Fixture(test: TestScope) {
        val appScope = AppScope(StandardTestDispatcher(test.testScheduler))
        val sessions = EnterpriseSessionController(EnterpriseAppliedStore(temporary.newFolder()))
        val scope = exampleEnterprisePackage().identity.scope
        val repository = mockk<ConversationRepository>()
        val settings = mockk<SettingsStore>()
        val artifactStore = mockk<ArtifactStore>()
        val rows = linkedMapOf<Uuid, ConversationAggregateSnapshot>()
        var retentionReleased = 0
        val locks = ConversationOperationLocks()
        val registry = ConversationRuntimeRegistry(appScope, repository, locks)
        val gate = ApplicationRecoveryGate().apply { ready() }
        val coordinator = ConversationCommandCoordinator(registry, repository, gate, locks)
        val finalizer = TurnFinalizer(repository, registry, coordinator, JsonInstant)
        val lifecycle = SubAssistantLifecycle(repository, registry, coordinator, JsonInstant)
        val effects = mockk<GenerationSideEffects>()
        val application = ConversationApplicationService(settings, repository, mockk(), registry, coordinator, gate,
            lifecycle, effects, artifactStore, mockk(), finalizer, JsonInstant, mockk(), ConversationTitleCoordinator(), sessions)
        val rootId = put(scope)
        lateinit var runtime: ConversationRuntime
        lateinit var page: ConversationViewLease

        init {
            coEvery { repository.getConversationHeader(any()) } answers { rows[firstArg()]?.header }
            coEvery { repository.getConversationSnapshotById(any()) } answers { rows[firstArg()] }
            coEvery { repository.getChildConversationIds(any()) } answers {
                val id = firstArg<Uuid>()
                rows.values.filter { it.header.parentConversationId == id }.map { it.conversationId }
            }
            coEvery { repository.getChildConversationSnapshots(any()) } answers {
                val id = firstArg<Uuid>()
                rows.values.filter { it.header.parentConversationId == id }
            }
            coEvery { repository.getTurnExecution(any()) } returns null
            coEvery { repository.getTurnExecutions(any()) } returns emptyList()
            coEvery { repository.existsConversationById(any()) } answers { rows.containsKey(firstArg()) }
            coEvery { repository.commit(any()) } returns true
            coEvery { repository.deleteConversation(any()) } answers {
                val id = firstArg<Uuid>()
                rows.entries.removeAll { it.key == id || it.value.header.parentConversationId == id }
                Unit
            }
            coEvery { repository.insertConversationSnapshot(any()) } answers {
                val snapshot = firstArg<ConversationAggregateSnapshot>()
                rows[snapshot.conversationId] = snapshot
            }
            coEvery { artifactStore.retainNodesForUndo(any()) } answers { ArtifactRetentionLease { retentionReleased++ } }
            every { effects.clearTitleTracking(any()) } returns Unit
            coEvery { settings.withResolvedConfiguration<Unit>(any(), any(), any()) } coAnswers {
                val configuration = ConfigurationResolver.resolve(UserSettingsDocument.empty(), firstArg(), secondArg())
                thirdArg<suspend (ResolvedConfiguration) -> Unit>()(configuration)
            }
        }

        suspend fun initialize() {
            sessions.enrollFixture(exampleEnterprisePackage())
            runtime = registry.registerSnapshot(rows.getValue(rootId))
            val selection = sessions.observeSelectedRealmSelection().first { it != null }!!
            page = ConversationViewLease(rootId, selection.access, selection.revision) {}
        }

        fun put(scope: ConfigurationScope, parent: Uuid? = null): Uuid {
            val conversation = Conversation.ofId(Uuid.random(), DEFAULT_ASSISTANT_ID).copy(scope = scope, parentConversationId = parent)
                .updateCurrentMessages(listOf(UIMessage.user("original")))
            rows[conversation.id] = conversation.toSnapshot()
            return conversation.id
        }
    }

    private suspend inline fun <reified T : Throwable> rejects(block: suspend () -> Unit) {
        try { block(); fail("Expected ${T::class.simpleName}") }
        catch (error: Throwable) { if (error !is T) throw error }
    }
}
