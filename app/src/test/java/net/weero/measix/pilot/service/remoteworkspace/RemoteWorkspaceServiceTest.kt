package net.weero.measix.pilot.service.remoteworkspace

import io.mockk.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.service.ApplicationRecoveryGate
import net.weero.measix.pilot.service.PlatformEnterpriseService
import net.weero.measix.pilot.utils.userVisibleDiagnostic
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteWorkspaceServiceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val ready = PlatformWorkspaceProjection(1, PlatformWorkspaceProjectionState.CONNECTED, 1,
        "spc_550e8400-e29b-41d4-a716-446655440000", mcpAvailable = false, filesAvailable = true,
        mcpReason = "mcp_not_published", filesReason = "available", serviceState = PlatformWorkspaceProjectionServiceState.ENABLED)
    private val file = RemoteFile("a.txt", false, 1, null, "\"v1\"")
    private val success = PlatformWorkspaceFileResult(PlatformWorkspaceFileResultOutcome.SUCCEEDED, emptyList(), false)

    private inner class Fixture(val sessions: EnterpriseSessionController, val service: RemoteWorkspaceService,
        val client: PlatformWorkspaceClient, val selection: RealmSelection)

    private suspend fun TestScope.fixture(): Fixture {
        val sessions = EnterpriseSessionController(enterpriseTestStore(temporary.newFolder()))
        sessions.enrollFixture(exampleEnterprisePackage())
        val client = mockk<PlatformWorkspaceClient>()
        coEvery { client.state(any(), any()) } returns ready
        coEvery { client.list(any(), any(), any(), any()) } returns PlatformWorkspaceFileList(emptyList())
        val platform = PlatformEnterpriseService(sessions, mockk())
        val service = RemoteWorkspaceService(sessions, platform, client, ApplicationRecoveryGate().apply { ready() },
            CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job])), temporary.newFolder())
        val selection = sessions.readPresentation().selection!!
        service.summary.first { it?.status == RemoteWorkspaceStatus.AVAILABLE }
        return Fixture(sessions, service, client, selection)
    }

    @Test fun `files without MCP open and a read failure requires successful explicit reading`() = runTest {
        val f = fixture()
        val handle = f.service.open(f.selection)
        assertTrue(f.service.summary.value!!.canOpenFiles)
        coEvery { f.client.list(any(), any(), any(), any()) } throws PlatformHttpException(503, null, "DAV unavailable")
        try { f.service.list(handle, ""); fail() } catch (_: PlatformHttpException) { }
        assertEquals(RemoteWorkspaceStatus.READ_FAILED, f.service.summary.value!!.status)
        f.service.refresh(f.selection)
        assertEquals(RemoteWorkspaceStatus.READ_FAILED, f.service.summary.value!!.status)
        coEvery { f.client.list(any(), any(), any(), any()) } returns PlatformWorkspaceFileList(emptyList())
        f.service.list(handle, "")
        assertTrue(f.service.summary.value!!.canOpenFiles)
    }

    @Test fun `unsupported Core hides only optional workspace and later upgrade is discovered`() = runTest {
        val f = fixture()
        val previous = f.service.open(f.selection)
        coEvery { f.client.state(any(), any()) } throws WorkspaceProtocolUnavailableException("workspace_endpoint_not_supported")
        f.service.refresh(f.selection)
        assertNull(f.service.summary.value)
        assertFalse(f.service.isValid(previous))
        assertEquals(f.selection, f.sessions.readPresentation().selection)
        try { f.service.list(previous, ""); fail("old capability survived downgrade") } catch (_: IllegalStateException) { }
        coVerify(exactly = 0) { f.client.list(any(), any(), any(), any()) }
        coEvery { f.client.state(any(), any()) } returns ready
        f.service.refresh(f.selection)
        assertEquals(RemoteWorkspaceStatus.AVAILABLE, f.service.summary.value!!.status)
        assertFalse(f.service.isValid(previous))
        assertTrue(f.service.isValid(f.service.open(f.selection)))
    }

    @Test fun `workspace domain 404 retains failure diagnostics and enterprise identity`() = runTest {
        val f = fixture()
        coEvery { f.client.state(any(), any()) } throws PlatformHttpException(404,
            PlatformProblem("about:blank", "Missing workspace", 404, "workspace_not_found"), "workspace row missing")
        f.service.refresh(f.selection)
        assertEquals(RemoteWorkspaceStatus.FAILED, f.service.summary.value!!.status)
        assertTrue(f.service.summary.value!!.diagnostic!!.contains("workspace_not_found"))
        assertEquals(f.selection, f.sessions.readPresentation().selection)
    }

    @Test fun `switch cancels IO under lock but waits for lease cleanup only outside lock`() = runTest {
        val f = fixture()
        val handle = f.service.open(f.selection)
        val started = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        val exited = CompletableDeferred<Unit>()
        coEvery { f.client.list(any(), any(), any(), any()) } coAnswers {
            started.complete(Unit)
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { cleanup.await(); exited.complete(Unit) } }
        }
        val read = async { f.service.list(handle, "") }
        started.await()
        lateinit var receipt: RemoteWorkspaceRevocation
        val personal = f.sessions.switchRealm(RealmSwitchRequest(f.selection, RealmAccess.Personal)) {
            receipt = f.service.revoke(it)
        }
        assertFalse(exited.isCompleted)
        val close = async { receipt.awaitClosed() }
        runCurrent(); assertFalse(close.isCompleted)
        cleanup.complete(Unit); close.await()
        f.sessions.awaitPlatformOperations(f.selection.access as RealmAccess.Enterprise)
        assertTrue(read.isCancelled)
        f.sessions.switchRealm(RealmSwitchRequest(personal, f.selection.access)) {}
        assertFalse(f.service.isValid(handle))
    }

    @Test fun `507 unknown prevents resending the same target but permits another name`() = runTest {
        val f = fixture(); val handle = f.service.open(f.selection)
        coEvery { f.client.upload(any(), any(), any(), any(), any(), any(), any(), any()) } throws PlatformHttpException(507,
            PlatformProblem("about:blank", "unknown", 507, "workspace_result_unknown"), "disk full after write")
        val result = f.service.upload(handle, file.path, file.etag, 1, { byteArrayOf(1).inputStream() })
        assertEquals(RemoteOutcome.UNKNOWN, result.outcome)
        val blocked = f.service.upload(handle, file.path, file.etag, 1, { byteArrayOf(1).inputStream() })
        assertEquals(RemoteOutcome.UNKNOWN, blocked.outcome)
        coVerify(exactly = 1) { f.client.upload(any(), any(), any(), any(), any(), any(), any(), any()) }
        coEvery { f.client.upload(any(), any(), any(), any(), any(), any(), any(), any()) } returns success
        assertEquals(RemoteOutcome.SUCCEEDED, f.service.upload(handle, "copy.txt", null, 1, { byteArrayOf(1).inputStream() }).outcome)
    }

    @Test fun `space replacement invalidates existing handle before next file request`() = runTest {
        val f = fixture(); val handle = f.service.open(f.selection)
        coEvery { f.client.state(any(), any()) } returns ready.copy(bindingRevision = 2,
            agentSpaceId = "spc_550e8400-e29b-41d4-a716-446655440001")
        f.service.refresh(f.selection)
        assertFalse(f.service.isValid(handle))
        try { f.service.list(handle, ""); fail() } catch (_: IllegalStateException) { }
        coVerify(exactly = 0) { f.client.list(any(), any(), any(), any()) }
    }

    @Test fun `output close failure deletes only owned unfinished destination and retains cleanup diagnostic`() = runTest {
        val f = fixture(); val handle = f.service.open(f.selection)
        coEvery { f.client.download(any(), any(), any(), any(), any(), any(), any()) } returns WorkspaceContentMetadata("\"v1\"", 1, null)
        var deletes = 0
        val destination = object : RemoteExportDestination {
            override val description = "content://owned/document/7"
            override fun open() = object : ByteArrayOutputStream() { override fun close() { throw IOException("provider close failed") } }
            override fun delete(): Boolean { deletes++; return false }
        }
        try { f.service.export(handle, file, destination); fail() }
        catch (error: IOException) {
            assertEquals("provider close failed", error.message)
            assertTrue(error.userVisibleDiagnostic().contains(destination.description))
        }
        assertEquals(1, deletes)
        assertEquals(RemoteWorkspaceStatus.AVAILABLE, f.service.summary.value!!.status)
    }

    @Test fun `connection change fences new admission and never revives an old handle`() = runTest {
        val f = fixture(); val handle = f.service.open(f.selection)
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val changing = async { f.service.changeConnection(f.selection.access) { entered.complete(Unit); finish.await() } }
        entered.await()
        try {
            try { f.service.open(f.selection); fail("new management session admitted during address change") }
            catch (error: IllegalStateException) { assertEquals("workspace_connection_change_in_progress", error.message) }
            assertFalse(f.service.isValid(handle))
            assertEquals(RemoteOutcome.FAILED, f.service.upload(handle, "a.txt", null, 1, { byteArrayOf(1).inputStream() }).outcome)
            coVerify(exactly = 0) { f.client.upload(any(), any(), any(), any(), any(), any(), any(), any()) }
        } finally { finish.complete(Unit); changing.await() }
        val replacement = f.service.open(f.selection)
        assertTrue(f.service.isValid(replacement))
        assertFalse(f.service.isValid(handle))
    }

    @Test fun `failed status query blocks writing through a previously available handle`() = runTest {
        val f = fixture(); val handle = f.service.open(f.selection)
        coEvery { f.client.state(any(), any()) } throws IOException("status connection lost")
        f.service.refresh(f.selection)
        assertEquals(RemoteWorkspaceStatus.FAILED, f.service.summary.value!!.status)
        assertEquals(RemoteOutcome.FAILED, f.service.upload(handle, file.path, file.etag, 1, { byteArrayOf(1).inputStream() }).outcome)
        coVerify(exactly = 0) { f.client.upload(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `refresh keeps an admitted read alive but cannot be bypassed by its late result`() = runTest {
        val f = fixture(); val handle = f.service.open(f.selection)
        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        val queryStarted = CompletableDeferred<Unit>()
        val releaseQuery = CompletableDeferred<Unit>()
        coEvery { f.client.list(any(), any(), any(), any()) } coAnswers {
            readStarted.complete(Unit); releaseRead.await(); PlatformWorkspaceFileList(emptyList())
        }
        val reading = async { f.service.list(handle, "") }
        readStarted.await()
        coEvery { f.client.state(any(), any()) } coAnswers { queryStarted.complete(Unit); releaseQuery.await(); ready }
        val refreshing = async { f.service.refresh(f.selection) }
        queryStarted.await()
        coEvery { f.client.upload(any(), any(), any(), any(), any(), any(), any(), any()) } returns success
        val writing = async { f.service.upload(handle, file.path, file.etag, 1, { byteArrayOf(1).inputStream() }) }
        try {
            assertTrue("status refresh must not revoke the management identity", f.service.isValid(handle))
            releaseRead.complete(Unit)
            assertEquals("", reading.await().path)
            assertEquals(RemoteWorkspaceStatus.CHECKING, f.service.summary.value!!.status)
            runCurrent()
            assertFalse("new write bypassed the unfinished status query", writing.isCompleted)
            coVerify(exactly = 0) { f.client.upload(any(), any(), any(), any(), any(), any(), any(), any()) }
        } finally { releaseRead.complete(Unit); releaseQuery.complete(Unit); refreshing.await() }
        assertEquals(RemoteOutcome.SUCCEEDED, writing.await().outcome)
        assertTrue(f.service.summary.value!!.canOpenFiles)
        assertTrue(f.service.isValid(handle))
    }

    @Test fun `accepted SAF document waits for foreground refresh and downloads after unchanged authorization`() = runTest {
        val f = fixture(); val handle = f.service.open(f.selection)
        val queryStarted = CompletableDeferred<Unit>()
        val releaseQuery = CompletableDeferred<PlatformWorkspaceProjection>()
        coEvery { f.client.state(any(), any()) } coAnswers { queryStarted.complete(Unit); releaseQuery.await() }
        val refreshing = async { f.service.refresh(f.selection) }
        queryStarted.await()
        val deletes = java.util.concurrent.atomic.AtomicInteger()
        val opens = java.util.concurrent.atomic.AtomicInteger()
        val bytes = ByteArrayOutputStream()
        val destination = object : RemoteExportDestination {
            override val description = "content://owned/document/returned-during-refresh"
            override fun open(): java.io.OutputStream { opens.incrementAndGet(); return bytes }
            override fun delete(): Boolean { deletes.incrementAndGet(); return true }
        }
        coEvery { f.client.download(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            arg<java.io.OutputStream>(4).write(byteArrayOf(7))
            WorkspaceContentMetadata(file.etag, 1, null)
        }
        val export = f.service.acceptExport(handle, file, destination)
        try {
            runCurrent()
            assertFalse(export.isCompleted)
            assertEquals(0, opens.get()); assertEquals(0, deletes.get())
            coVerify(exactly = 0) { f.client.download(any(), any(), any(), any(), any(), any(), any()) }
        } finally { releaseQuery.complete(ready) }
        refreshing.await(); export.await()
        assertEquals(1, opens.get()); assertEquals(0, deletes.get())
        assertArrayEquals(byteArrayOf(7), bytes.toByteArray())
    }

    @Test fun `failed refresh cleans accepted SAF destination without downloading through old projection`() = runTest {
        val f = fixture(); val handle = f.service.open(f.selection)
        val queryStarted = CompletableDeferred<Unit>()
        val releaseQuery = CompletableDeferred<Unit>()
        coEvery { f.client.state(any(), any()) } coAnswers {
            queryStarted.complete(Unit); releaseQuery.await(); throw IOException("foreground status unavailable")
        }
        val refreshing = async { f.service.refresh(f.selection) }
        queryStarted.await()
        val deletes = java.util.concurrent.atomic.AtomicInteger()
        val destination = object : RemoteExportDestination {
            override val description = "content://owned/document/failed-refresh"
            override fun open(): java.io.OutputStream = error("failed status must not open output")
            override fun delete(): Boolean { deletes.incrementAndGet(); return true }
        }
        val export = f.service.acceptExport(handle, file, destination)
        runCurrent(); assertFalse(export.isCompleted)
        releaseQuery.complete(Unit); refreshing.await()
        try { export.await(); fail("old projection admitted download after failed refresh") }
        catch (error: IllegalStateException) {
            assertTrue(error.message!!.contains("workspace_status_unavailable"))
            assertTrue(error.message!!.contains("IOException: foreground status unavailable"))
        }
        assertEquals(1, deletes.get())
        assertEquals(RemoteWorkspaceStatus.FAILED, f.service.summary.value!!.status)
        assertTrue(f.service.summary.value!!.diagnostic!!.contains("foreground status unavailable"))
        coVerify(exactly = 0) { f.client.download(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `changed space during refresh cancels waiting SAF export and cleans only its output`() = runTest {
        val f = fixture(); val handle = f.service.open(f.selection)
        val queryStarted = CompletableDeferred<Unit>()
        val releaseQuery = CompletableDeferred<PlatformWorkspaceProjection>()
        coEvery { f.client.state(any(), any()) } coAnswers { queryStarted.complete(Unit); releaseQuery.await() }
        val refreshing = async { f.service.refresh(f.selection) }
        queryStarted.await()
        val deletes = java.util.concurrent.atomic.AtomicInteger()
        val destination = object : RemoteExportDestination {
            override val description = "content://owned/document/replaced-space"
            override fun open(): java.io.OutputStream = error("replacement space must not open output")
            override fun delete(): Boolean { deletes.incrementAndGet(); return true }
        }
        val export = f.service.acceptExport(handle, file, destination)
        runCurrent(); assertFalse(export.isCompleted)
        releaseQuery.complete(ready.copy(bindingRevision = 2, agentSpaceId = "spc_550e8400-e29b-41d4-a716-446655440001"))
        refreshing.await(); export.join()
        assertTrue(export.isCancelled)
        assertEquals(1, deletes.get())
        assertFalse(f.service.isValid(handle))
        coVerify(exactly = 0) { f.client.download(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `realm switch cancels refresh wait without holding Session lock and awaits SAF cleanup`() = runTest {
        val f = fixture(); val handle = f.service.open(f.selection)
        val queryStarted = CompletableDeferred<Unit>()
        coEvery { f.client.state(any(), any()) } coAnswers { queryStarted.complete(Unit); awaitCancellation() }
        val refreshing = async { f.service.refresh(f.selection) }
        queryStarted.await()
        val deletes = java.util.concurrent.atomic.AtomicInteger()
        val deleting = CompletableDeferred<Unit>()
        val releaseDelete = java.util.concurrent.CountDownLatch(1)
        val destination = object : RemoteExportDestination {
            override val description = "content://owned/document/switch-during-refresh"
            override fun open(): java.io.OutputStream = error("switched realm must not open output")
            override fun delete(): Boolean {
                deletes.incrementAndGet(); deleting.complete(Unit)
                check(releaseDelete.await(10, java.util.concurrent.TimeUnit.SECONDS))
                return true
            }
        }
        val export = f.service.acceptExport(handle, file, destination)
        runCurrent(); assertFalse(export.isCompleted)
        lateinit var receipt: RemoteWorkspaceRevocation
        val personal = f.sessions.switchRealm(RealmSwitchRequest(f.selection, RealmAccess.Personal)) {
            receipt = f.service.revoke(it)
        }
        assertEquals(RealmAccess.Personal, personal.access)
        val closing = async { receipt.awaitClosed() }
        try {
            deleting.await(); runCurrent()
            assertFalse("switch receipt did not retain waiting SAF ownership", closing.isCompleted)
        } finally { releaseDelete.countDown(); closing.await(); export.join(); refreshing.join() }
        assertTrue(export.isCancelled)
        assertEquals(1, deletes.get())
        coVerify(exactly = 0) { f.client.download(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `network read failure blocks writes until explicit status and directory verification`() = runTest {
        val f = fixture(); val handle = f.service.open(f.selection)
        coEvery { f.client.list(any(), any(), any(), any()) } throws IOException("connection reset during directory read")
        try { f.service.list(handle, ""); fail() } catch (_: IOException) { }
        assertEquals(RemoteWorkspaceStatus.READ_FAILED, f.service.summary.value!!.status)
        assertEquals(RemoteOutcome.FAILED, f.service.upload(handle, file.path, file.etag, 1, { byteArrayOf(1).inputStream() }).outcome)
        f.service.refresh(f.selection)
        assertEquals(RemoteWorkspaceStatus.READ_FAILED, f.service.summary.value!!.status)
        coEvery { f.client.list(any(), any(), any(), any()) } returns PlatformWorkspaceFileList(emptyList())
        f.service.list(handle, "")
        assertTrue(f.service.summary.value!!.canOpenFiles)
        coEvery { f.client.upload(any(), any(), any(), any(), any(), any(), any(), any()) } returns success
        assertEquals(RemoteOutcome.SUCCEEDED, f.service.upload(handle, file.path, file.etag, 1, { byteArrayOf(1).inputStream() }).outcome)
    }

    @Test fun `unexpected successful HTTP statuses remain unknown and cannot be replayed`() = runTest {
        val f = fixture(); val handle = f.service.open(f.selection)
        for (status in listOf(201, 204)) {
            val path = "result-$status.txt"
            coEvery { f.client.upload(any(), any(), any(), path, any(), any(), any(), any()) } throws PlatformHttpException(status, null, "unexpected successful response")
            assertEquals(RemoteOutcome.UNKNOWN, f.service.upload(handle, path, null, 1, { byteArrayOf(1).inputStream() }).outcome)
            assertEquals(RemoteOutcome.UNKNOWN, f.service.upload(handle, path, null, 1, { byteArrayOf(1).inputStream() }).outcome)
            coVerify(exactly = 1) { f.client.upload(any(), any(), any(), path, any(), any(), any(), any()) }
        }
    }

    @Test fun `unknown target survives reopening and needs a fresh read plus explicit acknowledgement`() = runTest {
        val f = fixture(); val first = f.service.open(f.selection)
        coEvery { f.client.upload(any(), any(), any(), any(), any(), any(), any(), any()) } throws PlatformHttpException(507,
            PlatformProblem("about:blank", "unknown", 507, "workspace_result_unknown"), "write may have completed")
        assertEquals(RemoteOutcome.UNKNOWN, f.service.upload(first, file.path, file.etag, 1, { byteArrayOf(1).inputStream() }).outcome)
        f.service.close(first)
        val reopened = f.service.open(f.selection)
        try { f.service.acknowledgeVerified(reopened, file.path); fail("confirmation accepted without a fresh read") }
        catch (_: IllegalStateException) { }
        assertEquals(RemoteOutcome.UNKNOWN, f.service.upload(reopened, file.path, file.etag, 1, { byteArrayOf(1).inputStream() }).outcome)
        assertNull(f.service.verifyUnknown(reopened, file.path))
        assertEquals(RemoteOutcome.UNKNOWN, f.service.upload(reopened, file.path, file.etag, 1, { byteArrayOf(1).inputStream() }).outcome)
        f.service.acknowledgeVerified(reopened, file.path)
        coEvery { f.client.upload(any(), any(), any(), any(), any(), any(), any(), any()) } returns success
        assertEquals(RemoteOutcome.SUCCEEDED, f.service.upload(reopened, file.path, null, 1, { byteArrayOf(1).inputStream() }).outcome)
        coVerify(exactly = 2) { f.client.upload(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `share delivery failure removes the owned copy before releasing its lease`() = runTest {
        val f = fixture(); val handle = f.service.open(f.selection)
        coEvery { f.client.download(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            arg<java.io.OutputStream>(4).write(byteArrayOf(7))
            WorkspaceContentMetadata(file.etag, 1, null)
        }
        var delivered: java.io.File? = null
        val failure = IllegalStateException("no activity accepts the document")
        try {
            f.service.share(handle, file) { copy -> delivered = copy; assertTrue(copy.isFile); throw failure }
            fail("delivery failure swallowed")
        } catch (error: IllegalStateException) { assertEquals(failure.message, error.message) }
        assertNotNull(delivered)
        assertFalse(delivered!!.exists())
        f.sessions.awaitPlatformOperations(f.selection.access as RealmAccess.Enterprise)
    }

    @Test fun `a handed off share survives closing its management session`() = runTest {
        val f = fixture(); val handle = f.service.open(f.selection)
        coEvery { f.client.download(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            arg<java.io.OutputStream>(4).write(byteArrayOf(7))
            WorkspaceContentMetadata(file.etag, 1, null)
        }
        var delivered: java.io.File? = null
        f.service.share(handle, file) { copy -> delivered = copy }
        f.service.close(handle)
        assertNotNull(delivered)
        assertArrayEquals(byteArrayOf(7), delivered!!.readBytes())
        assertTrue(delivered!!.setLastModified(System.currentTimeMillis() - 86_400_001))
        f.service.cleanupExpiredShares()
        assertFalse(delivered!!.exists())
    }

    @Test fun `sharing a long Unicode remote name uses an independent short private basename`() = runTest {
        val f = fixture(); val handle = f.service.open(f.selection)
        coEvery { f.client.download(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            arg<java.io.OutputStream>(4).write(byteArrayOf(7))
            WorkspaceContentMetadata(file.etag, 1, null)
        }
        for ((name, extension) in listOf("文".repeat(83) + ".txt" to "txt", "image.PNG" to "png",
            "notes.TXT" to "txt", "untitled" to "", "notes." + "x".repeat(200) to "", "notes.文" to "")) {
            var delivered: java.io.File? = null
            f.service.share(handle, file.copy(path = name)) { copy ->
                delivered = copy
                assertTrue(copy.name.startsWith("share-"))
                assertTrue(copy.name.toByteArray(Charsets.UTF_8).size < 64)
                assertFalse(copy.name.contains(name))
                assertEquals(extension, copy.extension)
                assertArrayEquals(byteArrayOf(7), copy.readBytes())
            }
            assertNotNull(delivered)
        }
    }

    @Test fun `revocation waits for SAF cleanup even when export was cancelled before first execution`() = runTest {
        val f = fixture(); val handle = f.service.open(f.selection)
        val deletes = java.util.concurrent.atomic.AtomicInteger()
        val deleting = CompletableDeferred<Unit>()
        val releaseDelete = java.util.concurrent.CountDownLatch(1)
        val destination = object : RemoteExportDestination {
            override val description = "content://owned/document/not-started"
            override fun open(): java.io.OutputStream = error("cancelled operation must not open output")
            override fun delete(): Boolean {
                deletes.incrementAndGet()
                deleting.complete(Unit)
                check(releaseDelete.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "test did not release owned document cleanup" }
                return true
            }
        }
        val export = launch(start = CoroutineStart.UNDISPATCHED) { f.service.export(handle, file, destination) }
        val receipt = f.service.revoke(f.selection.access)
        val closing = async { receipt.awaitClosed() }
        try {
            deleting.await()
            runCurrent()
            assertFalse("revocation completed before the owned SAF document was cleaned up", closing.isCompleted)
        } finally {
            releaseDelete.countDown()
            closing.await(); export.join()
        }
        assertTrue(export.isCancelled)
        assertEquals(1, deletes.get())
        coVerify(exactly = 0) { f.client.download(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `accepting an existing SAF document synchronously owns its cleanup before dispatch`() = runTest {
        val f = fixture(); val handle = f.service.open(f.selection)
        val deletes = java.util.concurrent.atomic.AtomicInteger()
        val deleting = CompletableDeferred<Unit>()
        val releaseDelete = java.util.concurrent.CountDownLatch(1)
        val destination = object : RemoteExportDestination {
            override val description = "content://owned/document/accepted"
            override fun open(): java.io.OutputStream = error("revoked accepted document must not be opened")
            override fun delete(): Boolean {
                deletes.incrementAndGet(); deleting.complete(Unit)
                check(releaseDelete.await(10, java.util.concurrent.TimeUnit.SECONDS))
                return true
            }
        }
        val export = f.service.acceptExport(handle, file, destination)
        val receipt = f.service.revoke(f.selection.access)
        val closing = async { receipt.awaitClosed() }
        try {
            deleting.await(); runCurrent()
            assertFalse("revocation did not await the synchronously accepted document cleanup", closing.isCompleted)
        } finally {
            releaseDelete.countDown(); closing.await(); export.join()
        }
        assertTrue(export.isCancelled)
        assertEquals(1, deletes.get())
        coVerify(exactly = 0) { f.client.download(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `realm switch waits for an admitted SAF factory and deletes the document it returns`() = runTest {
        val f = fixture(); val handle = f.service.open(f.selection)
        val creating = CompletableDeferred<Unit>()
        val releaseCreate = java.util.concurrent.CountDownLatch(1)
        val deletes = java.util.concurrent.atomic.AtomicInteger()
        val opens = java.util.concurrent.atomic.AtomicInteger()
        val destination = object : RemoteExportDestination {
            override val description = "content://owned/document/created-during-switch"
            override fun open(): java.io.OutputStream { opens.incrementAndGet(); return ByteArrayOutputStream() }
            override fun delete(): Boolean { deletes.incrementAndGet(); return true }
        }
        val export = launch {
            f.service.exportCreated(handle, file, {
                creating.complete(Unit)
                check(releaseCreate.await(10, java.util.concurrent.TimeUnit.SECONDS))
                destination
            })
        }
        creating.await()
        lateinit var receipt: RemoteWorkspaceRevocation
        val personal = f.sessions.switchRealm(RealmSwitchRequest(f.selection, RealmAccess.Personal)) {
            receipt = f.service.revoke(it)
        }
        assertEquals(RealmAccess.Personal, personal.access)
        val closing = async { receipt.awaitClosed() }
        try {
            runCurrent()
            assertFalse("switch cleanup finished while the SAF factory still owned an unfinished document", closing.isCompleted)
            assertEquals(0, deletes.get())
        } finally {
            releaseCreate.countDown(); closing.await(); export.join()
        }
        assertTrue(export.isCancelled)
        assertEquals(1, deletes.get())
        assertEquals(0, opens.get())
        f.sessions.awaitPlatformOperations(f.selection.access as RealmAccess.Enterprise)
        coVerify(exactly = 0) { f.client.download(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `local SAF factory and open failures preserve remote availability`() = runTest {
        val f = fixture(); val handle = f.service.open(f.selection)
        try {
            f.service.exportCreated(handle, file, { throw IOException("local provider could not create a document") })
            fail("factory failure swallowed")
        } catch (error: IOException) { assertTrue(error.userVisibleDiagnostic().contains("local provider could not create a document")) }
        assertEquals(RemoteWorkspaceStatus.AVAILABLE, f.service.summary.value!!.status)
        var deletes = 0
        val destination = object : RemoteExportDestination {
            override val description = "content://owned/document/open-failed"
            override fun open(): java.io.OutputStream = throw IOException("local output could not be opened")
            override fun delete(): Boolean { deletes++; return true }
        }
        try { f.service.export(handle, file, destination); fail("open failure swallowed") }
        catch (error: IOException) { assertTrue(error.userVisibleDiagnostic().contains("local output could not be opened")) }
        assertEquals(1, deletes)
        assertEquals(RemoteWorkspaceStatus.AVAILABLE, f.service.summary.value!!.status)
        coVerify(exactly = 0) { f.client.download(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `old HTTP failure delayed by SAF cleanup cannot overwrite replacement space availability`() = runTest {
        for (failure in listOf(
            PlatformHttpException(503, null, "old DAV unavailable"),
            PlatformHttpException(409, PlatformProblem("about:blank", "Old space", 409, "workspace_space_mismatch"), "old binding replaced"),
        )) {
            val f = fixture(); val handle = f.service.open(f.selection)
            val deleting = CompletableDeferred<Unit>()
            val releaseDelete = java.util.concurrent.CountDownLatch(1)
            val deletes = java.util.concurrent.atomic.AtomicInteger()
            val destination = object : RemoteExportDestination {
                override val description = "content://owned/document/old-space-${failure.status}"
                override fun open(): java.io.OutputStream = ByteArrayOutputStream()
                override fun delete(): Boolean {
                    deletes.incrementAndGet(); deleting.complete(Unit)
                    check(releaseDelete.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    return true
                }
            }
            coEvery { f.client.download(any(), any(), any(), any(), any(), any(), any()) } throws failure
            val export = f.service.acceptExport(handle, file, destination)
            try {
                deleting.await()
                coEvery { f.client.state(any(), any()) } returns ready.copy(bindingRevision = 2,
                    agentSpaceId = "spc_550e8400-e29b-41d4-a716-446655440001")
                f.service.refresh(f.selection)
                assertFalse(f.service.isValid(handle))
                assertEquals(RemoteWorkspaceStatus.AVAILABLE, f.service.summary.value!!.status)
            } finally { releaseDelete.countDown(); export.join() }
            assertEquals(1, deletes.get())
            assertEquals("late ${failure.status} overwrote the replacement projection", RemoteWorkspaceStatus.AVAILABLE,
                f.service.summary.value!!.status)
            assertNull(f.service.summary.value!!.diagnostic)
        }
    }
}
