package net.weero.measix.pilot.service

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.weero.measix.pilot.data.model.Conversation
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.uuid.Uuid

private const val TAG = "ConversationSession"
private const val IDLE_TIMEOUT_MS = 5_000L

class ConversationSession(
    val id: Uuid,
    initial: Conversation,
    private val scope: CoroutineScope,
    private val onIdle: (Uuid) -> Unit,
) {
    // 会话状态
    val state = MutableStateFlow(initial)

    // 原子引用计数
    private val refCount = AtomicInteger(0)

    // 处理状态（如 OCR 识别中）
    val processingStatus = MutableStateFlow<String?>(null)

    // 生成任务（内聚在 session 中）
    private val _generationJob = MutableStateFlow<Job?>(null)
    private val toolApprovalMutex = Mutex()
    private var ttsQueueSessionId: String? = null
    val generationJob: StateFlow<Job?> = _generationJob.asStateFlow()
    val isGenerating: Boolean get() = _generationJob.value?.isActive == true
    val isInUse: Boolean get() = refCount.get() > 0 || isGenerating || isDirty()

    private val stateRevision = AtomicInteger(0)
    private val persistedRevision = AtomicInteger(0)
    private val persistMutex = Mutex()
    private val activeTurnId = AtomicReference<Uuid?>(null)
    private val cancelReasons = ConcurrentHashMap<Uuid, String>()
    private val finalizedTurns: MutableSet<Uuid> = ConcurrentHashMap.newKeySet()

    fun currentRevision(): Int = stateRevision.get()

    fun lastPersistedRevision(): Int = persistedRevision.get()

    fun bumpStateRevision(): Int = stateRevision.incrementAndGet()

    fun markPersisted(revision: Int) {
        persistedRevision.updateAndGet { current -> maxOf(current, revision) }
        if (refCount.get() <= 0 && !isGenerating && !isDirty()) {
            scheduleIdleCheck()
        }
    }

    fun isDirty(): Boolean = stateRevision.get() > persistedRevision.get()

    fun beginTurn(turnId: Uuid) {
        activeTurnId.set(turnId)
    }

    fun currentTurnId(): Uuid? = activeTurnId.get()

    fun requestCancel(turnId: Uuid, reason: String) {
        cancelReasons[turnId] = reason
    }

    fun consumeCancelReason(turnId: Uuid): String? = cancelReasons.remove(turnId)

    fun peekCancelReason(turnId: Uuid): String? = cancelReasons[turnId]

    /**
     * 登记已提交终态的 turn。一个 turn 只允许提交一次终态：
     * SUCCESS 提交后 job 可能仍在收尾（例如 generationDoneFlow 的 emit
     * 等待慢订阅者），此时被取消会再次进入 finalizer，已提交的终态不允许被覆盖。
     */
    fun markTurnFinalized(turnId: Uuid) {
        finalizedTurns.add(turnId)
    }

    fun isTurnFinalized(turnId: Uuid): Boolean = finalizedTurns.contains(turnId)

    suspend fun <T> withPersistLock(block: suspend () -> T): T = persistMutex.withLock { block() }

    // 空闲检查任务
    private var idleCheckJob: Job? = null

    fun acquire(): Int = refCount.incrementAndGet().also {
        cancelIdleCheck()
        Log.d(TAG, "acquire $id (refs=$it)")
    }

    fun release(): Int = refCount.decrementAndGet().also {
        Log.d(TAG, "release $id (refs=$it)")
        if (it <= 0) scheduleIdleCheck()
    }

    // 作用域 API - 短请求（REST）
    inline fun <T> withRef(block: () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    // 作用域 API - 长连接（SSE、挂起函数）
    suspend inline fun <T> withRefSuspend(block: () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    fun setJob(job: Job?, turnId: Uuid? = null) {
        _generationJob.value?.cancel()
        _generationJob.value = job
        if (job != null && turnId != null) {
            activeTurnId.set(turnId)
        }
        job?.invokeOnCompletion {
            // A cancelled previous job may finish after its replacement was installed.
            // Only the job that is still current may clear the slot or schedule eviction.
            if (_generationJob.compareAndSet(job, null)) {
                if (turnId != null) {
                    activeTurnId.compareAndSet(turnId, null)
                    cancelReasons.remove(turnId)
                }
                if (refCount.get() <= 0) scheduleIdleCheck()
            }
        }
    }

    fun getJob(): Job? = _generationJob.value

    /**
     * 返回主代理 turn 独占的 TTS 队列 ID。
     * 新消息/重新生成创建新 ID；工具审批恢复复用原 ID，避免把同一 turn 拆成两条队列。
     */
    @Synchronized
    fun getTtsQueueSessionId(resumeExistingTurn: Boolean): String {
        if (!resumeExistingTurn || ttsQueueSessionId == null) {
            ttsQueueSessionId = Uuid.random().toString()
        }
        return requireNotNull(ttsQueueSessionId)
    }

    @Synchronized
    fun peekTtsQueueSessionId(): String? = ttsQueueSessionId

    suspend fun <T> withToolApprovalLock(block: suspend () -> T): T =
        toolApprovalMutex.withLock { block() }

    private fun scheduleIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            if (refCount.get() <= 0 && !isGenerating && !isDirty()) {
                onIdle(id)
            }
        }
    }

    private fun cancelIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = null
    }

    fun cleanup() {
        _generationJob.value?.cancel()
        _generationJob.value = null
        activeTurnId.set(null)
        cancelReasons.clear()
        finalizedTurns.clear()
        ttsQueueSessionId = null
        idleCheckJob?.cancel()
        idleCheckJob = null
    }
}
