package net.weero.measix.pilot.data.files

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.configuration.ConfigurationResolver
import net.weero.measix.pilot.data.configuration.ResolvedConfiguration
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.UserSettingsDocument
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.db.RoomDatabaseTransactionRunner
import net.weero.measix.pilot.data.db.fts.MessageFtsManager
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.service.*
import net.weero.measix.pilot.service.runtime.*
import net.weero.measix.pilot.service.subassistant.SubAssistantLifecycle
import net.weero.measix.pilot.service.turn.TurnFinalizer
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

/** Real Room, files, draft ownership and send boundary; no Provider request is needed for an append. */
@RunWith(AndroidJUnit4::class)
class StarterSubmissionOwnershipAndroidTest {
    @Test fun installationRejectionReturnsClaimedOwnershipOnlyOnce() = runBlocking {
        withFixture { f ->
            val part = f.draft.createTextDocument("rejected installation")
            val artifact = f.store.list(f.candidate.identity.scope).single()
            val held = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val holder = launch { f.locks.withLock(f.runtime.id) { held.complete(Unit); release.await() } }
            held.await()
            try {
                val revision = f.draft.inputRevision.value
                val sending = async { runCatching { f.turns.sendMessage(f.view.commandTarget, listOf(part), false, f.draft) } }
                withTimeout(10_000) { f.draft.inputRevision.first { it > revision } }
                f.view.close()
                release.complete(Unit)
                val failure = withTimeout(10_000) { sending.await() }.exceptionOrNull()
                assertNotNull(failure)
                assertTrue(failure!!.suppressed.isEmpty())
                assertTrue(f.store.collectGarbage(0).isEmpty())
                assertNull(f.runtime.currentWorker())
                f.draft.close()
                assertEquals(listOf(artifact.id), f.store.collectGarbage(0).map { it.id })
            } finally { release.complete(Unit); holder.join() }
        }
    }

    @Test fun changedStarterReturnsAttachmentsBeforeFailureAndRetryCommitsTheSameFile() = runBlocking {
        withFixture { f ->
            val part = f.draft.createTextDocument("retained draft bytes")
            val artifact = f.store.list(f.candidate.identity.scope).single()
            val old = f.candidate.configuration.starters.single()
            val changed = f.candidate.copy(configuration = f.candidate.configuration.copy(
                generation = f.candidate.configuration.generation + 1,
                starters = listOf(old.copy(prompt = "updated prompt")),
            ))
            f.sessions.synchronize(f.selection.access as RealmAccess.Enterprise, changed)
            assertNull(f.turns.sendMessage(f.view.commandTarget, listOf(part), false, f.draft))
            // Check immediately after the failure receipt, before relying on worker completion.
            assertTrue(f.store.collectGarbage(0).isEmpty())
            assertEquals("retained draft bytes", f.store.file(artifact).readText())
            assertNull(f.repository.getConversationSnapshotById(f.runtime.id))
            f.runtime.currentWorker()?.join()
            assertTrue(f.errors.errors.value.any { it.detail.contains("enterprise_starter_updated") })

            f.commands.executeOrThrow(f.runtime.id, BindDraftOpening(changed.opening(changed.configuration.starters.single()),
                f.runtime.durable.draftOpeningSelectionToken))
            assertNotNull(f.turns.sendMessage(f.view.commandTarget, listOf(part), false, f.draft))
            f.runtime.currentWorker()?.join()
            f.draft.close()
            assertTrue(f.store.collectGarbage(0).isEmpty())
            assertTrue(f.database.artifactReferenceDao().existsByArtifactId(artifact.id))
            assertEquals(changed.configuration.starters.single(), f.repository.getConversationSnapshotById(f.runtime.id)!!.opening!!.definition)
            assertEquals("retained draft bytes", f.store.file(artifact).readText())
        }
    }

    @Test fun cancelledBeforeAppendReturnsOwnershipOrReleasesItForAClosedEditor() = runBlocking {
        for (closed in listOf(false, true)) withFixture { f ->
            val part = f.draft.createTextDocument("cancelled input")
            val artifact = f.store.list(f.candidate.identity.scope).single()
            val entered = CompletableDeferred<Unit>()
            f.beforeConfiguration = { entered.complete(Unit); awaitCancellation() }
            val send = async { f.turns.sendMessage(f.view.commandTarget, listOf(part), false, f.draft) }
            withTimeout(10_000) { entered.await() }
            if (closed) { f.draft.close(); f.view.close() }
            requireNotNull(f.runtime.currentWorker()).cancel()
            assertNull(withTimeout(10_000) { send.await() })
            assertNull(f.repository.getConversationSnapshotById(f.runtime.id))
            assertTrue(f.errors.errors.value.isEmpty())
            if (closed) {
                assertEquals(listOf(artifact.id), f.store.collectGarbage(0).map { it.id })
            } else {
                assertTrue(f.store.collectGarbage(0).isEmpty())
                f.runtime.currentWorker()?.join()
                f.beforeConfiguration = {}
                assertNotNull(f.turns.sendMessage(f.view.commandTarget, listOf(part), false, f.draft))
                f.runtime.currentWorker()?.join()
                assertTrue(f.database.artifactReferenceDao().existsByArtifactId(artifact.id))
            }
        }
    }

    @Test fun cancellationAfterAppendKeepsCommittedAttachmentOutOfDraftOwnership() = runBlocking {
        withFixture { f ->
            val part = f.draft.createTextDocument("committed before cancellation")
            val artifact = f.store.list(f.candidate.identity.scope).single()
            coEvery { f.repository.commit(any()) } coAnswers {
                val committed = f.realRepository.commit(firstArg())
                // The real Room transaction is committed; cancellation must not skip Runtime
                // publication or return the committed attachment to the editor.
                requireNotNull(f.runtime.currentWorker()).cancel(CancellationException("cancel after append"))
                committed
            }
            assertNotNull(f.turns.sendMessage(f.view.commandTarget, listOf(part), false, f.draft))
            f.runtime.currentWorker()?.join()
            coVerify(exactly = 1) { f.repository.commit(any()) }
            assertNotNull(f.repository.getConversationSnapshotById(f.runtime.id))
            f.draft.discard(android.net.Uri.parse(part.url))
            f.draft.close()
            assertTrue(f.store.collectGarbage(0).isEmpty())
            assertTrue(f.database.artifactReferenceDao().existsByArtifactId(artifact.id))
            assertEquals("committed before cancellation", f.store.file(artifact).readText())
            assertTrue(f.errors.errors.value.isEmpty())
        }
    }

    @Test fun publicationFailureAfterAppendKeepsDurableReferencesAndOriginalDiagnostic() = runBlocking {
        withFixture { f ->
            val part = f.draft.createTextDocument("committed before publication error")
            val artifact = f.store.list(f.candidate.identity.scope).single()
            coEvery { f.store.publishAllUnpublished(any()) } throws java.io.IOException(
                "publication receipt failure", IllegalStateException("publication state mismatch"),
            )
            assertNotNull(f.turns.sendMessage(f.view.commandTarget, listOf(part), false, f.draft))
            f.runtime.currentWorker()?.join()
            coVerify(exactly = 1) { f.store.publishAllUnpublished(any()) }
            f.draft.discard(android.net.Uri.parse(part.url))
            f.draft.close()
            assertTrue(f.store.collectGarbage(0).isEmpty())
            assertTrue(f.database.artifactReferenceDao().existsByArtifactId(artifact.id))
            val diagnostics = f.errors.errors.value.map { it.detail }
            assertTrue(diagnostics.toString(), diagnostics.any { detail ->
                detail.contains("IOException") && detail.contains("publication receipt failure") &&
                    detail.contains("IllegalStateException") && detail.contains("publication state mismatch")
            })
        }
    }

    private suspend fun withFixture(operation: suspend (Fixture) -> Unit) {
        val f = Fixture()
        try { f.initialize(); withTimeout(30_000) { operation(f) } }
        finally { f.close() }
    }

    private class Fixture {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val root = File(application.cacheDir, "starter-submission-${Uuid.random()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(application) { override fun getFilesDir() = root }
        val database = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java).build()
        val appScope = AppScope(Dispatchers.Default)
        val candidate = starterDeviceCandidate(false)
        val appliedStore = EnterpriseAppliedStore(File(root, "enterprise"))
        val sessions = EnterpriseSessionController(appliedStore)
        val settings = mockk<SettingsStore>()
        val store = spyk(ArtifactStore(ArtifactPayloadStore(context), database.artifactDao(), database.artifactReferenceDao(),
            database.systemMetaDao(), database.conversationDao(), database.messageNodeDao(), database.conversationModelContextDao(),
            ArtifactSettingsCoordinator(settings), RoomDatabaseTransactionRunner(database)))
        val realRepository = ConversationRepository(database.conversationDao(), database.messageNodeDao(), database.favoriteDao(), database,
            mockk<MessageFtsManager>(relaxed = true), database.turnExecutionDao(), database.toolExecutionDao(), database.conversationModelContextDao(), store)
        val repository = spyk(realRepository)
        val gate = ApplicationRecoveryGate().apply { ready() }
        val locks = ConversationOperationLocks()
        val registry = ConversationRuntimeRegistry(appScope, repository, locks)
        val commands = ConversationCommandCoordinator(registry, repository, gate, locks)
        val errors = ChatErrorStore()
        val titles = ConversationTitleCoordinator()
        lateinit var selection: RealmSelection
        lateinit var runtime: ConversationRuntime
        lateinit var lease: ConversationRuntimeLease
        lateinit var view: ConversationViewLease
        lateinit var draft: ArtifactDraftScope
        lateinit var turns: ConversationTurnService
        var beforeConfiguration: suspend () -> Unit = {}

        suspend fun initialize() {
            coEvery { settings.snapshotUserDocument() } returns UserSettingsDocument.empty()
            coEvery { settings.withResolvedConfiguration<Any?>(any(), any(), any()) } coAnswers {
                beforeConfiguration()
                thirdArg<suspend (ResolvedConfiguration) -> Any?>()(ConfigurationResolver.resolve(
                    UserSettingsDocument.empty(), candidate.identity.scope, sessions.state.value))
            }
            val id = "ses_${Uuid.random()}"
            val expiry = 2_000_000_000_000L
            val credential = appliedStore.prepareCredential(PlatformRefreshCredential(id, "fixture-refresh", expiry))
            val execution = candidate.execution as EnterpriseExecution.Platform
            val session = EnterpriseSession(id, candidate.identity, expiry,
                PlatformSessionDetails(execution.connection, "dev_${Uuid.random()}", credential))
            appliedStore.commit(EnterpriseManifest(6, EnterpriseSessionPhase.READY, session, appliedStore.prepare(candidate),
                candidate.identity.scope, candidate.identity))
            sessions.recover()
            selection = requireNotNull(sessions.readPresentation().selection)
            val opening = candidate.opening(candidate.configuration.starters.single())
            runtime = registry.installDraft(Conversation.ofId(Uuid.random(), opening.assistant, newConversation = true).copy(scope = candidate.identity.scope))
            lease = registry.acquireRegisteredRuntime(runtime.id, runtime)
            view = ConversationViewLease(runtime.id, selection.access, selection.revision) {}
            commands.executeOrThrow(runtime.id, BindDraftOpening(opening, null))
            val artifacts = ArtifactUseCase(store, gate, sessions)
            draft = artifacts.openDraftScope(view)
            store.ensureReferenceProjection()
            turns = withContext(Dispatchers.Main.immediate) {
                ConversationTurnService(application, appScope, mockk(relaxed = true), settings, mockk(), mockk(), mockk(), sessions,
                    mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(),
                    TurnFinalizer(repository, registry, commands, JsonInstant), SubAssistantLifecycle(repository, registry, commands, JsonInstant),
                    registry, commands, gate, errors, mockk(relaxed = true), artifacts, titles)
            }
        }

        suspend fun close() {
            if (::draft.isInitialized) draft.close()
            if (::view.isInitialized) view.close()
            if (::lease.isInitialized) lease.close()
            appScope.coroutineContext[Job]!!.cancelAndJoin()
            database.close()
            check(root.deleteRecursively())
        }
    }
}
