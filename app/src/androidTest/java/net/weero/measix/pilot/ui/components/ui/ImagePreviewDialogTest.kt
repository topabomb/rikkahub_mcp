package net.weero.measix.pilot.ui.components.ui

import net.weero.measix.pilot.utils.exportImageBytes
import android.graphics.Bitmap
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import net.weero.measix.pilot.R
import net.weero.measix.pilot.ui.adaptive.LocalAdaptiveLayoutInfo
import net.weero.measix.pilot.ui.adaptive.rememberAdaptiveLayoutInfo

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class ImagePreviewDialogTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var originalLoader: coil3.ImageLoader
    private lateinit var loader: coil3.ImageLoader

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    @org.junit.Before fun installImageComponents() {
        originalLoader = coil3.SingletonImageLoader.get(compose.activity)
        loader = coil3.ImageLoader.Builder(compose.activity).components {
            add(net.weero.measix.pilot.service.ImageSourceInterceptor)
            add(net.weero.measix.pilot.service.ImageSourceKeyer)
            add(net.weero.measix.pilot.service.ImageSourceFetcherFactory)
            add(coil3.svg.SvgDecoder.Factory(scaleToDensity = false))
        }.build()
        coil3.SingletonImageLoader.setUnsafe(loader)
    }

    private fun queryExport(name: String, vararg columns: String): android.database.Cursor =
        requireNotNull(compose.activity.contentResolver.query(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            columns, android.os.Bundle().apply {
                putInt(android.provider.MediaStore.QUERY_ARG_MATCH_PENDING, android.provider.MediaStore.MATCH_INCLUDE)
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "_display_name = ?")
                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(name))
            }, null))

    private val images = mutableListOf<File>()
    private lateinit var viewerView: View
    private var visible by mutableStateOf(true)

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    @After
    fun cleanImages() {
        coil3.SingletonImageLoader.setUnsafe(originalLoader)
        loader.shutdown()
        images.forEach(File::delete)
    }

    @Test fun exportPreservesEncodedImageAndMime() = kotlinx.coroutines.runBlocking {
        val context = compose.activity
        val bytes = android.util.Base64.decode("R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==", android.util.Base64.DEFAULT)
        val source = net.weero.measix.pilot.service.ImageSource("export-gif", net.weero.measix.pilot.service.ImageOrigin.INLINE,
            verifyAccess = {}, readPayload = { bytes })
        val name = net.weero.measix.pilot.service.MediaExportService(io.mockk.mockk()).saveImage(context, source)
        val collection = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        context.contentResolver.query(collection, arrayOf("_id", "mime_type", "is_pending"), "_display_name = ?", arrayOf(name), null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            val uri = android.content.ContentUris.withAppendedId(collection, cursor.getLong(0))
            try {
                assertEquals("image/gif", cursor.getString(1))
                assertEquals(0, cursor.getInt(2))
                org.junit.Assert.assertArrayEquals(bytes, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            } finally { context.contentResolver.delete(uri, null, null) }
        }
    }

    @Test fun rejectedOrCancelledPublicationRemovesPendingMedia() = kotlinx.coroutines.runBlocking {
        val context = compose.activity
        val collection = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        for (cancel in listOf(false, true)) {
            val name = "viewer-rejected-${java.util.UUID.randomUUID()}.png"
            var sawPending = false
            val failure = IllegalStateException("original image access ended", java.io.IOException("publication authority rejected"))
            val result = runCatching {
                context.exportImageBytes(context, byteArrayOf(1, 2, 3), "image/png", name) {
                    queryExport(name, "is_pending").use {
                        sawPending = it.moveToFirst() && it.getInt(0) == 1
                    }
                    if (cancel) throw kotlinx.coroutines.CancellationException("cancel export")
                    throw failure
                }
            }
            assertTrue(sawPending)
            if (cancel) assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            else {
                val reported = requireNotNull(result.exceptionOrNull())
                assertEquals(failure.javaClass, reported.javaClass)
                assertEquals(failure.message, reported.message)
                assertEquals(failure.cause?.javaClass, reported.cause?.javaClass)
                assertEquals(failure.cause?.message, reported.cause?.message)
            }
            queryExport(name, "_id").use {
                assertFalse(it.moveToFirst())
            }
        }
    }

    @Test
    fun viewerFromThumbnailFillsWindow() {
        openViewer()
        assertFullWindow()
    }

    @Test fun saveReadFailureKeepsCopyableCauseAndTheOriginalPreviewLeaseWithoutRetrying() {
        val bytes = android.util.Base64.decode("R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==", android.util.Base64.DEFAULT)
        val lease = net.weero.measix.pilot.service.ConversationViewLease(kotlin.uuid.Uuid.random(),
            net.weero.measix.pilot.data.enterprise.RealmAccess.Personal, 0L) {}
        val fail = java.util.concurrent.atomic.AtomicBoolean(false)
        val successfulReads = java.util.concurrent.atomic.AtomicInteger()
        val failedReads = java.util.concurrent.atomic.AtomicInteger()
        val failure = java.io.IOException("save payload failed token=private-token",
            IllegalStateException("original image payload unavailable"))
        val source = net.weero.measix.pilot.service.ImageSource("save-read-failure", net.weero.measix.pilot.service.ImageOrigin.INLINE,
            verifyAccess = { lease.requireOpen() }, readPayload = {
                lease.requireOpen()
                if (fail.get()) { failedReads.incrementAndGet(); throw failure }
                successfulReads.incrementAndGet()
                bytes
            })
        val clipboard = compose.activity.getSystemService(android.content.ClipboardManager::class.java)
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAdaptiveLayoutInfo provides rememberAdaptiveLayoutInfo()) {
                if (visible) ImagePreviewDialog(listOf(source), { visible = false })
            }
        } }
        try {
            compose.waitUntil(5_000) { successfulReads.get() > 0 }
            compose.runOnIdle { fail.set(true) }
            val saveDescription = compose.activity.getString(R.string.image_viewer_save_content_description)
            compose.onNodeWithContentDescription(saveDescription).performClick()
            val diagnostic = "IOException: save payload failed token=<redacted>\n" +
                "Caused by: IllegalStateException: original image payload unavailable"
            compose.waitUntil(5_000) {
                compose.onAllNodes(androidx.compose.ui.test.hasText(diagnostic)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(diagnostic).assertIsDisplayed()
            compose.onNodeWithText("private-token", substring = true).assertDoesNotExist()
            compose.onNodeWithText(compose.activity.getString(R.string.application_recovery_retry)).assertDoesNotExist()
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.chat_page_copy_error)).performClick()
            compose.waitUntil(5_000) { clipboard.primaryClip?.getItemAt(0)?.text?.toString() == diagnostic }
            compose.runOnIdle { assertTrue(visible); assertFalse(lease.closed.value); assertEquals(1, failedReads.get()) }
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.update_card_close)).performClick()
            compose.onNodeWithContentDescription(saveDescription).assertIsDisplayed()
            compose.runOnIdle { assertTrue(visible); assertFalse(lease.closed.value); assertEquals(1, failedReads.get()) }
        } finally {
            lease.close()
            clipboard.clearPrimaryClip()
        }
    }

    @Test fun imageInformationFailureKeepsDiagnosticAndCanBeRetried() {
        val bytes = android.util.Base64.decode("R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==", android.util.Base64.DEFAULT)
        val fail = java.util.concurrent.atomic.AtomicBoolean(false)
        val retries = java.util.concurrent.atomic.AtomicInteger()
        val source = net.weero.measix.pilot.service.ImageSource("retry-image-info", net.weero.measix.pilot.service.ImageOrigin.NETWORK,
            displayName = "remote.gif", verifyAccess = {}, readPayload = {
                if (fail.get()) throw java.io.IOException("HTTP 503 image information unavailable")
                bytes
            })
        compose.setContent { MaterialTheme { ImagePreviewDialog(listOf(source), {}, onInfoRetry = {
            retries.incrementAndGet(); fail.set(false)
        }) } }
        compose.runOnIdle { fail.set(true) }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.image_viewer_info_content_description)).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText(compose.activity.getString(R.string.image_viewer_info_load_failed))).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("HTTP 503 image information unavailable", substring = true).assertExists()
        assertEquals(0, retries.get())
        compose.onNodeWithText(compose.activity.getString(R.string.enterprise_budget_refresh)).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("remote.gif")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("1 × 1").assertExists()
        assertEquals(1, retries.get())
    }

    @Test
    fun viewerFromBoundedDialogFillsWindow() {
        openViewer(nested = true)
        assertFullWindow()
    }

    @Test
    fun horizontalSwipeChangesPageAndVerticalDragDismisses() {
        openViewer()
        compose.onNodeWithText("1 / 2").assertExists()
        compose.waitUntil(5_000) { imageCoverage(red = true) > 0.1f }
        compose.onNode(isDialog()).performTouchInput {
            swipe(Offset(width * 0.8f, height * 0.5f), Offset(width * 0.2f, height * 0.5f), 400)
        }
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("2 / 2"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(5_000) { imageCoverage(red = false) > 0.1f }
        compose.onNode(isDialog()).performTouchInput {
            swipe(Offset(width * 0.5f, height * 0.3f), Offset(width * 0.5f, height * 0.8f), 600)
        }
        compose.waitUntil(5_000) { !visible }
        assertFalse(visible)
    }

    @Test
    fun bottomActionsStayInsideSystemBarAndCutoutInsets() {
        openViewer()
        val safeInsets = compose.runOnIdle {
            requireNotNull(ViewCompat.getRootWindowInsets(viewerView)).getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
        }
        listOf(R.string.image_viewer_info_content_description, R.string.image_viewer_save_content_description)
            .forEach { label ->
                val bounds = compose.onNodeWithContentDescription(compose.activity.getString(label))
                    .fetchSemanticsNode().boundsInRoot
                assertTrue("action left", bounds.left >= safeInsets.left)
                assertTrue("action top", bounds.top >= safeInsets.top)
                assertTrue("action right", bounds.right <= viewerView.width - safeInsets.right)
                assertTrue("action bottom", bounds.bottom <= viewerView.height - safeInsets.bottom)
            }
    }

    @Test fun authenticatedInspectionThumbnailLoadsAndOpensSharedViewer() = kotlinx.coroutines.runBlocking<Unit> {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        val bytes = java.io.ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle(); output.toByteArray()
        }
        val address = java.net.NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }.first { it is java.net.Inet4Address && it.isSiteLocalAddress }.hostAddress
        val server = java.net.ServerSocket(0)
        val credentials = okhttp3.Credentials.basic("user", "pass")
        val authorizedRequests = java.util.concurrent.atomic.AtomicInteger()
        val worker = kotlin.concurrent.thread(isDaemon = true) {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    val headers = generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }.toList()
                    val authorized = headers.any { it.equals("Authorization: $credentials", ignoreCase = true) }
                    if (authorized) authorizedRequests.incrementAndGet()
                    socket.getOutputStream().apply {
                        write((if (authorized) "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                        else "HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").toByteArray())
                        if (authorized) write(bytes)
                        flush()
                    }
                }
            } catch (error: java.net.SocketException) { if (!server.isClosed) throw error }
        }
        val sessions = io.mockk.mockk<net.weero.measix.pilot.data.enterprise.EnterpriseSessionController>()
        io.mockk.coEvery { sessions.withSelectedRealmSelection<Unit>(any(), any()) } coAnswers {
            secondArg<suspend () -> Unit>()()
        }
        val files = net.weero.measix.pilot.service.FileManagementApplicationService(io.mockk.mockk(), io.mockk.mockk(),
            net.weero.measix.pilot.service.ApplicationRecoveryGate().apply { ready() }, sessions)
        val view = net.weero.measix.pilot.service.ConversationViewLease(kotlin.uuid.Uuid.random(),
            net.weero.measix.pilot.data.enterprise.RealmAccess.Personal, 0) {}
        val url = "http://user:pass@$address:${server.localPort}/test.png"
        val image = requireNotNull(files.externalImageSource(view, url, allowLocalNetwork = true))
        val arguments = kotlinx.serialization.json.buildJsonObject {
            put("attachments", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(url))))
            put("request", kotlinx.serialization.json.JsonPrimitive("Read the image"))
        }
        val tool = me.rerere.ai.ui.UIMessagePart.Tool(localCallId = kotlin.uuid.Uuid.random(), stepId = kotlin.uuid.Uuid.random(),
            providerCallId = "image", toolName = "inspect_attachments", input = arguments.toString())
        val context = net.weero.measix.pilot.ui.components.message.tools.ToolUIContext(tool, arguments, true, null,
            net.weero.measix.pilot.service.runtime.ToolLivePhase.COMPLETED)
        try {
            compose.setContent { MaterialTheme {
                CompositionLocalProvider(net.weero.measix.pilot.ui.components.message.LocalAttachmentPreview provides
                    { ref -> if (ref == url) net.weero.measix.pilot.service.AttachmentPreview(url, image) else null }) {
                    net.weero.measix.pilot.ui.components.message.tools.AttachmentInspectionToolUI.Summary(context)
                }
            } }
            val thumbnail = compose.onNode(androidx.compose.ui.test.hasClickAction())
            compose.waitUntil(10_000) {
                val pixels = thumbnail.captureToImage().toPixelMap()
                val center = pixels[pixels.width / 2, pixels.height / 2]
                center.red > .9f && center.green < .1f
            }
            assertTrue(authorizedRequests.get() > 0)
            captureEvidence("inspection-http-thumbnail")
            thumbnail.performClick()
            compose.onNodeWithText("test.png").assertIsDisplayed()
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.image_viewer_close_content_description))
                .assertIsDisplayed().performClick()
        } finally { view.close(); server.close(); worker.join(5000) }
    }

    @Test fun validatedSvgRendersThroughTheSharedImageSourceAndDecoder() {
        val bytes = """<svg xmlns="http://www.w3.org/2000/svg" width="900" height="300">
            <rect width="900" height="300" fill="red"/></svg>""".toByteArray()
        val source = net.weero.measix.pilot.service.ImageSource("image-safe-svg",
            net.weero.measix.pilot.service.ImageOrigin.LOCAL, displayName = "safe.svg",
            verifyAccess = {}, readPayload = {
                net.weero.measix.pilot.service.workspace.validateWorkspaceImage(bytes, "safe.svg")
                bytes
            }, mimeHint = "image/svg+xml", gallerySaveSupported = false)
        compose.setContent { MaterialTheme {
            if (visible) ImagePreviewDialog(listOf(source), { visible = false })
        } }
        try {
            compose.waitUntil(5_000) { imageCoverage(red = true) > 0.1f }
        } finally { captureEvidence("image-svg-no-viewbox") }
        compose.onNodeWithText("safe.svg").assertIsDisplayed()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.image_viewer_save_content_description))
            .assertDoesNotExist()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.image_viewer_close_content_description))
            .assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { !visible }
    }

    @Test fun rejectedImageShowsTheReadDiagnosticAndKeepsCloseAvailable() {
        val source = net.weero.measix.pilot.service.ImageSource("image-invalid",
            net.weero.measix.pilot.service.ImageOrigin.LOCAL, displayName = "unsafe.svg",
            verifyAccess = {}, readPayload = { error("workspace_svg_external_resource") })
        compose.setContent { MaterialTheme {
            if (visible) ImagePreviewDialog(listOf(source), { visible = false }, showSaveAction = false)
        } }
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("workspace_svg_external_resource", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.image_viewer_close_content_description))
            .assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { !visible }
    }

    @Test
    fun singleTapTogglesControlsAndExplicitCloseDismisses() {
        openViewer()
        compose.waitUntil(5_000) { imageCoverage(red = true) > 0.1f }
        val close = compose.activity.getString(R.string.image_viewer_close_content_description)
        compose.onNodeWithContentDescription(close).assertIsDisplayed()
        compose.onNode(isDialog()).performTouchInput { click(center) }
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasContentDescription(close))
                .fetchSemanticsNodes().isEmpty()
        }
        assertTrue(visible)
        compose.onNode(isDialog()).performTouchInput { click(center) }
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasContentDescription(close))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(close).performClick()
        compose.waitUntil(5_000) { !visible }
    }

    @Test
    fun doubleTapZoomsAndVerticalPanDoesNotDismiss() {
        openViewer()
        compose.waitUntil(5_000) { imageCoverage(red = true) > 0.1f }
        val initialCoverage = imageCoverage(red = true)
        compose.onNode(isDialog()).performTouchInput { doubleClick(center) }
        compose.waitUntil(5_000) { imageCoverage(red = true) > initialCoverage + 0.05f }
        compose.onNode(isDialog()).performTouchInput {
            swipe(Offset(width * 0.5f, height * 0.3f), Offset(width * 0.5f, height * 0.8f), 600)
        }
        compose.waitForIdle()
        assertTrue("A zoomed image must pan instead of closing the viewer", visible)
        compose.onNodeWithText("1 / 2").assertExists()
    }

    private fun captureEvidence(name: String) {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "preview-evidence")
        check(directory.isDirectory || directory.mkdirs())
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "$name.png").outputStream().use {
                check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { screenshot.recycle() }
        listOf("mkdir -p /data/local/tmp/preview-ui-evidence",
            "cp ${directory.absolutePath}/$name.png /data/local/tmp/preview-ui-evidence/$name.png").forEach { command ->
            val output = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
                .bufferedReader().use { it.readText() }
            check(output.isBlank()) { output }
        }
    }

    private fun imageCoverage(red: Boolean): Float {
        val pixels = compose.onNode(isDialog()).captureToImage().toPixelMap()
        var imageSamples = 0
        val samples = 20
        repeat(samples) { x ->
            repeat(samples) { y ->
                val pixel = pixels[(x * pixels.width / samples), (y * pixels.height / samples)]
                val matches = if (red) pixel.red > 0.8f && pixel.blue < 0.2f
                    else pixel.blue > 0.8f && pixel.red < 0.2f
                if (matches && pixel.green < 0.2f) imageSamples++
            }
        }
        return imageSamples.toFloat() / (samples * samples)
    }

    private fun openViewer(nested: Boolean = false) {
        val urls = listOf(android.graphics.Color.RED, android.graphics.Color.BLUE).map { color ->
            val file = File.createTempFile("viewer-test-", ".png", compose.activity.cacheDir)
            images += file
            val bitmap = Bitmap.createBitmap(900, 300, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            net.weero.measix.pilot.service.ImageSource(
                file.name, net.weero.measix.pilot.service.ImageOrigin.LOCAL,
                displayName = file.name,
                verifyAccess = { check(file.exists()) },
                readPayload = { file.readBytes() },
            )
        }
        compose.setContent {
            MaterialTheme {
                val content: @androidx.compose.runtime.Composable () -> Unit = {
                    Box(Modifier.size(72.dp)) {
                        if (visible) {
                            ImagePreviewDialog(
                                images = urls,
                                onDismissRequest = { visible = false },
                                overlay = {
                                    val view = LocalView.current
                                    SideEffect {
                                        viewerView = view
                                    }
                                },
                            )
                        }
                    }
                }
                if (nested) Dialog(onDismissRequest = {}) { content() } else content()
            }
        }
        compose.waitForIdle()
    }

    private fun assertFullWindow() {
        compose.runOnIdle {
            val bounds = compose.activity.windowManager.currentWindowMetrics.bounds
            val location = IntArray(2)
            viewerView.getLocationOnScreen(location)
            assertEquals("left", bounds.left, location[0])
            assertEquals("top", bounds.top, location[1])
            assertEquals("width", bounds.width(), viewerView.width)
            assertEquals("height", bounds.height(), viewerView.height)
        }
    }
}
