package net.weero.measix.pilot.ui.components.richtext

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dokar.sonner.rememberToasterState
import io.mockk.coEvery
import io.mockk.mockk
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.service.*
import net.weero.measix.pilot.ui.context.*
import net.weero.measix.pilot.ui.theme.LocalDarkMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RichTextHostAndroidTest {
    @get:Rule val compose = androidx.compose.ui.test.junit4.v2.createAndroidComposeRule<ComponentActivity>()

    @Test fun screenshotConsumerRendersWithoutAnInteractiveHostAndCleansItsCancelledTree() = kotlinx.coroutines.runBlocking {
        compose.setContent { androidx.compose.material3.Text("Host") }
        val exports = mockk<MediaExportService>()
        var dimensions: Pair<Int, Int>? = null
        coEvery { exports.saveAndShareBitmap(any(), any(), any(), any()) } coAnswers {
            val bitmap = secondArg<android.graphics.Bitmap>()
            dimensions = bitmap.width to bitmap.height
            arg<suspend () -> Unit>(3)()
        }
        val work = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())
        try {
            val capture = work.async {
                net.weero.measix.pilot.ui.pages.chat.exportToImage(
                    context = compose.activity, density = androidx.compose.ui.unit.Density(1f), conversationTitle = "Export",
                    messages = listOf(me.rerere.ai.ui.UIMessage(role = me.rerere.ai.core.MessageRole.ASSISTANT,
                        parts = listOf(me.rerere.ai.ui.UIMessagePart.Text("Original text\n\n```kotlin\nval original = 1\n```")))),
                    settings = Settings.dummy(), mediaExportService = exports, attachmentPreview = { null }, imageResolver = { null },
                    contentSource = RenderedContentSource.Static, verifyAccess = {},
                )
            }
            compose.waitUntil(15_000) { capture.isCompleted }
            capture.await()
            assertTrue(requireNotNull(dimensions).first > 0 && requireNotNull(dimensions).second > 0)
            var disposed = false
            var mounted = false
            var childrenBefore = 0
            compose.runOnIdle { childrenBefore = (compose.activity.window.decorView as android.view.ViewGroup).childCount }
            val disposeError = IllegalStateException("test disposal failure")
            val cancelled = work.async {
                net.weero.measix.pilot.ui.components.ui.BitmapComposer().composableToBitmap(
                    compose.activity, width = androidx.compose.ui.unit.Dp(100f), screenDensity = androidx.compose.ui.unit.Density(1f),
                ) {
                    DisposableEffect(Unit) { mounted = true; onDispose { disposed = true; throw disposeError } }
                    androidx.compose.material3.Text("Cancelled content")
                }
            }
            compose.waitUntil(5_000) { mounted }
            cancelled.cancel()
            compose.waitUntil(5_000) { cancelled.isCompleted && disposed }
            compose.runOnIdle { assertEquals(childrenBefore, (compose.activity.window.decorView as android.view.ViewGroup).childCount) }
            val cancellation = runCatching { cancelled.await() }.exceptionOrNull()
            assertTrue(cancellation is kotlinx.coroutines.CancellationException)
            assertTrue(requireNotNull(cancellation).suppressed.contains(disposeError))
        } finally { work.cancel() }
    }

    @Test fun savedNavigationCannotRebuildAPreviewFromItsOldHtmlOrSource() {
        kotlinx.coroutines.runBlocking {
            val recovery = org.koin.core.context.GlobalContext.get().get<ApplicationRecoveryCoordinator>()
            val ready = kotlinx.coroutines.withTimeout(20_000) { recovery.state.first { it !is ApplicationRecoveryState.Loading } }
            assertEquals(ApplicationRecoveryState.Ready, ready)
        }
        val initial = net.weero.measix.pilot.Screen.ContentPreview(document = RenderedContent(RenderedContentSource.Static,
            "<html><head><title>Original preview</title></head><body>Private content</body></html>"))
        val restoration = StateRestorationTester(compose)
        var route: net.weero.measix.pilot.Screen.ContentPreview? = null
        restoration.setContent {
            val stack = androidx.navigation3.runtime.rememberNavBackStack(initial)
            val current = stack.last() as net.weero.measix.pilot.Screen.ContentPreview
            route = current
            MaterialTheme {
                CompositionLocalProvider(LocalSettings provides Settings.dummy(), LocalDarkMode provides false,
                    LocalNavController provides Navigator(stack), LocalToaster provides rememberToasterState()) {
                    net.weero.measix.pilot.ui.pages.webview.ContentPreviewPage(current.document)
                }
            }
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Original preview").fetchSemanticsNodes().isNotEmpty() }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(compose.activity.getString(R.string.rendered_content_unavailable)).assertIsDisplayed()
        compose.onNodeWithText("Original preview").assertDoesNotExist()
        compose.runOnIdle { assertNull(requireNotNull(route).document); assertEquals(initial.id, route?.id) }
    }

    @Test fun pickerResultsKeepClickedTextAndSourceAndCannotRecoverAnUnownedRequest() {
        val source = mutableStateOf<RenderedContentSource>(RenderedContentSource.Static)
        val text = mutableStateOf("original code")
        val table = mutableStateOf<String?>(null)
        val received = CopyOnWriteArrayList<TextDocumentExport?>()
        val files = mockk<FileManagementApplicationService>(relaxed = true)
        val exports = mockk<MediaExportService>()
        coEvery { exports.saveTextDocument(any(), any(), any()) } coAnswers { received.add(thirdArg()); Unit }
        var requestCode = 0
        val intents = mutableListOf<Intent>()
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(code: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                requestCode = code
                intents += contract.createIntent(compose.activity, input)
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalActivityResultRegistryOwner provides owner,
                    LocalSettings provides Settings.dummy(), LocalDarkMode provides false,
                    LocalNavController provides Navigator(remember { mutableStateListOf<NavKey>() }),
                    LocalToaster provides rememberToasterState(),
                ) {
                    RichTextHost(source.value, files = files, exports = exports) {
                        table.value?.let { MarkdownBlock(it) } ?: HighlightCodeBlock(text.value, "kotlin")
                    }
                }
            }
        }
        fun clickExport() { compose.onNodeWithContentDescription(compose.activity.getString(R.string.chat_page_save)).performClick() }
        clickExport()
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intents.single().action)
        assertEquals("text/plain", intents.single().type)
        compose.runOnIdle { text.value = "replacement code"; source.value = RenderedContentSource.UserConfiguration }
        clickExport()
        assertEquals(1, intents.size)
        compose.runOnIdle { registry.dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(Uri.parse("content://documents/first"))) }
        compose.waitUntil(5_000) { received.size == 1 }
        assertEquals("original code", received[0]?.text)
        assertSame(RenderedContentSource.Static, received[0]?.source)
        clickExport()
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { registry.dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(Uri.parse("content://documents/restored"))) }
        compose.waitUntil(5_000) { received.size == 2 }
        assertNull(received[1])
        compose.runOnIdle { table.value = "| Name | Value |\n| --- | --- |\n| original | 1 |" }
        compose.onNodeWithContentDescription("Download").performClick()
        assertEquals("text/csv", intents.last().type)
        compose.runOnIdle {
            table.value = "| Name | Value |\n| --- | --- |\n| replacement | 2 |"
            registry.dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(Uri.parse("content://documents/table")))
        }
        compose.waitUntil(5_000) { received.size == 3 }
        assertEquals("Name,Value\noriginal,1\n", received[2]?.text)
        assertSame(RenderedContentSource.UserConfiguration, received[2]?.source)
    }

    @Test fun bothMarkdownEnginesAndHtmlLinksUseTheOriginalHostAction() {
        val files = mockk<FileManagementApplicationService>(relaxed = true)
        val exports = mockk<MediaExportService>()
        val received = CopyOnWriteArrayList<Pair<RenderedContentSource, String>>()
        coEvery { exports.openContentLink(any(), any(), any()) } coAnswers {
            received += secondArg<RenderedContentSource>() to thirdArg<String>(); Unit
        }
        val mode = mutableIntStateOf(0)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalSettings provides Settings.dummy(), LocalDarkMode provides false,
                    LocalNavController provides Navigator(remember { mutableStateListOf<NavKey>() }),
                    LocalToaster provides rememberToasterState()) {
                    RichTextHost(RenderedContentSource.UserConfiguration, files = files, exports = exports) {
                        when (mode.intValue) {
                            0 -> MarkdownBlock("[Owned link](/upload/one.pdf)")
                            1 -> MarkdownNew("<p><a href='/upload/two.pdf'>Owned link</a></p>")
                            else -> SimpleHtmlBlock("<p><a href='/upload/three.pdf'>Owned link</a></p>")
                        }
                    }
                }
            }
        }
        for (index in 0..2) {
            compose.runOnIdle { mode.intValue = index }
            val node = compose.onNodeWithText("Owned link")
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            node.performTouchInput { click(layouts.single().getBoundingBox(0).center) }
            try { compose.waitUntil(5_000) { received.size == index + 1 } }
            catch (error: AssertionError) { throw AssertionError("Link renderer $index did not invoke its host", error) }
        }
        assertEquals(listOf("/upload/one.pdf", "/upload/two.pdf", "/upload/three.pdf"), received.map { it.second })
        assertTrue(received.all { it.first == RenderedContentSource.UserConfiguration })
    }
}
