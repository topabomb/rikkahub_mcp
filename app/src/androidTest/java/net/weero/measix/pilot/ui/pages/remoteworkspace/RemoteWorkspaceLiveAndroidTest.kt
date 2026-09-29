package net.weero.measix.pilot.ui.pages.remoteworkspace

import android.content.ContentValues
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.*
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.service.*
import net.weero.measix.pilot.service.remoteworkspace.*
import net.weero.measix.pilot.ui.context.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** Explicit opt-in on a dedicated device; private material is read only from its app cache. */
@RunWith(AndroidJUnit4::class)
class RemoteWorkspaceLiveAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun filesOnlyRoundTripConflictCancellationAndRealFilePage() {
        val inputPath = InstrumentationRegistry.getArguments().getString("remoteWorkspaceInput")
        assumeTrue(inputPath != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val inputFile = File(requireNotNull(inputPath)).canonicalFile
        require(inputFile.toPath().startsWith(context.cacheDir.canonicalFile.toPath()))
        val input = JSONObject(inputFile.readText())
        check(inputFile.delete())
        val koin = GlobalContext.get()
        val sessions = koin.get<EnterpriseSessionController>()
        val enterprise = koin.get<EnterpriseApplicationService>()
        val remote = koin.get<RemoteWorkspaceService>()
        val evidence = JSONObject()
        val store = ViewModelStore()
        var handle: RemoteWorkspaceHandle? = null
        try {
            runBlocking {
                withTimeout(300_000) {
                    koin.get<ApplicationRecoveryCoordinator>()
                    koin.get<ApplicationRecoveryGate>().awaitReady()
                    val initial = (sessions.state.value as EnterpriseState.Available).manifest
                    check(initial.session == null && initial.pendingEnrollment == null && initial.lastIdentity == null) {
                        "remote_workspace_live_requires_unbound_test_device"
                    }
                    enterprise.confirmJoin(requireNotNull(enterprise.join(input.getString("enrollment"))))
                    val available = sessions.state.value as EnterpriseState.Available
                    assertEquals(EnterpriseSessionPhase.CONFIGURATION_PENDING, available.manifest.phase)
                    assertNull(available.manifest.applied)
                    val session = requireNotNull(available.manifest.session)
                    val access = RealmAccess.Enterprise(session.identity.scope, session.id)
                    val before = requireNotNull(sessions.readPresentation().selection)
                    if (before.access != access) enterprise.switchRealm(RealmSwitchRequest(before, access))
                    val selection = requireNotNull(sessions.readPresentation().selection)
                    val opened = remote.open(selection).also { handle = it }
                    assertTrue(remote.summary.value!!.canOpenFiles)
                    assertFalse(remote.summary.value!!.mcpAvailable)
                    evidence.put("configurationPendingFilesOnly", true)
                    val folder = "android-validation-${System.currentTimeMillis()}"
                    assertEquals(RemoteOutcome.SUCCEEDED, remote.createDirectory(opened, folder).outcome)
                    val textPath = "$folder/notes.txt"
                    val original = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) +
                        "# Android remote workspace\r\n\r\nFiles are available without MCP.\r\n".toByteArray()
                    assertEquals(RemoteOutcome.SUCCEEDED, remote.upload(opened, textPath, null, original.size.toLong(), { original.inputStream() }).outcome)
                    var file = remote.list(opened, folder).files.single()
                    val document = remote.readText(opened, file)
                    assertTrue(document.content.bom)
                    val competing = remote.readText(opened, file)
                    assertEquals(RemoteOutcome.SUCCEEDED, remote.save(opened, competing, competing.content.text + "Second editor\n").outcome)
                    val conflict = remote.save(opened, document, document.content.text + "My unsaved draft\n")
                    assertEquals(RemoteOutcome.FAILED, conflict.outcome)
                    assertTrue(conflict.diagnostic.orEmpty().contains("409") || conflict.diagnostic.orEmpty().contains("412"))
                    assertEquals(RemoteOutcome.SUCCEEDED, remote.save(opened, document, "My unsaved draft\n", "$folder/draft-copy.txt").outcome)
                    evidence.put("doubleEditorConflictAndSaveAs", true)
                    val length = 64L * 1024 * 1024
                    val uploadDigest = MessageDigest.getInstance("SHA-256")
                    val largePath = "$folder/64MiB.bin"
                    val result = remote.upload(opened, largePath, null, length, {
                        object : InputStream() {
                            var position = 0L
                            override fun read(): Int = if (position == length) -1 else ((position++ * 31 + 7) and 255).toInt()
                            override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                                if (position == length) return -1
                                val size = minOf(count.toLong(), length - position).toInt()
                                repeat(size) { bytes[offset + it] = ((position++ * 31 + 7) and 255).toByte() }
                                uploadDigest.update(bytes, offset, size)
                                return size
                            }
                        }
                    })
                    assertEquals(result.diagnostic, RemoteOutcome.SUCCEEDED, result.outcome)
                    file = remote.list(opened, folder).files.single { it.path == largePath }
                    val downloadDigest = MessageDigest.getInstance("SHA-256")
                    var received = 0L
                    remote.download(opened, file, { object : OutputStream() {
                        override fun write(value: Int) { downloadDigest.update(value.toByte()); received++ }
                        override fun write(bytes: ByteArray, offset: Int, count: Int) { downloadDigest.update(bytes, offset, count); received += count }
                    } })
                    assertEquals(length, received)
                    val uploadedHash = uploadDigest.digest()
                    val downloadedHash = downloadDigest.digest()
                    assertArrayEquals(uploadedHash, downloadedHash)
                    evidence.put("roundTripBytes", received).put("sha256Matched", true)
                        .put("sha256", downloadedHash.joinToString("") { "%02x".format(it) })
                    val started = CompletableDeferred<Unit>()
                    var closed = false
                    val slow = launch { remote.download(opened, file, { object : OutputStream() {
                        override fun write(value: Int) = Unit
                        override fun write(bytes: ByteArray, offset: Int, count: Int) {
                            started.complete(Unit)
                            Thread.sleep(25)
                        }
                        override fun close() { closed = true }
                    } }) }
                    started.await()
                    withTimeout(10_000) { slow.cancelAndJoin() }
                    assertTrue(closed)
                    evidence.put("slowDownloadCancelledAndClosed", true)
                    val client = koin.get<PlatformWorkspaceClient>()
                    val token = koin.get<PlatformEnterpriseService>().accessToken(access.sessionId, opened.connection)
                    try {
                        client.list(opened.connection, token.value, "spc_550e8400-e29b-41d4-a716-446655440000", "")
                        fail("Mismatching space must be rejected")
                    } catch (error: PlatformHttpException) { assertEquals("workspace_space_mismatch", error.problem?.code) }
                    evidence.put("foreignSpaceRejected", true)
                    assertEquals(RemoteOutcome.SUCCEEDED, remote.mutate(opened, RemoteFileAction.DELETE, file).outcome)
                    val bitmap = android.graphics.Bitmap.createBitmap(512, 320, android.graphics.Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.rgb(42, 88, 128))
                    android.graphics.Canvas(bitmap).drawText("Remote workspace", 40f, 160f,
                        android.graphics.Paint().apply { color = android.graphics.Color.WHITE; textSize = 36f })
                    val picture = java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                    bitmap.recycle()
                    val pdf = java.io.ByteArrayOutputStream().also { bytes ->
                        val pdf = android.graphics.pdf.PdfDocument()
                        try {
                            val page = pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(512, 720, 1).create())
                            page.canvas.drawText("Remote PDF preview", 40f, 90f,
                                android.graphics.Paint().apply { textSize = 28f })
                            pdf.finishPage(page); pdf.writeTo(bytes)
                        } finally { pdf.close() }
                    }.toByteArray()
                    val markdown = "# Remote Markdown\n\n![Authorized relative image](sample.png)\n".toByteArray()
                    for ((name, bytes) in listOf("sample.png" to picture, "sample.pdf" to pdf, "readme.md" to markdown)) {
                        assertEquals(RemoteOutcome.SUCCEEDED, remote.upload(opened, "$folder/$name", null,
                            bytes.size.toLong(), { bytes.inputStream() }).outcome)
                    }
                    remote.close(opened); handle = null
                    lateinit var vm: RemoteWorkspaceVM
                    compose.runOnUiThread { vm = RemoteWorkspaceVM(selection, remote, context); store.put("remote-live", vm) }
                    compose.setContent {
                        MaterialTheme {
                            CompositionLocalProvider(LocalNavController provides remember { Navigator(mutableStateListOf(Screen.Enterprise)) },
                                LocalSettings provides Settings()) { RemoteWorkspacePage(selection, vm) }
                        }
                    }
                    compose.waitUntil(30_000) { vm.state.value.directory != null }
                    compose.onNodeWithText(folder).performClick()
                    compose.waitUntil(30_000) { vm.state.value.directory?.path == folder }
                    compose.onNodeWithText("notes.txt").assertIsDisplayed()
                    capture("remote-live-files.png")
                    compose.onNodeWithText("sample.pdf").performClick()
                    compose.waitUntil(10_000) { compose.onAllNodesWithText("1 / 1").fetchSemanticsNodes().isNotEmpty() }
                    capture("remote-live-pdf.png")
                    androidx.test.espresso.Espresso.pressBack()
                    compose.onNodeWithText("sample.png").performClick()
                    compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(context.getString(net.weero.measix.pilot.R.string.image_viewer_info_content_description)).fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithContentDescription(context.getString(net.weero.measix.pilot.R.string.image_viewer_info_content_description)).performClick()
                    compose.waitUntil(10_000) { compose.onAllNodesWithText("512 × 320").fetchSemanticsNodes().isNotEmpty() }
                    androidx.test.espresso.Espresso.pressBack()
                    capture("remote-live-image.png")
                    androidx.test.espresso.Espresso.pressBack()
                    compose.onNodeWithText("readme.md").performClick()
                    compose.waitUntil(10_000) { compose.onAllNodesWithText("Remote Markdown").fetchSemanticsNodes().isNotEmpty() }
                    compose.onAllNodesWithText("Remote Markdown").onFirst().assertIsDisplayed()
                    // The square placeholder is replaced only after the authorized 512x320 image decodes.
                    compose.waitUntil(10_000) {
                        compose.onAllNodesWithContentDescription("Authorized relative image", substring = true).fetchSemanticsNodes()
                            .any { it.boundsInRoot.width / it.boundsInRoot.height > 1.5f }
                    }
                    capture("remote-live-markdown.png")
                    evidence.put("nativePdfAndImagePreviews", true).put("restrictedMarkdownRendered", true)
                    evidence.put("realDirectoryRendered", true).put("folder", folder)
                    if (input.optBoolean("lifecycle")) {
                        compose.runOnUiThread { store.clear() }
                        val beforeDisconnect = remote.open(selection).also { handle = it }
                        awaitHost(context.cacheDir, "disconnect")
                        remote.refresh(selection)
                        assertEquals(RemoteWorkspaceStatus.DISCONNECTED, remote.summary.value?.status)
                        assertFalse(remote.isValid(beforeDisconnect))
                        remote.close(beforeDisconnect)
                        awaitHost(context.cacheDir, "restore")
                        remote.refresh(selection)
                        evidence.put("filesAvailableImmediatelyAfterRestore", remote.summary.value!!.canOpenFiles)
                        if (!remote.summary.value!!.canOpenFiles) {
                            assertEquals("dav_credential_unavailable", remote.summary.value!!.reason)
                        }
                        awaitHost(context.cacheDir, "files")
                        val restored = remote.open(selection).also { handle = it }
                        assertTrue(remote.summary.value!!.canOpenFiles)
                        assertFalse(remote.isValid(beforeDisconnect))
                        remote.list(restored, "")
                        evidence.put("disconnectRestoreRevokedOriginalHandle", true)
                        awaitHost(context.cacheDir, "replace")
                        try {
                            remote.list(restored, "")
                            fail("An old handle must not read the replacement space")
                        } catch (error: PlatformHttpException) {
                            assertEquals("workspace_space_mismatch", error.problem?.code)
                        }
                        assertFalse(remote.isValid(restored))
                        remote.close(restored)
                        val replacement = remote.open(selection).also { handle = it }
                        assertNotEquals(restored.space, replacement.space)
                        assertTrue(remote.list(replacement, "").files.isEmpty())
                        evidence.put("replacedSpaceRejectsOriginalHandle", true)
                    }
                }
            }
        } finally {
            runBlocking { handle?.let { remote.close(it) } }
            compose.runOnUiThread { store.clear() }
            File(context.cacheDir, "remote-workspace-live-evidence.json").writeText(evidence.toString(2))
        }
    }

    private suspend fun awaitHost(cache: File, step: String) {
        val reply = File(cache, "remote-workspace-$step.done")
        check(!reply.exists())
        File(cache, "remote-workspace-step.txt").writeText(step)
        withTimeout(90_000) { while (!reply.exists()) delay(200) }
        check(reply.readText() == "ok") { "Host lifecycle step failed: $step" }
        check(reply.delete())
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/CodexRemoteWorkspace")
        })!!
        context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
