package net.weero.measix.pilot.service

import androidx.paging.PagingData
import androidx.paging.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import net.weero.measix.pilot.data.db.fts.MessageSearchSort
import net.weero.measix.pilot.data.repository.ConversationListRecord
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.data.repository.FolderRepository
import net.weero.measix.pilot.data.model.Folder
import net.weero.measix.pilot.service.runtime.ActiveContextCache
import net.weero.measix.pilot.service.runtime.ConversationRuntimeRegistry
import net.weero.measix.pilot.service.runtime.ConversationRuntimeState
import net.weero.measix.pilot.service.runtime.ConversationSnapshot
import net.weero.measix.pilot.service.runtime.ConversationPresentation
import net.weero.measix.pilot.service.runtime.ConversationTurnPhase
import net.weero.measix.pilot.service.runtime.activeContextCache
import net.weero.measix.pilot.service.runtime.toSnapshot
import java.time.Instant
import kotlin.uuid.Uuid

/** Stable list/read model; message trees never cross the query port for list rendering. */
data class ConversationSummary(
    val id: Uuid,
    val assistantId: Uuid,
    val title: String,
    val folderId: Uuid?,
    val isPinned: Boolean,
    val createAt: Instant,
    val updateAt: Instant,
)

/** Snapshot and process-local presentation observed from one Runtime projection. */
data class ConversationUiModel(
    val snapshot: ConversationSnapshot,
    val presentation: ConversationPresentation,
    val attachmentPreviews: Map<String, String> = emptyMap(),
)

sealed interface ConversationReadState {
    data object Loading : ConversationReadState
    data class Ready(val snapshot: ConversationSnapshot) : ConversationReadState
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
class ConversationQueryService(
    private val repository: ConversationRepository,
    private val runtimeRegistry: ConversationRuntimeRegistry,
    private val folderRepository: FolderRepository,
    private val titleCoordinator: ConversationTitleCoordinator,
    private val attachmentPreviewProjector: ConversationAttachmentPreviewProjector,
) {
    fun observeConversation(conversationId: Uuid): Flow<ConversationReadState> =
        runtimeRegistry.observeRuntimeState(conversationId).flatMapLatest { state ->
            when (state) {
                is ConversationRuntimeState.Draft -> state.runtime.snapshot.map(ConversationReadState::Ready)
                is ConversationRuntimeState.Ready -> state.runtime.snapshot.map(ConversationReadState::Ready)
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
            .mapLatest { (snapshot, presentation) ->
                ConversationUiModel(
                    snapshot = snapshot,
                    presentation = presentation,
                    attachmentPreviews = attachmentPreviewProjector.project(snapshot),
                )
            }

    suspend fun attachmentPreviews(snapshot: ConversationSnapshot): Map<String, String> =
        attachmentPreviewProjector.project(snapshot)

    /** Re-emits query models when ArtifactStore invalidates or removes a referenced payload. */
    fun attachmentPreviewChanges(): Flow<Unit> = attachmentPreviewProjector.lifecycleChanges()

    fun activeContextCache(snapshot: ConversationSnapshot): ActiveContextCache? = snapshot.activeContextCache()

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

    fun unfiledPaging(assistantId: Uuid): Flow<PagingData<ConversationSummary>> =
        repository.getUnfiledConversationsOfAssistantPaging(assistantId).map { paging -> paging.map { it.toSummary() } }

    fun folderPaging(folderId: Uuid): Flow<PagingData<ConversationSummary>> =
        repository.getConversationsOfFolderPaging(folderId).map { paging -> paging.map { it.toSummary() } }

    fun conversationsOfAssistant(assistantId: Uuid): Flow<List<ConversationSummary>> =
        repository.getConversationsOfAssistant(assistantId).map { list -> list.map { it.toSummary() } }

    fun pinnedConversations(): Flow<List<ConversationSummary>> =
        repository.getPinnedConversations().map { list -> list.map { it.toSummary() } }

    fun foldersOfAssistant(assistantId: Uuid): Flow<List<Folder>> =
        folderRepository.getFoldersOfAssistant(assistantId)

    suspend fun snapshot(conversationId: Uuid): ConversationSnapshot? =
        runtimeRegistry.findRuntime(conversationId)?.snapshot?.value
            ?: repository.getConversationById(conversationId)?.toSnapshot()

    internal fun residentSnapshot(conversationId: Uuid): StateFlow<ConversationSnapshot>? =
        runtimeRegistry.findRuntime(conversationId)?.snapshot

    suspend fun count(): Int = repository.countConversations()

    suspend fun searchMessages(keyword: String, sort: MessageSearchSort) =
        repository.searchMessages(keyword, sort)

    suspend fun recentConversations(assistantId: Uuid, limit: Int): List<ConversationSummary> =
        repository.getRecentConversationRecords(assistantId, limit).map { it.toSummary() }

    suspend fun searchMessagesOfAssistant(
        assistantId: Uuid,
        keyword: String,
        sort: MessageSearchSort,
    ) = repository.searchMessagesOfAssistant(assistantId, keyword, sort)
}

internal fun mergeConversationActivities(
    turnPresentations: Map<Uuid, ConversationPresentation>,
    titleGenerationIds: Set<Uuid>,
): Map<Uuid, Set<ConversationActivity>> =
    (turnPresentations.keys + titleGenerationIds).associateWith { conversationId ->
        buildSet {
            when (turnPresentations[conversationId]?.phase) {
                ConversationTurnPhase.GENERATING,
                ConversationTurnPhase.PREPARING,
                ConversationTurnPhase.STOPPING,
                -> add(ConversationActivity.RESPONSE_GENERATION)
                ConversationTurnPhase.AWAITING_APPROVAL -> add(ConversationActivity.APPROVAL_REQUIRED)
                ConversationTurnPhase.IDLE,
                null,
                -> Unit
            }
            if (conversationId in titleGenerationIds) add(ConversationActivity.TITLE_GENERATION)
        }
    }

data class ConversationDetailRead(
    val initial: ConversationSnapshot,
    val updates: Flow<ConversationSnapshot>?,
)

class SubAssistantDetailReader(private val queryService: ConversationQueryService) {
    suspend fun read(conversationId: Uuid): ConversationDetailRead? {
        val resident = queryService.residentSnapshot(conversationId)
        val initial = resident?.value ?: queryService.snapshot(conversationId) ?: return null
        return ConversationDetailRead(initial = initial, updates = resident)
    }

    suspend fun attachmentPreviews(snapshot: ConversationSnapshot): Map<String, String> =
        queryService.attachmentPreviews(snapshot)

    fun attachmentPreviewChanges(): Flow<Unit> = queryService.attachmentPreviewChanges()

    fun activeContextCache(snapshot: ConversationSnapshot): ActiveContextCache? = queryService.activeContextCache(snapshot)
}
