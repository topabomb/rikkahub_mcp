package net.weero.measix.pilot.data.event

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class AppEventBusTest {
    @Test
    fun `suspending terminal delivery survives a full incremental buffer`() = runTest {
        val bus = AppEventBus()
        val firstObserved = CompletableDeferred<Unit>()
        val releaseCollector = CompletableDeferred<Unit>()
        val received = mutableListOf<AppEvent>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            bus.events.collect { event ->
                received += event
                if (!firstObserved.isCompleted) {
                    firstObserved.complete(Unit)
                    releaseCollector.await()
                }
            }
        }

        bus.emit(AppEvent.Speak("first"))
        firstObserved.await()
        repeat(16) { index ->
            assertTrue(bus.tryEmit(AppEvent.Speak("buffered-$index")))
        }
        val terminal = AppEvent.ChatGenerationEnded(
            conversationId = Uuid.random(),
            senderName = "assistant",
            contentPreview = null,
            notifyCompletion = false,
        )
        val terminalDelivery = async { bus.emit(terminal) }
        runCurrent()
        assertFalse(terminalDelivery.isCompleted)

        releaseCollector.complete(Unit)
        terminalDelivery.await()
        runCurrent()
        assertTrue(received.contains(terminal))
        collector.cancel()
    }
}
