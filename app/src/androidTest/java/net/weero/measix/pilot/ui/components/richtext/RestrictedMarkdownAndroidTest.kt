package net.weero.measix.pilot.ui.components.richtext

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.ui.context.LocalSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class RestrictedMarkdownAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun rawHtmlInsideTableRemainsVisibleTextWithoutWebViewOrExternalResources() {
        val html = "<script>window.remoteWorkspaceExecuted = true</script>"
        val imageReads = AtomicInteger()
        val openedLinks = AtomicInteger()
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalSettings provides Settings()) {
                    RestrictedMarkdown(
                        "| File content |\n| --- |\n| $html |",
                        images = { imageReads.incrementAndGet(); null },
                        openLink = { openedLinks.incrementAndGet() },
                    )
                }
            }
        }
        compose.onAllNodesWithText(html, substring = true).onFirst().assertIsDisplayed()
        compose.runOnIdle {
            assertFalse(containsWebView(compose.activity.window.decorView))
            assertEquals(0, imageReads.get())
            assertEquals(0, openedLinks.get())
        }
    }

    private fun containsWebView(view: View): Boolean = view is WebView ||
        (view is ViewGroup && (0 until view.childCount).any { containsWebView(view.getChildAt(it)) })
}
