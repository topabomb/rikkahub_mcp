package net.weero.measix.pilot.data.files

import android.net.Uri
import android.util.Log
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first
import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.db.dao.ArtifactDAO
import net.weero.measix.pilot.data.db.dao.ArtifactReferenceDAO
import net.weero.measix.pilot.data.db.dao.ConversationDAO
import net.weero.measix.pilot.data.db.dao.MessageNodeDAO
import net.weero.measix.pilot.data.db.dao.SystemMetaDAO
import net.weero.measix.pilot.data.db.entity.ArtifactEntity
import net.weero.measix.pilot.data.db.entity.ArtifactReferenceEntity
import net.weero.measix.pilot.data.db.entity.ArtifactReferenceType
import net.weero.measix.pilot.data.db.entity.ArtifactState
import net.weero.measix.pilot.data.db.entity.SystemMetaEntity
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.Avatar
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.model.collectFileReferenceTokens
import net.weero.measix.pilot.data.model.collectFileUrlStrings
import net.weero.measix.pilot.utils.JsonInstant
import kotlin.uuid.Uuid

/** 显式删除结果 */
sealed interface ArtifactDeleteResult {
    data class Completed(val artifactId: Long) : ArtifactDeleteResult
    data class Rejected(val artifactId: Long, val reason: RejectionReason) : ArtifactDeleteResult
    data class Failed(val artifactId: Long, val reason: String) : ArtifactDeleteResult

    enum class RejectionReason { IN_PROGRESS, ALREADY_DELETED }
}

/** 删除影响（原 ManagedFileDeletionService.ManagedFileDeleteImpact 迁入） */
data class ArtifactDeleteImpact(
    val referencedByHistory: Boolean,
    val assistantBackgroundCount: Int,
    val assistantAvatarCount: Int,
)

/**
 * Artifact 域唯一服务类（合并 ManagedFileDeletionService + FilesRepository）。
 *
 * 承载：引用投影（syncReferences/backfillReferences）、影响检查（inspect）、生命周期协议
 * （CAS 幂等删除）、启动恢复（reconcileStartup）、孤儿回收。
 *
 * 职责裁决：元数据的 CRUD 门面（observe/list/get/登记）由 [FilesManager]
 * 直接经 ArtifactDAO 承担（FilesManager 无法注入本类——本类依赖 FilesManager，构造注入会成环）；
 * 本类聚焦引用投影与生命周期事实，不重复暴露元数据门面。
 *
 * 数据完整性契约：DB 是唯一事实源。写入侧由 [FilesManager.registerTrackedFile] 保证
 * "文件 + 记录"原子（登记失败即回滚删文件）；磁盘侧不一致（外部删除/外部放入）由
 * [reconcileStartup] 冷启动收敛——缺失行直接清理、untracked 文件仅日志，绝不补录。
 *
 * 磁盘 payload 操作委托 [FilesManager]；引用投影可由消息 JSON 全量重建，永不当事实源。
 */
class ArtifactStore(
    private val filesManager: FilesManager,
    private val artifactDAO: ArtifactDAO,
    private val artifactReferenceDAO: ArtifactReferenceDAO,
    private val systemMetaDAO: SystemMetaDAO,
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val settingsStore: SettingsStore,
) {
    // ---- 引用投影 ----

    /**
     * delta 同步，替换语义：
     *  - upsertedNodes：先 deleteByNodeIds 再 insertAll（node 变更后可能不再引用某文件）
     *  - deletedNodeIds：显式删除（node FK 级联为主路径，此为 FK 关闭环境的兜底）
     */
    suspend fun syncReferences(
        conversationId: Uuid,
        upsertedNodes: List<MessageNode>,
        deletedNodeIds: List<Uuid>,
    ) {
        if (upsertedNodes.isNotEmpty()) {
            val nodeIds = upsertedNodes.map { it.id.toString() }
            artifactReferenceDAO.deleteByNodeIds(nodeIds)
            val refs = buildMutableReferencesForNodes(upsertedNodes)
            if (refs.isNotEmpty()) artifactReferenceDAO.insertAll(refs)
        }
        if (deletedNodeIds.isNotEmpty()) {
            artifactReferenceDAO.deleteByNodeIds(deletedNodeIds.map { it.toString() })
        }
    }

    /** 会话级引用清理（删除会话时随事务调用；node FK 级联为主路径，此为显式兜底）。 */
    suspend fun deleteReferencesOfConversation(conversationId: Uuid) {
        artifactReferenceDAO.deleteByConversationId(conversationId.toString())
    }

    /** 引用回填：幂等；未置位时逐会话提取引用 token（URL + metadata 相对路径）登记。 */
    suspend fun backfillReferences() {
        if (isBackfilled()) return
        val inserted = mutableListOf<ArtifactReferenceEntity>()
        conversationDAO.getAllConversations().forEach { conversationEntity ->
            messageNodeDAO.getNodeIdsOfConversation(conversationEntity.id).forEach { nodeId ->
                val messagesJson = messageNodeDAO.getMessagesJsonById(nodeId) ?: return@forEach
                val messages = runCatching {
                    JsonInstant.decodeFromString<List<UIMessage>>(messagesJson)
                }.getOrNull() ?: return@forEach
                val refs = resolveNodeReferenceEntities(nodeId, messages)
                inserted.addAll(refs)
            }
        }
        if (inserted.isNotEmpty()) artifactReferenceDAO.insertAll(inserted)
        systemMetaDAO.put(SystemMetaEntity(BACKFILL_FLAG, "true"))
    }

    suspend fun isBackfilled(): Boolean =
        runCatching { systemMetaDAO.get(BACKFILL_FLAG) == "true" }.getOrDefault(false)

    // ---- 影响检查 ----

    suspend fun inspect(artifact: ArtifactEntity): ArtifactDeleteImpact {
        val fileUri = buildFileUri(artifact)
        val settings = settingsStore.settingsFlow.value
        val backgroundCount = settings.assistants.count { it.background == fileUri }
        val avatarCount = settings.assistants.count {
            it.avatar is Avatar.Image && it.avatar.url == fileUri
        }
        val referencedByHistory = if (isBackfilled()) {
            artifactReferenceDAO.existsByArtifactId(artifact.id)
        } else {
            hasConversationReferenceFallback(fileUri)
        }
        return ArtifactDeleteImpact(
            referencedByHistory = referencedByHistory,
            assistantBackgroundCount = backgroundCount,
            assistantAvatarCount = avatarCount,
        )
    }

    // ---- 生命周期协议（CAS 幂等，无 operationId） ----

    suspend fun deletePermanently(artifact: ArtifactEntity): ArtifactDeleteResult {
        val fileUri = buildFileUri(artifact)
        // CAS 幂等屏障：ACTIVE → DELETING；0 行 = 已变迁
        val gained = artifactDAO.compareAndSetState(artifact.id, ArtifactState.ACTIVE.name, ArtifactState.DELETING.name, System.currentTimeMillis())
        if (gained != 1) {
            val current = artifactDAO.getById(artifact.id)
            val reason = if (current == null) ArtifactDeleteResult.RejectionReason.ALREADY_DELETED
            else ArtifactDeleteResult.RejectionReason.IN_PROGRESS
            return ArtifactDeleteResult.Rejected(artifact.id, reason)
        }
        // 解除可变引用（失败回滚 ACTIVE）
        if (!detachMutableReferences(setOf(fileUri))) {
            artifactDAO.compareAndSetState(artifact.id, ArtifactState.DELETING.name, ArtifactState.ACTIVE.name, System.currentTimeMillis())
            return ArtifactDeleteResult.Failed(artifact.id, "settings_detach_failed")
        }
        // 删磁盘 + 删行（引用行经 FK 级联自动清）
        return try {
            val diskOk = filesManager.deleteManagedFilePermanently(artifact.id, deleteFromDisk = true)
            if (!diskOk) {
                ArtifactDeleteResult.Failed(artifact.id, "disk_delete_failed")
            } else {
                ArtifactDeleteResult.Completed(artifact.id)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.e(TAG, "deletePermanently: unexpected error for ${artifact.id}", error)
            ArtifactDeleteResult.Failed(artifact.id, error.message ?: "unknown")
        }
    }

    suspend fun deleteFolderPermanently(folder: String): ArtifactDeleteResult {
        val entities = artifactDAO.listByFolder(folder).first()
        if (entities.isEmpty()) {
            filesManager.deleteManagedFolderPermanently(folder)
            return ArtifactDeleteResult.Completed(0)
        }
        // 先解除可变引用（失败即中止，保持文件完整）
        val fileUris = entities.map(::buildFileUri).toSet()
        if (!detachMutableReferences(fileUris)) {
            return ArtifactDeleteResult.Failed(0, "settings_detach_failed")
        }
        // 删磁盘 + 清行（引用行经 FK 级联自动清）
        return try {
            val diskOk = filesManager.deleteManagedFolderPermanently(folder)
            if (diskOk) ArtifactDeleteResult.Completed(0)
            else ArtifactDeleteResult.Failed(0, "disk_folder_delete_failed")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.e(TAG, "deleteFolderPermanently: unexpected error", error)
            ArtifactDeleteResult.Failed(0, error.message ?: "unknown")
        }
    }

    // ---- 启动恢复 ----

    /**
     * 每次冷启动执行一次：
     *  - state=DELETING → 续删（删磁盘 + 删行）
     *  - state=ACTIVE 且磁盘缺失 → 删除行（文件已不存在，记录是死数据，直接清理；
     *    引用行经 FK 级联消失，不留悬挂）
     *  - 磁盘存在但 DB 无记录 → 仅日志，绝不补录（untracked 只可能来自外部，
     *    应用侧写入路径已原子化：登记失败即回滚删文件）
     */
    suspend fun reconcileStartup() {
        artifactDAO.listByState(ArtifactState.DELETING.name).forEach { entity ->
            val file = filesManager.getFile(entity)
            if (file.exists()) {
                runCatching { filesManager.deleteManagedFilePermanently(entity.id, deleteFromDisk = true) }
            } else {
                artifactDAO.deleteById(entity.id)
            }
        }
        artifactDAO.listByState(ArtifactState.ACTIVE.name).forEach { entity ->
            val file = filesManager.getFile(entity)
            if (!file.exists()) {
                artifactDAO.deleteById(entity.id)
                Log.w(TAG, "reconcileStartup: artifact ${entity.id} missing on disk → row removed")
            }
        }
        // 磁盘存在但无 DB 记录：仅日志（不补录；重启复活缺陷的回归锁定）
        filesManager.logUntrackedUploadFiles()
    }

    // ---- GC ----

    /**
     * GC：回收 state=ACTIVE 且 artifact_reference 无引用、created_at 超过保护窗口的 artifact。
     * 回填未完成时保守跳过。
     */
    suspend fun collectUnreferencedArtifacts(protectionWindowMillis: Long = 24 * 3600 * 1000L): List<ArtifactEntity> {
        if (!isBackfilled()) return emptyList()
        val threshold = System.currentTimeMillis() - protectionWindowMillis
        val candidates = artifactDAO.listByStateCreatedBefore(ArtifactState.ACTIVE.name, threshold)
        val toDelete = candidates.filter { entity ->
            !artifactReferenceDAO.existsByArtifactId(entity.id)
        }
        toDelete.forEach { entity ->
            filesManager.deleteManagedFilePermanently(entity.id, deleteFromDisk = true)
        }
        return toDelete
    }

    // ---- 私有 ----

    private suspend fun buildMutableReferencesForNodes(nodes: List<MessageNode>): List<ArtifactReferenceEntity> {
        val refs = mutableListOf<ArtifactReferenceEntity>()
        nodes.forEach { node ->
            refs.addAll(resolveNodeReferenceEntities(node.id.toString(), node.messages))
        }
        return refs
    }

    /**
     * 节点引用解析：引用 token = file:// URL + Tool.metadata 的 LocalArtifactRef
     * 相对路径（collectFileReferenceTokens）。URL 转 filesDir 相对路径，相对路径 token 直用；
     * metadata-only 引用（generate_image artifact 等）同样登记、阻止 GC 回收
     * （对齐原 deleteUnreferencedChatFiles 的 metadata 保留判定）。
     */
    private suspend fun resolveNodeReferenceEntities(nodeId: String, messages: List<UIMessage>): List<ArtifactReferenceEntity> {
        val refs = mutableListOf<ArtifactReferenceEntity>()
        val seenArtifacts = mutableSetOf<Long>()
        messages.collectFileReferenceTokens().forEach { token ->
            val relativePath = if (token.startsWith("file:")) {
                filesManager.getRelativePathForUri(token.toUriSafe()) ?: return@forEach
            } else {
                token
            }
            artifactDAO.getByPath(relativePath)?.let { artifact ->
                if (seenArtifacts.add(artifact.id)) {
                    refs.add(
                        ArtifactReferenceEntity(
                            artifactId = artifact.id,
                            nodeId = nodeId,
                            referenceType = ArtifactReferenceType.ATTACHMENT.name,
                        )
                    )
                }
            }
        }
        return refs
    }

    private suspend fun detachMutableReferences(fileUris: Set<String>): Boolean {
        if (fileUris.isEmpty()) return true
        val committed = try {
            settingsStore.updateAtomicAndGet { settings ->
                settings.copy(
                    assistants = settings.assistants.map { detachAssistantRefs(it, fileUris) },
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.e(TAG, "failed to detach assistant references", error)
            return false
        }
        val stillReferenced = committed.assistants.any { assistant ->
            assistant.background in fileUris ||
                (assistant.avatar is Avatar.Image && assistant.avatar.url in fileUris)
        }
        return !stillReferenced
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

    private fun buildFileUri(artifact: ArtifactEntity): String {
        val path = filesManager.getFile(artifact).absolutePath.replace('\\', '/')
        return if (path.startsWith("/")) "file://$path" else "file:///$path"
    }

    /** 回填完成前 inspect 的降级：全量扫描会话消息。 */
    private suspend fun hasConversationReferenceFallback(fileUri: String): Boolean {
        conversationDAO.getAllConversations().forEach { conversationEntity ->
            messageNodeDAO.getNodeIdsOfConversation(conversationEntity.id).forEach { nodeId ->
                val messagesJson = messageNodeDAO.getMessagesJsonById(nodeId) ?: return@forEach
                val messages = runCatching {
                    JsonInstant.decodeFromString<List<UIMessage>>(messagesJson)
                }.getOrNull() ?: return@forEach
                if (messages.collectFileUrlStrings().any { it == fileUri }) return true
            }
        }
        return false
    }

    private fun String.toUriSafe(): Uri = runCatching { Uri.parse(this) }.getOrNull() ?: Uri.EMPTY

    companion object {
        private const val TAG = "ArtifactStore"
        const val BACKFILL_FLAG = "artifact_reference_backfilled"
    }
}
