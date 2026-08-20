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
import net.weero.measix.pilot.data.db.entity.ConversationEntity
import net.weero.measix.pilot.data.db.entity.MessageNodeEntity
import net.weero.measix.pilot.data.files.FilesManager
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.model.collectFileReferenceTokens
import net.weero.measix.pilot.utils.JsonInstant
import java.time.Instant
import kotlin.uuid.Uuid

class ConversationRepository(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val favoriteDAO: FavoriteDAO,
    private val database: AppDatabase,
    private val filesManager: FilesManager,
    private val messageFtsManager: MessageFtsManager,
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

    fun searchConversations(titleKeyword: String): Flow<List<Conversation>> {
        return conversationDAO
            .searchConversations(titleKeyword)
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
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

    fun searchConversationsOfAssistant(assistantId: Uuid, titleKeyword: String): Flow<List<Conversation>> {
        return conversationDAO
            .searchConversationsOfAssistant(assistantId.toString(), titleKeyword)
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
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
    }

    /** Updates one Master and prunes/truncates its Child tree in a single Room transaction. */
    suspend fun updateConversationTree(
        master: Conversation,
        retainedChildren: List<Conversation>,
        deletedChildren: List<Conversation>,
    ) {
        require(master.parentConversationId == null)
        require(retainedChildren.all { it.parentConversationId == master.id })
        val retainedIds = retainedChildren.mapTo(mutableSetOf()) { it.id }
        require(deletedChildren.none { it.id in retainedIds })

        val oldMaster = getConversationById(master.id)
            ?: error("Master Conversation ${master.id} does not exist")
        val oldChildren = getChildConversations(master.id)
        val oldChildIds = oldChildren.mapTo(mutableSetOf()) { it.id }
        require(retainedChildren.map { it.id }.distinct().size == retainedChildren.size)
        require(deletedChildren.map { it.id }.distinct().size == deletedChildren.size)
        require(retainedIds + deletedChildren.map { it.id } == oldChildIds) {
            "Retained and deleted Child sets must cover the persisted Master tree exactly"
        }
        val oldTreeFiles = (oldChildren + oldMaster).flatMap { it.files }.toSet()
        val excludedIds = buildSet {
            add(master.id)
            addAll(oldChildIds)
        }

        database.withTransaction {
            conversationDAO.update(conversationToConversationEntity(master))
            messageNodeDAO.deleteByConversation(master.id.toString())
            saveMessageNodes(master.id.toString(), master.messageNodes)
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
        messageFtsManager.indexConversation(master)
        deletedChildren.forEach { messageFtsManager.deleteConversation(it.id.toString()) }
        deleteUnreferencedChatFiles(
            previousFiles = oldTreeFiles,
            retainedConversations = retainedChildren + master,
            excludedConversationIds = excludedIds,
        )
    }

    suspend fun updateConversation(conversation: Conversation) {
        database.withTransaction {
            requireValidParent(conversation)
            conversationDAO.update(
                conversationToConversationEntity(conversation)
            )
            // 删除旧的节点，插入新的节点
            messageNodeDAO.deleteByConversation(conversation.id.toString())
            saveMessageNodes(conversation.id.toString(), conversation.messageNodes)
        }
        // Child Conversation 不进入 FTS 索引，只通过调用卡片详情页访问
        if (conversation.parentConversationId == null) {
            messageFtsManager.indexConversation(conversation)
        }
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
        val safeFilesToDelete = findUnsharedFilesForDeletion(conversationsToDelete)

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
        filesManager.deleteChatFiles(safeFilesToDelete)
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
        val safeFilesToDelete = findUnsharedFilesForDeletion(children)
        database.withTransaction {
            children.forEach { child ->
                favoriteDAO.deleteNodeFavoritesOfConversation(child.id.toString())
            }
            conversationDAO.deleteChildConversations(parentConversationId.toString())
        }
        children.forEach { child ->
            messageFtsManager.deleteConversation(child.id.toString())
        }
        filesManager.deleteChatFiles(safeFilesToDelete)
    }

    /** Returns only local files no conversation outside [conversationsToDelete] still references. */
    private suspend fun findUnsharedFilesForDeletion(
        conversationsToDelete: Collection<Conversation>,
    ): List<android.net.Uri> {
        if (conversationsToDelete.isEmpty()) return emptyList()
        val deletedIds = conversationsToDelete.mapTo(mutableSetOf()) { it.id }
        val candidates = conversationsToDelete.flatMap { it.files }.toSet()
        return findUnsharedFileUris(candidates, deletedIds)
    }

    /**
     * 对每个候选 file:// URI 用 LIKE 探测其他会话的 messages JSON，
     * 命中后再反序列化该节点校验，避免为文件清理加载所有会话及消息。
     *
     * 除 file:// URL 外，也探测 filesDir 相对路径 token：Master 卡片通过
     * sub_assistant_call.artifacts[].artifact / generate_image 的 "artifact" metadata
     * 引用文件（不出现 file:// URL），这些引用同样阻止删除。
     */
    private suspend fun findUnsharedFileUris(
        candidates: Set<android.net.Uri>,
        excludedConversationIds: Set<Uuid>,
    ): List<android.net.Uri> {
        if (candidates.isEmpty()) return emptyList()
        val excludedIds = excludedConversationIds.map { it.toString() }
        if (excludedIds.isEmpty()) return emptyList()
        return candidates.filter { uri ->
            val tokens = fileReferenceTokensFor(uri)
            val hits = tokens.flatMap { token ->
                val needle = ConversationFileReferences.likeNeedleForToken(token)
                messageNodeDAO.findMessagesJsonContaining(excludedIds, needle)
            }.distinct()
            !ConversationFileReferences.isFileRetained(tokens, hits, JsonInstant)
        }
    }

    /** 候选 URI 的引用 token 集合：完整 URL + filesDir 相对路径（与 metadata 存储形态一致）。 */
    private fun fileReferenceTokensFor(uri: android.net.Uri): Set<String> {
        val url = uri.toString()
        val relative = runCatching { filesManager.getRelativePathForUri(uri) }.getOrNull()
        return if (relative != null && relative != url) setOf(url, relative) else setOf(url)
    }

    /**
     * 获取所有 Child 会话 ID（用于 recovery 时识别 orphan）
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

    /**
     * Deletes local files that [previousFiles] contained and no retained conversation still
     * references, including Tool metadata artifact tokens. Prefer keeping a file when retain
     * checks cannot decode a hit.
     */
    suspend fun deleteUnreferencedChatFiles(
        previousFiles: Set<android.net.Uri>,
        retainedConversations: Collection<Conversation>,
        excludedConversationIds: Set<Uuid>,
    ) {
        if (previousFiles.isEmpty()) return
        val retainedFiles = retainedConversations.flatMap { it.files }.toSet()
        val retainedReferenceTokens = retainedConversations
            .flatMap { conversation -> conversation.messageNodes.flatMap { node -> node.messages } }
            .let { messages -> messages.collectFileReferenceTokens() }
        val deletionCandidates = (previousFiles - retainedFiles).filter { uri ->
            fileReferenceTokensFor(uri).none { it in retainedReferenceTokens }
        }.toSet()
        val safeFilesToDelete = findUnsharedFileUris(deletionCandidates, excludedConversationIds)
        if (safeFilesToDelete.isNotEmpty()) {
            filesManager.deleteChatFiles(safeFilesToDelete)
        }
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
