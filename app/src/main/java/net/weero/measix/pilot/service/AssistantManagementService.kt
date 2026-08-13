package net.weero.measix.pilot.service

import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.withTimeout
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantAccessPolicy
import net.weero.measix.pilot.data.ai.subassistant.buildToolCreatedAssistant
import net.weero.measix.pilot.data.ai.tools.local.LocalToolOption
import net.weero.measix.pilot.data.datastore.PendingAssistantDeletion
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.getAssistantById
import net.weero.measix.pilot.data.files.FilesManager
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.Avatar
import net.weero.measix.pilot.data.model.normalizeDescription
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.data.repository.MemoryRepository
import kotlin.uuid.Uuid

private const val TAG = "AssistantManagementService"
private const val ASSISTANT_CLEANUP_STOP_TIMEOUT_MS = 5_000L

/**
 * Assistant CRUD、校验、通过 Settings 原子更新、文件/Memory/普通会话清理。
 * UI 删除与 [AssistantToolFactory] 的 assistant_manage 共用此 Service，避免两套清理逻辑。
 */
class AssistantManagementService(
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val conversationRepo: ConversationRepository,
    private val filesManager: FilesManager,
    private val sessionRegistry: ConversationSessionRegistry,
    private val subAssistantCoordinator: SubAssistantCoordinator,
    private val recoveryGate: AssistantDataRecoveryGate = AssistantDataRecoveryGate.completed(),
) {
    /**
     * 创建 Assistant，默认 Local Tools 与普通 Assistant 一致，其他扩展能力保持关闭。
     * CREATE 要求 name、description、instructions 均非空。
     * 创建目标固定为子助手、非全局可见，并把新 ID 原子加入 caller 的 allowedSubAssistantIds。
     */
    suspend fun createAssistant(
        name: String,
        description: String,
        instructions: String,
        callerAssistantId: Uuid? = null,
    ): Result<Assistant> {
        recoveryGate.awaitReady()
        val trimmedName = name.trim()
        val trimmedDescription = normalizeDescription(description)
        val trimmedInstructions = instructions.trim()

        if (trimmedName.isEmpty() || trimmedDescription.isEmpty() || trimmedInstructions.isEmpty()) {
            return Result.failure(IllegalArgumentException("invalid_arguments"))
        }

        val assistant = buildToolCreatedAssistant(
            name = trimmedName,
            description = trimmedDescription,
            systemPrompt = trimmedInstructions,
        )

        var createdAssistant: Assistant? = null
        settingsStore.updateAtomic { settings ->
            // 重新确认 caller 仍存在、AssistantManagement 仍启用
            val caller = callerAssistantId?.let { settings.getAssistantById(it) }
            if (callerAssistantId != null && caller == null) {
                return@updateAtomic settings
            }
            if (callerAssistantId != null && LocalToolOption.AssistantManagement !in caller!!.localTools) {
                return@updateAtomic settings
            }

            // 原子加入 caller 的 allowedSubAssistantIds
            val updatedAssistants = if (callerAssistantId != null && caller != null) {
                settings.assistants.map { a ->
                    if (a.id == callerAssistantId) {
                        a.copy(allowedSubAssistantIds = a.allowedSubAssistantIds + assistant.id)
                    } else {
                        a
                    }
                } + assistant
            } else {
                settings.assistants + assistant
            }
            createdAssistant = assistant
            settings.copy(assistants = updatedAssistants)
        }

        val result = createdAssistant
        return if (result != null) {
            Log.i(TAG, "createAssistant: ${result.id} ($trimmedName)")
            Result.success(result)
        } else {
            Result.failure(IllegalStateException("operation_failed"))
        }
    }

    /**
     * 更新 Assistant。只允许修改 name、description、instructions。
     * 不允许修改 model、工具、Memory、头像、Tag、背景、allowAsSubAssistant 等用户配置。
     * UPDATE 要求 assistant_id，且 name/description/instructions 至少提供一个。
     */
    suspend fun updateAssistant(
        assistantId: Uuid,
        name: String? = null,
        description: String? = null,
        instructions: String? = null,
        callerAssistantId: Uuid? = null,
    ): Result<Assistant> {
        recoveryGate.awaitReady()
        if (name == null && description == null && instructions == null) {
            return Result.failure(IllegalArgumentException("invalid_arguments"))
        }
        val normalizedName = name?.trim()
        val normalizedDescription = description?.let(::normalizeDescription)
        val normalizedInstructions = instructions?.trim()
        if (normalizedName?.isEmpty() == true || normalizedDescription?.isEmpty() == true ||
            normalizedInstructions?.isEmpty() == true
        ) {
            return Result.failure(IllegalArgumentException("invalid_arguments"))
        }

        var updatedAssistant: Assistant? = null
        var failureReason: String? = null
        settingsStore.updateAtomic { settings ->
            val caller = callerAssistantId?.let(settings::getAssistantById)
            if (callerAssistantId != null &&
                (caller == null || LocalToolOption.AssistantManagement !in caller.localTools)
            ) {
                failureReason = "tool_not_permitted"
                return@updateAtomic settings
            }
            val existing = settings.getAssistantById(assistantId)
            if (existing == null) {
                failureReason = "assistant_not_found"
                return@updateAtomic settings
            }
            if (caller != null && !SubAssistantAccessPolicy.canAccess(caller, existing)) {
                failureReason = "target_not_allowed"
                return@updateAtomic settings
            }

            val updated = existing.copy(
                name = normalizedName ?: existing.name,
                description = normalizedDescription ?: existing.description,
                systemPrompt = normalizedInstructions ?: existing.systemPrompt,
            )
            updatedAssistant = updated
            settings.copy(
                assistants = settings.assistants.map { if (it.id == assistantId) updated else it }
            )
        }

        val result = updatedAssistant
        return if (result != null) {
            Log.i(TAG, "updateAssistant: $assistantId")
            Result.success(result)
        } else {
            when (failureReason) {
                "assistant_not_found" -> Result.failure(NoSuchElementException("assistant_not_found"))
                else -> Result.failure(IllegalArgumentException(failureReason ?: "operation_failed"))
            }
        }
    }

    /**
     * 删除 Assistant。
     * - 不能删除 caller（如果有）；
     * - 不能删除最后一个 Assistant；
     * - 在同一 Settings 原子变换中移除 Target、清理所有 allowedSubAssistantIds、
     *   在 Target 是全局当前选择时切换、写入 durable cleanup tombstone；
     * - Settings 提交后幂等删除普通顶层 Conversations、Local Memory 和助手文件；
     * - 历史 Child Conversation 保留，新调用失败。
     */
    suspend fun deleteAssistant(
        assistantId: Uuid,
        callerAssistantId: Uuid? = null,
    ): Result<AssistantDeletionResult> {
        recoveryGate.awaitReady()
        if (callerAssistantId != null && assistantId == callerAssistantId) {
            return Result.failure(IllegalArgumentException("target_is_caller"))
        }

        var assistantToDelete: Assistant? = null
        var tombstone: PendingAssistantDeletion? = null
        var failureReason: String? = null
        settingsStore.updateAtomic { settings ->
            if (settings.assistants.size <= 1) {
                failureReason = "last_assistant"
                return@updateAtomic settings
            }
            val target = settings.getAssistantById(assistantId)
            if (target == null) {
                failureReason = "assistant_not_found"
                return@updateAtomic settings
            }
            val caller = callerAssistantId?.let(settings::getAssistantById)
            if (callerAssistantId != null &&
                (caller == null || LocalToolOption.AssistantManagement !in caller.localTools)
            ) {
                failureReason = "tool_not_permitted"
                return@updateAtomic settings
            }
            if (caller != null && !SubAssistantAccessPolicy.canAccess(caller, target)) {
                failureReason = "target_not_allowed"
                return@updateAtomic settings
            }

            assistantToDelete = target

            // 检查是否需要切换全局当前选择
            val needsGlobalSwitch = settings.assistantId == assistantId

            // 从所有 Assistant 的允许列表移除其 ID（反向授权清理）
            val updatedAssistants = settings.assistants
                .filter { it.id != assistantId }
                .map { a ->
                    a.copy(allowedSubAssistantIds = a.allowedSubAssistantIds - assistantId)
                }

            // 切换全局当前选择
            val newAssistantId = if (needsGlobalSwitch) {
                // 优先选择普通 Assistant，否则选第一个可用
                updatedAssistants.firstOrNull { !it.allowAsSubAssistant }?.id
                    ?: updatedAssistants.firstOrNull()?.id
                    ?: settings.assistantId
            } else {
                settings.assistantId
            }

            // 写入 durable cleanup tombstone
            val avatarUri = (target.avatar as? Avatar.Image)?.url
            val backgroundUri = target.background
            val newTombstone = PendingAssistantDeletion(
                assistantId = assistantId,
                avatarUri = avatarUri,
                backgroundUri = backgroundUri,
            )
            tombstone = newTombstone

            settings.copy(
                assistants = updatedAssistants,
                assistantId = newAssistantId,
                pendingAssistantDeletions = (settings.pendingAssistantDeletions + newTombstone)
                    .distinctBy { it.assistantId },
            )
        }

        val assistant = assistantToDelete
        if (assistant == null) {
            return when (failureReason) {
                "assistant_not_found" -> Result.failure(NoSuchElementException("assistant_not_found"))
                else -> Result.failure(IllegalArgumentException(failureReason ?: "operation_failed"))
            }
        }

        val cleanupCompleted = tombstone?.let { cleanupPendingDeletion(it) } == true

        Log.i(TAG, "deleteAssistant: $assistantId")
        return Result.success(
            AssistantDeletionResult(
                assistant = assistant,
                cleanupPending = !cleanupCompleted,
            )
        )
    }

    /** App 启动时幂等消费尚未完成的删除 tombstone。 */
    suspend fun performPendingDeletionCleanup() {
        val pending = settingsStore.settingsFlow.value.pendingAssistantDeletions
        pending.forEach { cleanupPendingDeletion(it) }
    }

    /**
     * 读取指定 Assistant 的 Local Memory。
     * 只有 active_memory == local 时读取 Target 的 Local namespace；
     * global 或 disabled 均返回空 rows，既不暴露共享 Global Memory，
     * 也不把当前不会生效的旧局部记录误报为该角色正在使用的记忆。
     */
    suspend fun listAssistantMemory(assistantId: Uuid): Result<MemoryListResult> {
        val settings = settingsStore.settingsFlow.value
        val assistant = settings.getAssistantById(assistantId)
            ?: return Result.failure(NoSuchElementException("assistant_not_found"))

        val scope = when {
            !assistant.enableMemory -> "disabled"
            assistant.useGlobalMemory -> "global"
            else -> "local"
        }

        // 只有 local 模式才读取记忆；global/disabled 返回空列表
        val memories = if (scope == "local") {
            memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
        } else {
            emptyList()
        }

        return Result.success(
            MemoryListResult(
                assistantId = assistant.id.toString(),
                assistantName = assistant.name,
                delegatedMemoryScope = scope,
                memories = memories.map { MemoryItem(it.id, it.content) },
            )
        )
    }

    private suspend fun cleanupPendingDeletion(tombstone: PendingAssistantDeletion): Boolean {
        val latestSettings = settingsStore.settingsFlow.value
        if (latestSettings.assistants.any { it.id == tombstone.assistantId }) {
            // 备份恢复或其他显式恢复重新带回了同 ID Assistant：旧 tombstone 不得删除新数据。
            removePendingDeletion(tombstone.assistantId)
            return true
        }

        val result = runCatching {
            // A non-cooperative Provider or native tool must not leave deletion suspended forever.
            // On timeout the tombstone remains durable and startup will retry without deleting data
            // that an active run may still be using.
            withTimeout(ASSISTANT_CLEANUP_STOP_TIMEOUT_MS) {
                subAssistantCoordinator.cancelRunsForAssistant(
                    assistantId = tombstone.assistantId,
                )
                sessionRegistry.cancelGenerationsForAssistant(
                    assistantId = tombstone.assistantId,
                    reason = "assistant_removed",
                )
            }
            memoryRepository.deleteMemoriesOfAssistant(tombstone.assistantId.toString())
            conversationRepo.deleteConversationOfAssistant(tombstone.assistantId)
            check(cleanupAssistantFilesIfNotReferenced(tombstone)) {
                "Unable to delete one or more managed assistant files"
            }
        }
        result.onFailure { error ->
            Log.e(TAG, "Pending cleanup failed for ${tombstone.assistantId}", error)
        }
        if (result.isSuccess) {
            removePendingDeletion(tombstone.assistantId)
        }
        return result.isSuccess
    }

    private suspend fun removePendingDeletion(assistantId: Uuid) {
        settingsStore.updateAtomic { settings ->
            settings.copy(
                pendingAssistantDeletions = settings.pendingAssistantDeletions.filterNot {
                    it.assistantId == assistantId
                }
            )
        }
    }

    private fun cleanupAssistantFilesIfNotReferenced(tombstone: PendingAssistantDeletion): Boolean {
        val settings = settingsStore.settingsFlow.value

        // 只有在最新 Settings 中已无其他 Assistant 引用时才删除
        val uris = buildList {
            tombstone.avatarUri?.let { uri ->
                val referenced = settings.assistants.any { other ->
                    (other.avatar as? Avatar.Image)?.url == uri
                }
                if (!referenced) add(uri.toUri())
            }
            tombstone.backgroundUri?.let { uri ->
                val referenced = settings.assistants.any { other ->
                    other.background == uri
                }
                if (!referenced) add(uri.toUri())
            }
        }
        return uris.isEmpty() || filesManager.deleteChatFiles(uris)
    }
}

data class AssistantDeletionResult(
    val assistant: Assistant,
    val cleanupPending: Boolean,
)

data class MemoryListResult(
    val assistantId: String,
    val assistantName: String,
    val delegatedMemoryScope: String,
    val memories: List<MemoryItem>,
)

data class MemoryItem(
    val id: Int,
    val content: String,
)
