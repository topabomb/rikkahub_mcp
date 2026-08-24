package net.weero.measix.pilot.service

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.weero.measix.pilot.data.db.entity.FavoriteEntity
import net.weero.measix.pilot.data.favorite.NodeFavoriteAdapter
import net.weero.measix.pilot.data.model.FavoriteType
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.model.NodeFavoriteTarget
import net.weero.measix.pilot.data.repository.FavoriteRepository
import kotlin.uuid.Uuid

data class NodeFavoriteItem(
    val id: String,
    val refKey: String,
    val conversationId: Uuid,
    val nodeId: Uuid,
    val conversationTitle: String,
    val preview: String,
    val createdAt: Long,
)

/** Favorite read/write port; DAO entities remain behind the service boundary. */
class FavoriteService(
    private val repository: FavoriteRepository,
    private val recoveryGate: ApplicationRecoveryGate,
) {
    class RestoreToken internal constructor(internal val entity: FavoriteEntity)
    private val mutationStripes = Array(32) { Mutex() }

    fun observeNodeFavorites(): Flow<List<NodeFavoriteItem>> =
        repository.listByType(FavoriteType.NODE).map { favorites ->
            favorites.mapNotNull { entity ->
                val ref = NodeFavoriteAdapter.decodeRef(entity) ?: return@mapNotNull null
                val meta = NodeFavoriteAdapter.decodeMeta(entity)
                NodeFavoriteItem(
                    id = entity.id,
                    refKey = entity.refKey,
                    conversationId = ref.conversationId,
                    nodeId = ref.nodeId,
                    conversationTitle = meta?.title.orEmpty(),
                    preview = meta?.previewText.orEmpty(),
                    createdAt = entity.createdAt,
                )
            }
        }

    fun observeNodeIds(conversationId: Uuid): Flow<Set<Uuid>> =
        observeNodeFavorites().map { items ->
            items.filter { it.conversationId == conversationId }.mapTo(mutableSetOf()) { it.nodeId }
        }

    suspend fun toggleNode(conversationId: Uuid, conversationTitle: String, node: MessageNode) {
        recoveryGate.awaitReady()
        val refKey = NodeFavoriteAdapter.buildRefKey(conversationId.toString(), node.id.toString())
        mutexFor(refKey).withLock {
            if (repository.existsByRefKey(refKey)) {
                repository.deleteByRefKey(refKey)
            } else {
                repository.addNodeFavorite(
                    NodeFavoriteTarget(
                        conversationId = conversationId,
                        conversationTitle = conversationTitle,
                        nodeId = node.id,
                        node = node,
                    )
                )
            }
        }
    }

    suspend fun removeForUndo(refKey: String): RestoreToken? {
        recoveryGate.awaitReady()
        return mutexFor(refKey).withLock {
            val entity = repository.getByRefKey(refKey) ?: return@withLock null
            repository.deleteByRefKey(refKey)
            RestoreToken(entity)
        }
    }

    suspend fun restore(token: RestoreToken) {
        recoveryGate.awaitReady()
        mutexFor(token.entity.refKey).withLock { repository.upsert(token.entity) }
    }

    private fun mutexFor(refKey: String): Mutex =
        mutationStripes[(refKey.hashCode() and Int.MAX_VALUE) % mutationStripes.size]
}
