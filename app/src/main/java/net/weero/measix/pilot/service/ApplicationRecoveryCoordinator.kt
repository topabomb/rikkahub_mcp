package net.weero.measix.pilot.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.ManagedConfigurationState
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.imggen.GeneratedMediaStore
import net.weero.measix.pilot.data.repository.ConversationRepository

sealed interface ApplicationRecoveryState {
    data object Loading : ApplicationRecoveryState
    data object Ready : ApplicationRecoveryState
    data class Failed(val error: Throwable) : ApplicationRecoveryState
}

class ApplicationRecoveryUnavailableException(cause: Throwable) :
    IllegalStateException("Application recovery has not completed", cause)

class ManagedConfigurationBlockedException(reason: String?) :
    IllegalStateException(reason ?: "Managed configuration cannot be verified")

/** 所有 durable write 共用的 fail-closed 门禁。 */
class ApplicationRecoveryGate internal constructor() {
    private val _state = MutableStateFlow<ApplicationRecoveryState>(ApplicationRecoveryState.Loading)
    val state: StateFlow<ApplicationRecoveryState> = _state.asStateFlow()

    suspend fun awaitReady() {
        when (val terminal = state.first { it !is ApplicationRecoveryState.Loading }) {
            ApplicationRecoveryState.Ready -> Unit
            is ApplicationRecoveryState.Failed -> throw ApplicationRecoveryUnavailableException(terminal.error)
            ApplicationRecoveryState.Loading -> error("unreachable")
        }
    }

    internal fun loading() { _state.value = ApplicationRecoveryState.Loading }
    internal fun ready() { _state.value = ApplicationRecoveryState.Ready }
    internal fun failed(error: Throwable) { _state.value = ApplicationRecoveryState.Failed(error) }
}

/**
 * 唯一启动恢复入口。顺序固定为 Settings → artifact/generated-media reconcile → projection →
 * interrupted run/turn → pending assistant deletion；任一步失败都保持 fail-closed，可显式 retry。
 */
class ApplicationRecoveryCoordinator(
    private val appScope: CoroutineScope,
    private val settingsStore: SettingsStore,
    private val artifactStore: ArtifactStore,
    private val generatedMediaStore: GeneratedMediaStore,
    private val conversationRepository: ConversationRepository,
    private val turnRecovery: TurnRecovery,
    private val assistantManagementService: AssistantManagementService,
    private val gate: ApplicationRecoveryGate,
    private val restorePendingBackup: suspend () -> Unit = {},
    private val completePendingBackup: () -> Unit = {},
    private val postRecoveryMaintenance: suspend () -> Unit = {},
    startImmediately: Boolean = true,
) {
    private val runMutex = Mutex()
    private val retryOwner = Any()
    private var recoveryJob: Job? = null
    val state: StateFlow<ApplicationRecoveryState> = gate.state

    init {
        if (startImmediately) retry()
    }

    fun retry() {
        synchronized(retryOwner) {
            if (gate.state.value is ApplicationRecoveryState.Ready || recoveryJob?.isActive == true) return
            val job = appScope.launch(start = CoroutineStart.LAZY) {
                try {
                    recoverNow()
                } finally {
                    synchronized(retryOwner) { recoveryJob = null }
                }
            }
            recoveryJob = job
            job.start()
        }
    }

    internal suspend fun recoverNow() {
        runMutex.withLock {
            gate.loading()
            try {
                restorePendingBackup()
                val effective = settingsStore.effectiveSettings.first { !it.settings.init }
                if (effective.managedState == ManagedConfigurationState.BLOCKED) {
                    throw ManagedConfigurationBlockedException(effective.managedFailureReason)
                }
                artifactStore.reconcileStartup()
                generatedMediaStore.reconcile()
                artifactStore.ensureReferenceProjection()
                conversationRepository.ensureSearchProjection()
                turnRecovery.recoverInterruptedRuns()
                turnRecovery.recoverInterruptedTurns()
                assistantManagementService.performPendingDeletionCleanupDuringRecovery()
                postRecoveryMaintenance()
                completePendingBackup()
                gate.ready()
            } catch (cancelled: CancellationException) {
                gate.failed(cancelled)
                throw cancelled
            } catch (error: Exception) {
                gate.failed(error)
            }
        }
    }
}
