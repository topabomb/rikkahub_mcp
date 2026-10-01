package net.weero.measix.pilot.utils

import android.os.SystemClock
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UserVisibleDiagnosticAndroidTest {
    @Test
    fun nativeLogRetainsLongUnicodeMessageCauseSuppressedAndFinalFrameWithoutSecrets() {
        val tag = "Diag${System.nanoTime()}"
        val cause = IOException("cause-tail-marker")
        cause.stackTrace = arrayOf(StackTraceElement("Files", "read", "Files.kt", 42))
        val failure = IOException("文件😀".repeat(1600) + " message-tail-marker token=private-token", cause)
        failure.stackTrace = arrayOf(StackTraceElement("Files", "save", "Files.kt", 84))
        failure.addSuppressed(IOException("cleanup-tail-marker sk-abcdefghijklmnop").apply {
            stackTrace = arrayOf(StackTraceElement("Files", "cleanup", "Files.kt", 21))
        })
        logDiagnosticFailure(tag, "operation apiKey=header-secret failed", failure)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + 5000
        var output: String
        do {
            output = ParcelFileDescriptor.AutoCloseInputStream(
                automation.executeShellCommand("logcat -d -v raw -s $tag:E"),
            ).bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (output.contains("Files.read(Files.kt:42)")) break
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        assertTrue(output.contains("文件😀"))
        assertTrue(output.contains("message-tail-marker"))
        assertTrue(output.contains("Caused by: java.io.IOException: cause-tail-marker"))
        assertTrue(output.contains("Suppressed: java.io.IOException: cleanup-tail-marker"))
        assertTrue(output.contains("Files.read(Files.kt:42)"))
        assertFalse(output.contains("private-token"))
        assertFalse(output.contains("header-secret"))
        assertFalse(output.contains("sk-abcdefghijklmnop"))
        assertFalse(output.contains('\uFFFD'))
    }
}
