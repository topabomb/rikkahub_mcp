package net.weero.measix.pilot.service

import me.rerere.common.configuration.ConfigurationReference
import androidx.paging.PagingData
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import net.weero.measix.pilot.data.db.dao.LightConversationEntity
import android.util.Log
import androidx.paging.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.CancellationException
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.EnterpriseConfigurationException
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.db.fts.MessageSearchSort
import net.weero.measix.pilot.data.repository.ConversationListRecord
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.data.repository.FolderRepository
import net.weero.measix.pilot.data.model.Folder
import net.weero.measix.pilot.service.runtime.ConversationRuntimeRegistry
import net.weero.measix.pilot.service.runtime.ConversationRuntimeState
import net.weero.measix.pilot.service.runtime.ConversationAggregateSnapshot
import net.weero.measix.pilot.service.runtime.ConversationRuntimeSnapshot
import net.weero.measix.pilot.service.runtime.ConversationPresentation
import net.weero.measix.pilot.service.runtime.ConversationPresentationSnapshot
import net.weero.measix.pilot.service.runtime.toPresentationSnapshot
import net.weero.measix.pilot.service.runtime.TurnLivePhase
import java.time.Instant
import kotlin.uuid.Uuid

/** Stable list/read model; message trees never cross the query port for list rendering. */
data class ConversationSummary(
    val id: Uuid,
    val assistantId: ConfigurationReference,
    val title: String,
    val folderId: Uuid?,
    val isPinned: Boolean,
    val createAt: Instant,
    val updateAt: Instant,
)

/** Snapshot and process-local presentation observed from one Runtime projection. */
data class ConversationUiModel(
    val snapshot: ConversationPresentationSnapshot,
    val presentation: ConversationPresentation,
    val attachmentPreviews: Map<String, String> = emptyMap(),
) {
    val turnFeedback: ConversationTurnFeedback? = projectConversationTurnFeedback(snapshot, presentation)
}

sealed interface ConversationReadState {
    data object Loading : ConversationReadState
    data class Ready(val snapshot: ConversationPresentationSnapshot) : ConversationReadState
    data object Missing : ConversationReadState
    data class Failed(val error: Throwable) : ConversationReadState
}

enum class ConversationActivity {
    RESPONSE_GENERATION,
    APPROVAL_REQUIRED,
    TITLE_GENERATION,
}

private fun ConversationListRecord.toSummary() = ConversationSummary(
    id = id,
    assistantId = assistantId,
    title = title,
    folderId = folderId,
    isPinned = isPinned,
    createAt = createAt,
    updateAt = updateAt,
)

/** UI read port: persisted/resident 选择被封装在 service 边界内。 */
class ConversationQueryService internal constructor(
    private val repository: ConversationRepository,
    private val runtimeRegistry: ConversationRuntimeRegistry,
    private val folderRepository: FolderRepository,
    private val titleCoordinator: ConversationTitleCoordinator,
    private val attachmentPreviewProjector: ConversationAttachmentPreviewProjector,
    private val sessions: EnterpriseSessionController,
    private val recoveryGate: ApplicationRecoveryGate,
) {
    suspend fun captureCurrentAccess(): RealmAccess {
        recoveryGate.awaitReady()
        return sessions.captureSelectedRealmAccess()
    }

    fun observeCurrentAccess(): Flow<RealmAccess?> = flow {
        recoveryGate.awaitReady()
        emitAll(sessions.observeSelectedRealmAccess())
    }

    private fun <T> selectedRows(empty: T, query: (RealmAccess) -> Flow<T>): Flow<T> =
        observeCurrentAccess().flatMapLatest { access ->
            if (access == null) flowOf(empty) else query(access)
                .map { rows -> sessions.withSelectedRealmAccess(access) { rows } }
                .onStart { emit(empty) }
                .catch { error ->
                    if (error is CancellationException) throw error
                    if (error !is EnterpriseConfigurationException) {
                        Log.e("ConversationQuery", "Directory query failed", error)
                    }
                    emit(empty)
                }
        }

    private suspend fun <T> read(access: RealmAccess, query: suspend () -> T): T {
        recoveryGate.awaitReady()
        return sessions.withRealmAccess(access, query)
    }

    fun observeConversation(conversationId: Uuid): Flow<ConversationReadState> =
        runtimeRegistry.observeRuntimeState(conversationId).flatMapLatest { state ->
            when (state) {
                is ConversationRuntimeState.Draft -> state.runtime.snapshot.map { it.toPresentationSnapshot() }.map(ConversationReadState::Ready)
                is ConversationRuntimeState.Ready -> state.runtime.snapshot.map { it.toPresentationSnapshot() }.map(ConversationReadState::Ready)
                ConversationRuntimeState.Loading -> flowOf(ConversationReadState.Loading)
                ConversationRuntimeState.Missing -> flowOf(ConversationReadState.Missing)
                is ConversationRuntimeState.Failed -> flowOf(ConversationReadState.Failed(state.error))
            }
        }

    fun turnPresentation(conversationId: Uuid): Flow<ConversationPresentation> =
        runtimeRegistry.getTurnPresentationFlow(conversationId)

    fun conversationUiModel(conversationId: Uuid): Flow<ConversationUiModel> =
        runtimeRegistry.getConversationUiFlow(conversationId)
            .combine(attachmentPreviewProjector.lifecycleChanges()) { joined, _ -> joined }
            .mapLatest { (aggregate, presentation) ->
                val snapshot = aggregate.toPresentationSnapshot()
                ConversationUiModel(
                    snapshot = snapshot,
                    presentation = presentation,
                    attachmentPreviews = attachmentPreviewProjector.project(snapshot),
                )
            }

    suspend fun attachmentPreviews(snapshot: ConversationPresentationSnapshot): Map<String, String> =
        attachmentPreviewProjector.project(snapshot)

    /** Re-emits query models when ArtifactStore invalidates or removes a referenced payload. */
    fun attachmentPreviewChanges(): Flow<Unit> = attachmentPreviewProjector.lifecycleChanges()

    fun ttsQueueSessionId(conversationId: Uuid): String? =
        runtimeRegistry.findRuntime(conversationId)?.peekTtsQueueSessionId()

    fun conversationActivities(): Flow<Map<Uuid, Set<ConversationActivity>>> = combine(
        runtimeRegistry.getConversationTurnPresentations(),
        titleCoordinator.phases.map { phases ->
            phases.filterValues { it == ConversationTitlePhase.MODEL_GENERATING }.keys
        },
    ) { turnPresentations, titleGenerationIds ->
        mergeConversationActivities(turnPresentations, titleGenerationIds)
    }

    fun unfiledPaging(assistantId: ConfigurationReference): Flow<PagingData<ConversationSummary>> =
        observeCurrentAccess().flatMapLatest { access ->
            if (access == null) flowOf(PagingData.empty()) else paging(access) {
                repository.unfiledPagingSource(access.scope, assistantId)
            }
        }

    fun folderPaging(folderId: Uuid): Flow<PagingData<ConversationSummary>> =
        observeCurrentAccess().flatMapLatest { access ->
            if (access == null) flowOf(PagingData.empty()) else paging(access) {
                repository.folderPagingSource(access.scope, folderId)
            }
        }

    private fun paging(
        access: RealmAccess,
        source: () -> PagingSource<Int, LightConversationEntity>,
    ): Flow<PagingData<ConversationSummary>> = flow {
        val owner = Any()
        val active = mutableSetOf<PagingSource<Int, LightConversationEntity>>()
        var closed = false
        try {
            emit(PagingData.empty())
            val pager = Pager(PagingConfig(pageSize = 20, initialLoadSize = 40, enablePlaceholders = false)) {
                synchronized(owner) {
                    SelectedRealmPagingSource(source(), sessions, access).also { page ->
                        if (closed) page.invalidate() else {
                            active.add(page)
                            page.registerInvalidatedCallback { synchronized(owner) { active.remove(page) } }
                        }
                    }
                }
            }
            emitAll(pager.flow.map { data ->
                data.map { row ->
                    ConversationSummary(
                        id = Uuid.parse(row.id),
                        assistantId = ConfigurationReference.parse(row.assistantId),
                        title = row.title,
                        folderId = row.folderId.takeIf(String::isNotEmpty)?.let(Uuid::parse),
                        isPinned = row.isPinned,
                        createAt = Instant.ofEpochMilli(row.createAt),
                        updateAt = Instant.ofEpochMilli(row.updateAt),
                    )
                }
            })
        } finally {
            synchronized(owner) {
                closed = true
                active.toList().forEach { it.invalidate() }
                active.clear()
            }
        }
    }

    fun conversationsOfAssistant(assistantId: ConfigurationReference): Flow<List<ConversationSummary>> =
        selectedRows(emptyList()) { access ->
            repository.getConversationsOfAssistant(access.scope, assistantId).map { list -> list.map { it.toSummary() } }
        }

    fun pinnedConversations(): Flow<List<ConversationSummary>> =
        selectedRows(emptyList()) { access ->
            repository.getPinnedConversations(access.scope).map { list -> list.map { it.toSummary() } }
        }

    fun foldersOfAssistant(assistantId: ConfigurationReference): Flow<List<Folder>> =
        selectedRows(emptyList()) { access -> folderRepository.getFoldersOfAssistant(access.scope, assistantId) }

    /**
     * internal 聚合读端口：只有 command / turn planning 需要它（含 model context）。
     * UI 读端口是 [observeConversation] 与 [conversationUiModel]，它们只给 presentation。
     */
    internal suspend fun aggregateSnapshot(conversationId: Uuid): ConversationAggregateSnapshot? =
        runtimeRegistry.findRuntime(conversationId)?.snapshot?.value?.durable
            ?: repository.getConversationSnapshotById(conversationId)

    internal fun residentRuntimeSnapshot(conversationId: Uuid): StateFlow<ConversationRuntimeSnapshot>? =
        runtimeRegistry.findRuntime(conversationId)?.snapshot

    suspend fun count(): Int {
        val access = captureCurrentAccess()
        return read(access) { repository.countConversations(access.scope) }
    }

    suspend fun searchMessages(access: RealmAccess, keyword: String, sort: MessageSearchSort) =
        read(access) { repository.searchMessages(access.scope, keyword, sort) }

    suspend fun recentConversations(
        access: RealmAccess,
        assistantId: ConfigurationReference,
        limit: Int,
    ): List<ConversationSummary> =
        read(access) { repository.getRecentConversationRecords(access.scope, assistantId, limit).map { it.toSummary() } }

    suspend fun searchMessagesOfAssistant(
        access: RealmAccess,
        assistantId: ConfigurationReference,
        keyword: String,
        sort: MessageSearchSort,
    ) = read(access) { repository.searchMessagesOfAssistant(access.scope, assistantId, keyword, sort) }
}

internal fun mergeConversationActivities(
    turnPresentations: Map<Uuid, ConversationPresentation>,
    titleGenerationIds: Set<Uuid>,
): Map<Uuid, Set<ConversationActivity>> =
    (turnPresentations.keys + titleGenerationIds).associateWith { conversationId ->
        buildSet {
            when (turnPresentations[conversationId]?.phase) {
                TurnLivePhase.PREPARING,
                TurnLivePhase.MODEL_WAITING,
                TurnLivePhase.MODEL_STREAMING,
                TurnLivePhase.TOOL_PREPARING,
                TurnLivePhase.TOOL_EXECUTING,
                TurnLivePhase.STOPPING,
                -> add(ConversationActivity.RESPONSE_GENERATION)
                TurnLivePhase.AWAITING_USER -> add(ConversationActivity.APPROVAL_REQUIRED)
                null -> Unit
            }
            if (conversationId in titleGenerationIds) add(ConversationActivity.TITLE_GENERATION)
        }
    }

data class ConversationDetailRead(
    val initial: ConversationPresentationSnapshot,
    val updates: Flow<ConversationPresentationSnapshot>?,
)

class SubAssistantDetailReader(private val queryService: ConversationQueryService) {
    suspend fun read(conversationId: Uuid): ConversationDetailRead? {
        val resident = queryService.residentRuntimeSnapshot(conversationId)
        val initial = resident?.value
            ?: queryService.aggregateSnapshot(conversationId)?.let { ConversationRuntimeSnapshot(it, null) }
            ?: return null
        return ConversationDetailRead(
            initial = initial.toPresentationSnapshot(),
            updates = resident?.map { it.toPresentationSnapshot() },
        )
    }

    suspend fun attachmentPreviews(snapshot: ConversationPresentationSnapshot): Map<String, String> =
        queryService.attachmentPreviews(snapshot)

    fun attachmentPreviewChanges(): Flow<Unit> = queryService.attachmentPreviewChanges()

}
