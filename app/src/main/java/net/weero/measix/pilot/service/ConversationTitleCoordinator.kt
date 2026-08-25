package net.weero.measix.pilot.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.uuid.Uuid

internal const val MAX_AUTO_TITLE_GENERATION_ATTEMPTS = 5
private const val LOCAL_TITLE_MAX_CODE_POINTS = 40

enum class ConversationTitlePhase {
    EMPTY,
    LOCAL_FALLBACK,
    MODEL_GENERATING,
    RESOLVED,
}

internal enum class ConversationTitleDecision {
    Proceed,
    SkipResolved,
    SkipInFlight,
    SkipAttemptsExhausted,
}

internal data class ConversationTitleRetry(val force: Boolean)

internal data class ConversationTitleGenerationToken(
    val id: Uuid,
    val conversationId: Uuid,
    val expectedTitle: String,
)

internal sealed interface ConversationTitleBeginResult {
    data class Granted(val token: ConversationTitleGenerationToken) : ConversationTitleBeginResult
    data class Skipped(val decision: ConversationTitleDecision) : ConversationTitleBeginResult
}

internal fun decideConversationTitleGeneration(
    force: Boolean,
    autoEligible: Boolean,
    inFlight: Boolean,
    attempts: Int,
    maxAttempts: Int = MAX_AUTO_TITLE_GENERATION_ATTEMPTS,
): ConversationTitleDecision {
    if (!force && !autoEligible) return ConversationTitleDecision.SkipResolved
    if (inFlight) return ConversationTitleDecision.SkipInFlight
    if (force) return ConversationTitleDecision.Proceed
    if (attempts >= maxAttempts) return ConversationTitleDecision.SkipAttemptsExhausted
    return ConversationTitleDecision.Proceed
}

/**
 * Creates the immediate durable title used by the first user-message transaction.
 * It is deterministic, locale-independent and Unicode-safe, without adding a second database field.
 */
internal fun deriveLocalConversationTitle(
    message: UIMessage,
    maxCodePoints: Int = LOCAL_TITLE_MAX_CODE_POINTS,
): String? {
    if (message.role != MessageRole.USER) return null
    val normalized = message.parts
        .filterIsInstance<UIMessagePart.Text>()
        .joinToString(" ", transform = UIMessagePart.Text::text)
        .replace(Regex("\\s+"), " ")
        .trim()
    if (normalized.isEmpty()) return null
    val codePointCount = normalized.codePointCount(0, normalized.length)
    if (codePointCount <= maxCodePoints) return normalized
    val end = normalized.offsetByCodePoints(0, min(maxCodePoints, codePointCount))
    return normalized.substring(0, end).trimEnd() + "…"
}

internal fun resolveConversationTitlePhase(
    title: String,
    localFallbackTitle: String?,
): ConversationTitlePhase = when {
    title.isBlank() -> ConversationTitlePhase.EMPTY
    localFallbackTitle != null && title == localFallbackTitle -> ConversationTitlePhase.LOCAL_FALLBACK
    else -> ConversationTitlePhase.RESOLVED
}

/** Normalizes provider output; write authority is enforced by the title CAS command. */
internal fun normalizeGeneratedTitle(generatedTitle: String): String? {
    val trimmed = generatedTitle.trim()
    return trimmed.takeIf(String::isNotEmpty)
}

/**
 * The single process owner for title phase, request de-duplication and bounded automatic retries.
 * Durable title text remains in the existing conversation header; phase is a typed projection.
 */
class ConversationTitleCoordinator internal constructor(
    private val maxAttempts: Int = MAX_AUTO_TITLE_GENERATION_ATTEMPTS,
) {
    private class State {
        val commitMutex = Mutex()
        var activeToken: ConversationTitleGenerationToken? = null
        var attempts: Int = 0
        var pendingForce: Boolean = false
        var pendingAuto: Boolean = false
        var stablePhase: ConversationTitlePhase = ConversationTitlePhase.EMPTY
        var localFallbackTitle: String? = null
    }

    private val states = ConcurrentHashMap<Uuid, State>()
    private val _phases = MutableStateFlow<Map<Uuid, ConversationTitlePhase>>(emptyMap())
    val phases: StateFlow<Map<Uuid, ConversationTitlePhase>> = _phases.asStateFlow()

    fun synchronize(conversationId: Uuid, title: String, localFallbackTitle: String?) {
        val state = states.getOrPut(conversationId) { State() }
        synchronized(state) {
            state.localFallbackTitle = localFallbackTitle
            state.stablePhase = resolveConversationTitlePhase(title, localFallbackTitle)
            if (state.activeToken == null) publish(conversationId, state.stablePhase)
        }
    }

    fun phaseOf(conversationId: Uuid): ConversationTitlePhase? = phases.value[conversationId]

    fun localFallbackTitle(conversationId: Uuid): String? {
        val state = states[conversationId] ?: return null
        return synchronized(state) { state.localFallbackTitle }
    }

    internal fun begin(
        conversationId: Uuid,
        force: Boolean,
        autoEligible: Boolean,
        expectedTitle: String,
    ): ConversationTitleBeginResult {
        val state = states.getOrPut(conversationId) { State() }
        synchronized(state) {
            val decision = decideConversationTitleGeneration(
                force = force,
                autoEligible = autoEligible,
                inFlight = state.activeToken != null,
                attempts = state.attempts,
                maxAttempts = maxAttempts,
            )
            when (decision) {
                ConversationTitleDecision.Proceed -> {
                    val token = ConversationTitleGenerationToken(
                        id = Uuid.random(),
                        conversationId = conversationId,
                        expectedTitle = expectedTitle,
                    )
                    state.activeToken = token
                    publish(conversationId, ConversationTitlePhase.MODEL_GENERATING)
                    return ConversationTitleBeginResult.Granted(token)
                }
                ConversationTitleDecision.SkipInFlight -> if (force) {
                    state.pendingForce = true
                } else {
                    state.pendingAuto = true
                }
                ConversationTitleDecision.SkipResolved,
                ConversationTitleDecision.SkipAttemptsExhausted,
                -> Unit
            }
            return ConversationTitleBeginResult.Skipped(decision)
        }
    }

    internal fun recordAttempt(token: ConversationTitleGenerationToken) {
        val state = states[token.conversationId] ?: return
        synchronized(state) {
            if (state.activeToken?.id == token.id) state.attempts++
        }
    }

    suspend fun commitManualTitle(
        conversationId: Uuid,
        title: String,
        commit: suspend () -> Unit,
    ) {
        val state = states.getOrPut(conversationId) { State() }
        state.commitMutex.withLock {
            commit()
            synchronized(state) {
                state.activeToken = null
                state.pendingForce = false
                state.pendingAuto = false
                state.stablePhase = if (title.isBlank()) {
                    ConversationTitlePhase.EMPTY
                } else {
                    ConversationTitlePhase.RESOLVED
                }
                state.localFallbackTitle = null
                publish(conversationId, state.stablePhase)
            }
        }
    }

    internal suspend fun commitGeneratedTitle(
        token: ConversationTitleGenerationToken,
        title: String,
        commitIfCurrent: suspend (expectedTitle: String, title: String) -> Boolean,
    ): Boolean {
        val state = states[token.conversationId] ?: return false
        return state.commitMutex.withLock {
            if (synchronized(state) { state.activeToken?.id != token.id }) return@withLock false
            if (!commitIfCurrent(token.expectedTitle, title)) return@withLock false
            synchronized(state) {
                if (state.activeToken?.id != token.id) return@withLock false
                state.stablePhase = ConversationTitlePhase.RESOLVED
                state.localFallbackTitle = null
            }
            true
        }
    }

    internal fun end(token: ConversationTitleGenerationToken): ConversationTitleRetry? {
        val state = states[token.conversationId] ?: return null
        synchronized(state) {
            if (state.activeToken?.id != token.id) return null
            state.activeToken = null
            val retry = when {
                state.pendingForce -> ConversationTitleRetry(force = true)
                state.pendingAuto -> ConversationTitleRetry(force = false)
                else -> null
            }
            state.pendingForce = false
            state.pendingAuto = false
            publish(token.conversationId, state.stablePhase)
            return retry
        }
    }

    fun clear(conversationId: Uuid) {
        states.remove(conversationId)
        _phases.update { it - conversationId }
    }

    private fun publish(conversationId: Uuid, phase: ConversationTitlePhase) {
        _phases.update { current -> current + (conversationId to phase) }
    }
}
