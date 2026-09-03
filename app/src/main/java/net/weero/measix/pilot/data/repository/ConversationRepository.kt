package net.weero.measix.pilot.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.db.fts.MessageFtsManager
import net.weero.measix.pilot.data.db.fts.MessageSearchSort
import net.weero.measix.pilot.data.db.dao.ConversationDAO
import net.weero.measix.pilot.data.db.dao.ConversationModelContextDAO
import net.weero.measix.pilot.data.db.dao.FavoriteDAO
import net.weero.measix.pilot.data.db.dao.LightConversationEntity
import net.weero.measix.pilot.data.db.dao.MessageNodeDAO
import net.weero.measix.pilot.data.db.dao.MessagePayloadReadException
import net.weero.measix.pilot.data.db.dao.readMessagesPayload
import net.weero.measix.pilot.data.db.dao.ScopedTurnExecution
import net.weero.measix.pilot.data.db.dao.ToolExecutionDAO
import net.weero.measix.pilot.data.db.dao.TurnExecutionDAO
import net.weero.measix.pilot.data.db.entity.ConversationEntity
import net.weero.measix.pilot.data.db.entity.ConversationModelContextEntity
import net.weero.measix.pilot.data.db.entity.MessageNodeEntity
import net.weero.measix.pilot.data.db.entity.ToolExecutionEntity
import net.weero.measix.pilot.data.db.entity.ToolExecutionStatus
import net.weero.measix.pilot.data.db.entity.TurnExecutionEntity
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.ArtifactReferenceDelta
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.ConversationModelContextEntry
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.service.runtime.ConversationHeaderPatch
import net.weero.measix.pilot.service.runtime.ConversationHeader
import net.weero.measix.pilot.service.runtime.ConversationMutation
import net.weero.measix.pilot.service.runtime.ConversationWrite
import net.weero.measix.pilot.service.runtime.ExecutionFacts
import net.weero.measix.pilot.service.runtime.hasChanges
import net.weero.measix.pilot.service.runtime.OptionalFolderId
import net.weero.measix.pilot.service.runtime.OptionalString
import net.weero.measix.pilot.service.runtime.OptionalUuidSet
import net.weero.measix.pilot.service.ConversationDisclosureSnapshotService
import net.weero.measix.pilot.service.runtime.TurnExecutionOperation
import net.weero.measix.pilot.service.runtime.ConversationAggregateSnapshot
import net.weero.measix.pilot.service.runtime.toSnapshot
import net.weero.measix.pilot.utils.JsonInstant
import java.time.Instant
import kotlin.uuid.Uuid

class ExecutionStateConflictException(message: String) : IllegalStateException(message)

private val RECOVERABLE_TURN_STATUSES = listOf(
    TurnExecutionStatus.CREATED,
    TurnExecutionStatus.RUNNING,
    TurnExecutionStatus.AWAITING_APPROVAL,
)

class ConversationRepository(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val favoriteDAO: FavoriteDAO,
    private val database: AppDatabase,
    private val messageFtsManager: MessageFtsManager,
    private val turnExecutionDAO: TurnExecutionDAO,
    private val toolExecutionDAO: ToolExecutionDAO,
    private val modelContextDAO: ConversationModelContextDAO,
    private val artifactStore: ArtifactStore,
) {
    companion object {
        private const val PAGE_SIZE = 20
        private const val INITIAL_LOAD_SIZE = 40
    }

    suspend fun getRecentConversationRecords(
        assistantId: Uuid,
        limit: Int = 10,
    ): List<ConversationListRecord> = conversationDAO.getRecentConversationsOfAssistant(
        assistantId = assistantId.toString(),
        limit = limit,
    ).map(::conversationEntityToListRecord)

    fun getConversationsOfAssistant(assistantId: Uuid): Flow<List<ConversationListRecord>> {
        return conversationDAO
            .getConversationsOfAssistant(assistantId.toString())
            .map { entities -> entities.map(::conversationEntityToListRecord) }
    }

    fun getUnfiledConversationsOfAssistantPaging(
        assistantId: Uuid,
    ): Flow<PagingData<ConversationListRecord>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getUnfiledConversationsOfAssistantPaging(assistantId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map(::lightEntityToListRecord)
    }

    fun getConversationsOfFolderPaging(folderId: Uuid): Flow<PagingData<ConversationListRecord>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getConversationsOfFolderPaging(folderId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map(::lightEntityToListRecord)
    }

    suspend fun getConversationById(uuid: Uuid): Conversation? = database.withTransaction {
        val entity = conversationDAO.getConversationById(uuid.toString()) ?: return@withTransaction null
        conversationEntityToConversation(entity, loadMessageNodes(entity.id))
    }

    /** Loads the internal durable aggregate; public Conversation reads never carry model context. */
    internal suspend fun getConversationSnapshotById(uuid: Uuid): ConversationAggregateSnapshot? =
        database.withTransaction {
            val entity = conversationDAO.getConversationById(uuid.toString()) ?: return@withTransaction null
            val nodes = loadMessageNodes(entity.id)
            conversationEntityToConversation(entity, nodes).toSnapshot(
                modelContextEntries = loadModelContextEntries(entity.id, nodes),
            )
        }

    suspend fun existsConversationById(uuid: Uuid): Boolean {
        return conversationDAO.existsById(uuid.toString())
    }

    suspend fun countConversations(): Int {
        return conversationDAO.countAll()
    }

    internal suspend fun insertConversation(conversation: Conversation) {
        artifactStore.withLifecycleLock {
            val referenceDelta = artifactStore.prepareReferenceDelta(
                conversation.messageNodes,
                emptyList(),
            )
            database.withTransaction {
                requireValidParent(conversation)
                conversationDAO.insert(conversationToConversationEntity(conversation))
                saveMessageNodes(conversation.id.toString(), conversation.messageNodes)
                artifactStore.applyReferenceDeltaInTransaction(referenceDelta)
                if (conversation.parentConversationId == null) {
                    messageFtsManager.indexConversationInTransaction(conversation)
                }
            }
        }
    }

    /** Inserts one already-remapped internal aggregate without exposing context through Conversation. */
    internal suspend fun insertConversationSnapshot(snapshot: ConversationAggregateSnapshot) {
        val conversation = snapshotToConversation(snapshot)
        artifactStore.withLifecycleLock {
            val referenceDelta = artifactStore.prepareReferenceDelta(snapshot.nodes, emptyList())
            database.withTransaction {
                requireValidParent(conversation)
                conversationDAO.insert(conversationToConversationEntity(conversation))
                saveMessageNodes(snapshot.conversationId.toString(), snapshot.nodes)
                saveModelContextEntries(snapshot.modelContextEntries)
                artifactStore.applyReferenceDeltaInTransaction(referenceDelta)
                if (snapshot.header.parentConversationId == null) {
                    messageFtsManager.indexConversationInTransaction(conversation)
                }
            }
        }
    }

    /**
     * 随整棵 Conversation 原子落库的 context entries（Fork / undo-restore 携带历史 entries）。
     * 走同一条 insert-once 主键语义；entries 均来自已通过 §12.3 校验的装载结果。
     */
    private suspend fun saveModelContextEntries(entries: List<ConversationModelContextEntry>) {
        if (entries.isEmpty()) return
        modelContextDAO.insertOnce(entries.map(::modelContextEntityOf))
    }

    private fun modelContextEntityOf(entry: ConversationModelContextEntry): ConversationModelContextEntity =
        ConversationModelContextEntity(
            ownerMessageId = entry.ownerMessageId.toString(),
            ownerNodeId = entry.ownerNodeId.toString(),
            anchorNodeId = entry.anchorNodeId.toString(),
            anchorMessageId = entry.anchorMessageId.toString(),
            content = entry.content,
        )

    suspend fun getConversationHeader(uuid: Uuid): ConversationHeader? =
        conversationDAO.getConversationById(uuid.toString())
            ?.let(::conversationEntityToHeader)

    private fun conversationEntityToHeader(entity: ConversationEntity): ConversationHeader {
        val id = Uuid.parse(entity.id)
        return ConversationHeader(
            id = id,
            title = entity.title,
            assistantId = Uuid.parse(entity.assistantId),
            folderId = entity.folderId.ifEmpty { null }?.let(Uuid::parse),
            isPinned = entity.isPinned,
            chatSuggestions = JsonInstant.decodeFromString(entity.chatSuggestions),
            customSystemPrompt = entity.customSystemPrompt.ifEmpty { null },
            modeInjectionIds = JsonInstant.decodeFromString(entity.modeInjectionIds),
            workspaceCwd = entity.workspaceCwd.ifEmpty { null },
            parentConversationId = entity.parentConversationId?.let(Uuid::parse),
            newConversation = false,
            createAt = entity.createAt,
            updateAt = entity.updateAt,
        )
    }

    /** Inserts a forked Master and remapped Child aggregates, including context rows, atomically. */
    internal suspend fun insertConversationTree(
        master: ConversationAggregateSnapshot,
        children: List<ConversationAggregateSnapshot>,
    ) {
        require(master.header.parentConversationId == null) { "Fork root must be a top-level conversation" }
        require(children.map { it.conversationId }.distinct().size == children.size) { "Duplicate Child conversation ID" }
        require(children.all { it.header.parentConversationId == master.conversationId }) {
            "Every forked Child must reference the new Master"
        }
        val masterConversation = snapshotToConversation(master)
        val childConversations = children.associate { it.conversationId to snapshotToConversation(it) }
        artifactStore.withLifecycleLock {
            val masterReferences = artifactStore.prepareReferenceDelta(master.nodes, emptyList())
            val childReferences = children.associate { child ->
                child.conversationId to artifactStore.prepareReferenceDelta(child.nodes, emptyList())
            }
            database.withTransaction {
                conversationDAO.insert(conversationToConversationEntity(masterConversation))
                saveMessageNodes(master.conversationId.toString(), master.nodes)
                saveModelContextEntries(master.modelContextEntries)
                artifactStore.applyReferenceDeltaInTransaction(masterReferences)
                children.forEach { child ->
                    val conversation = childConversations.getValue(child.conversationId)
                    conversationDAO.insert(conversationToConversationEntity(conversation))
                    saveMessageNodes(child.conversationId.toString(), child.nodes)
                    saveModelContextEntries(child.modelContextEntries)
                    artifactStore.applyReferenceDeltaInTransaction(requireNotNull(childReferences[child.conversationId]))
                }
                messageFtsManager.indexConversationInTransaction(masterConversation)
            }
        }
    }

    /**
     * Unique durable conversation write. One Room transaction either materializes a Draft or
     * applies a delta mutation and execution facts.
     */
    internal suspend fun commit(write: ConversationWrite): Boolean = when (write) {
        is ConversationWrite.MaterializeDraft -> {
            insertConversation(write.conversation)
            true
        }
        is ConversationWrite.Mutate -> {
            if (!write.mutation.hasChanges() && write.executionFacts == null) false
            else applyMutation(write.mutation, write.executionFacts)
        }
    }

    /**
     * Runtime 结构性修改的唯一落库入口。
     * 单事务提交：
     *  1. headerPatch → 窄列原语
     *  2. deletedNodeIds → deleteByIds + favorite 清理（引用行经 node FK 级联自动清）
     *  3. upsertedNodes → upsertAll
     *  4. executionFacts → TurnExecutionDAO / ToolExecutionDAO upsert（事务内）
     *  5. artifact_reference 与 FTS 投影在同一事务同步
     * 返回是否实际写入。
     */
    internal suspend fun applyMutation(mutation: ConversationMutation, executionFacts: ExecutionFacts? = null): Boolean {
        val headerPatch = mutation.headerPatch
        val hasHeaderChange = headerPatch != null
        val hasNodeChange = mutation.upsertedNodes.isNotEmpty() || mutation.deletedNodeIds.isNotEmpty()
        val hasContextDelta = mutation.insertedModelContextEntries.isNotEmpty() ||
            mutation.deletedModelContextEntries.isNotEmpty()
        val hasExecutionFacts = executionFacts != null
        if (!hasHeaderChange && !hasNodeChange && !hasContextDelta && !hasExecutionFacts) return false

        val conversationId = mutation.conversationId.toString()
        require(mutation.upsertedNodeIndices.size == mutation.upsertedNodes.size) {
            "upsertedNodeIndices must align 1:1 with upsertedNodes (new-tree positions)"
        }
        val nodeEntities = mutation.upsertedNodes.mapIndexed { i, node ->
            MessageNodeEntity(
                id = node.id.toString(),
                conversationId = conversationId,
                nodeIndex = mutation.upsertedNodeIndices[i],
                messages = JsonInstant.encodeToString(node.messages),
                selectIndex = node.selectIndex,
            )
        }
        suspend fun commit(referenceDelta: ArtifactReferenceDelta?) {
            database.withTransaction {
                headerPatch?.let { patch -> applyHeaderPatch(mutation.conversationId, patch, mutation.updateAt) }
                if (mutation.deletedNodeIds.isNotEmpty()) {
                    val deletedIds = mutation.deletedNodeIds.map { it.toString() }
                    favoriteDAO.deleteNodeFavoritesByRefKeys(deletedIds.map { "node:$conversationId:$it" })
                    messageNodeDAO.deleteByIds(deletedIds)
                }
                if (nodeEntities.isNotEmpty()) {
                    messageNodeDAO.upsertAll(nodeEntities)
                }
                // §12.2 事务顺序：node 删除与 upsert 之后、执行事实之前收口 context。
                // deleted 列表携带消失 entry 的完整事实：按 (owner_node_id, owner_message_id)
                // 主键精确删除（Fork / Child clone 会在其他 Conversation 保留相同 message id）；
                // node 级消失同时由 FK cascade 闭合。插入只走 insert-once，同 key 不同行
                // 直接冲突而不覆盖历史。
                if (mutation.deletedModelContextEntries.isNotEmpty()) {
                    modelContextDAO.deleteByPrimaryKeys(mutation.deletedModelContextEntries.map(::modelContextEntityOf))
                }
                if (mutation.insertedModelContextEntries.isNotEmpty()) {
                    modelContextDAO.insertOnce(mutation.insertedModelContextEntries.map(::modelContextEntityOf))
                }
                persistExecutionFacts(executionFacts)
                referenceDelta?.let { artifactStore.applyReferenceDeltaInTransaction(it) }
                if (mutation.indexForSearch) {
                    val title = mutation.titleForIndex ?: ""
                    if (mutation.searchMetadataChanged) {
                        messageFtsManager.updateConversationMetadataInTransaction(
                            conversationId = conversationId,
                            title = title,
                            updateAt = mutation.updateAt,
                        )
                    }
                    if (mutation.deletedNodeIds.isNotEmpty()) {
                        messageFtsManager.deleteNodesIndexInTransaction(conversationId, mutation.deletedNodeIds)
                    }
                    if (mutation.upsertedNodes.isNotEmpty()) {
                        messageFtsManager.reindexNodesInTransaction(
                            conversationId = conversationId,
                            title = title,
                            updateAt = mutation.updateAt,
                            nodes = mutation.upsertedNodes,
                        )
                    }
                }
            }
        }
        if (hasNodeChange) {
            artifactStore.withLifecycleLock {
                val referenceDelta = artifactStore.prepareReferenceDelta(
                    mutation.upsertedNodes,
                    mutation.deletedNodeIds,
                )
                commit(referenceDelta)
            }
        } else {
            commit(null)
        }
        return true
    }

    private suspend fun persistExecutionFacts(facts: ExecutionFacts?) {
        if (facts == null) return
        facts.toolExecution?.let { persistToolExecution(it) }
        facts.turn?.let { execution ->
            if (execution.status.isTerminal()) {
                toolExecutionDAO.transitionStartedByTurn(
                    turnId = execution.turnId,
                    targetStatus = if (execution.status == TurnExecutionStatus.CANCELLED) {
                        ToolExecutionStatus.CANCELLED
                    } else {
                        ToolExecutionStatus.UNKNOWN
                    },
                    reason = execution.reason,
                    updatedAt = execution.updatedAt,
                )
            }
            persistTurnExecution(execution, facts.turnOperation)
        }
    }

    private fun TurnExecutionStatus.isTerminal(): Boolean = when (this) {
        TurnExecutionStatus.COMPLETED,
        TurnExecutionStatus.CANCELLED,
        TurnExecutionStatus.FAILED,
        TurnExecutionStatus.INCOMPLETE,
        TurnExecutionStatus.INTERRUPTED,
        -> true
        TurnExecutionStatus.CREATED,
        TurnExecutionStatus.RUNNING,
        TurnExecutionStatus.AWAITING_APPROVAL,
        -> false
    }

    private suspend fun persistTurnExecution(
        execution: TurnExecutionEntity,
        operation: TurnExecutionOperation,
    ) {
        if (operation == TurnExecutionOperation.START) {
            require(execution.status == TurnExecutionStatus.RUNNING) {
                "a turn must start in RUNNING state"
            }
            requireNotNull(execution.assistantMessageId) {
                "a turn must start with its assistant message owner"
            }
            if (turnExecutionDAO.insert(execution) != -1L) return
            val current = turnExecutionDAO.getById(execution.turnId)
            if (current?.conversationId == execution.conversationId &&
                current.assistantMessageId == execution.assistantMessageId &&
                current.status == TurnExecutionStatus.RUNNING
            ) {
                return
            }
            throw ExecutionStateConflictException(
                "cannot start turn ${execution.turnId}: current=${current?.status}",
            )
        }

        val sourceStatuses = when (operation) {
            TurnExecutionOperation.START -> error("handled above")
            TurnExecutionOperation.RECOVER -> listOf(
                TurnExecutionStatus.CREATED,
                TurnExecutionStatus.RUNNING,
                TurnExecutionStatus.AWAITING_APPROVAL,
            )
            TurnExecutionOperation.ADVANCE -> when (execution.status) {
                TurnExecutionStatus.RUNNING -> listOf(
                    TurnExecutionStatus.RUNNING,
                    TurnExecutionStatus.AWAITING_APPROVAL,
                )
                TurnExecutionStatus.AWAITING_APPROVAL -> listOf(
                    TurnExecutionStatus.RUNNING,
                    TurnExecutionStatus.AWAITING_APPROVAL,
                )
                TurnExecutionStatus.COMPLETED,
                TurnExecutionStatus.CANCELLED,
                TurnExecutionStatus.FAILED,
                TurnExecutionStatus.INCOMPLETE,
                TurnExecutionStatus.INTERRUPTED,
                -> listOf(TurnExecutionStatus.RUNNING, TurnExecutionStatus.AWAITING_APPROVAL)
                TurnExecutionStatus.CREATED -> emptyList()
            }
        }
        val changed = if (sourceStatuses.isEmpty()) {
            0
        } else {
            turnExecutionDAO.transition(
                turnId = execution.turnId,
                conversationId = execution.conversationId,
                sourceStatuses = sourceStatuses,
                targetStatus = execution.status,
                reason = execution.reason,
                assistantMessageId = execution.assistantMessageId,
                updatedAt = execution.updatedAt,
            )
        }
        if (changed == 1) return
        val current = turnExecutionDAO.getById(execution.turnId)
        if (current?.status == execution.status &&
            current.conversationId == execution.conversationId &&
            current.assistantMessageId == execution.assistantMessageId &&
            current.reason == execution.reason
        ) {
            return
        }
        throw ExecutionStateConflictException(
            "illegal turn transition ${execution.turnId}: ${current?.status} -> ${execution.status}",
        )
    }

    private suspend fun persistToolExecution(execution: ToolExecutionEntity) {
        if (execution.status == ToolExecutionStatus.STARTED) {
            if (toolExecutionDAO.insertStartedIfTurnActive(
                    executionId = execution.executionId,
                    turnId = execution.turnId,
                    toolOrdinal = execution.toolOrdinal,
                    reason = execution.reason,
                    childConversationId = execution.childConversationId,
                    createdAt = execution.createdAt,
                    updatedAt = execution.updatedAt,
                ) != -1L
            ) return
            if (toolExecutionDAO.updateStartedIfTurnActive(
                    executionId = execution.executionId,
                    turnId = execution.turnId,
                    toolOrdinal = execution.toolOrdinal,
                    reason = execution.reason,
                    childConversationId = execution.childConversationId,
                    updatedAt = execution.updatedAt,
                ) == 1
            ) return
            val current = toolExecutionDAO.getById(execution.executionId)
            val owner = turnExecutionDAO.getById(execution.turnId)
            throw ExecutionStateConflictException(
                "cannot start tool ${execution.executionId}: current=${current?.status}, owner=${owner?.status}",
            )
        }
        val changed = toolExecutionDAO.transition(
            executionId = execution.executionId,
            turnId = execution.turnId,
            toolOrdinal = execution.toolOrdinal,
            sourceStatuses = listOf(ToolExecutionStatus.STARTED),
            targetStatus = execution.status,
            reason = execution.reason,
            childConversationId = execution.childConversationId,
            updatedAt = execution.updatedAt,
        )
        if (changed == 1) return
        val current = toolExecutionDAO.getById(execution.executionId)
        if (current?.status == execution.status &&
            current.turnId == execution.turnId &&
            current.toolOrdinal == execution.toolOrdinal &&
            current.reason == execution.reason &&
            current.childConversationId == execution.childConversationId
        ) return
        throw ExecutionStateConflictException(
            "illegal tool transition ${execution.executionId}: ${current?.status} -> ${execution.status}",
        )
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
        check(conversationDAO.updateTimestamp(id, updateAt) == 1) {
            "conversation disappeared during header mutation: $conversationId"
        }
    }

    suspend fun getTurnExecution(turnId: String): TurnExecutionEntity? =
        turnExecutionDAO.getById(turnId)

    suspend fun getTurnExecutions(conversationId: Uuid): List<TurnExecutionEntity> =
        turnExecutionDAO.getByConversationId(conversationId.toString())

    suspend fun getRecoverableTurnExecutionsByConversation(): Map<Uuid, List<TurnExecutionEntity>> =
        turnExecutionDAO.getMasterByStatuses(
            RECOVERABLE_TURN_STATUSES
        ).map { execution -> Uuid.parse(execution.conversationId) to execution }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })

    /** 非终态 turn 事实行 + Master/Child 域标注——子助手恢复域的定点输入。 */
    suspend fun getNonTerminalTurnExecutionsWithScope(): List<ScopedTurnExecution> =
        turnExecutionDAO.getByStatusesWithScope(RECOVERABLE_TURN_STATUSES)

    suspend fun getToolExecutions(turnId: String): List<ToolExecutionEntity> =
        toolExecutionDAO.getByTurnId(turnId)

    internal suspend fun deleteConversation(conversationId: Uuid) {
        val persisted = conversationDAO.getConversationById(conversationId.toString()) ?: return
        val conversationIds = if (persisted.parentConversationId == null) {
            conversationDAO.getChildConversationIds(conversationId.toString()).map(Uuid::parse) + conversationId
        } else {
            listOf(conversationId)
        }

        // Favorite 与 FTS 是独立投影，必须显式清理；Conversation、Child、
        // MessageNode 与 ArtifactReference 的主数据关系只由 FK CASCADE 收口。
        database.withTransaction {
            conversationIds.forEach { id ->
                favoriteDAO.deleteNodeFavoritesOfConversation(id.toString())
                messageFtsManager.deleteConversationInTransaction(id.toString())
            }
            conversationDAO.deleteById(conversationId.toString())
        }
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

    internal suspend fun rebuildAllIndexes(onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }) {
        database.withTransaction {
            val allIds = conversationDAO.getAllIds()
            val total = allIds.size
            messageFtsManager.deleteAllInTransaction()
            allIds.forEachIndexed { index, id ->
                val entity = conversationDAO.getConversationById(id) ?: return@forEachIndexed
                val conversation = conversationEntityToConversation(entity, loadMessageNodes(entity.id))
                if (conversation.parentConversationId == null) {
                    messageFtsManager.indexConversationInTransaction(conversation)
                }
                onProgress(index + 1, total)
            }
            messageFtsManager.markProjectionCurrentInTransaction()
        }
    }

    suspend fun ensureSearchProjection() {
        if (!messageFtsManager.isProjectionCurrent()) rebuildAllIndexes()
    }

    /**
     * 获取指定 Master 会话的所有 Child 会话
     */
    suspend fun getChildConversations(parentConversationId: Uuid): List<Conversation> = database.withTransaction {
        conversationDAO.getChildConversations(parentConversationId.toString()).map { entity ->
            conversationEntityToConversation(entity, loadMessageNodes(entity.id))
        }
    }

    internal suspend fun getChildConversationSnapshots(
        parentConversationId: Uuid,
    ): List<ConversationAggregateSnapshot> = database.withTransaction {
        conversationDAO.getChildConversations(parentConversationId.toString()).map { entity ->
            val nodes = loadMessageNodes(entity.id)
            conversationEntityToConversation(entity, nodes).toSnapshot(
                modelContextEntries = loadModelContextEntries(entity.id, nodes),
            )
        }
    }

    suspend fun getChildConversationIds(parentConversationId: Uuid): List<Uuid> =
        conversationDAO.getChildConversationIds(parentConversationId.toString()).map(Uuid::parse)

    private fun snapshotToConversation(snapshot: ConversationAggregateSnapshot): Conversation = Conversation(
        id = snapshot.conversationId,
        assistantId = snapshot.header.assistantId,
        title = snapshot.header.title,
        messageNodes = snapshot.nodes,
        chatSuggestions = snapshot.header.chatSuggestions,
        isPinned = snapshot.header.isPinned,
        createAt = Instant.ofEpochMilli(snapshot.header.createAt),
        updateAt = Instant.ofEpochMilli(snapshot.header.updateAt),
        customSystemPrompt = snapshot.header.customSystemPrompt,
        modeInjectionIds = snapshot.header.modeInjectionIds,
        workspaceCwd = snapshot.header.workspaceCwd,
        folderId = snapshot.header.folderId,
        parentConversationId = snapshot.header.parentConversationId,
    )

    internal fun conversationToConversationEntity(conversation: Conversation): ConversationEntity {
        require(conversation.messageNodes.none { it.messages.any { message -> message.hasBase64Part() } })
        return ConversationEntity(
            id = conversation.id.toString(),
            title = conversation.title,
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
        messageNodes: List<MessageNode>,
    ): Conversation {
        return Conversation(
            id = Uuid.parse(conversationEntity.id),
            title = conversationEntity.title,
            messageNodes = messageNodes,
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

    fun getPinnedConversations(): Flow<List<ConversationListRecord>> {
        return conversationDAO
            .getPinnedConversations()
            .map { entities -> entities.map(::conversationEntityToListRecord) }
    }

    private fun conversationEntityToListRecord(entity: ConversationEntity): ConversationListRecord =
        ConversationListRecord(
            id = Uuid.parse(entity.id),
            assistantId = Uuid.parse(entity.assistantId),
            title = entity.title,
            isPinned = entity.isPinned,
            createAt = Instant.ofEpochMilli(entity.createAt),
            updateAt = Instant.ofEpochMilli(entity.updateAt),
            folderId = entity.folderId.ifEmpty { null }?.let(Uuid::parse),
        )

    private fun lightEntityToListRecord(entity: LightConversationEntity): ConversationListRecord =
        ConversationListRecord(
            id = Uuid.parse(entity.id),
            assistantId = Uuid.parse(entity.assistantId),
            title = entity.title,
            isPinned = entity.isPinned,
            createAt = Instant.ofEpochMilli(entity.createAt),
            updateAt = Instant.ofEpochMilli(entity.updateAt),
            folderId = entity.folderId.ifEmpty { null }?.let { Uuid.parse(it) },
        )

    /**
     * 模型上下文的唯一装载入口（§12.3）：先取归属本 Conversation 的行，再交给 mapper 校验
     * owner / anchor node 与 variant identity。任何不一致都抛出，不静默当作没有 context。
     */
    private suspend fun loadModelContextEntries(
        conversationId: String,
        nodes: List<MessageNode>,
    ): List<ConversationModelContextEntry> = mapModelContextEntries(
        rows = modelContextDAO.getEntriesOfConversation(conversationId),
        nodes = nodes,
        conversationId = conversationId,
    )
    private suspend fun loadMessageNodes(conversationId: String): List<MessageNode> {
        val favoriteNodeIds = favoriteDAO
            .getFavoriteNodeIdsOfConversation(conversationId)
            .mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }
            .toSet()

        return database.withTransaction {
            messageNodeDAO.getNodeHeadersOfConversation(conversationId).map { header ->
                val payload = try {
                    messageNodeDAO.readMessagesPayload(header, "conversation=$conversationId")
                } catch (error: MessagePayloadReadException) {
                    throw ConversationPayloadException(error.message ?: "invalid message payload", error)
                }
                val messages = try {
                    JsonInstant.decodeFromString<List<UIMessage>>(payload)
                } catch (error: Exception) {
                    throw ConversationPayloadException(
                        "invalid message payload: conversation=$conversationId, node=${header.id}",
                        error,
                    )
                }
                if (messages.isEmpty() || header.selectIndex !in messages.indices) {
                    throw ConversationPayloadException(
                        "invalid message node shape: conversation=$conversationId, node=${header.id}",
                    )
                }
                val nodeId = Uuid.parse(header.id)
                MessageNode(
                    id = nodeId,
                    messages = messages,
                    selectIndex = header.selectIndex,
                    isFavorite = favoriteNodeIds.contains(nodeId),
                )
            }
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

/**
 * 模型上下文行的 Repository mapper（权威方案 §5.2、§12.3）。
 *
 * 数据库 FK 只能保证 owner / anchor 两个 **node** 存在；两个 message ID 位于 message_node 的
 * messages JSON 内，无法建 FK，因此下列语义事实只能在这里校验，并且必须 fail-closed：
 *  - owner / anchor node 都属于本 Conversation（nodes 就是该 Conversation 的装载结果）；
 *  - owner message 属于 owner node 且角色是 ASSISTANT；
 *  - anchor message 属于 anchor node 且角色是 USER；
 *  - content 是本 App 支持的 Disclosure envelope（type / format / 固定形状）；
 *    装载不做 encode round-trip，逐字 canonical 由 StartTurn 写入时强制；
 *  - 同一 ownerMessageId 不承载两份不同 content。
 *
 * 不写库、不修复、不丢弃：损坏的 context 让 Conversation 无法 Ready，而不是被当作没有
 * context —— 静默降级等于凭空改变模型基线。
 */
internal fun mapModelContextEntries(
    rows: List<ConversationModelContextEntity>,
    nodes: List<MessageNode>,
    conversationId: String,
    validateContent: (String) -> Unit = { ConversationDisclosureSnapshotService.requireDurableEnvelope(it) },
): List<ConversationModelContextEntry> {
    if (rows.isEmpty()) return emptyList()
    val duplicateMessageIds = nodes.flatMap { it.messages }.groupingBy { it.id }.eachCount()
        .filterValues { it > 1 }
        .keys
    if (duplicateMessageIds.isNotEmpty()) {
        throw ConversationModelContextIntegrityException(
            "$conversationId: duplicate message identities make model-context locators ambiguous: $duplicateMessageIds",
        )
    }
    val nodesById = nodes.associateBy { it.id }
    fun uuidOf(raw: String, field: String, row: String): Uuid =
        runCatching { Uuid.parse(raw) }.getOrNull()
            ?: throw ConversationModelContextIntegrityException(
                "$conversationId/$row: $field is not a Uuid: $raw",
            )
    fun variantOf(nodeId: Uuid, messageId: Uuid, what: String, row: String): UIMessage {
        val node = nodesById[nodeId] ?: throw ConversationModelContextIntegrityException(
            "$conversationId/$row: $what node $nodeId does not belong to this conversation",
        )
        return node.messages.firstOrNull { it.id == messageId }
            ?: throw ConversationModelContextIntegrityException(
                "$conversationId/$row: $what message $messageId is not a variant of node $nodeId",
            )
    }
    val contentByOwner = HashMap<Uuid, String>()
    return rows.map { row ->
        val ownerNodeId = uuidOf(row.ownerNodeId, "owner_node_id", row.ownerMessageId)
        val ownerMessageId = uuidOf(row.ownerMessageId, "owner_message_id", row.ownerMessageId)
        val anchorNodeId = uuidOf(row.anchorNodeId, "anchor_node_id", row.ownerMessageId)
        val anchorMessageId = uuidOf(row.anchorMessageId, "anchor_message_id", row.ownerMessageId)
        val owner = variantOf(ownerNodeId, ownerMessageId, "owner", row.ownerMessageId)
        if (owner.role != MessageRole.ASSISTANT) {
            throw ConversationModelContextIntegrityException(
                "$conversationId/${row.ownerMessageId}: owner variant must be ASSISTANT, got ${owner.role}",
            )
        }
        val anchor = variantOf(anchorNodeId, anchorMessageId, "anchor", row.ownerMessageId)
        if (anchor.role != MessageRole.USER) {
            throw ConversationModelContextIntegrityException(
                "$conversationId/${row.ownerMessageId}: anchor variant must be USER, got ${anchor.role}",
            )
        }
        validateContent(row.content)
        val previous = contentByOwner.put(ownerMessageId, row.content)
        if (previous != null && previous != row.content) {
            throw ConversationModelContextIntegrityException(
                "$conversationId/${row.ownerMessageId}: two different contents for one owner",
            )
        }
        ConversationModelContextEntry(
            ownerNodeId = ownerNodeId,
            ownerMessageId = ownerMessageId,
            anchorNodeId = anchorNodeId,
            anchorMessageId = anchorMessageId,
            content = row.content,
        )
    }
}

/** durable context 与消息树不一致：该 Conversation 不得继续以 Ready 形态使用。 */
class ConversationModelContextIntegrityException(message: String) : IllegalStateException(message)
class ConversationPayloadException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

data class ConversationListRecord(
    val id: Uuid,
    val assistantId: Uuid,
    val title: String,
    val folderId: Uuid?,
    val isPinned: Boolean,
    val createAt: Instant,
    val updateAt: Instant,
)
