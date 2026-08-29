package me.rerere.workspace

import org.junit.Assert.assertFalse
import org.junit.Test

/** 不依赖 /bin/sh 的共享进程协议测试，在 Windows 与 Unix CI 都验证 stdin EOF。 */
class ProcessReadResultTest {
    @Test
    fun `null stdin closes the child pipe immediately`() {
        val result = eofWaitingProcess().readResult(timeoutMillis = 5_000, stdin = null)

        assertFalse("child waiting for EOF must not time out", result.timedOut)
    }

    @Test
    fun `empty stdin is written and closed by the single writer`() {
        val result = eofWaitingProcess().readResult(timeoutMillis = 5_000, stdin = ByteArray(0))

        assertFalse("empty input must still deliver EOF", result.timedOut)
    }

    private fun eofWaitingProcess(): Process = if (System.getProperty("os.name").orEmpty().startsWith("Windows")) {
        ProcessBuilder("cmd.exe", "/d", "/c", "more >NUL").start()
    } else {
        ProcessBuilder("/bin/sh", "-c", "cat >/dev/null").start()
    }
}
