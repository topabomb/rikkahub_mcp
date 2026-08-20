package net.weero.measix.pilot.data.files

import android.util.Log
import kotlin.coroutines.cancellation.CancellationException
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.db.entity.ManagedFileEntity
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.Avatar
import net.weero.measix.pilot.data.model.collectFileUrlStrings
import net.weero.measix.pilot.data.repository.ConversationRepository

/**
 * 显式永久删除的协调层（Setting → Files → Delete / Delete all）。
 *
 * 与自动 GC 的边界：
 * - 自动清理（deleteMessage / deleteConversation / compression 等）必须走
 *   reference-aware 路径（`ConversationRepository.deleteUnreferencedChatFiles`），
 *   有引用绝不能删除；
 * - 用户显式确认的永久删除属于 destructive operation，允许删除仍被历史引用的文件，
 *   但必须先解除 Assistant background / avatar 等可变当前引用，
 *   历史 Conversation 中的引用保持不动（Replay 投影为 unavailable）。
 *
 * 删除顺序：先解除可变引用（Settings 写入成功）→ 再删除物理文件。
 * Settings 写入失败时绝不删除文件，避免主动制造 dangling live state；
 * 文件删除失败时引用已解除，最多留下 orphan file，不会留下 UI 指向不存在的文件。
 */
class ManagedFileDeletionService(
    private val filesManager: FilesManager,
    private val settingsStore: SettingsStore,
    private val conversationRepository: ConversationRepository,
) {
    data class ManagedFileDeleteImpact(
        val referencedByHistory: Boolean,
        val assistantBackgroundCount: Int,
        val assistantAvatarCount: Int,
    )

    /** 检查删除该文件将影响哪些引用，用于确认文案展示实际影响。 */
    suspend fun inspect(file: ManagedFileEntity): ManagedFileDeleteImpact {
        val fileUri = buildFileUri(file)
        val settings = settingsStore.settingsFlow.value
        val backgroundCount = settings.assistants.count { it.background == fileUri }
        val avatarCount = settings.assistants.count {
            it.avatar is Avatar.Image && it.avatar.url == fileUri
        }
        val referencedByHistory = hasConversationReference(fileUri)
        return ManagedFileDeleteImpact(
            referencedByHistory = referencedByHistory,
            assistantBackgroundCount = backgroundCount,
            assistantAvatarCount = avatarCount,
        )
    }

    /**
     * 显式永久删除单个托管文件。
     *
     * 1. 解除 Assistant background/avatar 等可变当前引用（原子 Settings 更新）；
     * 2. Settings 提交确认无残留引用后才删除物理文件与 DB 行；
     * 3. 历史 Conversation 引用保持不动。
     */
    suspend fun deletePermanently(file: ManagedFileEntity): Boolean {
        val fileUri = buildFileUri(file)
        if (!detachMutableReferences(setOf(fileUri))) {
            return false
        }
        return filesManager.deleteManagedFilePermanently(file.id, deleteFromDisk = true)
    }

    /**
     * 显式永久清空整个托管目录（如 Upload）。
     *
     * 一次性解除该目录所有文件的可变当前引用，成功后再执行物理清空。
     */
    suspend fun deleteFolderPermanently(folder: String = FileFolders.UPLOAD): Boolean {
        val entities = filesManager.list(folder)
        if (entities.isEmpty()) {
            return filesManager.deleteManagedFolderPermanently(folder)
        }
        val fileUris = entities.map(::buildFileUri).toSet()
        if (!detachMutableReferences(fileUris)) {
            return false
        }
        return filesManager.deleteManagedFolderPermanently(folder)
    }

    private suspend fun detachMutableReferences(fileUris: Set<String>): Boolean {
        if (fileUris.isEmpty()) return true
        val committed = try {
            settingsStore.updateAtomicAndGet { settings ->
                settings.copy(
                    assistants = settings.assistants.map { assistant ->
                        detachAssistantRefs(assistant, fileUris)
                    },
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.e(TAG, "failed to detach assistant references before explicit delete", error)
            return false
        }
        val stillReferenced = committed.assistants.any { assistant ->
            assistant.background in fileUris ||
                (assistant.avatar is Avatar.Image && assistant.avatar.url in fileUris)
        }
        if (stillReferenced) {
            Log.w(TAG, "settings write rejected; keeping files to avoid dangling references")
            return false
        }
        return true
    }

    private fun detachAssistantRefs(assistant: Assistant, fileUris: Set<String>): Assistant {
        var updated = assistant
        if (updated.background != null && updated.background in fileUris) {
            updated = updated.copy(background = null)
        }
        val avatar = updated.avatar
        if (avatar is Avatar.Image && avatar.url in fileUris) {
            updated = updated.copy(avatar = Avatar.Dummy)
        }
        return updated
    }

    private suspend fun hasConversationReference(fileUri: String): Boolean {
        val conversations = conversationRepository.getAllTopLevelConversationsSync() +
            conversationRepository.getAllChildConversationIds().mapNotNull { id ->
                conversationRepository.getConversationById(id)
            }
        return conversations.any { conversation ->
            conversation.messageNodes.flatMap { it.messages }
                .collectFileUrlStrings()
                .any { it == fileUri }
        }
    }

    private fun buildFileUri(file: ManagedFileEntity): String {
        val path = filesManager.getFile(file).absolutePath.replace('\\', '/')
        return if (path.startsWith("/")) "file://$path" else "file:///$path"
    }

    companion object {
        private const val TAG = "ManagedFileDeletionService"
    }
}
