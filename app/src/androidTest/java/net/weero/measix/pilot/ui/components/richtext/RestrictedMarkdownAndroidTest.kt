package net.weero.measix.pilot.ui.components.richtext

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.weero.measix.pilot.R
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

    @Test fun restrictedFileTableAndCodeOfferCopyWithoutUnownedExportActions() {
        val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        compose.runOnUiThread { clipboard.setPrimaryClip(ClipData.newPlainText("test", "before copy")) }
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalSettings provides Settings()) {
                    RestrictedMarkdown(
                        "| Action | Result |\n| --- | --- |\n| Save | Version |\n\n```kotlin\nval original = 1\n```",
                        images = { null }, openLink = {},
                    )
                }
            }
        }
        compose.onAllNodesWithContentDescription(compose.activity.getString(R.string.markdown_table_download)).assertCountEquals(0)
        compose.onAllNodesWithContentDescription(compose.activity.getString(R.string.chat_page_save)).assertCountEquals(0)
        compose.onAllNodesWithContentDescription(compose.activity.getString(R.string.copy)).onFirst().performClick()
        compose.waitUntil(5_000) {
            clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.contains("| Action |") == true
        }
        compose.runOnIdle { assertEquals(true, clipboard.primaryClip?.getItemAt(0)?.text?.contains("| Save |")) }
    }

    private fun containsWebView(view: View): Boolean = view is WebView ||
        (view is ViewGroup && (0 until view.childCount).any { containsWebView(view.getChildAt(it)) })
}
