package net.weero.measix.pilot.service.runtime

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
import net.weero.measix.pilot.data.repository.ConversationRepository
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "ConversationRuntimeRegistry"

/**
 * 会话运行时生命周期管理。
 * 管理 Runtime/Job/StateFlow 的创建、引用计数和空闲清理。
 * idle 判定并入 [ConversationRuntime.isWriteInFlight]（写通道占用期间不回收）。
 */
class ConversationRuntimeRegistry(
    private val appScope: net.weero.measix.pilot.AppScope,
    private val settingsStore: SettingsStore,
    private val repository: ConversationRepository,
) {
    private val runtimes = ConcurrentHashMap<Uuid, ConversationRuntime>()
    private val _runtimesVersion = MutableStateFlow(0L)

    fun getOrCreateSession(conversationId: Uuid): ConversationRuntime {
        return runtimes.computeIfAbsent(conversationId) { id ->
            val settings = settingsStore.settingsFlow.value
            val assistantId = settings.assistants.firstOrNull()?.id ?: Uuid.random()
            ConversationRuntime(
                id = id,
                initial = Conversation.ofId(
                    id = id,
                    assistantId = assistantId
                ).toSnapshot(),
                scope = appScope,
                onIdle = { removeSession(it) },
                repository = repository,
            ).also {
                _runtimesVersion.value++
                Log.i(TAG, "createSession: $id (total: ${runtimes.size + 1})")
            }
        }
    }

    fun getOrCreateSessionWithConversation(conversationId: Uuid, conversation: Conversation): ConversationRuntime {
        return runtimes.computeIfAbsent(conversationId) { id ->
            ConversationRuntime(
                id = id,
                initial = conversation.toSnapshot(),
                scope = appScope,
                onIdle = { removeSession(it) },
                repository = repository,
            ).also {
                _runtimesVersion.value++
                Log.i(TAG, "createSession with conversation: $id (total: ${runtimes.size + 1})")
            }
        }
    }

    fun getSession(conversationId: Uuid): ConversationRuntime? = runtimes[conversationId]

    private fun removeSession(conversationId: Uuid) {
        val runtime = runtimes[conversationId] ?: return
        if (runtime.isInUse) {
            Log.d(TAG, "removeSession: skipped $conversationId (still in use)")
            return
        }
        if (runtimes.remove(conversationId, runtime)) {
            runtime.cleanup()
            _runtimesVersion.value++
            Log.i(TAG, "removeSession: $conversationId (remaining: ${runtimes.size})")
        }
    }

    fun addConversationReference(conversationId: Uuid) {
        getOrCreateSession(conversationId).acquire()
    }

    fun removeConversationReference(conversationId: Uuid) {
        runtimes[conversationId]?.release()
    }

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> {
        val runtime = runtimes[conversationId] ?: return flowOf(null)
        return runtime.generationJob
    }

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> {
        val runtime = runtimes[conversationId] ?: return MutableStateFlow(null)
        return runtime.processingStatus
    }

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> {
        return _runtimesVersion.flatMapLatest {
            val currentRuntimes = runtimes.values.toList()
            if (currentRuntimes.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(currentRuntimes.map { runtime ->
                    runtime.generationJob.map { job -> runtime.id to job }
                }) { pairs ->
                    pairs.filter { it.second != null }.toMap()
                }
            }
        }
    }

    /**
     * 整对象装载（DB 加载 / 导入路径）：内存快照与持久化基线同步重置。
     */
    fun loadConversation(conversationId: Uuid, conversation: Conversation) {
        if (conversation.id != conversationId) return
        // 使用 getOrCreateSessionWithConversation 避免先创建空 Session 再覆盖
        val runtime = getOrCreateSessionWithConversation(conversationId, conversation)
        runtime.loadSnapshot(conversation)
    }

    fun getAllActiveSessionIds(): Set<Uuid> = runtimes.keys.toSet()

    fun getSessionsSnapshot(): List<ConversationRuntime> = runtimes.values.toList()

    /** Removes a conversation that has already been durably deleted. */
    fun evictSession(conversationId: Uuid) {
        runtimes.remove(conversationId)?.let { runtime ->
            runtime.cleanup()
            _runtimesVersion.value++
        }
    }

    /** 取消并等待指定 Assistant 的普通会话生成，避免清理后迟到 checkpoint 重新写回会话。 */
    suspend fun cancelGenerationsForAssistant(assistantId: Uuid, reason: String) {
        val jobs = runtimes.values
            .filter { it.snapshot.value.header.assistantId == assistantId }
            .mapNotNull { it.generationJob.value }
            .distinct()
        jobs.forEach { it.cancel(reason) }
        jobs.joinAll()
    }

    fun cleanup() = runCatching {
        runtimes.values.forEach { it.cleanup() }
        runtimes.clear()
    }
}
