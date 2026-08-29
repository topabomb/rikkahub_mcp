package net.weero.measix.pilot.service

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifySequence
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.ManagedConfigurationState
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.toEffectiveSettingsSnapshot
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.imggen.GeneratedMediaStore
import net.weero.measix.pilot.data.repository.ConversationRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationRecoveryCoordinatorTest {
    @Test
    fun `recovery executes the only valid order before opening the gate`() = runTest {
        val env = Env(this)

        env.coordinator.recoverNow()

        assertEquals(ApplicationRecoveryState.Ready, env.gate.state.value)
        coVerifySequence {
            env.artifactStore.reconcileStartup()
            env.generatedMediaStore.reconcile(any())
            env.artifactStore.ensureReferenceProjection()
            env.repository.ensureSearchProjection()
            env.turnRecovery.recoverInterruptedRuns()
            env.turnRecovery.recoverInterruptedTurns()
            env.assistantManagement.performPendingDeletionCleanupDuringRecovery()
        }
    }

    @Test
    fun `pending backup is completed only after recovery maintenance succeeds`() = runTest {
        val events = mutableListOf<String>()
        val env = Env(
            scope = this,
            restorePendingBackup = { events += "restore" },
            postRecoveryMaintenance = { events += "maintenance" },
            completePendingBackup = { events += "complete" },
        )
        coEvery { env.artifactStore.reconcileStartup() } coAnswers { events += "artifact" }
        coEvery { env.generatedMediaStore.reconcile(any()) } coAnswers { events += "generated" }
        coEvery { env.artifactStore.ensureReferenceProjection() } coAnswers { events += "references" }
        coEvery { env.repository.ensureSearchProjection() } coAnswers { events += "search" }
        coEvery { env.turnRecovery.recoverInterruptedRuns() } coAnswers { events += "runs" }
        coEvery { env.turnRecovery.recoverInterruptedTurns() } coAnswers { events += "turns" }
        coEvery { env.assistantManagement.performPendingDeletionCleanupDuringRecovery() } coAnswers {
            events += "assistants"
        }

        env.coordinator.recoverNow()

        assertEquals(
            listOf(
                "restore",
                "artifact",
                "generated",
                "references",
                "search",
                "runs",
                "turns",
                "assistants",
                "maintenance",
                "complete",
            ),
            events,
        )
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
    fun `blocked managed configuration keeps every recovery-dependent service closed`() = runTest {
        val env = Env(this, managedState = ManagedConfigurationState.BLOCKED)

        env.coordinator.recoverNow()

        val failure = env.gate.state.value as ApplicationRecoveryState.Failed
        assertTrue(failure.error is ManagedConfigurationBlockedException)
        coVerify(exactly = 0) { env.artifactStore.reconcileStartup() }
        coVerify(exactly = 0) { env.generatedMediaStore.reconcile(any()) }
        coVerify(exactly = 0) { env.repository.ensureSearchProjection() }
        coVerify(exactly = 0) { env.turnRecovery.recoverInterruptedRuns() }
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

    private class Env(
        scope: kotlinx.coroutines.CoroutineScope,
        restorePendingBackup: suspend () -> Unit = {},
        completePendingBackup: () -> Unit = {},
        postRecoveryMaintenance: suspend () -> Unit = {},
        managedState: ManagedConfigurationState = ManagedConfigurationState.ABSENT,
    ) {
        val gate = ApplicationRecoveryGate()
        val artifactStore = mockk<ArtifactStore>()
        val generatedMediaStore = mockk<GeneratedMediaStore>()
        val repository = mockk<ConversationRepository>()
        val turnRecovery = mockk<TurnRecovery>()
        val assistantManagement = mockk<AssistantManagementService>()
        private val settingsStore = mockk<SettingsStore>()
        val coordinator: ApplicationRecoveryCoordinator

        init {
            every { settingsStore.effectiveSettings } returns
                MutableStateFlow(Settings(init = false).toEffectiveSettingsSnapshot(managedState = managedState))
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
                assistantManagementService = assistantManagement,
                gate = gate,
                restorePendingBackup = restorePendingBackup,
                completePendingBackup = completePendingBackup,
                postRecoveryMaintenance = postRecoveryMaintenance,
                startImmediately = false,
            )
        }
    }
}
