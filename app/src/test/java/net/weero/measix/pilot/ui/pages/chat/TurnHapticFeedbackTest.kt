package net.weero.measix.pilot.ui.pages.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.service.ConversationTurnFeedback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class TurnHapticFeedbackTest {
    @Test
    fun fastOutputFollowsThreeQuarterSecondLimitWithoutDebounceStarvation() = runTest {
        val probe = Probe(this)
        repeat(121) {
            probe.append(2)
            advanceTimeBy(25)
        }
        assertEquals(listOf(0L, 750L, 1_500L, 2_250L, 3_000L), probe.pulses)
        assertEquals(List(5) { TurnHapticPulse.WORK }, probe.kinds)
    }

    @Test
    fun mediumOutputNaturallyProducesOneSecondFeedbackWithoutSpeedTiers() = runTest {
        val probe = Probe(this)
        probe.append(1)
        repeat(6) {
            advanceTimeBy(1_000)
            probe.append(24)
        }
        assertEquals((0L..6_000L step 1_000L).toList(), probe.pulses)
    }

    @Test
    fun slowOutputUsesThreeSecondsOnlyWhenAnotherCharacterArrives() = runTest {
        val probe = Probe(this)
        probe.append(1)
        repeat(6) {
            advanceTimeBy(1_000)
            probe.append(1)
        }
        assertEquals(listOf(0L, 3_000L, 6_000L), probe.pulses)
    }

    @Test
    fun characterThresholdIsIndependentOfHowAnOutputBurstIsPartitioned() = runTest {
        val whole = Probe(this)
        val split = Probe(this)
        whole.append(1)
        split.append(1)
        advanceTimeBy(1_000)
        whole.append(24)
        repeat(24) { split.append(1) }
        assertEquals(listOf(0L, 1_000L), whole.pulses)
        assertEquals(whole.pulses, split.pulses)
    }

    @Test
    fun bothCharacterThresholdAndMinimumTimeMustBeSatisfied() = runTest {
        val probe = Probe(this)
        probe.append(1)
        advanceTimeBy(749)
        probe.append(23)
        assertEquals(listOf(0L), probe.pulses)
        advanceTimeBy(1)
        probe.append(1)
        assertEquals(listOf(0L, 750L), probe.pulses)
        probe.append(100)
        advanceTimeBy(750)
        runCurrent()
        assertEquals(listOf(0L, 750L), probe.pulses)
        probe.append(1)
        assertEquals(listOf(0L, 750L, 1_500L), probe.pulses)
    }

    @Test
    fun largeChunksDoNotLeavePulseDebtOrScheduleTrailingOutput() = runTest {
        val probe = Probe(this)
        probe.append(10_000)
        advanceTimeBy(2_999)
        probe.append(1)
        assertEquals(listOf(0L), probe.pulses)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(0L), probe.pulses)
        probe.append(1)
        assertEquals(listOf(0L, 3_000L), probe.pulses)
    }

    @Test
    fun initialContentIsNotReplayedAndWaitingHeartbeatIsEveryFiveSeconds() = runTest {
        val probe = Probe(this, working().copy(outputCharacters = 1_000))
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(listOf(5_000L, 10_000L, 15_000L), probe.pulses)
        assertEquals(List(3) { TurnHapticPulse.WORK }, probe.kinds)
    }

    @Test
    fun evenSubthresholdOutputDefersHeartbeatUntilFiveSecondsOfSilence() = runTest {
        val probe = Probe(this)
        probe.append(1)
        advanceTimeBy(2_000)
        probe.append(1)
        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(listOf(0L), probe.pulses)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(0L, 7_000L), probe.pulses)
    }

    @Test
    fun outputAfterHeartbeatSharesMinimumGapAndCannotDoublePulse() = runTest {
        val probe = Probe(this)
        advanceTimeBy(5_000)
        runCurrent()
        advanceTimeBy(100)
        probe.append(100)
        assertEquals(listOf(5_000L), probe.pulses)
        advanceTimeBy(650)
        runCurrent()
        assertEquals(listOf(5_000L), probe.pulses)
        probe.append(1)
        assertEquals(listOf(5_000L, 5_750L), probe.pulses)
    }

    @Test
    fun outputAtHeartbeatDeadlineDoesNotDoublePulseInEitherDispatchOrder() = runTest {
        val outputFirst = Probe(this)
        advanceTimeBy(5_000)
        outputFirst.append(24)
        runCurrent()
        assertEquals(listOf(5_000L), outputFirst.pulses)
        outputFirst.job.cancelAndJoin()

        val heartbeatFirst = Probe(this)
        advanceTimeBy(5_000)
        runCurrent()
        heartbeatFirst.append(24)
        assertEquals(listOf(10_000L), heartbeatFirst.pulses)
    }

    @Test
    fun shrinkingTransformerRebasesOutputAndDoesNotHideSubsequentGrowth() = runTest {
        val probe = Probe(this, working().copy(outputCharacters = 100))
        advanceTimeBy(1_000)
        probe.states.value = probe.states.value!!.copy(outputCharacters = 80)
        advanceTimeBy(1_000)
        probe.append(10)
        assertEquals(listOf(2_000L), probe.pulses)
        advanceTimeBy(2_000)
        probe.append(10)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf(2_000L), probe.pulses)
        probe.append(1)
        assertEquals(listOf(2_000L, 6_000L), probe.pulses)
    }

    @Test
    fun unknownSlotAndReusedCommittedMessageEstablishBaselineWithoutReplayingOldContent() = runTest {
        val probe = Probe(this, working().copy(outputCharacters = null))
        advanceTimeBy(1_000)
        probe.states.value = probe.states.value!!.copy(outputCharacters = 100)
        advanceTimeBy(1_000)
        probe.states.value = probe.states.value!!.copy(outputCharacters = 100)
        assertTrue(probe.pulses.isEmpty())
        probe.append(1)
        assertEquals(listOf(2_000L), probe.pulses)
    }

    @Test
    fun shrinkingClearsPendingCharactersButPreservesTheMinimumPulseGap() = runTest {
        val probe = Probe(this)
        probe.append(100)
        advanceTimeBy(100)
        probe.append(23)
        probe.states.value = probe.states.value!!.copy(outputCharacters = 100)
        advanceTimeBy(649)
        probe.append(24)
        assertEquals(listOf(0L), probe.pulses)
        advanceTimeBy(1)
        probe.append(1)
        assertEquals(listOf(0L, 750L), probe.pulses)
        advanceTimeBy(100)
        probe.append(23)
        probe.states.value = probe.states.value!!.copy(outputCharacters = 125)
        advanceTimeBy(650)
        probe.append(1)
        assertEquals(listOf(0L, 750L), probe.pulses)
    }

    @Test
    fun duplicateProjectionsDoNotPostponeWaitingHeartbeat() = runTest {
        val pulses = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            runTurnHapticFeedback(flow {
                val state = working().copy(outputCharacters = null)
                repeat(11) {
                    emit(state)
                    delay(1_000)
                }
            }, nowMillis = { currentTime }) { pulses += currentTime }
        }
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf(5_000L, 10_000L), pulses)
    }

    @Test
    fun attentionCancelsWaitingTimerAndPausesAllWorkFeedback() = runTest {
        val probe = Probe(this)
        advanceTimeBy(4_900)
        probe.waitFor("question")
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(listOf(4_900L, 5_100L), probe.pulses)
        assertEquals(List(2) { TurnHapticPulse.ATTENTION }, probe.kinds)
    }

    @Test
    fun attentionUsesHeavyPairImmediatelyAndResumedWorkSharesItsCooldown() = runTest {
        val probe = Probe(this)
        probe.append(1)
        advanceTimeBy(100)
        probe.waitFor("approval")
        advanceTimeBy(200)
        runCurrent()
        assertEquals(listOf(0L, 100L, 300L), probe.pulses)
        assertEquals(listOf(TurnHapticPulse.WORK, TurnHapticPulse.ATTENTION, TurnHapticPulse.ATTENTION), probe.kinds)
        probe.states.value = probe.states.value!!.copy(awaitingUser = false, attentionKeys = emptySet())
        advanceTimeBy(749)
        probe.append(24)
        assertEquals(3, probe.pulses.size)
        advanceTimeBy(1)
        probe.append(1)
        assertEquals(listOf(0L, 100L, 300L, 1_050L), probe.pulses)
        assertEquals(TurnHapticPulse.WORK, probe.kinds.last())
    }

    @Test
    fun removingAnInteractionOrReplacingTheTurnCancelsItsSecondHeavyPulse() = runTest {
        val probe = Probe(this)
        probe.waitFor("a")
        advanceTimeBy(100)
        probe.states.value = probe.states.value!!.copy(attentionKeys = emptySet())
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf(0L), probe.pulses)
        probe.waitFor("b")
        advanceTimeBy(100)
        probe.states.value = working().copy(awaitingUser = true, attentionKeys = setOf("other-turn"))
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf(0L, 200L), probe.pulses)
        assertEquals(List(2) { TurnHapticPulse.ATTENTION }, probe.kinds)
    }

    @Test
    fun sameAttentionIsNotReplayedByRefreshPartialDecisionsOrReturningToWait() = runTest {
        val probe = Probe(this)
        probe.waitFor("a", "b")
        advanceTimeBy(200)
        runCurrent()
        probe.append(100)
        probe.waitFor("b")
        probe.states.value = probe.states.value!!.copy(awaitingUser = false, attentionKeys = emptySet())
        probe.waitFor("a")
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(0L, 200L), probe.pulses)
        probe.waitFor("new")
        advanceTimeBy(200)
        runCurrent()
        assertEquals(listOf(0L, 200L, 1_200L, 1_400L), probe.pulses)
    }

    @Test
    fun initialWaitingStateIsSilentWhileNewInteractionAlerts() = runTest {
        val probe = Probe(this, working().copy(awaitingUser = true, attentionKeys = setOf("old")))
        advanceTimeBy(12_000)
        assertTrue(probe.pulses.isEmpty())
        probe.waitFor("old", "new")
        advanceTimeBy(200)
        runCurrent()
        assertEquals(listOf(12_000L, 12_200L), probe.pulses)
    }

    @Test
    fun answerStopAndRemovedInteractionSuppressPendingSecondAttentionPulse() = runTest {
        val probe = Probe(this)
        probe.waitFor("a")
        advanceTimeBy(100)
        probe.states.value = probe.states.value!!.copy(awaitingUser = false, attentionKeys = emptySet())
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf(0L), probe.pulses)
        probe.waitFor("b")
        advanceTimeBy(100)
        probe.states.value = null
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf(0L, 200L), probe.pulses)
    }

    @Test
    fun newTurnResetsBaselineAndDiscardsOldWaitingDeadline() = runTest {
        val probe = Probe(this)
        advanceTimeBy(4_900)
        probe.states.value = working().copy(outputCharacters = 500)
        advanceTimeBy(100)
        runCurrent()
        assertTrue(probe.pulses.isEmpty())
        probe.append(1)
        assertEquals(listOf(5_000L), probe.pulses)
    }

    @Test
    fun foregroundCancellationKillsTimersAndResumingEstablishesFreshBaseline() = runTest {
        val probe = Probe(this)
        probe.waitFor("pending")
        advanceTimeBy(100)
        probe.job.cancelAndJoin()
        advanceTimeBy(10_000)
        val resumed = Probe(this, probe.states.value)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf(0L), probe.pulses)
        assertTrue(resumed.pulses.isEmpty())
    }

    @Test
    fun queuedSchedulerConsumesStopBeforeHeartbeatAndCancelsReadySecondPulse() = runTest {
        val states = MutableStateFlow<ConversationTurnFeedback?>(working())
        val pulses = mutableListOf<Long>()
        val job = backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
            runTurnHapticFeedback(states, nowMillis = { currentTime }) { pulses += currentTime }
        }
        runCurrent()
        advanceTimeBy(4_999)
        states.value = null
        runCurrent()
        advanceTimeBy(1)
        runCurrent()
        assertTrue(pulses.isEmpty())
        states.value = working()
        runCurrent()
        states.value = states.value!!.copy(awaitingUser = true, attentionKeys = setOf("a"))
        runCurrent()
        assertEquals(listOf(5_000L), pulses)
        advanceTimeBy(200)
        job.cancel()
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf(5_000L), pulses)
    }

    @Test
    fun upstreamFailurePropagatesAndClosesScheduledWork() = runTest {
        val pulses = mutableListOf<Long>()
        val failure = IllegalStateException("source failed")
        val result = runCatching {
            runTurnHapticFeedback(flow {
                emit(working())
                throw failure
            }, nowMillis = { currentTime }) { pulses += currentTime }
        }
        advanceTimeBy(10_000)
        assertEquals(failure::class, result.exceptionOrNull()!!::class)
        assertEquals(failure.message, result.exceptionOrNull()?.message)
        assertTrue(pulses.isEmpty())
    }

    private class Probe(scope: TestScope, initial: ConversationTurnFeedback? = working()) {
        val states = MutableStateFlow(initial)
        val pulses = mutableListOf<Long>()
        val kinds = mutableListOf<TurnHapticPulse>()
        val job = scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
            runTurnHapticFeedback(states, nowMillis = { scope.currentTime }) { kind ->
                pulses += scope.currentTime
                kinds += kind
            }
        }

        fun append(characters: Long) {
            states.value = states.value!!.copy(outputCharacters = states.value!!.outputCharacters!! + characters)
        }

        fun waitFor(vararg keys: String) {
            states.value = states.value!!.copy(awaitingUser = true, attentionKeys = keys.toSet())
        }
    }

    companion object {
        private fun working() = ConversationTurnFeedback(Uuid.random(), 0L, false, emptySet())
    }
}
