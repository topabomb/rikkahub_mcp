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
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
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
            f.sessions.selectPersonalFixture()
            f.sessions.selectEnterpriseFixture()
            val commands: List<suspend () -> Unit> = listOf(
                { f.application.updateTitle(target, "changed") },
                { f.application.generateTitle(target, true) },
                { f.application.compress(target, "", 100, 0) },
                { f.application.updateCustomSystemPrompt(ConversationAssistantTarget(target, DEFAULT_ASSISTANT_ID), "changed") },
                { f.application.updateModeInjectionIds(ConversationAssistantTarget(target, DEFAULT_ASSISTANT_ID), emptySet()) },
                { f.application.updateWorkspaceCwd(ConversationAssistantTarget(target, DEFAULT_ASSISTANT_ID), null, "changed") },
                { f.application.selectAssistantRequest(target.selection, DEFAULT_ASSISTANT_ID, false) },
                { f.application.togglePin(target) },
                { f.application.selectNode(target, Uuid.random(), 0) },
                { f.application.editMessage(target, Uuid.random(), listOf(UIMessagePart.Text("changed"))) },
                { f.application.moveToAssistant(ConversationAssistantTarget(target, DEFAULT_ASSISTANT_ID), DEFAULT_ASSISTANT_ID, false) },
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
            f.configureAssistant(net.weero.measix.pilot.data.model.Assistant(id = DEFAULT_ASSISTANT_ID, allowConversationSystemPrompt = true))
            val target = f.page.commandTarget
            f.application.updateTitle(target, "renamed")
            f.application.updateCustomSystemPrompt(ConversationAssistantTarget(target, DEFAULT_ASSISTANT_ID), "system")
            f.application.updateWorkspaceCwd(ConversationAssistantTarget(target, DEFAULT_ASSISTANT_ID), null, "workspace")
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

    @Test fun `assistant-dependent commands reject the former assistant without changing the current conversation`() = runTest {
        fixture { f ->
            val stale = ConversationAssistantTarget(f.page.commandTarget, ConfigurationReference.random())
            val before = f.runtime.durable
            rejects<IllegalStateException> { f.application.updateWorkspaceCwd(stale, null, "stale-directory") }
            rejects<IllegalStateException> { f.application.updateModeInjectionIds(stale, emptySet()) }
            rejects<IllegalStateException> { f.application.moveToAssistant(stale, DEFAULT_ASSISTANT_ID, false) }
            assertEquals(before, f.runtime.durable)
            coVerify(exactly = 0) { f.repository.commit(any()) }
        }
    }

    @Test fun `directory from a former workspace cannot overwrite the current workspace root`() = runTest {
        fixture { f ->
            val oldWorkspace = Uuid.random()
            val currentWorkspace = Uuid.random()
            val target = ConversationAssistantTarget(f.page.commandTarget, DEFAULT_ASSISTANT_ID)
            f.configureAssistant(net.weero.measix.pilot.data.model.Assistant(id = DEFAULT_ASSISTANT_ID, workspaceId = currentWorkspace))
            rejects<IllegalStateException> { f.application.updateWorkspaceCwd(target, oldWorkspace, "/workspace/old") }
            assertNull(f.runtime.durable.header.workspaceCwd)
            coVerify(exactly = 0) { f.repository.commit(any()) }
            f.application.updateWorkspaceCwd(target, currentWorkspace, "/workspace/current")
            assertEquals("/workspace/current", f.runtime.durable.header.workspaceCwd)
        }
    }

    @Test fun `closing conversation prompt permissions rejects late edits without replacing saved values`() = runTest {
        fixture { f ->
            val assistant = net.weero.measix.pilot.data.model.Assistant(id = DEFAULT_ASSISTANT_ID,
                allowConversationSystemPrompt = true, allowConversationPromptInjection = true)
            f.configureAssistant(assistant)
            val target = ConversationAssistantTarget(f.page.commandTarget, assistant.id)
            val selected = setOf(ConfigurationReference.random())
            f.application.updateCustomSystemPrompt(target, "accepted")
            f.application.updateModeInjectionIds(target, selected)
            f.configureAssistant(assistant.copy(allowConversationSystemPrompt = false, allowConversationPromptInjection = false))
            rejects<IllegalStateException> { f.application.updateCustomSystemPrompt(target, "late") }
            rejects<IllegalStateException> { f.application.updateModeInjectionIds(target, emptySet()) }
            assertEquals("accepted", f.runtime.durable.header.customSystemPrompt)
            assertEquals(selected, f.runtime.durable.header.modeInjectionIds)
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
            rejects<IllegalStateException> { f.application.updateCustomSystemPrompt(ConversationAssistantTarget(f.page.commandTarget, f.runtime.durable.header.assistantId), "override") }
            rejects<IllegalStateException> { f.application.moveToAssistant(ConversationAssistantTarget(f.page.commandTarget, DEFAULT_ASSISTANT_ID), ConfigurationReference.random(), false) }
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
            f.sessions.selectPersonalFixture()
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
            f.sessions.selectPersonalFixture()
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
            f.sessions.finishExit(f.sessions.beginExit(requireNotNull(f.sessions.captureExitRequest())))
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

    @Test fun `child answer requires the original root and pending Session`() = runTest {
        fixture { f ->
            val target = f.page.commandTarget
            val answer = f.runGate.registerPendingInteraction(f.rootId, target.selection.access, "run", "ask", Job())
            val otherRoot = ConversationCommandTarget(f.put(f.scope), target.selection) {}
            assertFalse(f.application.answerSubAssistant(otherRoot, "run", "ask", "foreign"))
            assertFalse(f.application.answerSubAssistant(target, "run", "old-ask", "stale"))
            assertFalse(answer.isCompleted)
            assertTrue(f.application.answerSubAssistant(target, "run", "ask", "accepted"))
            assertFalse(f.application.answerSubAssistant(target, "run", "ask", "duplicate"))
            assertEquals("accepted", answer.await())
            f.runGate.unregisterPendingInteraction("run", answer)
        }
    }

    @Test fun `relogin cannot answer the previous Session pending even from a new page`() = runTest {
        fixture { f ->
            val original = f.page.commandTarget
            val answer = f.runGate.registerPendingInteraction(f.rootId, original.selection.access, "run", "ask", Job())
            f.sessions.finishExit(f.sessions.beginExit(requireNotNull(f.sessions.captureExitRequest())))
            f.sessions.enrollFixture(exampleEnterprisePackage())
            rejects<EnterpriseConfigurationException> { f.application.answerSubAssistant(original, "run", "ask", "old page") }
            val selected = f.sessions.observeSelectedRealmSelection().first { it != null }!!
            val reopened = ConversationViewLease(f.rootId, selected.access, selected.revision) {}
            try {
                assertFalse(f.application.answerSubAssistant(reopened.commandTarget, "run", "ask", "new session"))
                assertFalse(answer.isCompleted)
            } finally { reopened.close(); f.runGate.unregisterPendingInteraction("run", answer) }
        }
    }

    @Test fun `switching back permits a fresh page to answer the same original background run`() = runTest {
        fixture { f ->
            val target = f.page.commandTarget
            val answer = f.runGate.registerPendingInteraction(f.rootId, target.selection.access, "run", "ask", Job())
            f.sessions.selectPersonalFixture()
            f.sessions.selectEnterpriseFixture()
            rejects<EnterpriseConfigurationException> { f.application.answerSubAssistant(target, "run", "ask", "old page") }
            val selected = f.sessions.observeSelectedRealmSelection().first { it != null }!!
            val reopened = ConversationViewLease(f.rootId, selected.access, selected.revision) {}
            try {
                assertTrue(f.application.answerSubAssistant(reopened.commandTarget, "run", "ask", "resumed"))
                assertEquals("resumed", answer.await())
            } finally { reopened.close(); f.runGate.unregisterPendingInteraction("run", answer) }
        }
    }

    @Test fun `closing or cancelling a queued child answer cannot complete its waiter`() = runTest {
        for (cancel in listOf(false, true)) fixture { f ->
            val target = f.page.commandTarget
            val answer = f.runGate.registerPendingInteraction(f.rootId, target.selection.access, "run", "ask", Job())
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val lockOwner = launch { f.locks.withLock(f.rootId) { entered.complete(Unit); release.await() } }
            entered.await()
            val command = launch {
                if (cancel) f.application.answerSubAssistant(target, "run", "ask", "queued")
                else rejects<IllegalStateException> { f.application.answerSubAssistant(target, "run", "ask", "closed") }
            }
            runCurrent()
            if (cancel) command.cancel() else f.page.close()
            release.complete(Unit)
            command.join()
            lockOwner.join()
            assertFalse(answer.isCompleted)
            f.runGate.unregisterPendingInteraction("run", answer)
        }
    }

    @Test fun `start rejects old selection and closed page before installing any worker`() = runTest {
        fixture { f ->
            val target = f.page.commandTarget
            f.sessions.selectPersonalFixture()
            f.sessions.selectEnterpriseFixture()
            rejects<EnterpriseConfigurationException> { f.turns.sendMessage(target, listOf(UIMessagePart.Text("late")), false) }
            assertNull(f.runtime.currentWorker())
            coVerify(exactly = 0) { f.repository.commit(any()) }
        }
        fixture { f ->
            f.page.close()
            rejects<IllegalStateException> { f.turns.sendMessage(f.page.commandTarget, listOf(UIMessagePart.Text("closed")), false) }
            assertNull(f.runtime.currentWorker())
        }
    }

    @Test fun `accepted append survives page closure and selection switch in its original realm`() = runTest {
        fixture { f ->
            val receipt = requireNotNull(f.turns.sendMessage(f.page.commandTarget, listOf(UIMessagePart.Text("accepted")), false))
            f.page.close()
            f.sessions.selectPersonalFixture()
            runCurrent()
            assertEquals(f.errors.errors.value.toString(), receipt.userMessageId, f.runtime.durable.currentMessages().last().id)
            assertEquals("accepted", f.runtime.durable.currentMessages().last().toText())
            assertEquals(f.scope, f.runtime.durable.header.scope)
            assertTrue(f.errors.errors.value.toString(), f.errors.errors.value.isEmpty())
            assertNull(f.runtime.currentWorker())
        }
    }

    @Test fun `closing Session after acceptance prevents an append from running under a later login`() = runTest {
        fixture { f ->
            f.turns.sendMessage(f.page.commandTarget, listOf(UIMessagePart.Text("revoked")), false)
            f.sessions.finishExit(f.sessions.beginExit(requireNotNull(f.sessions.captureExitRequest())))
            f.sessions.enrollFixture(exampleEnterprisePackage())
            runCurrent()
            assertEquals("original", f.runtime.durable.currentMessages().single().toText())
            assertNull(f.runtime.currentWorker())
            assertEquals(1, f.errors.errors.value.size)
            coVerify(exactly = 0) { f.repository.commit(any()) }
        }
    }

    @Test fun `cancellation before installation does not cancel the existing worker`() = runTest {
        fixture { f ->
            val held = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val worker = Job()
            f.runtime.installTurnWorker(Uuid.random(), worker)
            val holder = launch { f.locks.withLock(f.rootId) { held.complete(Unit); release.await() } }
            held.await()
            val sending = launch { f.turns.sendMessage(f.page.commandTarget, listOf(UIMessagePart.Text("cancelled")), false) }
            runCurrent()
            sending.cancel()
            release.complete(Unit)
            sending.join(); holder.join(); runCurrent()
            assertSame(worker, f.runtime.currentWorker())
            assertTrue(worker.isActive)
            coVerify(exactly = 0) { f.repository.commit(any()) }
            worker.cancel()
        }
    }

    @Test fun `three accepted replacements await the entire original cleanup before appending`() = runTest {
        fixture { f ->
            val release = CompletableDeferred<Unit>()
            val cleanup = CompletableDeferred<Unit>()
            val original = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { cleanup.complete(Unit); release.await() } }
            }
            f.runtime.installTurnWorker(Uuid.random(), original)
            f.turns.sendMessage(f.page.commandTarget, listOf(UIMessagePart.Text("B")), false)
            val latest = requireNotNull(f.turns.sendMessage(f.page.commandTarget, listOf(UIMessagePart.Text("C")), false))
            cleanup.await()
            runCurrent()
            assertEquals("original", f.runtime.durable.currentMessages().single().toText())
            release.complete(Unit)
            runCurrent()
            assertEquals(f.errors.errors.value.toString(), listOf("original", "C"), f.runtime.durable.currentMessages().map { it.toText() })
            assertEquals(latest.userMessageId, f.runtime.durable.currentMessages().last().id)
            assertNull(f.runtime.currentWorker())
            assertTrue(f.errors.errors.value.toString(), f.errors.errors.value.isEmpty())
        }
    }

    @Test fun `failed original terminal survives two preparing replacements and can be stopped later`() = runTest {
        fixture { f ->
            val originalTurn = Uuid.random()
            val originalWorker = Job()
            f.runtime.installTurnWorker(originalTurn, originalWorker)
            net.weero.measix.pilot.service.turn.TurnCommitter.start(f.coordinator, f.runtime, originalTurn, disclosureCandidate(), f.finalizer)
            f.failTerminal = true
            f.turns.sendMessage(f.page.commandTarget, listOf(UIMessagePart.Text("B")), false)
            f.turns.sendMessage(f.page.commandTarget, listOf(UIMessagePart.Text("C")), false)
            runCurrent()
            assertEquals(originalTurn, f.runtime.snapshot.value.stream?.turnId)
            assertSame(originalWorker, f.runtime.currentWorker())
            assertFalse(f.runtime.durable.currentMessages().any { it.toText() in listOf("B", "C") })
            f.failTerminal = false
            f.application.stopGeneration(f.page.commandTarget)
            assertNull(f.runtime.currentWorker())
            assertNull(f.runtime.snapshot.value.stream)
            assertNull(f.runtime.peekCancelReason(originalTurn))
        }
    }

    @Test fun `stop captured while replacement prepares retains the failed original terminal ticket`() = runTest {
        fixture { f ->
            val originalTurn = Uuid.random()
            val originalWorker = Job()
            f.runtime.installTurnWorker(originalTurn, originalWorker)
            net.weero.measix.pilot.service.turn.TurnCommitter.start(f.coordinator, f.runtime, originalTurn, disclosureCandidate(), f.finalizer)
            f.failTerminal = true
            f.turns.sendMessage(f.page.commandTarget, listOf(UIMessagePart.Text("B")), false)
            val stop = requireNotNull(f.finalizer.captureStop(f.rootId))
            runCurrent()
            assertSame(originalWorker, f.runtime.currentWorker())
            f.failTerminal = false
            f.finalizer.finishStop(stop)
            assertNull(f.runtime.snapshot.value.stream)
            assertNull(f.runtime.currentWorker())
            assertNull(f.runtime.peekCancelReason(originalTurn))
        }
    }

    @Test fun `stop and supersede serialize one terminal commit and replacement still appends`() = runTest {
        fixture { f ->
            val originalTurn = Uuid.random()
            f.runtime.installTurnWorker(originalTurn, Job())
            net.weero.measix.pilot.service.turn.TurnCommitter.start(f.coordinator, f.runtime, originalTurn, disclosureCandidate(), f.finalizer)
            val stop = requireNotNull(f.finalizer.captureStop(f.rootId))
            val receipt = requireNotNull(f.turns.sendMessage(f.page.commandTarget, listOf(UIMessagePart.Text("B")), false))
            f.finalizer.finishStop(stop)
            runCurrent()
            assertEquals(f.errors.errors.value.toString(), receipt.userMessageId, f.runtime.durable.currentMessages().last().id)
            assertNull(f.runtime.currentWorker())
            assertNull(f.runtime.peekCancelReason(originalTurn))
            assertEquals(1, f.terminalCommits)
            assertTrue(f.errors.errors.value.toString(), f.errors.errors.value.isEmpty())
        }
    }

    @Test fun `fresh page in a new Session cannot continue a turn frozen under the previous login`() = runTest {
        fixture { f ->
            val originalAccess = f.page.commandTarget.selection.access
            val turnId = Uuid.random()
            val worker = Job()
            f.runtime.installTurnWorker(turnId, worker)
            val started = net.weero.measix.pilot.service.turn.TurnCommitter.start(
                f.coordinator, f.runtime, turnId, disclosureCandidate(), f.finalizer)
            val lease = net.weero.measix.pilot.service.runtime.ModelExecutionLease { error("no request expected") }
            f.runtime.bindModelExecution(turnId, worker, lease)
            f.runtime.bindTurnContext(turnId, worker, mockk {
                every { realmAccess } returns originalAccess
                every { model } returns mockk { every { executionLease } returns lease }
            })
            f.runtime.retainAwaitingUser(started.handle)
            worker.complete()
            f.sessions.finishExit(f.sessions.beginExit(requireNotNull(f.sessions.captureExitRequest())))
            f.sessions.enrollFixture(exampleEnterprisePackage())
            val selected = f.sessions.observeSelectedRealmSelection().first { it != null }!!
            val reopened = ConversationViewLease(f.rootId, selected.access, selected.revision) {}
            try {
                rejects<IllegalStateException> {
                    f.turns.submitToolDecision(reopened.commandTarget,
                        me.rerere.ai.core.ToolCallLocator(Uuid.random(), Uuid.random(), Uuid.random()),
                        ToolInteractionDecision.Approve)
                }
                assertSame(worker, f.runtime.currentWorker())
                assertTrue(f.runtime.isAwaitingUser(turnId))
                assertEquals(turnId, f.runtime.snapshot.value.stream?.turnId)
            } finally { reopened.close() }
        }
    }

    @Test fun `cancellation during real Service START commit still hands off the terminal owner`() = runTest {
        fixture { f ->
            f.enableGeneration()
            f.cancelDuringStart = true
            val receipt = requireNotNull(f.turns.sendMessage(f.page.commandTarget, listOf(UIMessagePart.Text("start"))))
            requireNotNull(f.runtime.currentWorker()).join()
            assertEquals(net.weero.measix.pilot.data.db.entity.TurnExecutionStatus.CANCELLED,
                f.executions[receipt.turnId.toString()]?.status)
            assertEquals(1, f.terminalCommits)
            assertNull(f.runtime.snapshot.value.stream)
            assertNull(f.runtime.currentWorker())
            coVerify(exactly = 0) { f.runner.run(any()) }
            assertTrue(f.errors.errors.value.toString(), f.errors.errors.value.isEmpty())
        }
    }

    @Test fun `real Service continuation reuses the original context and Turn after switching away and back`() = runTest {
        fixture { f ->
            f.enableGeneration()
            val inputs = mutableListOf<net.weero.measix.pilot.service.turn.TurnRunInputs>()
            lateinit var locator: me.rerere.ai.core.ToolCallLocator
            coEvery { f.runner.run(any()) } coAnswers {
                val input = firstArg<net.weero.measix.pilot.service.turn.TurnRunInputs>()
                inputs += input
                if (inputs.size == 1) {
                    val assistant = input.messages.last()
                    val parts = assistant.parts.map {
                        if (it is UIMessagePart.Step) it.copy(modelResult = net.weero.measix.pilot.testkit.sampledModelResult()) else it
                    }
                    val step = parts.filterIsInstance<UIMessagePart.Step>().single()
                    val tool = UIMessagePart.Tool(localCallId = Uuid.random(), stepId = step.stepId,
                        providerCallId = "call", toolName = "ask_user", input = "{}",
                        interactionState = me.rerere.ai.ui.ToolInteractionState.AwaitingInput)
                    locator = me.rerere.ai.core.ToolCallLocator(assistant.id, step.stepId, tool.localCallId)
                    input.onCheckpoint(ModelResponseCheckpoint(input.handle, StepHandle(step.stepId),
                        assistant.copy(parts = parts + tool), net.weero.measix.pilot.data.db.entity.TurnExecutionStatus.AWAITING_USER))
                    net.weero.measix.pilot.service.turn.TurnPause(listOf(
                        net.weero.measix.pilot.data.ai.tools.PendingToolInteraction(locator, tool.interactionState)))
                } else awaitCancellation()
            }
            val receipt = requireNotNull(f.turns.sendMessage(f.page.commandTarget, listOf(UIMessagePart.Text("start"))))
            f.runtime.activeTurnRevision.first { f.runtime.isAwaitingUser(receipt.turnId) || f.runtime.currentWorker() == null }
            runCurrent()
            assertTrue(f.errors.errors.value.toString(), f.errors.errors.value.isEmpty())
            assertTrue(f.runtime.isAwaitingUser(receipt.turnId))
            f.sessions.selectPersonalFixture(); f.sessions.selectEnterpriseFixture()
            val selected = f.sessions.observeSelectedRealmSelection().first { it != null }!!
            val reopened = ConversationViewLease(f.rootId, selected.access, selected.revision) {}
            try {
                f.turns.submitToolDecision(reopened.commandTarget, locator, ToolInteractionDecision.Answer("answer"))
                runCurrent()
                assertEquals(2, inputs.size)
                assertSame(inputs[0].turnContext, inputs[1].turnContext)
                assertEquals(inputs[0].handle, inputs[1].handle)
                assertEquals(f.page.commandTarget.selection.access, inputs[1].turnContext.realmAccess)
                assertEquals(1, f.executions.size)
                f.application.stopGeneration(reopened.commandTarget)
                assertNull(f.runtime.snapshot.value.stream)
                assertNull(f.runtime.currentWorker())
            } finally { reopened.close() }
        }
    }

    private suspend fun TestScope.fixture(action: suspend (Fixture) -> Unit) {
        val f = Fixture(this)
        try { f.initialize(); action(f) }
        finally { f.appScope.cancel() }
    }

    @Test fun `summary registration remains owned when caller is cancelled before admission returns`() = runTest {
        fixture { f ->
            val cleanup = CompletableDeferred<Unit>()
            lateinit var caller: Job
            val worker = f.appScope.async<Result<Unit>>(start = CoroutineStart.LAZY) {
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { cleanup.await() } }
            }
            every { f.effects.launchCompression(any(), any(), any(), any(), any(), any()) } answers {
                f.runtime.registerAuxiliaryWorker(f.page.access, worker)
                worker.start()
                caller.cancel()
                worker
            }
            // Enter the worker first so its cancellation must wait for real cleanup.
            worker.start()
            runCurrent()
            caller = launch(start = CoroutineStart.LAZY) { f.application.compress(f.page.commandTarget, "", 100, 0) }
            caller.start()
            runCurrent()
            assertTrue(worker.isCancelled)
            assertFalse(caller.isCompleted)
            assertTrue(f.runtime.hasAuxiliaryWork)
            cleanup.complete(Unit)
            caller.join()
            assertTrue(worker.isCompleted)
            assertFalse(f.runtime.hasAuxiliaryWork)
            coVerify(exactly = 0) { f.repository.commit(any()) }
        }
    }

    @Test fun `enterprise exit awaits its auxiliary cleanup and durable terminal without stopping personal work`() = runTest {
        fixture { f ->
            val personalId = f.put(ConfigurationScope.Personal)
            val personal = f.registry.registerSnapshot(f.rows.getValue(personalId))
            val personalWorker = Job()
            personal.installTurnWorker(Uuid.random(), personalWorker)
            val turn = Uuid.random()
            val worker = Job()
            f.runtime.installTurnWorker(turn, worker)
            net.weero.measix.pilot.service.turn.TurnCommitter.start(f.coordinator, f.runtime, turn, disclosureCandidate(), f.finalizer)
            val release = CompletableDeferred<Unit>()
            val auxiliary = f.appScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { release.await() } }
            }
            f.runtime.registerAuxiliaryWorker(f.page.access, auxiliary)
            val token = f.sessions.beginExit(requireNotNull(f.sessions.captureExitRequest()))
            val stop = async { f.application.stopEnterpriseWork(token) }
            runCurrent()
            assertFalse(stop.isCompleted)
            assertTrue(auxiliary.isCancelled)
            assertFalse(auxiliary.isCompleted)
            assertTrue(personalWorker.isActive)
            release.complete(Unit)
            stop.await()
            assertEquals(TurnExecutionStatus.CANCELLED, f.executions[turn.toString()]?.status)
            assertNull(f.runtime.snapshot.value.stream)
            assertFalse(f.runtime.hasAuxiliaryWork)
            assertTrue(personalWorker.isActive)
            f.sessions.finishExit(token)
            personalWorker.cancel()
        }
    }

    @Test fun `enterprise stop failure retains terminal owner and retry verifies stored unfinished turns`() = runTest {
        fixture { f ->
            val turn = Uuid.random()
            val worker = Job()
            f.runtime.installTurnWorker(turn, worker)
            net.weero.measix.pilot.service.turn.TurnCommitter.start(f.coordinator, f.runtime, turn, disclosureCandidate(), f.finalizer)
            val token = f.sessions.beginExit(requireNotNull(f.sessions.captureExitRequest()))
            f.failTerminal = true
            rejects<java.io.IOException> { f.application.stopEnterpriseWork(token) }
            assertEquals(token, f.sessions.pendingExit())
            assertSame(worker, f.runtime.currentWorker())
            assertEquals(TurnExecutionStatus.RUNNING, f.executions[turn.toString()]?.status)
            f.failTerminal = false
            f.application.stopEnterpriseWork(token)
            assertNull(f.runtime.currentWorker())
            assertEquals(TurnExecutionStatus.CANCELLED, f.executions[turn.toString()]?.status)
            coEvery { f.repository.countUnfinishedTurns(f.scope) } returns 1
            rejects<IllegalStateException> { f.application.requireEnterpriseStopped(token) }
            assertEquals(token, f.sessions.pendingExit())
        }
    }

    @Test fun `cache eviction after enterprise runtime capture does not skip remaining active work`() = runTest {
        fixture { f ->
            val turn = Uuid.random()
            f.runtime.installTurnWorker(turn, Job())
            net.weero.measix.pilot.service.turn.TurnCommitter.start(f.coordinator, f.runtime, turn, disclosureCandidate(), f.finalizer)
            val token = f.sessions.beginExit(requireNotNull(f.sessions.captureExitRequest()))
            val idleId = f.put(f.scope, parent = f.rootId)
            f.registry.registerSnapshot(f.rows.getValue(idleId))
            val held = CompletableDeferred<Unit>()
            val evict = CompletableDeferred<Unit>()
            val holder = launch {
                f.locks.withLock(idleId) {
                    held.complete(Unit)
                    evict.await()
                    f.registry.evictRuntime(idleId)
                }
            }
            held.await()
            val stop = async { f.application.stopEnterpriseWork(token) }
            runCurrent()
            assertFalse(stop.isCompleted)
            evict.complete(Unit)
            holder.join()
            stop.await()
            assertEquals(TurnExecutionStatus.CANCELLED, f.executions[turn.toString()]?.status)
            assertNull(f.runtime.currentWorker())
            assertEquals(token, f.sessions.pendingExit())
        }
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
        val executions = linkedMapOf<String, net.weero.measix.pilot.data.db.entity.TurnExecutionEntity>()
        var failTerminal = false
        var cancelDuringStart = false
        var terminalCommits = 0
        val locks = ConversationOperationLocks()
        val registry = ConversationRuntimeRegistry(appScope, repository, locks)
        val gate = ApplicationRecoveryGate().apply { ready() }
        val coordinator = ConversationCommandCoordinator(registry, repository, gate, locks)
        val finalizer = TurnFinalizer(repository, registry, coordinator, JsonInstant)
        val lifecycle = SubAssistantLifecycle(repository, registry, coordinator, JsonInstant)
        val effects = mockk<GenerationSideEffects>(relaxed = true)
        val errors = ChatErrorStore()
        val runner = mockk<net.weero.measix.pilot.service.turn.TurnRunner>()
        val memory = mockk<MemoryService>()
        val mcp = mockk<net.weero.measix.pilot.data.ai.mcp.McpRuntimeCoordinator>()
        val turns by lazy {
            val context = mockk<android.app.Application>()
            every { context.getString(any()) } returns "operation"
            every { effects.preloadSoundEffects() } returns Unit
            ConversationTurnService(context, appScope, mockk(relaxed = true), settings, net.weero.measix.pilot.test.testModelExecutionService(settings, sessions, gate), memory, sessions, runner, mockk(relaxed = true),
                mcp, mockk(relaxed = true), net.weero.measix.pilot.service.turn.TurnContextFactory(mockk()),
                mockk(relaxed = true), mockk(), finalizer, lifecycle, registry, coordinator, gate,
                errors, effects, ArtifactUseCase(artifactStore, gate), ConversationTitleCoordinator())
        }
        val runGate = net.weero.measix.pilot.service.subassistant.SubAssistantRunGate()
        val application = ConversationApplicationService(settings, repository, mockk(), registry, coordinator, gate,
            lifecycle, effects, artifactStore, mockk(), finalizer, JsonInstant, mockk(), ConversationTitleCoordinator(), sessions, runGate)
        val rootId = put(scope)
        lateinit var runtime: ConversationRuntime
        lateinit var page: ConversationViewLease

        init {
            every { settings.effectiveSettings } returns kotlinx.coroutines.flow.MutableStateFlow(
                net.weero.measix.pilot.data.datastore.EffectiveSettingsSnapshot(
                    net.weero.measix.pilot.data.datastore.Settings(),
                    net.weero.measix.pilot.data.datastore.SettingsAccessIndex(), 0,
                    net.weero.measix.pilot.data.datastore.ManagedConfigurationState.ABSENT))
            net.weero.measix.pilot.test.installExecutionConfigurationFixture(settings)
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
            coEvery { repository.getTurnExecution(any()) } answers { executions[firstArg()] }
            coEvery { repository.countUnfinishedTurns(any()) } answers {
                val requested = firstArg<ConfigurationScope>()
                executions.values.count { execution ->
                    rows[Uuid.parse(execution.conversationId)]?.header?.scope == requested &&
                        execution.status in setOf(TurnExecutionStatus.RUNNING, TurnExecutionStatus.AWAITING_USER)
                }
            }
            coEvery { repository.getTurnExecutions(any()) } returns emptyList()
            coEvery { repository.getToolExecutions(any()) } returns emptyList()
            coEvery { repository.existsConversationById(any()) } answers { rows.containsKey(firstArg()) }
            coEvery { repository.commit(any()) } answers {
                val facts = (firstArg<ConversationWrite>() as? ConversationWrite.Mutate)?.executionFacts
                facts?.turn?.let {
                    if (it.status == net.weero.measix.pilot.data.db.entity.TurnExecutionStatus.CANCELLED) {
                        if (failTerminal) throw java.io.IOException("terminal disk failure")
                        terminalCommits++
                    }
                    executions[it.turnId] = it
                    if (cancelDuringStart && facts.turnOperation == TurnExecutionOperation.START) {
                        runtime.requestCancel(Uuid.parse(it.turnId), "user_stop")
                    }
                }
                true
            }
            coEvery { repository.deleteConversation(any()) } answers {
                val id = firstArg<Uuid>()
                rows.entries.removeAll { it.key == id || it.value.header.parentConversationId == id }
                Unit
            }
            coEvery { repository.insertConversationSnapshot(any()) } answers {
                val snapshot = firstArg<ConversationAggregateSnapshot>()
                rows[snapshot.conversationId] = snapshot
            }
            coEvery { artifactStore.retainNodesForUndo(any(), any()) } answers { ArtifactRetentionLease { retentionReleased++ } }
            every { effects.clearTitleTracking(any()) } returns Unit
        }

        fun configureAssistant(assistant: net.weero.measix.pilot.data.model.Assistant) {
            val current = settings.effectiveSettings.value
            every { settings.effectiveSettings } returns kotlinx.coroutines.flow.MutableStateFlow(current.copy(
                settings = current.settings.copy(assistants = listOf(assistant))))
        }

        fun enableGeneration() {
            val model = me.rerere.ai.provider.Model(modelId = "test")
            val assistant = net.weero.measix.pilot.data.model.Assistant(id = DEFAULT_ASSISTANT_ID, enableMemory = false, chatModelId = model.id)
            val config = net.weero.measix.pilot.data.datastore.Settings(assistants = listOf(assistant),
                providers = listOf(me.rerere.ai.provider.ProviderSetting.OpenAI(models = listOf(model))), chatModelId = model.id)
            every { settings.effectiveSettings } returns kotlinx.coroutines.flow.MutableStateFlow(
                net.weero.measix.pilot.data.datastore.EffectiveSettingsSnapshot(config,
                    net.weero.measix.pilot.data.datastore.SettingsAccessIndex(), 0,
                    net.weero.measix.pilot.data.datastore.ManagedConfigurationState.ABSENT))
            coEvery { memory.captureExecution(any(), any()) } returns null
            coEvery { mcp.prepareTurnCapabilities(any()) } returns net.weero.measix.pilot.data.ai.mcp.TurnMcpCapabilitySnapshot.EMPTY
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
