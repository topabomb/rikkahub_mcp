package net.weero.measix.pilot.ui.pages.chat

import android.media.AudioAttributes
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import net.weero.measix.pilot.service.ConversationTurnFeedback
import kotlin.uuid.Uuid

internal const val TURN_HAPTIC_MIN_INTERVAL_MS = 750L
internal const val TURN_HAPTIC_SLOW_OUTPUT_INTERVAL_MS = 3_000L
internal const val TURN_HAPTIC_WAIT_INTERVAL_MS = 5_000L
internal const val TURN_HAPTIC_OUTPUT_CHARACTERS = 24L
internal const val TURN_HAPTIC_ATTENTION_GAP_MS = 200L

internal enum class TurnHapticPulse { WORK, ATTENTION }

@Composable
internal fun TurnHapticFeedback(
    conversationId: Uuid,
    updates: Flow<ConversationTurnFeedback?>,
    nowMillis: () -> Long = SystemClock::uptimeMillis,
    pulse: (TurnHapticPulse) -> Unit = rememberTurnHapticPlayer(),
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestPulse by rememberUpdatedState(pulse)
    val latestClock by rememberUpdatedState(nowMillis)
    LaunchedEffect(conversationId, updates, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // Collect current query values on resume, not a possibly stale rendered snapshot.
            runTurnHapticFeedback(updates, nowMillis = { latestClock() }) { kind ->
                latestPulse(kind)
            }
        }
    }
}

@Composable
private fun rememberTurnHapticPlayer(): (TurnHapticPulse) -> Unit {
    val view = LocalView.current
    val haptic = LocalHapticFeedback.current
    return remember(view, haptic) { AndroidTurnHapticPlayer(view, haptic)::play }
}

/** Platform delivery only; the page coroutine owns timing, deduplication and cancellation. */
internal class AndroidTurnHapticPlayer(
    private val view: View,
    private val haptic: HapticFeedback,
    private val vibrator: Vibrator? = view.context.getSystemService(Vibrator::class.java),
) {
    fun play(kind: TurnHapticPulse) {
        if (kind == TurnHapticPulse.WORK) {
            haptic.performHapticFeedback(HapticFeedbackType.KeyboardTap)
            return
        }
        if (!view.isHapticFeedbackEnabled) return
        // On Android 13+, USAGE_TOUCH lets the system apply current user preferences, not this legacy key.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            @Suppress("DEPRECATION")
            val enabled = Settings.System.getInt(
                view.context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1,
            )
            if (enabled == 0) return
        }
        val motor = vibrator?.takeIf { it.hasVibrator() } ?: return
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
        } else {
            // Android 8/9 have no predefined effects; keep the attention pulse short and non-repeating.
            VibrationEffect.createOneShot(35L, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            motor.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        } else {
            @Suppress("DEPRECATION")
            motor.vibrate(
                effect,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }
    }
}

/** One page observation owns output-driven feedback, the waiting heartbeat and attention pair. */
internal suspend fun runTurnHapticFeedback(
    updates: Flow<ConversationTurnFeedback?>,
    nowMillis: () -> Long,
    pulse: (TurnHapticPulse) -> Unit,
): Unit = coroutineScope {
    var current: ConversationTurnFeedback? = null
    var pendingCharacters = 0L
    var lastPulseAt: Long? = null
    var waitSince = 0L
    val seenAttentionKeys = mutableSetOf<String>()
    var workJob: Job? = null
    var attentionJob: Job? = null

    fun stopWork() {
        workJob?.cancel()
        workJob = null
        pendingCharacters = 0L
    }

    fun emitPulse(kind: TurnHapticPulse) {
        pulse(kind)
        lastPulseAt = nowMillis()
        pendingCharacters = 0L
    }

    try {
        updates.collect { next ->
            val previous = current
            val newObservation = previous == null || previous.turnId != next?.turnId
            if (newObservation) {
                stopWork()
                attentionJob?.cancel()
                lastPulseAt = null
                seenAttentionKeys.clear()
                // Initial snapshots are a baseline, never a replay of output or attention.
                seenAttentionKeys.addAll(next?.attentionKeys.orEmpty())
            }
            current = next
            if (next == null) return@collect

            if (next.awaitingUser) {
                stopWork()
                val unseen = next.attentionKeys.filter { seenAttentionKeys.add(it) }.toSet()
                if (unseen.isNotEmpty()) {
                    attentionJob?.cancel()
                    attentionJob = launch {
                        ensureActive()
                        emitPulse(TurnHapticPulse.ATTENTION)
                        delay(TURN_HAPTIC_ATTENTION_GAP_MS)
                        ensureActive()
                        if (current?.attentionKeys?.any { it in unseen } == true) {
                            emitPulse(TurnHapticPulse.ATTENTION)
                        }
                    }
                }
            } else {
                attentionJob?.cancel()
                val now = nowMillis()
                if (newObservation || previous.awaitingUser) {
                    waitSince = now
                } else {
                    val before = previous.outputCharacters
                    val after = next.outputCharacters
                    val added = if (before != null && after != null) (after - before).coerceAtLeast(0L) else 0L
                    if (before != after) {
                        waitSince = now
                        // Missing slots establish a baseline; shrinking transforms discard pending volume.
                        if (added == 0L) pendingCharacters = 0L
                    }
                    if (added > 0) {
                        pendingCharacters = (pendingCharacters + added).coerceAtMost(TURN_HAPTIC_OUTPUT_CHARACTERS)
                        val elapsed = lastPulseAt?.let { now - it }
                        if (elapsed == null ||
                            (elapsed >= TURN_HAPTIC_MIN_INTERVAL_MS &&
                                (pendingCharacters >= TURN_HAPTIC_OUTPUT_CHARACTERS ||
                                    elapsed >= TURN_HAPTIC_SLOW_OUTPUT_INTERVAL_MS))
                        ) {
                            ensureActive()
                            emitPulse(TurnHapticPulse.WORK)
                        }
                    }
                }
                if (workJob == null) {
                    workJob = launch {
                        while (true) {
                            val remaining = TURN_HAPTIC_WAIT_INTERVAL_MS - (nowMillis() - waitSince)
                            delay(remaining.coerceAtLeast(1L))
                            ensureActive()
                            if (nowMillis() - waitSince >= TURN_HAPTIC_WAIT_INTERVAL_MS) {
                                emitPulse(TurnHapticPulse.WORK)
                                waitSince = nowMillis()
                            }
                        }
                    }
                }
            }
        }
    } finally {
        workJob?.cancel()
        attentionJob?.cancel()
    }
}
