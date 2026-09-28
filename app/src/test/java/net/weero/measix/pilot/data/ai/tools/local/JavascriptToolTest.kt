package net.weero.measix.pilot.data.ai.tools.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.dokar.quickjs.QuickJsException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.core.ToolExecutionFailure
import me.rerere.ai.ui.UIMessagePart
import kotlinx.serialization.json.jsonObject

class JavascriptToolTest {
    @Test(timeout = 20_000)
    fun `native completion values and unicode console keep physical lines`() = runBlocking {
        assertEquals("[result]\n3", evaluateJavascript("1 + 2"))
        assertEquals("[result]\n10", evaluateJavascript("const x = 5; x * 2"))
        assertEquals("[result]\n{\"value\":42}", evaluateJavascript("({value:42})"))
        assertEquals("[result]\nnull", evaluateJavascript("undefined"))
        assertEquals("[console]\n[LOG] 你好 42\n[WARN] 注意\n[result]\n😀",
            evaluateJavascript("console.log('你好',42); console.warn('注意'); '😀'"))
    }

    @Test(timeout = 20_000)
    fun `native deadline includes getters toJSON and console formatting`() = runBlocking {
        evaluateJavascript("1")
        for (code in listOf(
            "console.log('entered'); while(true){}",
            "({toJSON(){console.log('entered');while(true){}}})",
            "({get value(){console.log('entered');while(true){}}})",
            "console.log({toJSON(){console.log('entered');while(true){}}})",
            "console.log('entered'); while(true){console.log('spam')}",
        )) {
            var entered = false
            val failure = runCatching { evaluateJavascript(code, 250) { if (it == "[LOG] entered") entered = true } }.exceptionOrNull()
            assertTrue("The user expression must execute before its deadline", entered)
            assertEquals("javascript_timeout", reason(failure))
            assertEquals("[result]\n2", evaluateJavascript("1+1"))
        }
    }

    @Test(timeout = 20_000)
    fun `native logs and results are bounded and script errors are not successful text`() = runBlocking {
        val output = evaluateJavascript("for(let i=0;i<10000;i++)console.log('1234567890');1")
        assertTrue(output.length < JS_MAX_LOG_CHARS + 100)
        assertTrue(output.contains("[Logs truncated]"))
        assertEquals("javascript_output_limit", reason(runCatching {
            evaluateJavascript("'x'.repeat(2*1024*1024)")
        }.exceptionOrNull()))
        for (code in listOf("new ArrayBuffer(128*1024*1024)", "function f(){return f()} f()", "throw new Error('original failure')")) {
            val failure = runCatching { evaluateJavascript(code) }.exceptionOrNull()
            assertTrue(failure is QuickJsException)
            assertTrue(!failure?.message.isNullOrBlank())
            assertEquals("[result]\n3", evaluateJavascript("1+2"))
        }
    }

    @Test(timeout = 20_000)
    fun `parent cancellation interrupts a confirmed running native evaluation`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val observed = CompletableDeferred<Throwable>()
        val execution = async(Dispatchers.Default) {
            try {
                evaluateJavascript("console.log('started');while(true){}", 60_000) { started.complete(Unit) }
            } catch (error: Throwable) {
                observed.complete(error)
                throw error
            }
        }
        try {
            withTimeout(5_000) { started.await() }
            withTimeout(2_000) { execution.cancelAndJoin() }
            assertTrue(observed.await() is CancellationException)
            assertEquals("[result]\n4", evaluateJavascript("2+2"))
        } finally { execution.cancelAndJoin() }
    }

    private fun reason(error: Throwable?): String {
        assertTrue("Expected a typed tool failure, got ${error?.javaClass?.simpleName}", error is ToolExecutionFailure)
        val output = ((error as ToolExecutionFailure).output.single() as UIMessagePart.Text).text
        assertTrue(error.cause != null)
        return Json.parseToJsonElement(output).jsonObject.getValue("reason").jsonPrimitive.content
    }

    @Test(timeout = 20_000)
    fun `parent cancellation wins when the native deadline has also elapsed`() = runBlocking {
        evaluateJavascript("1")
        val entered = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val observed = CompletableDeferred<Throwable>()
        val execution = async(Dispatchers.Default) {
            try {
                evaluateJavascript("console.log('barrier');while(true){}", 100) {
                    entered.complete(Unit)
                    release.await()
                }
            } catch (error: Throwable) {
                observed.complete(error)
                throw error
            }
        }
        try {
            withTimeout(5_000) { entered.await() }
            // Native code is confirmed inside the barrier; let its deadline expire before releasing it.
            kotlinx.coroutines.delay(200)
            execution.cancel()
            release.countDown()
            withTimeout(2_000) { execution.join() }
            assertTrue(observed.await() is CancellationException)
        } finally {
            release.countDown()
            execution.cancelAndJoin()
        }
    }

    @Test
    fun `missing or non-string code is rejected before QuickJS starts`() {
        val tool = buildJavascriptTool()
        for (input in listOf("{}", "{\"code\":null}", "{\"code\":5}")) {
            val error = tool.validateArguments(Json.parseToJsonElement(input))!!
            assertEquals("invalid_arguments", error.getValue("reason").jsonPrimitive.content)
            assertEquals("code must be a string.", error.getValue("detail").jsonPrimitive.content)
        }
    }

    @Test
    fun `local range tools reject malformed fields before permission checks`() {
        val invalidBegin = Json.parseToJsonElement("{\"begin\":{}}")
        val invalidRange = Json.parseToJsonElement("{\"range\":\"yesterday\"}")
        val invalidTop = Json.parseToJsonElement("{\"top\":\"many\"}")
        assertEquals("invalid_arguments", validateLocalTimeRangeArguments(
            invalidBegin, setOf("today", "week"), "top",
        )!!.getValue("reason").jsonPrimitive.content)
        assertEquals("invalid_arguments", validateLocalTimeRangeArguments(
            invalidRange, setOf("today", "week"), "top",
        )!!.getValue("reason").jsonPrimitive.content)
        assertEquals("invalid_arguments", validateLocalTimeRangeArguments(
            invalidTop, setOf("today", "week"), "top",
        )!!.getValue("reason").jsonPrimitive.content)
    }

    @Test
    fun `output preserves console entries as physical lines`() {
        val output = formatJavascriptOutput(
            logs = listOf("[LOG] first", "[WARN] second"),
            result = "42",
        )

        assertEquals(
            listOf("[console]", "[LOG] first", "[WARN] second", "[result]", "42"),
            output.lines(),
        )
        assertFalse("\\n" in output)
    }

    @Test
    fun `output without console starts with result section`() {
        assertEquals("[result]\nnull", formatJavascriptOutput(emptyList(), "null"))
    }
}
