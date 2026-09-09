package me.rerere.workspace

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class WorkspaceProcessLifetimeTest {
    @Test fun timeoutWaitsForTerminationEvenWhenDestroyFails() {
        val failure = IllegalStateException("kill rejected")
        val process = DeferredExitProcess(false, failure)
        val outcome = AtomicReference<Throwable?>()
        val worker = Thread { try { process.readResult(1) } catch (error: Throwable) { outcome.set(error) } }.apply { start() }
        try {
            assertTrue(process.awaitingExit.await(5, TimeUnit.SECONDS))
            assertTrue(worker.isAlive)
            assertNull(outcome.get())
        } finally { process.exited.countDown(); worker.join(5_000) }
        assertFalse(worker.isAlive)
        assertSame(failure, outcome.get())
    }

    @Test fun repeatedCancellationCannotReleaseAStillRunningProcess() {
        val process = DeferredExitProcess(true)
        val outcome = AtomicReference<Throwable?>()
        val worker = Thread { try { process.readResult(60_000) } catch (error: Throwable) { outcome.set(error) } }.apply { start() }
        try {
            assertTrue(process.entered.await(5, TimeUnit.SECONDS))
            worker.interrupt()
            assertTrue(process.awaitingExit.await(5, TimeUnit.SECONDS))
            worker.interrupt()
            assertTrue(worker.isAlive)
            assertNull(outcome.get())
        } finally { process.exited.countDown(); worker.join(5_000) }
        assertFalse(worker.isAlive)
        assertTrue(outcome.get() is InterruptedException)
    }

    private class DeferredExitProcess(private val interrupt: Boolean, private val killFailure: Throwable? = null) : Process() {
        val entered = CountDownLatch(1)
        val awaitingExit = CountDownLatch(1)
        val exited = CountDownLatch(1)
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            entered.countDown()
            if (interrupt) CountDownLatch(1).await()
            return false
        }
        override fun waitFor(): Int { awaitingExit.countDown(); exited.await(); return 0 }
        override fun exitValue(): Int { check(exited.count == 0L); return 0 }
        override fun destroy() = Unit
        override fun destroyForcibly(): Process { killFailure?.let { throw it }; return this }
    }
}
