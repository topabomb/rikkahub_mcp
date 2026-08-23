package net.weero.measix.pilot.data.repository

import android.database.sqlite.SQLiteBlobTooBigException
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.map
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.db.fts.MessageFtsManager
import net.weero.measix.pilot.data.db.fts.MessageSearchSort
import net.weero.measix.pilot.data.db.dao.ConversationDAO
import net.weero.measix.pilot.data.db.dao.FavoriteDAO
import net.weero.measix.pilot.data.db.dao.LightConversationEntity
import net.weero.measix.pilot.data.db.dao.MessageNodeDAO
import net.weero.measix.pilot.data.db.dao.ToolExecutionDAO
import net.weero.measix.pilot.data.db.dao.TurnExecutionDAO
import net.weero.measix.pilot.data.db.entity.ArtifactEntity
import net.weero.measix.pilot.data.db.entity.ConversationEntity
import net.weero.measix.pilot.data.db.entity.MessageNodeEntity
import net.weero.measix.pilot.data.db.entity.ToolExecutionEntity
import net.weero.measix.pilot.data.db.entity.ToolExecutionStatus
import net.weero.measix.pilot.data.db.entity.TurnExecutionEntity
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.FilesManager
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.model.collectFileReferenceTokens
import net.weero.measix.pilot.service.runtime.ConversationHeaderPatch
import net.weero.measix.pilot.service.runtime.ConversationMutation
import net.weero.measix.pilot.service.runtime.ExecutionFacts
import net.weero.measix.pilot.service.runtime.OptionalFolderId
import net.weero.measix.pilot.service.runtime.OptionalString
import net.weero.measix.pilot.service.runtime.OptionalUuidSet
import net.weero.measix.pilot.utils.JsonInstant
import java.time.Instant
import kotlin.uuid.Uuid

data class RecoveredExecutionCount(
    val turns: Int,
    val tools: Int,
)

class ConversationRepository(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val favoriteDAO: FavoriteDAO,
    private val database: AppDatabase,
    private val filesManager: FilesManager,
    private val messageFtsManager: MessageFtsManager,
    private val turnExecutionDAO: TurnExecutionDAO,
    private val toolExecutionDAO: ToolExecutionDAO,
    private val artifactStore: ArtifactStore,
) {
    companion object {
        private const val PAGE_SIZE = 20
        private const val INITIAL_LOAD_SIZE = 40
    }

    suspend fun getRecentConversations(assistantId: Uuid, limit: Int = 10): List<Conversation> {
        return conversationDAO.getRecentConversationsOfAssistant(
            assistantId = assistantId.toString(),
            limit = limit
        ).map { entity ->
            val nodes = loadMessageNodes(entity.id)
            conversationEntityToConversation(entity, nodes)
        }
    }

    fun getConversationsOfAssistant(assistantId: Uuid): Flow<List<Conversation>> {
        return conversationDAO
            .getConversationsOfAssistant(assistantId.toString())
            .map { flow ->
                flow.map { entity ->
                    // 列表视图不需要完整的 nodes，使用空列表
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun getConversationsOfAssistantPaging(assistantId: Uuid): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getConversationsOfAssistantPaging(assistantId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun getUnfiledConversationsOfAssistantPaging(assistantId: Uuid): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getUnfiledConversationsOfAssistantPaging(assistantId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun getConversationsOfFolderPaging(folderId: Uuid): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getConversationsOfFolderPaging(folderId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    suspend fun getConversationsOfAssistantPage(
        assistantId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        val pagingSource = conversationDAO.getConversationsOfAssistantPaging(assistantId.toString())
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    suspend fun searchConversationsOfAssistantPage(
        assistantId: Uuid,
        titleKeyword: String,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        val pagingSource = conversationDAO.searchConversationsOfAssistantPaging(
            assistantId = assistantId.toString(),
            searchText = titleKeyword
        )
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    fun searchConversationsPaging(titleKeyword: String): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.searchConversationsPaging(titleKeyword) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun searchConversationsOfAssistantPaging(assistantId: Uuid, titleKeyword: String): Flow<PagingData<Conversation>> =
        Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                initialLoadSize = INITIAL_LOAD_SIZE,
                enablePlaceholders = false
            ),
            pagingSourceFactory = {
                conversationDAO.searchConversationsOfAssistantPaging(
                    assistantId.toString(),
                    titleKeyword
                )
            }
        ).flow.map { pagingData ->
            pagingData.map { entity ->
                conversationSummaryToConversation(entity)
            }
        }

    suspend fun getConversationById(uuid: Uuid): Conversation? {
        val entity = conversationDAO.getConversationById(uuid.toString())
        return if (entity != null) {
            val nodes = loadMessageNodes(entity.id)
            conversationEntityToConversation(entity, nodes)
        } else null
    }

    suspend fun existsConversationById(uuid: Uuid): Boolean {
        return conversationDAO.existsById(uuid.toString())
    }

    suspend fun countConversations(): Int {
        return conversationDAO.countAll()
    }

    suspend fun insertConversation(conversation: Conversation) {
        database.withTransaction {
            requireValidParent(conversation)
            conversationDAO.insert(
                conversationToConversationEntity(conversation)
            )
            saveMessageNodes(conversation.id.toString(), conversation.messageNodes)
        }
        // Child Conversation 不进入 FTS 索引
        if (conversation.parentConversationId == null) {
            messageFtsManager.indexConversation(conversation)
        }
        // 引用投影登记（新会话创建 → 全部节点登记）
        if (conversation.messageNodes.isNotEmpty()) {
            artifactStore.syncReferences(conversation.id, conversation.messageNodes, emptyList())
        }
    }

    /** Inserts a forked top-level Master and all remapped Child lineages atomically in Room. */
    suspend fun insertConversationTree(master: Conversation, children: List<Conversation>) {
        require(master.parentConversationId == null) { "Fork root must be a top-level conversation" }
        require(children.map { it.id }.distinct().size == children.size) { "Duplicate Child conversation ID" }
        require(children.all { it.parentConversationId == master.id }) {
            "Every forked Child must reference the new Master"
        }
        database.withTransaction {
            conversationDAO.insert(conversationToConversationEntity(master))
            saveMessageNodes(master.id.toString(), master.messageNodes)
            children.forEach { child ->
                conversationDAO.insert(conversationToConversationEntity(child))
                saveMessageNodes(child.id.toString(), child.messageNodes)
            }
        }
        messageFtsManager.indexConversation(master)
        // 引用投影登记（fork 入口无悬挂）
        if (master.messageNodes.isNotEmpty()) {
            artifactStore.syncReferences(master.id, master.messageNodes, emptyList())
        }
        children.forEach { child ->
            if (child.messageNodes.isNotEmpty()) {
                artifactStore.syncReferences(child.id, child.messageNodes, emptyList())
            }
        }
    }

    /** @Deprecated 导入/迁移/启动恢复专用（运行时零调用由单一写者契约测试锁定）。 */
    @Deprecated("导入/迁移/启动恢复专用", ReplaceWith("applyMutation"))
    suspend fun updateConversation(conversation: Conversation) {
        persistConversationNodes(conversation, reindexFts = true)
    }

    /**
     * Runtime 结构性修改的唯一落库入口。
     * 单事务提交：
     *  1. headerPatch → 窄列原语
     *  2. deletedNodeIds → deleteByIds + favorite 清理（引用行经 node FK 级联自动清）
     *  3. upsertedNodes → upsertAll
     *  4. executionFacts → TurnExecutionDAO / ToolExecutionDAO upsert（事务内）
     * 事务提交后（投影，允许最终一致）：
     *  - MessageFtsManager.reindexNodes / deleteNodesIndex
     *  - ArtifactStore.syncReferences(conversationId, upsertedNodes, deletedNodeIds)
     * 返回是否实际写入。
     */
    suspend fun applyMutation(mutation: ConversationMutation, executionFacts: ExecutionFacts? = null): Boolean {
        val headerPatch = mutation.headerPatch
        val hasHeaderChange = headerPatch != null
        val hasNodeChange = mutation.upsertedNodes.isNotEmpty() || mutation.deletedNodeIds.isNotEmpty()
        val hasExecutionFacts = executionFacts != null
        if (!hasHeaderChange && !hasNodeChange && !hasExecutionFacts) return false

        val conversationId = mutation.conversationId.toString()
        database.withTransaction {
            headerPatch?.let { patch -> applyHeaderPatch(mutation.conversationId, patch, mutation.updateAt) }
            if (mutation.deletedNodeIds.isNotEmpty()) {
                val deletedIds = mutation.deletedNodeIds.map { it.toString() }
                favoriteDAO.deleteNodeFavoritesByRefKeys(deletedIds.map { "node:$conversationId:$it" })
                messageNodeDAO.deleteByIds(deletedIds)
            }
            if (mutation.upsertedNodes.isNotEmpty()) {
                val entities = mutation.upsertedNodes.mapIndexed { index, node ->
                    MessageNodeEntity(
                        id = node.id.toString(),
                        conversationId = conversationId,
                        nodeIndex = index,
                        messages = JsonInstant.encodeToString(node.messages),
                        selectIndex = node.selectIndex,
                    )
                }
                messageNodeDAO.upsertAll(entities)
            }
            executionFacts?.turn?.let { turnExecutionDAO.upsert(it) }
            executionFacts?.toolExecution?.let { toolExecutionDAO.upsert(it) }
        }

        // 事务后投影
        val title = conversationDAO.getConversationById(conversationId)?.title ?: ""
        if (mutation.upsertedNodes.isNotEmpty()) {
            messageFtsManager.reindexNodes(
                conversationId = conversationId,
                title = title,
                updateAt = mutation.updateAt,
                nodes = mutation.upsertedNodes,
            )
            artifactStore.syncReferences(mutation.conversationId, mutation.upsertedNodes, mutation.deletedNodeIds)
        } else if (mutation.deletedNodeIds.isNotEmpty()) {
            messageFtsManager.deleteNodesIndex(conversationId, mutation.deletedNodeIds)
        }
        return true
    }

    private suspend fun applyHeaderPatch(
        conversationId: Uuid,
        patch: ConversationHeaderPatch,
        updateAt: Long,
    ) {
        val id = conversationId.toString()
        patch.title?.let { conversationDAO.updateTitle(id, it) }
        patch.chatSuggestions?.let { conversationDAO.updateChatSuggestions(id, JsonInstant.encodeToString(it)) }
        patch.isPinned?.let { conversationDAO.updatePinStatus(id, it) }
        when (patch.folderId) {
            is OptionalFolderId.Keep -> Unit
            is OptionalFolderId.Clear -> conversationDAO.updateFolderId(id, "")
            is OptionalFolderId.SetTo -> conversationDAO.updateFolderId(id, patch.folderId.id.toString())
        }
        patch.assistantId?.let { conversationDAO.updateAssistantId(id, it.toString()) }
        when (patch.customSystemPrompt) {
            is OptionalString.Keep -> Unit
            is OptionalString.Set -> conversationDAO.updateCustomSystemPrompt(id, patch.customSystemPrompt.value ?: "")
        }
        when (patch.modeInjectionIds) {
            is OptionalUuidSet.Keep -> Unit
            is OptionalUuidSet.Set -> conversationDAO.updateModeInjectionIds(id, JsonInstant.encodeToString(patch.modeInjectionIds.value))
        }
        when (patch.workspaceCwd) {
            is OptionalString.Keep -> Unit
            is OptionalString.Set -> conversationDAO.updateWorkspaceCwd(id, patch.workspaceCwd.value ?: "")
        }
        conversationDAO.updateGenerationCheckpoint(id, updateAt)
    }

    /**
     * GC：回收 state=ACTIVE 且 artifact_reference 无引用、created_at 超过保护窗口的 artifact。
     * 回填未完成时保守跳过（宁可保留文件）。
     */
    suspend fun collectUnreferencedArtifacts(
        protectionWindowMillis: Long = 24 * 3600 * 1000L,
    ): List<ArtifactEntity> = artifactStore.collectUnreferencedArtifacts(protectionWindowMillis)

    private suspend fun persistConversationNodes(
        conversation: Conversation,
        reindexFts: Boolean,
    ) {
        database.withTransaction {
            requireValidParent(conversation)
            conversationDAO.update(
                conversationToConversationEntity(conversation)
            )
            persistMessageNodes(conversation)
        }
        if (reindexFts && conversation.parentConversationId == null) {
            messageFtsManager.indexConversation(conversation)
        }
    }

    suspend fun upsertTurnExecution(execution: TurnExecutionEntity) {
        turnExecutionDAO.upsert(execution)
    }

    suspend fun getTurnExecution(turnId: String): TurnExecutionEntity? =
        turnExecutionDAO.getById(turnId)

    suspend fun getTurnExecutions(conversationId: Uuid): List<TurnExecutionEntity> =
        turnExecutionDAO.getByConversationId(conversationId.toString())

    suspend fun getRecoverableTurnExecutionsByConversation(): Map<Uuid, List<TurnExecutionEntity>> =
        turnExecutionDAO.getByStatuses(
            listOf(TurnExecutionStatus.CREATED, TurnExecutionStatus.RUNNING)
        ).mapNotNull { execution ->
            runCatching { Uuid.parse(execution.conversationId) }.getOrNull()?.let { it to execution }
        }.groupBy(keySelector = { it.first }, valueTransform = { it.second })

    suspend fun upsertToolExecution(execution: ToolExecutionEntity) {
        toolExecutionDAO.upsert(execution)
    }

    suspend fun getToolExecution(executionId: String): ToolExecutionEntity? =
        toolExecutionDAO.getById(executionId)

    suspend fun getToolExecutions(turnId: String): List<ToolExecutionEntity> =
        toolExecutionDAO.getByTurnId(turnId)

    suspend fun recoverInterruptedExecutions(
        updatedAt: Long,
        reason: String = "process_restarted",
    ): RecoveredExecutionCount = database.withTransaction {
        val recoverableTurnStatuses = listOf(TurnExecutionStatus.CREATED, TurnExecutionStatus.RUNNING)
        val tools = toolExecutionDAO.updateStatusForTurns(
            sourceStatus = ToolExecutionStatus.STARTED,
            sourceTurnStatuses = recoverableTurnStatuses,
            targetStatus = ToolExecutionStatus.UNKNOWN,
            reason = reason,
            updatedAt = updatedAt,
        )
        val turns = turnExecutionDAO.updateStatuses(
            sourceStatuses = recoverableTurnStatuses,
            targetStatus = TurnExecutionStatus.INTERRUPTED,
            reason = reason,
            updatedAt = updatedAt,
        )
        RecoveredExecutionCount(turns = turns, tools = tools)
    }

    private suspend fun persistMessageNodes(conversation: Conversation) {
        val conversationId = conversation.id.toString()
        val existing = messageNodeDAO.getNodesOfConversation(conversationId)
        val newIds = conversation.messageNodes.mapTo(mutableSetOf()) { it.id.toString() }
        existing.filter { it.id !in newIds }.forEach { stale ->
            favoriteDAO.deleteByRefKey("node:$conversationId:${stale.id}")
            messageNodeDAO.deleteById(stale.id)
        }
        saveMessageNodes(conversationId, conversation.messageNodes)
    }

    suspend fun deleteConversation(conversation: Conversation) {
        val persisted = conversationDAO.getConversationById(conversation.id.toString()) ?: return
        val fullConversation = getConversationById(conversation.id) ?: conversation
        val childConversations = if (persisted.parentConversationId == null) {
            conversationDAO.getChildConversations(conversation.id.toString()).mapNotNull { childEntity ->
                runCatching { Uuid.parse(childEntity.id) }.getOrNull()?.let { getConversationById(it) }
            }
        } else {
            emptyList()
        }
        val conversationsToDelete = childConversations + fullConversation

        // Master、Child、MessageNode 和 Favorite 必须在同一个 Room 事务中提交。
        database.withTransaction {
            conversationsToDelete.forEach { item ->
                favoriteDAO.deleteNodeFavoritesOfConversation(item.id.toString())
            }
            if (persisted.parentConversationId == null) {
                conversationDAO.deleteChildConversations(conversation.id.toString())
            }
            conversationDAO.deleteById(conversation.id.toString())
        }

        // 可重建的 FTS 与文件在数据库提交后清理，数据库失败时不会留下半棵树。
        conversationsToDelete.forEach { item ->
            messageFtsManager.deleteConversation(item.id.toString())
        }
        // 引用投影经 node FK 级联自动清；文件清理走 GC
        // （findUnshared* 探测路径删除，collectUnreferencedArtifacts 兜底回收）
        runCatching { collectUnreferencedArtifacts() }
    }

    suspend fun searchMessages(
        keyword: String,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
    ) = messageFtsManager.search(keyword, sort)

    suspend fun searchMessagesOfAssistant(
        assistantId: Uuid,
        keyword: String,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
    ) = messageFtsManager.search(keyword, sort, assistantId.toString())

    suspend fun rebuildAllIndexes(onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }) {
        messageFtsManager.deleteAll()
        val allIds = conversationDAO.getAllIds()
        val total = allIds.size
        allIds.forEachIndexed { index, id ->
            val entity = conversationDAO.getConversationById(id) ?: return@forEachIndexed
            val nodes = loadMessageNodes(entity.id)
            val conversation = conversationEntityToConversation(entity, nodes)
            messageFtsManager.indexConversation(conversation)
            onProgress(index + 1, total)
        }
    }

    suspend fun deleteConversationOfAssistant(assistantId: Uuid) {
        getConversationsOfAssistant(assistantId).first().forEach { conversation ->
            deleteConversation(conversation)
        }
    }

    /**
     * 获取指定 Master 会话的所有 Child 会话
     */
    suspend fun getChildConversations(parentConversationId: Uuid): List<Conversation> {
        return conversationDAO.getChildConversations(parentConversationId.toString()).map { entity ->
            val nodes = loadMessageNodes(entity.id)
            conversationEntityToConversation(entity, nodes)
        }
    }

    /**
     * 按 ID 获取单个会话（不加载 messageNodes），用于轻量级检查
     */
    suspend fun getConversationEntityById(uuid: Uuid): ConversationEntity? {
        return conversationDAO.getConversationById(uuid.toString())
    }

    /**
     * 删除指定 Master 会话的所有 Child 会话
     */
    suspend fun deleteChildConversations(parentConversationId: Uuid) {
        val children = conversationDAO.getChildConversations(parentConversationId.toString()).mapNotNull { child ->
            runCatching { Uuid.parse(child.id) }.getOrNull()?.let { getConversationById(it) }
        }
        database.withTransaction {
            children.forEach { child ->
                favoriteDAO.deleteNodeFavoritesOfConversation(child.id.toString())
            }
            conversationDAO.deleteChildConversations(parentConversationId.toString())
        }
        children.forEach { child ->
            messageFtsManager.deleteConversation(child.id.toString())
        }
        // 引用投影经 node FK 级联自动清；文件清理走 GC
        runCatching { collectUnreferencedArtifacts() }
    }

    /**
     * 获取所有 Child 会话 ID（保留——供 DelegationCoordinator.performRecovery 的
     * orphan 识别与 AssistantBackgroundService 的全库引用扫描使用）
     */
    suspend fun getAllChildConversationIds(): List<Uuid> {
        return conversationDAO.getAllChildConversationIds().mapNotNull {
            runCatching { Uuid.parse(it) }.getOrNull()
        }
    }

    /** Loads complete top-level conversations without deserializing every Child payload. */
    suspend fun getAllTopLevelConversationsSync(): List<Conversation> {
        return conversationDAO.getAllIds().mapNotNull { id ->
            val entity = conversationDAO.getConversationById(id) ?: return@mapNotNull null
            conversationEntityToConversation(entity, loadMessageNodes(id))
        }
    }

    fun conversationToConversationEntity(conversation: Conversation): ConversationEntity {
        require(conversation.messageNodes.none { it.messages.any { message -> message.hasBase64Part() } })
        return ConversationEntity(
            id = conversation.id.toString(),
            title = conversation.title,
            nodes = "[]",  // nodes 现在存储在单独的表中
            createAt = conversation.createAt.toEpochMilli(),
            updateAt = conversation.updateAt.toEpochMilli(),
            assistantId = conversation.assistantId.toString(),
            chatSuggestions = JsonInstant.encodeToString(conversation.chatSuggestions),
            isPinned = conversation.isPinned,
            customSystemPrompt = conversation.customSystemPrompt ?: "",
            modeInjectionIds = JsonInstant.encodeToString(conversation.modeInjectionIds),
            workspaceCwd = conversation.workspaceCwd ?: "",
            folderId = conversation.folderId?.toString() ?: "",
            parentConversationId = conversation.parentConversationId?.toString(),
        )
    }

    fun conversationEntityToConversation(
        conversationEntity: ConversationEntity,
        messageNodes: List<MessageNode>
    ): Conversation {
        return Conversation(
            id = Uuid.parse(conversationEntity.id),
            title = conversationEntity.title,
            messageNodes = messageNodes.filter { it.messages.isNotEmpty() },
            createAt = Instant.ofEpochMilli(conversationEntity.createAt),
            updateAt = Instant.ofEpochMilli(conversationEntity.updateAt),
            assistantId = Uuid.parse(conversationEntity.assistantId),
            chatSuggestions = JsonInstant.decodeFromString(conversationEntity.chatSuggestions),
            isPinned = conversationEntity.isPinned,
            customSystemPrompt = conversationEntity.customSystemPrompt.ifEmpty { null },
            modeInjectionIds = JsonInstant.decodeFromString(conversationEntity.modeInjectionIds),
            workspaceCwd = conversationEntity.workspaceCwd.ifEmpty { null },
            folderId = conversationEntity.folderId.ifEmpty { null }?.let { Uuid.parse(it) },
            parentConversationId = conversationEntity.parentConversationId?.let { Uuid.parse(it) },
        )
    }

    fun getPinnedConversations(): Flow<List<Conversation>> {
        return conversationDAO
            .getPinnedConversations()
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    suspend fun togglePinStatus(conversationId: Uuid) {
        conversationDAO.updatePinStatus(
            id = conversationId.toString(),
            isPinned = !(getConversationById(conversationId)?.isPinned ?: false)
        )
    }

    /**
     * 单列更新会话的文件夹归属，folderId 为 null 表示移出文件夹（未归类）。
     */
    suspend fun updateConversationFolderId(conversationId: Uuid, folderId: Uuid?) {
        conversationDAO.updateFolderId(
            id = conversationId.toString(),
            folderId = folderId?.toString() ?: ""
        )
    }

    /**
     * 单列更新会话归属助手（非活跃会话的助手迁移；活跃会话走 UpdateHeader 命令）。
     * 调用方负责配套的 folder 清空（withAssistant 语义）。
     */
    suspend fun updateConversationAssistantId(conversationId: Uuid, assistantId: Uuid) {
        conversationDAO.updateAssistantId(conversationId.toString(), assistantId.toString())
    }

    /**
     * Child 恢复收口：把指定会话的 RUNNING turn 行置为 INTERRUPTED。
     * Master 全库恢复扫描（recoverInterruptedTurns）过滤 Child 会话的 turn——Child 由
     * DelegationCoordinator（SubAssistantRecovery 载体）全权收口，避免双路径。
     */
    suspend fun finalizeRunningTurnsOfConversation(conversationId: Uuid, reason: String) {
        val now = System.currentTimeMillis()
        turnExecutionDAO.getByConversationId(conversationId.toString())
            .filter { it.status == TurnExecutionStatus.RUNNING }
            .forEach { execution ->
                turnExecutionDAO.upsert(
                    execution.copy(
                        status = TurnExecutionStatus.INTERRUPTED,
                        reason = reason,
                        updatedAt = now,
                    )
                )
            }
    }

    /**
     * Child retention 收缩（master 树 mutation 后的子助手域收口；master 树本身已由
     * 命令通道 applyMutation delta 落库，本方法只处理 children）：
     * retained children 全量收缩事务 + deleted children 删除；事务后 FTS 增删、
     * retained 节点引用替换登记、无引用文件 GC（引用维护表）。
     */
    suspend fun updateChildRetention(
        retainedChildren: List<Conversation>,
        deletedChildren: List<Conversation>,
    ) {
        if (retainedChildren.isEmpty() && deletedChildren.isEmpty()) return
        database.withTransaction {
            retainedChildren.forEach { child ->
                conversationDAO.update(conversationToConversationEntity(child))
                messageNodeDAO.deleteByConversation(child.id.toString())
                saveMessageNodes(child.id.toString(), child.messageNodes)
            }
            deletedChildren.forEach { child ->
                favoriteDAO.deleteNodeFavoritesOfConversation(child.id.toString())
                conversationDAO.deleteById(child.id.toString())
            }
        }
        // Child 不进入 FTS 索引（与 insertConversation 的裁决一致）：
        // retained children 只做 DB 收缩，无索引动作；deleted children 防御性清理
        // （历史数据若曾入索引，避免悬挂行）。
        deletedChildren.forEach { messageFtsManager.deleteConversation(it.id.toString()) }
        // 引用投影同步（retained 节点全量登记替换）
        retainedChildren.forEach { child ->
            if (child.messageNodes.isNotEmpty()) {
                artifactStore.syncReferences(child.id, child.messageNodes, emptyList())
            }
        }
        collectUnreferencedArtifacts()
    }

    /** Column-only title write. Does not replace message nodes. */
    suspend fun updateConversationTitle(conversationId: Uuid, title: String) {
        conversationDAO.updateTitle(conversationId.toString(), title)
        messageFtsManager.updateConversationTitle(conversationId.toString(), title)
    }

    /** Column-only suggestion write. Does not replace message nodes. */
    suspend fun updateConversationSuggestions(conversationId: Uuid, suggestions: List<String>) {
        conversationDAO.updateChatSuggestions(
            id = conversationId.toString(),
            chatSuggestions = JsonInstant.encodeToString(suggestions),
        )
    }

    private fun conversationSummaryToConversation(entity: LightConversationEntity): Conversation {
        return Conversation(
            id = Uuid.parse(entity.id),
            assistantId = Uuid.parse(entity.assistantId),
            title = entity.title,
            isPinned = entity.isPinned,
            createAt = Instant.ofEpochMilli(entity.createAt),
            updateAt = Instant.ofEpochMilli(entity.updateAt),
            messageNodes = emptyList(),
            folderId = entity.folderId.ifEmpty { null }?.let { Uuid.parse(it) },
        )
    }

    private suspend fun loadMessageNodes(conversationId: String): List<MessageNode> {
        val favoriteNodeIds = favoriteDAO
            .getFavoriteNodeIdsOfConversation(conversationId)
            .mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }
            .toSet()

        return database.withTransaction {
            val nodes = mutableListOf<MessageNode>()
            var offset = 0
            val pageSize = 64
            while (true) {
                val page = try {
                    messageNodeDAO.getNodesOfConversationPaged(conversationId, pageSize, offset)
                } catch (e: SQLiteBlobTooBigException) {
                    e.printStackTrace()
                    offset += pageSize
                    continue
                } catch (e: IllegalStateException) {
                    e.printStackTrace()
                    offset += pageSize
                    continue
                }
                if (page.isEmpty()) break
                page.forEach { entity ->
                    val messages = JsonInstant.decodeFromString<List<UIMessage>>(entity.messages)
                    val nodeId = Uuid.parse(entity.id)
                    nodes.add(
                        MessageNode(
                            id = nodeId,
                            messages = messages,
                            selectIndex = entity.selectIndex,
                            isFavorite = favoriteNodeIds.contains(nodeId)
                        )
                    )
                }
                offset += page.size
            }
            nodes
        }
    }

    private suspend fun saveMessageNodes(conversationId: String, nodes: List<MessageNode>) {
        val entities = nodes.mapIndexed { index, node ->
            MessageNodeEntity(
                id = node.id.toString(),
                conversationId = conversationId,
                nodeIndex = index,
                messages = JsonInstant.encodeToString(node.messages),
                selectIndex = node.selectIndex
            )
        }
        messageNodeDAO.insertAll(entities)
    }

    private suspend fun requireValidParent(conversation: Conversation) {
        val parentId = conversation.parentConversationId ?: return
        require(parentId != conversation.id) { "Child Conversation cannot reference itself" }
        val parent = conversationDAO.getConversationById(parentId.toString())
            ?: error("Parent Conversation $parentId does not exist")
        require(parent.parentConversationId == null) {
            "Child Conversation must reference a top-level Master Conversation"
        }
    }
}

data class ConversationPageResult(
    val items: List<Conversation>,
    val nextOffset: Int?,
)
