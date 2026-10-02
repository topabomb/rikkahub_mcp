package net.weero.measix.pilot.service
import net.weero.measix.pilot.service.turn.TurnRecovery

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.imggen.GeneratedMediaStore
import net.weero.measix.pilot.data.repository.ConversationRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationRecoveryCoordinatorTest {
    @Test
    fun `pending backup is completed only after recovery maintenance succeeds`() = runTest {
        val events = mutableListOf<String>()
        val env = Env(
            scope = this,
            restorePendingBackup = { events += "restore" },
            recoverEnterpriseDataReset = { events += "reset" },
            recoverEnterpriseConfiguration = { events += "enterprise" },
            completePendingEnterpriseExit = { events += "exit" },
            postRecoveryMaintenance = { events += "maintenance" },
            completePendingBackup = { events += "complete" },
        )
        coEvery { env.settingsStore.initializeForRecovery() } coAnswers { events += "settings" }
        coEvery { env.artifactStore.reconcileStartup() } coAnswers { events += "artifact" }
        coEvery { env.generatedMediaStore.reconcile(any()) } coAnswers { events += "generated" }
        coEvery { env.artifactStore.ensureReferenceProjection() } coAnswers { events += "references" }
        coEvery { env.repository.ensureSearchProjection() } coAnswers { events += "search" }
        coEvery { env.turnRecovery.recoverInterruptedRuns() } coAnswers { events += "runs" }
        coEvery { env.turnRecovery.recoverInterruptedTurns() } coAnswers { events += "turns" }
        coEvery { env.assistantManagement.performPendingDeletionCleanupDuringRecovery() } coAnswers {
            events += "assistants"
        }

        org.junit.Assert.assertFalse(env.assistantDependency.isInitialized())
        env.coordinator.recoverNow()
        assertTrue(env.assistantDependency.isInitialized())

        assertEquals(
            listOf(
                "restore",
                "settings",
                "artifact",
                "generated",
                "reset",
                "enterprise",
                "references",
                "search",
                "runs",
                "turns",
                "assistants",
                "exit",
                "reset",
                "maintenance",
                "complete",
            ),
            events,
        )
        assertEquals(ApplicationRecoveryState.Ready, env.gate.state.value)
    }

    @Test
    fun `settings failure preserves its cause and retry resumes the existing recovery owner`() = runTest {
        val env = Env(this)
        val failure = java.io.IOException("settings read denied", IllegalStateException("volume unavailable"))
        coEvery { env.settingsStore.initializeForRecovery() } throws failure
        env.coordinator.retry()
        advanceUntilIdle()
        assertEquals(failure, (env.gate.state.value as ApplicationRecoveryState.Failed).error)
        val rejection = runCatching { env.gate.awaitReady() }.exceptionOrNull()
        assertEquals(failure, rejection?.cause)
        coVerify(exactly = 0) { env.artifactStore.reconcileStartup() }
        coEvery { env.settingsStore.initializeForRecovery() } returns Unit
        env.coordinator.retry()
        advanceUntilIdle()
        assertEquals(ApplicationRecoveryState.Ready, env.gate.state.value)
        coVerify(exactly = 1) { env.artifactStore.reconcileStartup() }
    }

    @Test
    fun `cancelled settings recovery stays closed without presenting a failure and can retry`() = runTest {
        val env = Env(this)
        coEvery { env.settingsStore.initializeForRecovery() } throws CancellationException("caller cancelled")
        env.coordinator.retry()
        advanceUntilIdle()
        assertEquals(ApplicationRecoveryState.Loading, env.gate.state.value)
        coVerify(exactly = 0) { env.artifactStore.reconcileStartup() }
        coEvery { env.settingsStore.initializeForRecovery() } returns Unit
        env.coordinator.retry()
        advanceUntilIdle()
        assertEquals(ApplicationRecoveryState.Ready, env.gate.state.value)
    }

    @Test
    fun `failure is fail-closed and retry converges once`() = runTest {
        val env = Env(this)
        val failure = IllegalStateException("projection invalid")
        coEvery { env.artifactStore.ensureReferenceProjection() } throws failure

        env.coordinator.recoverNow()

        val failed = env.gate.state.value as ApplicationRecoveryState.Failed
        assertEquals(failure, failed.error)
        assertTrue(runCatching { env.gate.awaitReady() }.exceptionOrNull() is ApplicationRecoveryUnavailableException)
        coVerify(exactly = 0) { env.repository.ensureSearchProjection() }
        org.junit.Assert.assertFalse(env.assistantDependency.isInitialized())

        coEvery { env.artifactStore.ensureReferenceProjection() } returns Unit
        env.coordinator.recoverNow()

        assertEquals(ApplicationRecoveryState.Ready, env.gate.state.value)
        coVerify(exactly = 1) { env.turnRecovery.recoverInterruptedRuns() }
        coVerify(exactly = 1) { env.turnRecovery.recoverInterruptedTurns() }
        coVerify(exactly = 1) { env.assistantManagement.performPendingDeletionCleanupDuringRecovery() }
    }

    @Test
    fun `generated media reconcile failure keeps file ports closed`() = runTest {
        val env = Env(this)
        val failure = IllegalStateException("generated media tombstone is corrupt")
        coEvery { env.generatedMediaStore.reconcile(any()) } throws failure

        env.coordinator.recoverNow()

        assertEquals(failure, (env.gate.state.value as ApplicationRecoveryState.Failed).error)
        assertTrue(runCatching { env.gate.awaitReady() }.isFailure)
        coVerify(exactly = 0) { env.artifactStore.ensureReferenceProjection() }
        coVerify(exactly = 0) { env.repository.ensureSearchProjection() }
    }

    @Test
    fun `concurrent retry requests share one recovery owner`() = runTest {
        val env = Env(this)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { env.artifactStore.reconcileStartup() } coAnswers {
            entered.complete(Unit)
            release.await()
        }

        env.coordinator.retry()
        env.coordinator.retry()
        runCurrent()
        entered.await()

        coVerify(exactly = 1) { env.artifactStore.reconcileStartup() }
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(ApplicationRecoveryState.Ready, env.gate.state.value)

        env.coordinator.retry()
        advanceUntilIdle()
        coVerify(exactly = 1) { env.artifactStore.reconcileStartup() }
    }

    @Test
    fun `assistant graph is initialized by the recovery dispatcher before ready`() = runTest {
        Executors.newSingleThreadExecutor { task -> Thread(task, "recovery-test") }
            .asCoroutineDispatcher().use { dispatcher ->
                var initializedOn: String? = null
                val env = Env(this, recoveryDispatcher = dispatcher, onAssistantInitialization = {
                    initializedOn = Thread.currentThread().name
                })
                org.junit.Assert.assertNull(initializedOn)
                env.coordinator.recoverNow()
                assertEquals("recovery-test", initializedOn)
                assertEquals(ApplicationRecoveryState.Ready, env.gate.state.value)
            }
    }

    private class Env(
        scope: TestScope,
        recoveryDispatcher: CoroutineDispatcher = StandardTestDispatcher(scope.testScheduler),
        onAssistantInitialization: () -> Unit = {},
        restorePendingBackup: suspend () -> Unit = {},
        recoverEnterpriseDataReset: suspend () -> Unit = {},
        recoverEnterpriseConfiguration: suspend () -> Unit = {},
        completePendingEnterpriseExit: suspend () -> Unit = {},
        completePendingBackup: () -> Unit = {},
        postRecoveryMaintenance: suspend () -> Unit = {},
    ) {
        val gate = ApplicationRecoveryGate()
        val artifactStore = mockk<ArtifactStore>()
        val generatedMediaStore = mockk<GeneratedMediaStore>()
        val repository = mockk<ConversationRepository>()
        val turnRecovery = mockk<TurnRecovery>()
        val assistantManagement = mockk<AssistantManagementService>()
        val settingsStore = mockk<SettingsStore>()
        val assistantDependency = lazy { onAssistantInitialization(); assistantManagement }
        val coordinator: ApplicationRecoveryCoordinator

        init {
            coEvery { settingsStore.initializeForRecovery() } returns Unit
            coEvery { artifactStore.reconcileStartup() } returns Unit
            coEvery { generatedMediaStore.reconcile(any()) } returns Unit
            coEvery { artifactStore.ensureReferenceProjection() } returns Unit
            coEvery { repository.ensureSearchProjection() } returns Unit
            coEvery { turnRecovery.recoverInterruptedRuns() } returns Unit
            coEvery { turnRecovery.recoverInterruptedTurns() } returns Unit
            coEvery { assistantManagement.performPendingDeletionCleanupDuringRecovery() } returns Unit
            coordinator = ApplicationRecoveryCoordinator(
                appScope = scope,
                settingsStore = settingsStore,
                artifactStore = artifactStore,
                generatedMediaStore = generatedMediaStore,
                conversationRepository = repository,
                turnRecovery = turnRecovery,
                assistantManagementService = assistantDependency,
                recoveryDispatcher = recoveryDispatcher,
                gate = gate,
                restorePendingBackup = restorePendingBackup,
                recoverEnterpriseDataReset = recoverEnterpriseDataReset,
                recoverEnterpriseConfiguration = recoverEnterpriseConfiguration,
                completePendingEnterpriseExit = completePendingEnterpriseExit,
                completePendingBackup = completePendingBackup,
                postRecoveryMaintenance = postRecoveryMaintenance,
                startImmediately = false,
            )
        }
    }
}
