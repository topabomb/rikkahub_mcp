package net.weero.measix.pilot.ui.pages.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import net.weero.measix.pilot.service.ConversationTurnFeedback
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

/** Verifies Compose/lifecycle cancellation with a recording haptic sink, not physical motor strength. */
@RunWith(AndroidJUnit4::class)
class TurnHapticFeedbackLifecycleTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun layoutRecompositionPreservesThePageCadenceAndTerminalProjectionStopsIt() {
        val owner = Owner()
        val sink = RecordingHaptic()
        val conversationId = Uuid.random()
        val feedback = MutableStateFlow<ConversationTurnFeedback?>(working())
        var alternateLayout by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                TurnHapticFeedback(
                    conversationId, feedback, nowMillis = { compose.mainClock.currentTime }, pulse = sink::play,
                )
                if (alternateLayout) Box {} else Box {}
            }
        }
        frame()
        compose.runOnUiThread { feedback.value = feedback.value!!.copy(outputCharacters = 24L) }
        frame()
        assertEquals(1, sink.count)
        advance(1_000)
        compose.runOnUiThread { alternateLayout = true }
        frame()
        advance(4_100)
        assertEquals(2, sink.count)
        compose.runOnUiThread { feedback.value = null }
        frame()
        advance(10_000)
        assertEquals(2, sink.count)
        assertEquals(List(2) { TurnHapticPulse.WORK }, sink.kinds)
    }

    @Test
    fun leavingResumedDisablingAndDisposingCancelTheSecondPulseWithoutReplay() {
        val owner = Owner()
        val sink = RecordingHaptic()
        val conversationId = Uuid.random()
        val feedback = MutableStateFlow(working())
        val enabled = MutableStateFlow(true)
        val updates = combine(feedback, enabled) { state, on -> state.takeIf { on } }
        var visible by mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                if (visible) {
                    TurnHapticFeedback(
                        conversationId, updates, nowMillis = { compose.mainClock.currentTime }, pulse = sink::play,
                    )
                }
            }
        }
        frame()
        compose.runOnUiThread { feedback.value = feedback.value.copy(awaitingUser = true, attentionKeys = setOf("a")) }
        frame()
        assertEquals(1, sink.count)
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED }
        advance(1_000)
        assertEquals(1, sink.count)
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        frame()
        advance(1_000)
        assertEquals(1, sink.count)

        compose.runOnUiThread { feedback.value = feedback.value.copy(attentionKeys = setOf("b")) }
        frame()
        assertEquals(2, sink.count)
        compose.runOnUiThread { enabled.value = false }
        frame()
        advance(1_000)
        assertEquals(2, sink.count)
        compose.runOnUiThread { enabled.value = true }
        frame()
        advance(1_000)
        assertEquals(2, sink.count)

        compose.runOnUiThread { feedback.value = feedback.value.copy(attentionKeys = setOf("c")) }
        frame()
        assertEquals(3, sink.count)
        compose.runOnUiThread { visible = false }
        frame()
        advance(1_000)
        assertEquals(3, sink.count)
        assertEquals(List(3) { TurnHapticPulse.ATTENTION }, sink.kinds)
    }

    @Test
    fun replacingThePlatformSinkKeepsThePairAndUsesTheLatestSink() {
        val owner = Owner()
        val first = RecordingHaptic()
        val replacement = RecordingHaptic()
        var sink by mutableStateOf(first)
        val conversationId = Uuid.random()
        val feedback = MutableStateFlow(working())
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                TurnHapticFeedback(
                    conversationId, feedback, nowMillis = { compose.mainClock.currentTime }, pulse = sink::play,
                )
            }
        }
        frame()
        compose.runOnUiThread {
            feedback.value = feedback.value.copy(awaitingUser = true, attentionKeys = setOf("approval"))
        }
        frame()
        assertEquals(listOf(TurnHapticPulse.ATTENTION), first.kinds)
        compose.runOnUiThread { sink = replacement }
        frame()
        advance(300)
        assertEquals(listOf(TurnHapticPulse.ATTENTION), first.kinds)
        assertEquals(listOf(TurnHapticPulse.ATTENTION), replacement.kinds)
        advance(10_000)
        assertEquals(1, replacement.count)
    }

    @Test
    fun backgroundQueryUpdatesBecomeTheResumeBaselineBeforeRenderingCatchesUp() {
        val owner = Owner()
        val sink = RecordingHaptic()
        val conversationId = Uuid.random()
        val feedback = MutableStateFlow(working())
        val enabled = MutableStateFlow(true)
        val updates = combine(feedback, enabled) { state, on -> state.takeIf { on } }
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                val rendered by feedback.collectAsStateWithLifecycle()
                Text(rendered.attentionKeys.joinToString())
                TurnHapticFeedback(
                    conversationId, updates, nowMillis = { compose.mainClock.currentTime }, pulse = sink::play,
                )
            }
        }
        frame()
        compose.runOnUiThread {
            owner.registry.currentState = Lifecycle.State.CREATED
            feedback.value = feedback.value.copy(awaitingUser = true, attentionKeys = setOf("background"))
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        frame()
        advance(10_000)
        assertEquals(0, sink.count)
        compose.runOnUiThread { feedback.value = feedback.value.copy(attentionKeys = setOf("foreground")) }
        frame()
        advance(300)
        assertEquals(2, sink.count)

        compose.runOnUiThread {
            owner.registry.currentState = Lifecycle.State.CREATED
            enabled.value = false
            feedback.value = feedback.value.copy(attentionKeys = setOf("disabled"))
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        frame()
        advance(10_000)
        assertEquals(2, sink.count)
        assertEquals(List(2) { TurnHapticPulse.ATTENTION }, sink.kinds)
    }

    private fun frame() {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun advance(milliseconds: Long) {
        compose.mainClock.advanceTimeBy(milliseconds)
        compose.waitForIdle()
    }

    private fun working() = ConversationTurnFeedback(Uuid.random(), 0L, false, emptySet())

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private class RecordingHaptic {
        val kinds = mutableListOf<TurnHapticPulse>()
        val count get() = kinds.size
        fun play(kind: TurnHapticPulse) {
            kinds += kind
        }
    }
}
