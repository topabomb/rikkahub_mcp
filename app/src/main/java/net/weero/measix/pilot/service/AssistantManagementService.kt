package net.weero.measix.pilot.service

import me.rerere.common.configuration.ConfigurationReference
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import net.weero.measix.pilot.data.ai.subassistant.buildToolCreatedAssistant
import net.weero.measix.pilot.data.datastore.PendingAssistantDeletion
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.AssistantManagementChange
import net.weero.measix.pilot.data.datastore.AssistantManagementResult
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.normalizeDescription
import net.weero.measix.pilot.data.repository.MemoryRepository
import net.weero.measix.pilot.service.subassistant.SubAssistantRunCoordinator

private const val TAG = "AssistantManagementService"
private const val ASSISTANT_CLEANUP_STOP_TIMEOUT_MS = 5_000L

internal data class AssistantManagementCaller(val assistantId: ConfigurationReference, val realmAccess: RealmAccess)

/**
 * 管理工具的助手 CRUD 与 UI 删除入口；配置提交和可恢复数据清理共用既有 owner。
 */
class AssistantManagementService internal constructor(
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val artifactStore: ArtifactStore,
    private val subAssistantRunCoordinator: SubAssistantRunCoordinator,
    private val recoveryGate: ApplicationRecoveryGate,
    private val conversationApplicationService: ConversationApplicationService,
    private val sessions: EnterpriseSessionController,
) {
    /** A created user definition and the caller's own-realm grant share one durable transaction. */
    internal suspend fun createAssistant(
        name: String,
        description: String,
        instructions: String,
        caller: AssistantManagementCaller? = null,
    ): Result<Assistant> {
        recoveryGate.awaitReady()
        val trimmedName = name.trim()
        val trimmedDescription = normalizeDescription(description)
        val trimmedInstructions = instructions.trim()
        if (trimmedName.isEmpty() || trimmedDescription.isEmpty() || trimmedInstructions.isEmpty()) {
            return Result.failure(IllegalArgumentException("invalid_arguments"))
        }
        val assistant = buildToolCreatedAssistant(trimmedName, trimmedDescription, trimmedInstructions)
        return manage(caller, AssistantManagementChange.Create(assistant)).map { it.assistant }
    }

    /** Only shared user name, description and instructions are editable through the management tool. */
    internal suspend fun updateAssistant(
        assistantId: ConfigurationReference,
        name: String? = null,
        description: String? = null,
        instructions: String? = null,
        caller: AssistantManagementCaller? = null,
    ): Result<Assistant> {
        recoveryGate.awaitReady()
        if (name == null && description == null && instructions == null) return Result.failure(IllegalArgumentException("invalid_arguments"))
        val normalizedName = name?.trim()
        val normalizedDescription = description?.let(::normalizeDescription)
        val normalizedInstructions = instructions?.trim()
        if (normalizedName?.isEmpty() == true || normalizedDescription?.isEmpty() == true || normalizedInstructions?.isEmpty() == true) {
            return Result.failure(IllegalArgumentException("invalid_arguments"))
        }
        return manage(caller, AssistantManagementChange.Update(assistantId, normalizedName, normalizedDescription, normalizedInstructions)).map { it.assistant }
    }

    /** Grants and usage references retire with the definition. Data cleanup waits outside authorization locks. */
    internal suspend fun deleteAssistant(
        assistantId: ConfigurationReference,
        caller: AssistantManagementCaller? = null,
    ): Result<AssistantDeletionResult> {
        recoveryGate.awaitReady()
        val committed = manage(caller, AssistantManagementChange.Delete(assistantId)).getOrElse { return Result.failure(it) }
        val cleanupCompleted = committed.deletion?.let { cleanupPendingDeletion(it) } == true
        return Result.success(AssistantDeletionResult(committed.assistant, cleanupPending = !cleanupCompleted))
    }

    private suspend fun manage(caller: AssistantManagementCaller?, change: AssistantManagementChange): Result<AssistantManagementResult> {
        val access = caller?.realmAccess ?: RealmAccess.Personal
        return try {
            val result = sessions.withRealmAccess(access) {
                artifactStore.manageAssistantReferences(access.scope, sessions.state.value, caller?.assistantId, change) {
                    sessions.requirePublishedRealmAccess(access)
                }
            }
            Result.success(result)
        } catch (missing: NoSuchElementException) { Result.failure(missing) }
        catch (rejected: IllegalArgumentException) { Result.failure(rejected) }
    }

    /** App 启动时幂等消费尚未完成的删除 tombstone。 */
    internal suspend fun performPendingDeletionCleanupDuringRecovery() {
        val pending = settingsStore.userSettings.value.pendingAssistantDeletions
        pending.forEach { tombstone ->
            if (!cleanupPendingDeletion(tombstone)) {
                throw PendingAssistantCleanupException(tombstone.assistantId)
            }
        }
    }

    private suspend fun cleanupPendingDeletion(tombstone: PendingAssistantDeletion): Boolean {
        val latestSettings = settingsStore.userSettings.value
        if (latestSettings.assistants.any { it.id == tombstone.assistantId }) {
            // 备份恢复或其他显式恢复重新带回了同 ID Assistant：旧 tombstone 不得删除新数据。
            removePendingDeletion(tombstone.assistantId)
            return true
        }

        val failure = try {
            // A non-cooperative Provider or native tool must not leave deletion suspended forever.
            // On timeout the tombstone remains durable and startup will retry without deleting data
            // that an active run may still be using.
            withTimeout(ASSISTANT_CLEANUP_STOP_TIMEOUT_MS) {
                subAssistantRunCoordinator.cancelRunsForAssistant(
                    assistantId = tombstone.assistantId,
                )
                conversationApplicationService.cancelGenerationsForAssistant(
                    assistantId = tombstone.assistantId,
                    reason = "assistant_removed",
                )
            }
            memoryRepository.deleteAll(net.weero.measix.pilot.data.model.MemoryAddress(
                net.weero.measix.pilot.data.configuration.ConfigurationScope.Personal,
                net.weero.measix.pilot.data.model.MemoryOwner.Assistant(tombstone.assistantId),
            ))
            conversationApplicationService.deleteOfAssistantFromPendingCleanup(tombstone.assistantId)
            artifactStore.collectGarbage(protectionWindowMillis = 0)
            null
        } catch (timeout: TimeoutCancellationException) {
            timeout
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            error
        }
        if (failure != null) {
            Log.e(TAG, "Pending cleanup failed for ${tombstone.assistantId}", failure)
            return false
        }
        removePendingDeletion(tombstone.assistantId)
        return true
    }

    private suspend fun removePendingDeletion(assistantId: ConfigurationReference) {
        artifactStore.updateSettingsReferences { settings ->
            settings.copy(
                pendingAssistantDeletions = settings.pendingAssistantDeletions.filterNot {
                    it.assistantId == assistantId
                }
            )
        }
    }

}

class PendingAssistantCleanupException(assistantId: ConfigurationReference) :
    IllegalStateException("Pending assistant cleanup did not converge: $assistantId")

data class AssistantDeletionResult(
    val assistant: Assistant,
    val cleanupPending: Boolean,
)
