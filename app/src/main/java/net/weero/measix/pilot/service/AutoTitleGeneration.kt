package net.weero.measix.pilot.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * 同一进程内，空白标题的自动命名最多发起这么多次实际 LLM 请求（含首次）。
 * 缺模型等尚未发出请求的情况不计入；手动重新生成不受此上限。
 */
internal const val MAX_AUTO_TITLE_GENERATION_ATTEMPTS = 5

internal enum class AutoTitleGenerationDecision {
    Proceed,
    SkipHasTitle,
    SkipInFlight,
    SkipAttemptsExhausted,
}

internal data class AutoTitleRetry(
    val force: Boolean,
)

internal fun decideAutoTitleGeneration(
    force: Boolean,
    titleBlank: Boolean,
    inFlight: Boolean,
    attempts: Int,
    maxAttempts: Int = MAX_AUTO_TITLE_GENERATION_ATTEMPTS,
): AutoTitleGenerationDecision {
    // 非强制且已有标题时直接跳过，避免进行中的请求把自动重试记进 pending。
    if (!force && !titleBlank) return AutoTitleGenerationDecision.SkipHasTitle
    if (inFlight) return AutoTitleGenerationDecision.SkipInFlight
    if (force) return AutoTitleGenerationDecision.Proceed
    if (attempts >= maxAttempts) return AutoTitleGenerationDecision.SkipAttemptsExhausted
    return AutoTitleGenerationDecision.Proceed
}

/**
 * 模型已经返回标题文本后，决定要不要写回。
 * 空白结果不落库；自动命名不覆盖用户已有的非空标题。
 */
internal fun resolveGeneratedTitleWrite(
    force: Boolean,
    latestTitle: String,
    generatedTitle: String,
): String? {
    val trimmed = generatedTitle.trim()
    if (trimmed.isEmpty()) return null
    if (!force && latestTitle.isNotBlank()) return null
    return trimmed
}

/**
 * 进程内标题生成门闩：进行中去重 + 自动请求次数。
 * 进行中被挡住的触发会记一笔待重试，当前请求结束后由调用方再跑一次。
 * 不落盘；进程退出或 [clear] 后重新计数。
 */
class AutoTitleGenerationTracker internal constructor(
    private val maxAttempts: Int = MAX_AUTO_TITLE_GENERATION_ATTEMPTS,
) {
    private class State {
        var inFlight: Boolean = false
        var attempts: Int = 0
        var pendingForce: Boolean = false
        var pendingAuto: Boolean = false
    }

    private val states = ConcurrentHashMap<Uuid, State>()
    private val _inFlightIds = MutableStateFlow<Set<Uuid>>(emptySet())
    internal val inFlightIds: StateFlow<Set<Uuid>> = _inFlightIds.asStateFlow()

    internal fun begin(
        conversationId: Uuid,
        force: Boolean,
        titleBlank: Boolean,
    ): AutoTitleGenerationDecision {
        val state = states.getOrPut(conversationId) { State() }
        synchronized(state) {
            val decision = decideAutoTitleGeneration(
                force = force,
                titleBlank = titleBlank,
                inFlight = state.inFlight,
                attempts = state.attempts,
                maxAttempts = maxAttempts,
            )
            when (decision) {
                AutoTitleGenerationDecision.Proceed -> {
                    state.inFlight = true
                    _inFlightIds.update { it + conversationId }
                }
                AutoTitleGenerationDecision.SkipInFlight -> if (force) {
                    state.pendingForce = true
                } else {
                    state.pendingAuto = true
                }
                AutoTitleGenerationDecision.SkipHasTitle,
                AutoTitleGenerationDecision.SkipAttemptsExhausted -> Unit
            }
            return decision
        }
    }

    internal fun recordAttempt(conversationId: Uuid) {
        val state = states[conversationId] ?: return
        synchronized(state) {
            state.attempts++
        }
    }

    internal fun end(conversationId: Uuid): AutoTitleRetry? {
        val state = states[conversationId] ?: return null
        synchronized(state) {
            state.inFlight = false
            val retry = when {
                state.pendingForce -> AutoTitleRetry(force = true)
                state.pendingAuto -> AutoTitleRetry(force = false)
                else -> null
            }
            state.pendingForce = false
            state.pendingAuto = false
            _inFlightIds.update { it - conversationId }
            return retry
        }
    }

    internal fun clear(conversationId: Uuid) {
        states.remove(conversationId)
        _inFlightIds.update { it - conversationId }
    }
}
