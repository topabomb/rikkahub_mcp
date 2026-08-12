package net.weero.measix.pilot.service

import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.model.Conversation
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "ConversationSessionRegistry"

/**
 * 从 ChatService 抽取的会话生命周期管理，供 Master 和 Child 共用。
 * 管理 Session/Job/StateFlow 的创建、引用计数和空闲清理。
 */
class ConversationSessionRegistry(
    private val appScope: net.weero.measix.pilot.AppScope,
    private val settingsStore: SettingsStore,
) {
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    private val _sessionsVersion = MutableStateFlow(0L)

    fun getOrCreateSession(conversationId: Uuid): ConversationSession {
        return sessions.computeIfAbsent(conversationId) { id ->
            val settings = settingsStore.settingsFlow.value
            val assistantId = settings.assistants.firstOrNull()?.id ?: Uuid.random()
            ConversationSession(
                id = id,
                initial = Conversation.ofId(
                    id = id,
                    assistantId = assistantId
                ),
                scope = appScope,
                onIdle = { removeSession(it) }
            ).also {
                _sessionsVersion.value++
                Log.i(TAG, "createSession: $id (total: ${sessions.size + 1})")
            }
        }
    }

    fun getOrCreateSessionWithConversation(conversationId: Uuid, conversation: Conversation): ConversationSession {
        return sessions.computeIfAbsent(conversationId) { id ->
            ConversationSession(
                id = id,
                initial = conversation,
                scope = appScope,
                onIdle = { removeSession(it) }
            ).also {
                _sessionsVersion.value++
                Log.i(TAG, "createSession with conversation: $id (total: ${sessions.size + 1})")
            }
        }
    }

    fun getSession(conversationId: Uuid): ConversationSession? = sessions[conversationId]

    private fun removeSession(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        if (session.isInUse) {
            Log.d(TAG, "removeSession: skipped $conversationId (still in use)")
            return
        }
        if (sessions.remove(conversationId, session)) {
            session.cleanup()
            _sessionsVersion.value++
            Log.i(TAG, "removeSession: $conversationId (remaining: ${sessions.size})")
        }
    }

    fun addConversationReference(conversationId: Uuid) {
        getOrCreateSession(conversationId).acquire()
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessions[conversationId]?.release()
    }

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> {
        return getOrCreateSession(conversationId).state
    }

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> {
        val session = sessions[conversationId] ?: return flowOf(null)
        return session.generationJob
    }

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> {
        val session = sessions[conversationId] ?: return MutableStateFlow(null)
        return session.processingStatus
    }

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> {
        return _sessionsVersion.flatMapLatest {
            val currentSessions = sessions.values.toList()
            if (currentSessions.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(currentSessions.map { session ->
                    session.generationJob.map { job -> session.id to job }
                }) { pairs ->
                    pairs.filter { it.second != null }.toMap()
                }
            }
        }
    }

    fun updateConversationState(conversationId: Uuid, conversation: Conversation) {
        if (conversation.id != conversationId) return
        // 使用 getOrCreateSessionWithConversation 避免先创建空 Session 再覆盖，
        // 符合设计文档 §7.1 "不得先创建空 Session 再遮蔽 Room 快照"的要求。
        val session = getOrCreateSessionWithConversation(conversationId, conversation)
        session.state.value = conversation
    }

    fun getAllActiveSessionIds(): Set<Uuid> = sessions.keys.toSet()

    fun getSessionsSnapshot(): List<ConversationSession> = sessions.values.toList()

    /** Removes a conversation that has already been durably deleted. */
    fun evictSession(conversationId: Uuid) {
        sessions.remove(conversationId)?.let { session ->
            session.cleanup()
            _sessionsVersion.value++
        }
    }

    /** 取消并等待指定 Assistant 的普通会话生成，避免清理后迟到 checkpoint 重新写回会话。 */
    suspend fun cancelGenerationsForAssistant(assistantId: Uuid, reason: String) {
        val jobs = sessions.values
            .filter { it.state.value.assistantId == assistantId }
            .mapNotNull { it.generationJob.value }
            .distinct()
        jobs.forEach { it.cancel(reason) }
        jobs.joinAll()
    }

    fun cleanup() = runCatching {
        sessions.values.forEach { it.cleanup() }
        sessions.clear()
    }
}
