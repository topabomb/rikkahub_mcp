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
        val client: PlatformWorkspaceClient, val selection: RealmSelection, val control: PlatformControlClient,
        val invalidations: MutableList<Pair<RealmAccess.Enterprise, EnterpriseExitReason>>)

    private suspend fun TestScope.fixture(initial: PlatformWorkspaceProjection = ready, initialError: Exception? = null): Fixture {
        val sessions = EnterpriseSessionController(enterpriseTestStore(temporary.newFolder()))
        sessions.enrollFixture(exampleEnterprisePackage())
        val client = mockk<PlatformWorkspaceClient>()
        if (initialError == null) coEvery { client.state(any(), any()) } returns initial
        else coEvery { client.state(any(), any()) } throws initialError
        coEvery { client.list(any(), any(), any(), any()) } returns PlatformWorkspaceFileList(emptyList())
        val control = mockk<PlatformControlClient>()
        val invalidations = mutableListOf<Pair<RealmAccess.Enterprise, EnterpriseExitReason>>()
        val platform = PlatformEnterpriseService(sessions, control,
            onSessionInvalidated = { access, reason -> invalidations += access to reason })
        val service = RemoteWorkspaceService(sessions, platform, client, ApplicationRecoveryGate().apply { ready() },
            CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job])), temporary.newFolder())
        val selection = sessions.readPresentation().selection!!
        service.queryState.first { it?.refreshing == false }
        return Fixture(sessions, service, client, selection, control, invalidations)
    }

    @Test fun `media source pins HEAD version and length instead of directory metadata`() = runTest {
        val f = fixture(); val handle = f.service.open(f.selection)
        val metadata = WorkspaceContentMetadata("\"head-version\"", 4294967296L, "video/mp4")
        coEvery { f.client.mediaMetadata(any(), any(), any(), any()) } returns metadata
        coEvery { f.client.mediaRange(any(), any(), any(), any(), any(), any(), any()) } returns byteArrayOf(42)
        val source = f.service.mediaSource(handle, file.copy(path = "movie.mp4", size = 1, etag = "\"old-list-version\""))
        assertEquals(metadata.length, source.length)
        assertArrayEquals(byteArrayOf(42), source.readRange(metadata.length - 1, 1))
        coVerify(exactly = 1) { f.client.mediaMetadata(handle.connection, any(), handle.space, "movie.mp4") }
        coVerify(exactly = 1) {
            f.client.mediaRange(handle.connection, any(), handle.space, "movie.mp4", metadata, metadata.length - 1, 1)
        }
        f.service.close(handle)
    }

    @Test fun `media range token recovery retains original file metadata and position`() = runTest {
        val f = fixture(); val handle = f.service.open(f.selection)
        val metadata = WorkspaceContentMetadata("\"original\"", 4294967296L, "video/mp4")
        coEvery { f.client.mediaMetadata(any(), any(), any(), any()) } returns metadata
        coEvery { f.control.refresh(any(), any(), any()) } returns refreshedToken()
        val identities = mutableListOf<WorkspaceContentMetadata>()
        val positions = mutableListOf<Long>()
        coEvery { f.client.mediaRange(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            identities += arg<WorkspaceContentMetadata>(4)
            positions += arg<Long>(5)
            if (arg<String>(1) != "replacement-access") throw accessRejected()
            byteArrayOf(7)
        }
        val source = f.service.mediaSource(handle, file)
        assertArrayEquals(byteArrayOf(7), source.readRange(2147483649L, 1))
        assertEquals(listOf(metadata, metadata), identities)
        assertEquals(listOf(2147483649L, 2147483649L), positions)
        coVerify(exactly = 1) { f.client.mediaMetadata(any(), any(), any(), any()) }
        coVerify(exactly = 1) { f.control.refresh(any(), any(), any()) }
        assertTrue(f.invalidations.isEmpty())
        f.service.close(handle)
    }

    @Test fun `revocation cancels media range and waits for its resource cleanup`() = runTest {
        val f = fixture(); val handle = f.service.open(f.selection)
        coEvery { f.client.mediaMetadata(any(), any(), any(), any()) } returns WorkspaceContentMetadata("\"v1\"", 8, null)
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var cleaned = false
        coEvery { f.client.mediaRange(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            entered.complete(Unit)
            try { awaitCancellation() }
            finally {
                withContext(NonCancellable) {
                    cleaning.complete(Unit)
                    release.await()
                    cleaned = true
                }
            }
        }
        val source = f.service.mediaSource(handle, file)
        val reading = async { runCatching { source.readRange(0, 8) } }
        entered.await()
        val receipt = f.service.revoke(f.selection.access)
        val closing = async { receipt.awaitClosed() }
        try {
            cleaning.await(); runCurrent()
            assertFalse(closing.isCompleted)
            assertFalse(cleaned)
        } finally { release.complete(Unit) }
        closing.await()
        assertTrue(cleaned)
        assertTrue(reading.await().exceptionOrNull() is CancellationException)
    }

    @Test fun `revoked media source denies buffered access checks and all later requests`() = runTest {
        val f = fixture(); val handle = f.service.open(f.selection)
        coEvery { f.client.mediaMetadata(any(), any(), any(), any()) } returns WorkspaceContentMetadata("\"v1\"", 8, null)
        coEvery { f.client.mediaRange(any(), any(), any(), any(), any(), any(), any()) } returns ByteArray(8)
        val source = f.service.mediaSource(handle, file)
        source.readRange(0, 8)
        f.service.revoke(f.selection.access).awaitClosed()
        assertTrue(runCatching { source.verifyAccess() }.isFailure)
        assertTrue(runCatching { source.readRange(0, 8) }.isFailure)
        coVerify(exactly = 1) { f.client.mediaMetadata(any(), any(), any(), any()) }
        coVerify(exactly = 1) { f.client.mediaRange(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `unknown failure stays outside resource cards and a later successful query discovers provisioned files`() = runTest {
        val f = fixture(initialError = IOException("state endpoint unavailable"))
        assertNull(f.service.summary.value)
        assertTrue(f.service.queryState.value!!.diagnostic!!.contains("state endpoint unavailable"))
        coEvery { f.client.state(any(), any()) } returns ready
        f.service.refresh(f.selection)
        assertTrue(f.service.summary.value!!.canOpenFiles)
        assertNull(f.service.queryState.value!!.diagnostic)
    }

    @Test fun `enabled service without a user resource stays hidden and successful removal replaces earlier facts`() = runTest {
        val unprovisioned = ready.copy(agentSpaceId = null, state = PlatformWorkspaceProjectionState.UNPROVISIONED, filesAvailable = false)
        val f = fixture(unprovisioned)
        assertNull(f.service.summary.value)
        coEvery { f.client.state(any(), any()) } returns ready
        val handle = f.service.open(f.selection)
        coEvery { f.client.state(any(), any()) } returns unprovisioned
        f.service.refresh(f.selection)
        assertNull(f.service.summary.value)
        assertFalse(f.service.isValid(handle))
    }

    @Test fun `refresh retains a known resource while withholding file actions and merging concurrent queries`() = runTest {
        val f = fixture()
        val entered = CompletableDeferred<Unit>()
        val complete = CompletableDeferred<Unit>()
        coEvery { f.client.state(any(), any()) } coAnswers { entered.complete(Unit); complete.await(); ready }
        val first = async { f.service.refresh(f.selection) }
        entered.await()
        assertEquals(RemoteWorkspaceStatus.CHECKING, f.service.summary.value!!.status)
        assertFalse(f.service.summary.value!!.canOpenFiles)
        val second = async { f.service.refresh(f.selection) }
        runCurrent()
        coVerify(exactly = 2) { f.client.state(any(), any()) }
        try { f.service.requireNavigation(f.selection, filesRequired = true); fail("stale file capability was admitted") }
        catch (_: IllegalStateException) { }
        complete.complete(Unit)
        first.await(); second.await()
        assertTrue(f.service.summary.value!!.canOpenFiles)
    }

    @Test fun `file failure supersedes a status response but the completed query always stops refreshing`() = runTest {
        for (readError in listOf(PlatformHttpException(503, null, "DAV unavailable"), IOException("DAV connection closed"))) {
            for (queryError in listOf<Exception?>(null, IOException("state endpoint unavailable"))) {
                val f = fixture()
                val handle = f.service.open(f.selection)
                val readEntered = CompletableDeferred<Unit>()
                val releaseRead = CompletableDeferred<Unit>()
                coEvery { f.client.list(any(), any(), any(), any()) } coAnswers {
                    readEntered.complete(Unit); releaseRead.await(); throw readError
                }
                val reading = async { runCatching { f.service.list(handle, "") } }
                readEntered.await()
                val queryEntered = CompletableDeferred<Unit>()
                val releaseQuery = CompletableDeferred<Unit>()
                coEvery { f.client.state(any(), any()) } coAnswers {
                    queryEntered.complete(Unit); releaseQuery.await()
                    if (queryError != null) throw queryError
                    ready
                }
                val refreshing = async { f.service.refresh(f.selection) }
                queryEntered.await()
                releaseRead.complete(Unit)
                assertTrue(reading.await().isFailure)
                assertEquals(RemoteWorkspaceStatus.READ_FAILED, f.service.summary.value!!.status)
                assertTrue(f.service.queryState.value!!.refreshing)
                releaseQuery.complete(Unit)
                refreshing.await()
                assertFalse(f.service.queryState.value!!.refreshing)
                assertEquals(RemoteWorkspaceStatus.READ_FAILED, f.service.summary.value!!.status)
                assertFalse(f.service.summary.value!!.canOpenFiles)
                assertTrue(f.service.summary.value!!.diagnostic!!.contains(readError.message!!))
            }
        }
    }

    @Test fun `completion of a revoked query cannot stop a newer query for the same selection`() = runTest {
        val f = fixture()
        val oldEntered = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val newEntered = CompletableDeferred<Unit>()
        val releaseNew = CompletableDeferred<Unit>()
        var calls = 0
        coEvery { f.client.state(any(), any()) } coAnswers {
            if (++calls == 1) {
                oldEntered.complete(Unit)
                withContext(NonCancellable) { releaseOld.await() }
            } else {
                newEntered.complete(Unit); releaseNew.await()
            }
            ready
        }
        val oldQuery = async { runCatching { f.service.refresh(f.selection) } }
        oldEntered.await()
        val revocation = f.service.revoke(f.selection.access)
        val newQuery = async { f.service.refresh(f.selection) }
        newEntered.await()
        releaseOld.complete(Unit)
        assertTrue(oldQuery.await().exceptionOrNull() is CancellationException)
        revocation.awaitClosed()
        assertTrue(f.service.queryState.value!!.refreshing)
        releaseNew.complete(Unit)
        newQuery.await()
        assertFalse(f.service.queryState.value!!.refreshing)
        assertTrue(f.service.summary.value!!.canOpenFiles)
    }

    private fun refreshedToken() = PlatformRefreshResponse("replacement-access", "2099-01-01T00:00:00Z",
        "replacement-refresh", "2099-01-08T00:00:00Z", "2099-01-01T00:00:00Z")

    private fun accessRejected() = PlatformHttpException(401,
        PlatformProblem("about:blank", "Unauthorized", 401, "invalid_credential"), "Access token expired")

    @Test fun `directory access rejection refreshes once without retiring the session`() = runTest {
        val f = fixture(); val handle = f.service.open(f.selection)
        val rejected = accessRejected()
        coEvery { f.control.refresh(any(), any(), any()) } returns refreshedToken()
        coEvery { f.client.list(any(), any(), any(), any()) } coAnswers {
            if (arg<String>(1) != "replacement-access") throw rejected
            PlatformWorkspaceFileList(emptyList())
        }
        assertTrue(f.service.list(handle, "").files.isEmpty())
        coVerify(exactly = 2) { f.client.list(any(), any(), any(), any()) }
        coVerify(exactly = 1) { f.control.refresh(any(), any(), any()) }
        assertTrue(f.invalidations.isEmpty())
        assertEquals(f.selection, f.sessions.readPresentation().selection)
        assertEquals(RemoteWorkspaceStatus.AVAILABLE, f.service.summary.value!!.status)
    }

    @Test fun `write access rejection refreshes credentials but only a new explicit attempt sends again`() = runTest {
        val f = fixture(); val handle = f.service.open(f.selection)
        coEvery { f.control.refresh(any(), any(), any()) } returns refreshedToken()
        coEvery { f.client.upload(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            if (arg<String>(1) != "replacement-access") throw accessRejected()
            success
        }
        val failed = f.service.upload(handle, file.path, file.etag, 1, { byteArrayOf(1).inputStream() })
        assertEquals(RemoteOutcome.FAILED, failed.outcome)
        assertTrue(failed.diagnostic!!.contains("invalid_credential"))
        coVerify(exactly = 1) { f.client.upload(any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { f.control.refresh(any(), any(), any()) }
        assertTrue(f.invalidations.isEmpty())
        assertEquals(f.selection, f.sessions.readPresentation().selection)

        assertEquals(RemoteOutcome.SUCCEEDED,
            f.service.upload(handle, file.path, file.etag, 1, { byteArrayOf(1).inputStream() }).outcome)
        coVerify(exactly = 2) { f.client.upload(any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { f.control.refresh(any(), any(), any()) }
    }

    @Test fun `cancelling credential recovery after a confirmed write rejection leaves the target retryable`() = runTest {
        val f = fixture(); val handle = f.service.open(f.selection)
        val refreshing = CompletableDeferred<Unit>()
        coEvery { f.control.refresh(any(), any(), any()) } coAnswers {
            refreshing.complete(Unit)
            awaitCancellation()
        }
        coEvery { f.client.upload(any(), any(), any(), any(), any(), any(), any(), any()) } throws accessRejected()
        val first = async { f.service.upload(handle, file.path, file.etag, 1, { byteArrayOf(1).inputStream() }) }
        refreshing.await()
        first.cancelAndJoin()
        assertTrue(first.isCancelled)
        assertEquals(RemoteOutcome.CANCELLED, f.service.outcomeAfterCancellation(handle, file.path))
        coVerify(exactly = 1) { f.client.upload(any(), any(), any(), any(), any(), any(), any(), any()) }
        assertTrue(f.invalidations.isEmpty())
        assertEquals(f.selection, f.sessions.readPresentation().selection)

        coEvery { f.control.refresh(any(), any(), any()) } returns refreshedToken()
        coEvery { f.client.upload(any(), any(), any(), any(), any(), any(), any(), any()) } returns success
        assertEquals(RemoteOutcome.SUCCEEDED,
            f.service.upload(handle, file.path, file.etag, 1, { byteArrayOf(1).inputStream() }).outcome)
        coVerify(exactly = 2) { f.client.upload(any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 2) { f.control.refresh(any(), any(), any()) }
    }

    @Test fun `cancellation during send remains unknown and blocks an explicit resend`() = runTest {
        val f = fixture(); val handle = f.service.open(f.selection)
        val sendEntered = CompletableDeferred<Unit>()
        val sendExited = CompletableDeferred<Unit>()
        val cancellationReachedCaller = CompletableDeferred<CancellationException>()
        coEvery { f.client.upload(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            sendEntered.complete(Unit)
            try { awaitCancellation() } finally { sendExited.complete(Unit) }
        }
        val first = async {
            try {
                f.service.upload(handle, file.path, file.etag, 1, { byteArrayOf(1).inputStream() })
            } catch (cancelled: CancellationException) {
                cancellationReachedCaller.complete(cancelled)
                throw cancelled
            }
        }
        sendEntered.await()
        first.cancelAndJoin()
        assertTrue(first.isCancelled)
        assertTrue(cancellationReachedCaller.isCompleted)
        assertTrue(sendExited.isCompleted)
        assertEquals(RemoteOutcome.UNKNOWN, f.service.outcomeAfterCancellation(handle, file.path))
        coEvery { f.client.upload(any(), any(), any(), any(), any(), any(), any(), any()) } returns success
        val blocked = f.service.upload(handle, file.path, file.etag, 1, { byteArrayOf(1).inputStream() })
        assertEquals(RemoteOutcome.UNKNOWN, blocked.outcome)
        assertEquals("workspace_verify_unknown_result_first", blocked.diagnostic)
        coVerify(exactly = 1) { f.client.upload(any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { f.control.refresh(any(), any(), any()) }
        assertTrue(f.invalidations.isEmpty())
        assertEquals(f.selection, f.sessions.readPresentation().selection)
    }

    @Test fun `IO failure during send remains unknown and blocks an explicit resend`() = runTest {
        val f = fixture(); val handle = f.service.open(f.selection)
        val sendEntered = CompletableDeferred<Unit>()
        coEvery { f.client.upload(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            sendEntered.complete(Unit)
            throw IOException("connection reset before a write response")
        }
        val result = f.service.upload(handle, file.path, file.etag, 1, { byteArrayOf(1).inputStream() })
        assertTrue(sendEntered.isCompleted)
        assertEquals(RemoteOutcome.UNKNOWN, result.outcome)
        assertTrue(result.diagnostic!!.contains("IOException"))
        assertTrue(result.diagnostic!!.contains("connection reset before a write response"))
        assertEquals(RemoteOutcome.UNKNOWN, f.service.outcomeAfterCancellation(handle, file.path))
        coEvery { f.client.upload(any(), any(), any(), any(), any(), any(), any(), any()) } returns success
        val blocked = f.service.upload(handle, file.path, file.etag, 1, { byteArrayOf(1).inputStream() })
        assertEquals(RemoteOutcome.UNKNOWN, blocked.outcome)
        assertEquals("workspace_verify_unknown_result_first", blocked.diagnostic)
        coVerify(exactly = 1) { f.client.upload(any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { f.control.refresh(any(), any(), any()) }
        assertTrue(f.invalidations.isEmpty())
        assertEquals(f.selection, f.sessions.readPresentation().selection)
    }

    @Test fun `export access rejection preserves the error and cleans its output without replay`() = runTest {
        val f = fixture(); val handle = f.service.open(f.selection)
        val rejected = accessRejected()
        var closes = 0
        var deletes = 0
        val destination = object : RemoteExportDestination {
            override val description = "content://owned/export/rejected"
            override fun open() = object : ByteArrayOutputStream() {
                override fun close() { closes++; super.close() }
            }
            override fun delete(): Boolean { deletes++; return true }
        }
        coEvery { f.control.refresh(any(), any(), any()) } returns refreshedToken()
        coEvery { f.client.download(any(), any(), any(), any(), any(), any(), any()) } throws rejected
        try { f.service.export(handle, file, destination); fail("rejected export succeeded") }
        catch (error: PlatformHttpException) { assertSame(rejected, error) }
        assertEquals(1, closes)
        assertEquals(1, deletes)
        coVerify(exactly = 1) { f.client.download(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { f.control.refresh(any(), any(), any()) }
        assertTrue(f.invalidations.isEmpty())
        assertEquals(f.selection, f.sessions.readPresentation().selection)
    }

    @Test fun `temporary token recovery failure retains the original access diagnostic`() = runTest {
        val f = fixture(); val handle = f.service.open(f.selection)
        val rejected = accessRejected()
        val recoveryFailure = IOException("refresh endpoint unavailable")
        coEvery { f.control.refresh(any(), any(), any()) } throws recoveryFailure
        coEvery { f.client.list(any(), any(), any(), any()) } throws rejected
        try { f.service.list(handle, ""); fail("failed token recovery hidden") }
        catch (error: PlatformHttpException) {
            assertSame(rejected, error)
            assertTrue(error.suppressed.any { it === recoveryFailure })
        }
        coVerify(exactly = 1) { f.client.list(any(), any(), any(), any()) }
        assertTrue(f.invalidations.isEmpty())
        assertEquals(f.selection, f.sessions.readPresentation().selection)
    }

    @Test fun `token recovery cancellation propagates without becoming an access failure`() = runTest {
        val f = fixture(); val handle = f.service.open(f.selection)
        val rejected = accessRejected()
        coEvery { f.control.refresh(any(), any(), any()) } throws CancellationException("refresh cancelled")
        coEvery { f.client.list(any(), any(), any(), any()) } throws rejected
        try { f.service.list(handle, ""); fail("token recovery cancellation swallowed") }
        catch (_: CancellationException) { }
        assertTrue(rejected.suppressed.isEmpty())
        assertTrue(f.invalidations.isEmpty())
        assertEquals(f.selection, f.sessions.readPresentation().selection)
    }

    @Test fun `only a terminal refresh rejection retires a file access token session`() = runTest {
        val f = fixture(); val handle = f.service.open(f.selection)
        val rejected = accessRejected()
        val terminal = PlatformHttpException(401,
            PlatformProblem("about:blank", "Unauthorized", 401, "invalid_credential"), "Refresh credential invalid")
        coEvery { f.control.refresh(any(), any(), any()) } throws terminal
        coEvery { f.client.list(any(), any(), any(), any()) } throws rejected
        try { f.service.list(handle, ""); fail("terminal refresh rejection hidden") }
        catch (error: PlatformHttpException) { assertSame(rejected, error); assertTrue(error.suppressed.contains(terminal)) }
        assertEquals(listOf(f.selection.access to EnterpriseExitReason.AUTHORIZATION_EXPIRED), f.invalidations)
        coVerify(exactly = 1) { f.client.list(any(), any(), any(), any()) }
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

    @Test fun `preview cancellation at completed download removes unhanded copy`() = runTest {
        val f = fixture(); val handle = f.service.open(f.selection)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { f.client.download(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            arg<java.io.OutputStream>(4).write(byteArrayOf(7))
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            WorkspaceContentMetadata(file.etag, 1, null)
        }
        val reading = async { f.service.previewCopy(handle, file) }
        entered.await()
        reading.cancel()
        release.complete(Unit)
        reading.join()
        assertTrue(reading.isCancelled)
        assertTrue(temporary.root.walkTopDown().none { it.isFile && it.name.startsWith("preview-") })
        f.service.close(handle)
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
