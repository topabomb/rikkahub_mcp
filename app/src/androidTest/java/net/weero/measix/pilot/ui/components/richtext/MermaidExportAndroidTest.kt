package net.weero.measix.pilot.ui.components.richtext

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dokar.sonner.rememberToasterState
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.weero.measix.pilot.service.ImageSource
import net.weero.measix.pilot.ui.context.LocalToaster
import net.weero.measix.pilot.ui.theme.LocalDarkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.resume

@RunWith(AndroidJUnit4::class)
class MermaidExportAndroidTest {
    @get:Rule val compose = androidx.compose.ui.test.junit4.v2.createAndroidComposeRule<ComponentActivity>()

    @Test fun renderedExportsBelongToOriginalDocumentAndLatestExplicitRequest() = runBlocking {
        val principal = mutableIntStateOf(0)
        val request = mutableIntStateOf(0)
        val dark = mutableStateOf(false)
        val received = listOf(AtomicInteger(), AtomicInteger())
        val resolvers: List<suspend (String) -> ImageSource?> = received.map { count ->
            { _: String -> count.incrementAndGet(); null }
        }
        compose.setContent {
            MaterialTheme(colorScheme = if (dark.value) darkColorScheme() else lightColorScheme()) {
                CompositionLocalProvider(LocalToaster provides rememberToasterState(), LocalDarkMode provides dark.value,
                    LocalImageSourceResolver provides resolvers[principal.intValue]) {
                    Mermaid("graph TD; A-->B", request.intValue)
                }
            }
        }
        suspend fun evaluate(view: WebView, script: String): String = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation -> view.evaluateJavascript(script) {
                if (continuation.isActive) continuation.resume(it)
            } }
        }
        fun find(view: View): WebView? = when (view) {
            is WebView -> view
            is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
            else -> null
        }
        suspend fun readyDocument(): WebView {
            var view: WebView? = null
            compose.runOnIdle { view = find(compose.activity.window.decorView) }
            val current = requireNotNull(view)
            withTimeout(20_000) {
                while (evaluate(current, "typeof exportSvgToPng === 'function' && document.querySelector('svg') !== null") != "true") delay(50)
            }
            return current
        }
        fun click() { compose.runOnIdle { request.intValue++ }; compose.waitForIdle() }
        val first = readyDocument()
        click()
        compose.waitUntil(10_000) { received[0].get() == 1 }
        compose.runOnIdle { principal.intValue = 1 }
        val second = readyDocument()
        assertNotSame(first, second)
        assertEquals(0, received[1].get())

        // Leave the canvas callback outstanding, then replace its entire rendering document.
        evaluate(second, "window.exportSvgToPng = function(id) { window.pendingExport = id; }; true;")
        click()
        assertEquals("2", evaluate(second, "window.pendingExport"))
        compose.runOnIdle { dark.value = true }
        val third = readyDocument()
        assertNotSame(second, third)
        assertEquals(0, received[1].get())
        click()
        compose.waitUntil(10_000) { received[1].get() == 1 }

        evaluate(third, "window.savedExport = window.exportSvgToPng; delete window.exportSvgToPng; true;")
        click()
        compose.waitForIdle()
        evaluate(third, "window.exportSvgToPng = window.savedExport; true;")
        click()
        compose.waitUntil(10_000) { received[1].get() == 2 }

        evaluate(third, "window.exportSvgToPng = function(id) { window.pendingExport = id; }; true;")
        click()
        assertEquals("6", evaluate(third, "window.pendingExport"))
        click()
        assertEquals("7", evaluate(third, "window.pendingExport"))
        evaluate(third, "AndroidInterface.exportImage(6, 'AQ=='); AndroidInterface.exportImage(7, 'AQ=='); true;")
        compose.waitUntil(5_000) { received[1].get() == 3 }
        assertEquals(1, received[0].get())
    }
}
