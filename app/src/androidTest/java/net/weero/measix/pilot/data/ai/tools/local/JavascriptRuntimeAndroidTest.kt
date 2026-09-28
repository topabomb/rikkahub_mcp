package net.weero.measix.pilot.data.ai.tools.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.core.ToolExecutionFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Executes the packaged Android binding, including its native cancellation and release paths. */
@RunWith(AndroidJUnit4::class)
class JavascriptRuntimeAndroidTest {
    @Test(timeout = 30_000)
    fun nativeDeadlineAndRelease() = runBlocking {
        assertEquals("[result]\n你好😀", evaluateJavascript("'你好😀'"))
        repeat(3) {
            var serializationEntered = false
            val failure = runCatching {
                evaluateJavascript("({toJSON(){console.log('entered');while(true){}}})", 500) {
                    serializationEntered = true
                }
            }.exceptionOrNull()
            assertTrue(serializationEntered)
            assertTrue(failure is ToolExecutionFailure)
            assertEquals("[result]\n42", evaluateJavascript("6*7"))
        }
    }

    @Test(timeout = 30_000)
    fun cancellationStopsNativeBeforeTheNextCall() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Throwable>()
        val execution = async(Dispatchers.Default) {
            try {
                evaluateJavascript("console.log('started');while(true){}", 60_000) { started.complete(Unit) }
            } catch (error: Throwable) {
                stopped.complete(error)
                throw error
            }
        }
        try {
            withTimeout(5_000) { started.await() }
            withTimeout(3_000) { execution.cancelAndJoin() }
            assertTrue(stopped.await() is CancellationException)
            assertEquals("[result]\n2", evaluateJavascript("1+1"))
        } finally { execution.cancelAndJoin() }
    }
}
