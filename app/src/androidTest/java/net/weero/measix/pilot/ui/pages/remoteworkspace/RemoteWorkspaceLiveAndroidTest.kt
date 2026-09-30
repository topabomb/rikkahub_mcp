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

    @Test fun realFileRoundTripConditionsLifecycleAndNativePage() {
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
                    // Exercise the real enrollment owner; synchronize the deployed publication explicitly.
                    // Only the unpublished fixture requires no Applied state.
                    // Opt-in material already confirms its origin.
                    val platform = koin.get<PlatformEnterpriseService>()
                    val access = platform.enroll(EnrollmentMaterialParser().parse(input.getString("enrollment")),
                        android.os.Build.MODEL, net.weero.measix.pilot.BuildConfig.VERSION_NAME)
                    val available = sessions.state.value as EnterpriseState.Available
                    val configurationPublished = input.optBoolean("configurationPublished", false)
                    if (!configurationPublished) {
                        assertEquals(EnterpriseSessionPhase.CONFIGURATION_PENDING, available.manifest.phase)
                        assertNull(available.manifest.applied)
                    }
                    if (configurationPublished) {
                        assertEquals(EnterpriseSynchronizationCommandResult.COMPLETED, enterprise.synchronize(access))
                        val synchronized = (sessions.state.value as EnterpriseState.Available).manifest
                        assertEquals(EnterpriseSessionPhase.READY, synchronized.phase)
                        assertNotNull(synchronized.applied)
                    }
                    val before = requireNotNull(sessions.readPresentation().selection)
                    // No prior views or resources exist on this unbound test device. Select through
                    // the Session owner here; realm selection does not synchronize configuration.
                    if (before.access != access) sessions.switchRealm(RealmSwitchRequest(before, access)) {}
                    var selection = requireNotNull(sessions.readPresentation().selection)
                    var opened = remote.open(selection).also { handle = it }
                    assertTrue(remote.summary.value!!.canOpenFiles)
                    assertEquals(input.optBoolean("expectedMcpAvailable", false), remote.summary.value!!.mcpAvailable)
                    evidence.put("mcpAvailable", remote.summary.value!!.mcpAvailable)
                    val folder = "android-validation-${System.currentTimeMillis()}"
                    assertEquals(RemoteOutcome.SUCCEEDED, remote.createDirectory(opened, folder).outcome)
                    val textPath = "$folder/notes.txt"
                    val original = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) +
                        "# Android remote workspace\r\n\r\nBody \uFEFF marker. Files before configuration.\r\n".toByteArray()
                    assertEquals(RemoteOutcome.SUCCEEDED, remote.upload(opened, textPath, null, original.size.toLong(), { original.inputStream() }).outcome)
                    var file = remote.list(opened, folder).files.single()
                    assertConflict(remote.upload(opened, textPath, null, 5, { "wrong".byteInputStream() }))
                    assertArrayEquals(original, readBytes(remote, opened, file))
                    val document = remote.readText(opened, file)
                    assertTrue(document.content.bom)
                    val afterRead = (sessions.state.value as EnterpriseState.Available).manifest
                    if (configurationPublished) {
                        assertEquals(EnterpriseSessionPhase.READY, afterRead.phase)
                        assertNotNull(afterRead.applied)
                    } else {
                        assertEquals(EnterpriseSessionPhase.CONFIGURATION_PENDING, afterRead.phase)
                        assertNull(afterRead.applied)
                    }
                    evidence.put("configurationPendingWithoutAppliedSnapshot", !configurationPublished)
                        .put("configurationPublished", configurationPublished)
                    val competing = remote.readText(opened, file)
                    assertEquals(RemoteOutcome.SUCCEEDED, remote.save(opened, competing, competing.content.text + "Second editor\n").outcome)
                    val conflict = remote.save(opened, document, document.content.text + "My unsaved draft\n")
                    assertEquals(RemoteOutcome.FAILED, conflict.outcome)
                    assertTrue(conflict.diagnostic.orEmpty().contains("409") || conflict.diagnostic.orEmpty().contains("412"))
                    assertEquals(RemoteOutcome.SUCCEEDED, remote.save(opened, document, "My unsaved draft\n", "$folder/draft-copy.txt").outcome)
                    val copied = remote.list(opened, folder).files.single { it.name == "draft-copy.txt" }
                    assertArrayEquals(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) +
                        "My unsaved draft\r\n".toByteArray(), readBytes(remote, opened, copied))
                    val currentText = remote.list(opened, folder).files.single { it.path == textPath }
                    assertArrayEquals(original + "Second editor\r\n".toByteArray(), readBytes(remote, opened, currentText))
                    evidence.put("doubleEditorConflictAndSaveAs", true).put("bomBodyMarkerAndCrLfPreserved", true)
                    val sourcePath = "$folder/source.txt"
                    val targetPath = "$folder/target.txt"
                    put(remote, opened, sourcePath, "original source".toByteArray())
                    put(remote, opened, targetPath, "original target".toByteArray())
                    val staleSource = remote.list(opened, folder).files.single { it.path == sourcePath }
                    put(remote, opened, sourcePath, "updated source".toByteArray(), staleSource.etag)
                    for (action in listOf(RemoteFileAction.COPY, RemoteFileAction.MOVE)) {
                        assertConflict(remote.mutate(opened, action, staleSource, "$folder/stale-${action.name}.txt"))
                        assertFalse(remote.list(opened, folder).files.any { it.name == "stale-${action.name}.txt" })
                    }
                    val currentSource = remote.list(opened, folder).files.single { it.path == sourcePath }
                    assertArrayEquals("updated source".toByteArray(), readBytes(remote, opened, currentSource))
                    assertEquals(RemoteOutcome.SUCCEEDED, remote.mutate(opened, RemoteFileAction.COPY, currentSource,
                        "$folder/copied.txt").outcome)
                    assertEquals(RemoteOutcome.SUCCEEDED, remote.mutate(opened, RemoteFileAction.MOVE, currentSource,
                        "$folder/moved.txt").outcome)
                    assertFalse(remote.list(opened, folder).files.any { it.path == sourcePath })
                    for (name in listOf("copied.txt", "moved.txt")) {
                        assertArrayEquals("updated source".toByteArray(), readBytes(remote, opened,
                            remote.list(opened, folder).files.single { it.name == name }))
                    }
                    evidence.put("createAndStaleCopyMoveConditions", true)
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
                    val beforeSwitch = opened
                    val switchStarted = CompletableDeferred<Unit>()
                    var switchClosed = false
                    val switchingRead = launch {
                        remote.download(beforeSwitch, file, { object : OutputStream() {
                            override fun write(value: Int) = Unit
                            override fun write(bytes: ByteArray, offset: Int, count: Int) {
                                switchStarted.complete(Unit); Thread.sleep(25)
                            }
                            override fun close() { switchClosed = true }
                        } })
                    }
                    switchStarted.await()
                    enterprise.switchRealm(RealmSwitchRequest(selection, RealmAccess.Personal))
                    withTimeout(10_000) { switchingRead.join() }
                    assertTrue(switchClosed)
                    assertTrue(switchingRead.isCancelled)
                    assertFalse(remote.isValid(beforeSwitch))
                    enterprise.switchRealm(RealmSwitchRequest(requireNotNull(sessions.readPresentation().selection), access))
                    selection = requireNotNull(sessions.readPresentation().selection)
                    assertFalse(remote.isValid(beforeSwitch))
                    remote.close(beforeSwitch)
                    opened = remote.open(selection).also { handle = it }
                    evidence.put("switchRealmCancelsAndClosesInFlightRead", true)
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
                    val markdown = "# Remote Markdown\n\n![Space image](images/space%20name.png)\n\n" +
                        "![Chinese image](images/%E4%B8%AD%E6%96%87.png)\n\n![Plus image](images/a+b.png)\n"
                    assertEquals(RemoteOutcome.SUCCEEDED, remote.createDirectory(opened, "$folder/images").outcome)
                    assertEquals(RemoteOutcome.SUCCEEDED, remote.createDirectory(opened, "$folder/docs").outcome)
                    for (name in listOf("space name.png", "中文.png", "a+b.png")) {
                        put(remote, opened, "$folder/images/$name", picture)
                    }
                    put(remote, opened, "$folder/docs/parent.md",
                        "# Parent image\n\n![Parent image](../images/space%20name.png)\n".toByteArray())
                    for (name in listOf("selected-a.txt", "selected-b.txt")) put(remote, opened, "$folder/$name", name.toByteArray())
                    for ((name, bytes) in listOf("sample.png" to picture, "sample.pdf" to pdf, "readme.md" to markdown.toByteArray())) {
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
                    fileNode(folder).performClick()
                    compose.waitUntil(30_000) { vm.state.value.directory?.path == folder }
                    fileNode("notes.txt").assertIsDisplayed()
                    capture("remote-live-files.png")
                    fileNode("sample.pdf").performClick()
                    compose.waitUntil(10_000) { compose.onAllNodesWithText("1 / 1").fetchSemanticsNodes().isNotEmpty() }
                    capture("remote-live-pdf.png")
                    androidx.test.espresso.Espresso.pressBack()
                    fileNode("sample.png").performClick()
                    compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(context.getString(net.weero.measix.pilot.R.string.image_viewer_info_content_description)).fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithContentDescription(context.getString(net.weero.measix.pilot.R.string.image_viewer_info_content_description)).performClick()
                    compose.waitUntil(10_000) { compose.onAllNodesWithText("512 × 320").fetchSemanticsNodes().isNotEmpty() }
                    androidx.test.espresso.Espresso.pressBack()
                    capture("remote-live-image.png")
                    androidx.test.espresso.Espresso.pressBack()
                    fileNode("readme.md").performClick()
                    compose.waitUntil(10_000) { compose.onAllNodesWithText("Remote Markdown").fetchSemanticsNodes().isNotEmpty() }
                    compose.onAllNodesWithText("Remote Markdown").onFirst().assertIsDisplayed()
                    for (description in listOf("Space image", "Chinese image", "Plus image")) {
                        compose.onNodeWithContentDescription(description, substring = true).performScrollTo()
                        assertDecodedImage(description)
                    }
                    capture("remote-live-markdown.png")
                    androidx.test.espresso.Espresso.pressBack()
                    fileNode("docs").performClick()
                    compose.waitUntil(10_000) { vm.state.value.directory?.path == "$folder/docs" }
                    fileNode("parent.md").performClick()
                    assertDecodedImage("Parent image")
                    androidx.test.espresso.Espresso.pressBack()
                    compose.runOnIdle { vm.browse(folder) }
                    compose.waitUntil(10_000) { vm.state.value.directory?.path == folder }
                    val peer = remote.open(selection).also { handle = it }
                    fileNode("selected-a.txt").performTouchInput { longClick() }
                    fileNode("selected-b.txt").performClick()
                    compose.onNodeWithText(context.getString(net.weero.measix.pilot.R.string.remote_workspace_selected, 2)).assertIsDisplayed()
                    for ((name, remaining) in listOf("selected-a.txt" to 1, "selected-b.txt" to 0)) {
                        val selectedFile = remote.list(peer, folder).files.single { it.name == name }
                        assertEquals(RemoteOutcome.SUCCEEDED, remote.mutate(peer, RemoteFileAction.DELETE, selectedFile).outcome)
                        compose.runOnIdle { vm.retry() }
                        compose.waitUntil(10_000) { vm.state.value.directory?.files?.none { it.name == name } == true }
                        compose.onNodeWithText(name).assertDoesNotExist()
                        if (remaining > 0) compose.onNodeWithText(context.getString(net.weero.measix.pilot.R.string.remote_workspace_selected, remaining)).assertIsDisplayed()
                        else compose.onNodeWithText(context.getString(net.weero.measix.pilot.R.string.remote_workspace_title)).assertIsDisplayed()
                    }
                    val source = remote.list(peer, folder).files.single { it.name == "moved.txt" }
                    val target = remote.list(peer, folder).files.single { it.name == "target.txt" }
                    compose.runOnIdle { vm.rename(requireNotNull(vm.state.value.handle), source, target.path) }
                    compose.waitUntil(10_000) { vm.state.value.overwrite != null }
                    put(remote, peer, target.path, "changed while confirming".toByteArray(), target.etag)
                    compose.onNodeWithText(context.getString(net.weero.measix.pilot.R.string.common_confirm)).performClick()
                    compose.waitUntil(10_000) { !vm.state.value.running && vm.state.value.results.isNotEmpty() && vm.state.value.directory != null }
                    assertConflict(vm.state.value.results.first())
                    assertArrayEquals("changed while confirming".toByteArray(), readBytes(remote, peer,
                        remote.list(peer, folder).files.single { it.path == target.path }))
                    assertArrayEquals("updated source".toByteArray(), readBytes(remote, peer,
                        remote.list(peer, folder).files.single { it.path == source.path }))
                    compose.runOnIdle { vm.clearResults() }
                    compose.runOnIdle { vm.rename(requireNotNull(vm.state.value.handle), source, target.path) }
                    compose.waitUntil(10_000) { vm.state.value.overwrite != null }
                    compose.onNodeWithText(context.getString(net.weero.measix.pilot.R.string.common_confirm)).performClick()
                    compose.waitUntil(10_000) {
                        !vm.state.value.running && vm.state.value.results.firstOrNull()?.outcome == RemoteOutcome.SUCCEEDED &&
                            vm.state.value.directory?.files?.none { it.path == source.path } == true
                    }
                    compose.onNodeWithText(source.name).assertDoesNotExist()
                    assertArrayEquals("updated source".toByteArray(), readBytes(remote, peer,
                        remote.list(peer, folder).files.single { it.path == target.path }))
                    remote.close(peer); handle = null
                    capture("remote-live-updated-files.png")
                    evidence.put("realSelectionReconciliationAndMutationRefresh", true)
                        .put("overwriteConfirmationRacePreservesBothFiles", true)
                        .put("encodedSpaceChinesePlusAndParentMarkdownImages", true)
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
                        try {
                            val currentToken = platform.accessToken(access.sessionId, restored.connection)
                            client.upload(restored.connection, currentToken.value, restored.space, "old-space-write.txt", null, 3,
                                { "bad".byteInputStream() })
                            fail("An old pinned space must reject a write")
                        } catch (error: PlatformHttpException) { assertEquals("workspace_space_mismatch", error.problem?.code) }
                        assertFalse(remote.isValid(restored))
                        remote.close(restored)
                        val replacement = remote.open(selection).also { handle = it }
                        assertNotEquals(restored.space, replacement.space)
                        assertTrue(remote.list(replacement, "").files.isEmpty())
                        evidence.put("replacedSpaceRejectsOriginalReadAndWrite", true)
                    }
                    assertEquals(EnterpriseSynchronizationCommandResult.COMPLETED, enterprise.synchronize(access))
                    val finalManifest = (sessions.state.value as EnterpriseState.Available).manifest
                    if (configurationPublished) {
                        assertEquals(EnterpriseSessionPhase.READY, finalManifest.phase)
                        assertNotNull(finalManifest.applied)
                    } else {
                        assertEquals(EnterpriseSessionPhase.CONFIGURATION_PENDING, finalManifest.phase)
                        assertNull(finalManifest.applied)
                    }
                    evidence.put("currentPublishedConfigurationSynchronized", configurationPublished)
                        .put("unpublishedConfigurationRemainsPending", !configurationPublished)
                }
            }
        } finally {
            runBlocking { handle?.let { remote.close(it) } }
            compose.runOnUiThread { store.clear() }
            File(context.cacheDir, "remote-workspace-live-evidence.json").writeText(evidence.toString(2))
        }
    }

    private suspend fun put(remote: RemoteWorkspaceService, handle: RemoteWorkspaceHandle,
        path: String, bytes: ByteArray, etag: String? = null,
    ) {
        val result = remote.upload(handle, path, etag, bytes.size.toLong(), { bytes.inputStream() })
        assertEquals(result.diagnostic, RemoteOutcome.SUCCEEDED, result.outcome)
    }

    private suspend fun readBytes(remote: RemoteWorkspaceService, handle: RemoteWorkspaceHandle, file: RemoteFile): ByteArray =
        java.io.ByteArrayOutputStream().also { bytes -> remote.download(handle, file, { bytes }) }.toByteArray()

    private fun assertConflict(result: RemoteOperationResult) {
        assertEquals(result.diagnostic, RemoteOutcome.FAILED, result.outcome)
        assertTrue(result.diagnostic, result.diagnostic.orEmpty().contains("409") || result.diagnostic.orEmpty().contains("412"))
    }

    private fun fileNode(name: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("remote-file-list").performScrollToNode(hasText(name))
        return compose.onNodeWithText(name)
    }

    private fun assertDecodedImage(description: String) {
        // The square placeholder is replaced only after the authorized 512x320 image decodes.
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription(description, substring = true).fetchSemanticsNodes()
                .any { it.boundsInRoot.width / it.boundsInRoot.height > 1.5f }
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
        try {
            val uri = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "${name.removeSuffix(".png")}-${java.util.UUID.randomUUID()}.png")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/CodexRemoteWorkspace")
            }))
            try {
                requireNotNull(context.contentResolver.openOutputStream(uri)).use {
                    assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                }
            } catch (error: Throwable) {
                context.contentResolver.delete(uri, null, null)
                throw error
            }
        } finally { bitmap.recycle() }
    }
}
