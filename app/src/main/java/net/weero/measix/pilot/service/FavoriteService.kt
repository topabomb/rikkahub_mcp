package net.weero.measix.pilot.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import net.weero.measix.pilot.data.db.entity.FavoriteEntity
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.data.favorite.NodeFavoriteAdapter
import net.weero.measix.pilot.data.model.FavoriteType
import net.weero.measix.pilot.data.repository.FavoriteRepository
import kotlin.uuid.Uuid

class NodeFavoriteItem internal constructor(
    val id: String,
    val refKey: String,
    val conversationId: Uuid,
    val nodeId: Uuid,
    val conversationTitle: String,
    val preview: String,
    val createdAt: Long,
    internal val target: ConversationCommandTarget,
)

/** Favorite writes retain their original selection and serialize with the durable conversation owner. */
class FavoriteService internal constructor(
    private val repository: FavoriteRepository,
    private val recoveryGate: ApplicationRecoveryGate,
    private val sessions: EnterpriseSessionController,
    private val conversations: ConversationApplicationService,
) {
    class RestoreToken internal constructor(
        internal val entity: FavoriteEntity,
        internal val target: ConversationCommandTarget,
        internal val nodeId: Uuid,
    ) {
        internal var consumed = false
            private set
        internal fun complete() { consumed = true }
    }

    fun observeNodeFavorites(): Flow<List<NodeFavoriteItem>> = flow {
        recoveryGate.awaitReady()
        emitAll(sessions.observeSelectedRealmSelection().flatMapLatest { selection ->
            if (selection == null) flowOf(emptyList()) else observeSelection(selection)
        })
    }

    private fun observeSelection(selection: RealmSelection): Flow<List<NodeFavoriteItem>> =
        repository.listByType(selection.access.scope, FavoriteType.NODE).map { favorites ->
            sessions.withSelectedRealmSelection(selection) {
                favorites.mapNotNull { entity ->
                    check(entity.scope == selection.access.scope) { "favorite_scope_mismatch" }
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
                        target = ConversationCommandTarget(ref.conversationId, selection) {},
                    )
                }
            }
        }.onStart { emit(emptyList()) }.catch { error ->
            if (error is CancellationException) throw error
            android.util.Log.e("FavoriteService", "Favorite directory unavailable", error)
            emit(emptyList())
        }

    fun observeNodeIds(target: ConversationCommandTarget): Flow<Set<Uuid>> =
        observeNodeFavorites().map { items ->
            target.requireOpen()
            items.filter { it.target.selection == target.selection && it.conversationId == target.conversationId }
                .map { it.nodeId }.toSet()
        }.catch { error ->
            if (error is CancellationException) throw error
            emit(emptySet())
        }

    suspend fun toggleNode(target: ConversationCommandTarget, nodeId: Uuid) =
        conversations.withFavoriteNode(target, nodeId) { node ->
            val key = NodeFavoriteAdapter.buildRefKey(node)
            val existing = repository.getByRefKey(node.scope, key)
            sessions.requirePublishedSelection(target.selection)
            if (existing != null) repository.deleteByRefKey(node.scope, key)
            else repository.upsert(NodeFavoriteAdapter.buildFavoriteEntity(node))
            Unit
        }

    suspend fun openRequest(item: NodeFavoriteItem): ConversationOpenRequest.OpenExisting =
        conversations.withFavoriteNode(item.target, item.nodeId) {
            ConversationOpenRequest.OpenExisting(item.conversationId, item.target.selection.access)
        }

    suspend fun removeForUndo(item: NodeFavoriteItem): RestoreToken? =
        conversations.withFavoriteNode(item.target, item.nodeId) { node ->
            val key = NodeFavoriteAdapter.buildRefKey(node)
            val entity = repository.getByRefKey(node.scope, key) ?: return@withFavoriteNode null
            check(entity.id == item.id) { "favorite_identity_changed" }
            sessions.requirePublishedSelection(item.target.selection)
            check(repository.deleteByRefKey(node.scope, key) == 1) { "favorite_missing" }
            RestoreToken(entity, item.target, node.nodeId)
        }

    suspend fun restore(token: RestoreToken) {
        conversations.withFavoriteNode(token.target, token.nodeId) { node ->
            check(node.scope == token.entity.scope) { "favorite_scope_mismatch" }
            check(!token.consumed) { "favorite_restore_token_consumed" }
            // A later favorite operation wins; undo never overwrites a newly created row.
            val existing = repository.getByRefKey(node.scope, token.entity.refKey)
            sessions.requirePublishedSelection(token.target.selection)
            if (existing == null) {
                repository.upsert(NodeFavoriteAdapter.buildFavoriteEntity(node, token.entity))
            }
            token.complete()
        }
    }
}
