package net.weero.measix.pilot.service

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import androidx.paging.AsyncPagingDataDiffer
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import net.weero.measix.pilot.data.db.entity.GenMediaEntity
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.data.files.ArtifactCleanupResult
import net.weero.measix.pilot.data.imggen.GeneratedMediaCleanupResult
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.FileFolders
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config
import net.weero.measix.pilot.data.imggen.GeneratedMediaStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.days

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FileManagementServicesTest {
    private lateinit var root: File
    private lateinit var sessions: EnterpriseSessionController
    private lateinit var selection: RealmSelection

    @Before fun setUp() = runBlocking {
        root = kotlin.io.path.createTempDirectory("file-session").toFile()
        sessions = EnterpriseSessionController(EnterpriseAppliedStore(root), nowMillis = { 1_800_000_000_000L })
        sessions.recover()
        selection = requireNotNull(sessions.observeSelectedRealmSelection().first())
    }

    @After fun tearDown() { root.deleteRecursively() }

    @Test fun `cleanup counts cannot return after expiry while querying either file owner`() = runTest {
        for (category in FileCleanupCategory.entries) {
            var now = 1000L
            val controller = EnterpriseSessionController(EnterpriseAppliedStore(File(root, "count-${category.name}"))) { now }
            val ready = controller.enrollFixture(exampleEnterprisePackage())
            val selected = requireNotNull(controller.readPresentation().selection)
            val artifacts = mockk<ArtifactStore>()
            val generated = mockk<GeneratedMediaStore>()
            coEvery { artifacts.countFolderCreatedBefore(selected.access.scope, FileFolders.UPLOAD, any()) } coAnswers {
                now = ready.manifest.session!!.expiresAtMillis
                4
            }
            coEvery { generated.candidateCount(selected.access.scope, any()) } coAnswers {
                now = ready.manifest.session!!.expiresAtMillis
                5
            }
            val query = FileManagementQueryService(artifacts, generated, ApplicationRecoveryGate().apply { ready() }, controller)
            val error = runCatching { query.candidateCount(selected, category, FileCleanupRange.All) }.exceptionOrNull()
            assertTrue(error is EnterpriseConfigurationException)
            assertEquals("enterprise_data_access_unavailable", error?.message)
            assertEquals(ready, controller.state.value)
        }
    }

    @Test fun `attachment handoff rechecks expiry after waiting for the artifact owner`() = runTest {
        var now = 1_800_000_000_000L
        val controller = EnterpriseSessionController(EnterpriseAppliedStore(File(root, "export-expiry"))) { now }
        val ready = controller.enrollFixture(exampleEnterprisePackage())
        val selected = requireNotNull(controller.observeSelectedRealmSelection().first())
        val artifacts = mockk<ArtifactStore>()
        val files = FileManagementApplicationService(artifacts, mockk(), ApplicationRecoveryGate().apply { ready() }, controller)
        val view = ConversationViewLease(kotlin.uuid.Uuid.random(), selected.access, selected.revision) {}
        val preview = AttachmentPreview("file:///report.pdf", null, AttachmentPreview.FileTarget(net.weero.measix.pilot.service.RenderedContentSource.Conversation(view), 1, "report.pdf"))
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val resume = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { artifacts.requireMediaAccess(selected.access.scope, 1) } coAnswers { entered.complete(Unit); resume.await() }
        var handedOff = false
        val handoff = async { runCatching { files.withAttachmentAccess(preview) { handedOff = true } } }
        entered.await()
        now = requireNotNull(ready.manifest.session).expiresAtMillis
        resume.complete(Unit)
        assertTrue(handoff.await().isFailure)
        assertEquals(false, handedOff)
    }

    @Test fun `reference import rechecks selection after IO and removes the unreceived file`() = runTest {
        val selected = mockk<EnterpriseSessionController>()
        var allowed = true
        coEvery { selected.withSelectedRealmSelection<Unit>(selection, any()) } coAnswers {
            check(allowed) { "selection_changed" }
            secondArg<suspend () -> Unit>()()
        }
        var candidate: File? = null
        val service = FileManagementApplicationService(mockk(), mockk(), ApplicationRecoveryGate().apply { ready() }, selected,
            writeTemporaryImage = { file, bytes -> file.writeBytes(bytes); candidate = file; allowed = false })
        val source = File(root, "picked.png").apply { writeBytes(java.util.Base64.getDecoder().decode(PNG)) }
        val failure = runCatching { service.importImageReference(androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            android.net.Uri.fromFile(source), root, selection, Job()) }.exceptionOrNull()
        assertEquals("selection_changed", failure?.message)
        assertTrue(candidate != null && !candidate!!.exists())
        assertTrue(source.isFile)
    }

    @Test fun `temporary image compensation preserves cancellation and deletion failure`() = runTest {
        val cancelled = kotlinx.coroutines.CancellationException("cancelled image writer")
        var writer: Job? = null
        val service = FileManagementApplicationService(mockk(), mockk(), ApplicationRecoveryGate().apply { ready() }, sessions,
            writeTemporaryImage = { file, _ ->
                check(file.mkdir())
                File(file, "blocks-delete").writeText("owned failed output")
                requireNotNull(writer).cancel(cancelled)
                throw cancelled
            })
        var failure: Throwable? = null
        val job = launch {
            writer = currentCoroutineContext()[Job]
            try { service.createGeneratedPreview(me.rerere.ai.ui.ImageGenerationItem(PNG, "image/png"), root, selection, requireNotNull(writer)) }
            catch (error: Throwable) { failure = error }
        }
        job.join()
        assertTrue(failure is kotlinx.coroutines.CancellationException)
        assertEquals(cancelled.message, failure?.message)
        assertTrue(generateSequence(requireNotNull(failure)) { it.cause }.flatMap { it.suppressed.asSequence() }
            .any { it is java.io.IOException && it.message!!.contains("incomplete temporary image") })
    }

    @Test fun `preview production survives selection changes while image access stays with its request`() = runTest {
        val selected = io.mockk.mockk<EnterpriseSessionController>()
        var selectionAllowed = true
        coEvery { selected.withRealmAccess<Unit>(selection.access, any()) } coAnswers { secondArg<suspend () -> Unit>()() }
        coEvery { selected.withSelectedRealmSelection<Unit>(selection, any()) } coAnswers {
            check(selectionAllowed) { "selection_changed" }
            secondArg<suspend () -> Unit>()()
        }
        val service = FileManagementApplicationService(mockk(), mockk(), ApplicationRecoveryGate().apply { ready() }, selected)
        val owner = Job()
        val item = me.rerere.ai.ui.ImageGenerationItem(
            data = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
            mimeType = "image/png",
        )
        val first = service.createGeneratedPreview(item, root, selection, owner)
        try {
            first.image.requireAccess()
            selectionAllowed = false
            assertTrue(runCatching { first.image.readBytes() }.isFailure)
            val second = service.createGeneratedPreview(item, root, selection, owner)
            try {
                assertTrue(second.file.isFile)
                assertTrue(runCatching { second.image.requireAccess() }.isFailure)
                selectionAllowed = true
                second.image.requireAccess()
                owner.cancel()
                assertTrue(runCatching { second.image.readBytes() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            } finally { second.file.delete() }
        } finally { owner.cancel(); first.file.delete() }
    }

    @Test fun `closing the original page during image work rejects the result`() = runTest {
        for (checkOnly in listOf(true, false)) {
            val artifacts = mockk<ArtifactStore>()
            val service = FileManagementApplicationService(artifacts, mockk(), ApplicationRecoveryGate().apply { ready() }, sessions)
            val view = ConversationViewLease(kotlin.uuid.Uuid.random(), selection.access, selection.revision) {}
            val source = service.conversationImageSource(view, 1)
            assertEquals(source, service.conversationImageSource(view, 1))
            val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
            val resume = kotlinx.coroutines.CompletableDeferred<Unit>()
            suspend fun ownerWork() { entered.complete(Unit); resume.await() }
            coEvery { artifacts.requireImageAccess(ConfigurationScope.Personal, 1) } coAnswers { ownerWork() }
            coEvery { artifacts.readImage(ConfigurationScope.Personal, 1) } coAnswers { ownerWork(); byteArrayOf(1) }
            val reading = async { runCatching { if (checkOnly) source.requireAccess() else source.readBytes() } }
            entered.await()
            view.close()
            resume.complete(Unit)
            assertEquals("conversation_view_closed", reading.await().exceptionOrNull()?.message)
        }
    }

    @Test fun `image authorization and bytes cannot return after expiry during owner work`() = runTest {
        for (artifact in listOf(true, false)) for (checkOnly in listOf(true, false)) {
            var now = 1_800_000_000_000L
            val controller = EnterpriseSessionController(EnterpriseAppliedStore(File(root, "$artifact-$checkOnly"))) { now }
            val ready = controller.enrollFixture(exampleEnterprisePackage())
            val selected = requireNotNull(controller.observeSelectedRealmSelection().first())
            val artifacts = mockk<ArtifactStore>()
            val generated = mockk<GeneratedMediaStore>()
            val service = FileManagementApplicationService(artifacts, generated, ApplicationRecoveryGate().apply { ready() }, controller)
            val key = if (artifact) ManagedFileKey.Artifact(1, selected) else ManagedFileKey.Generated(1, selected)
            val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
            val resume = kotlinx.coroutines.CompletableDeferred<Unit>()
            suspend fun ownerWork() { entered.complete(Unit); resume.await() }
            coEvery { artifacts.requireImageAccess(any(), any()) } coAnswers { ownerWork() }
            coEvery { generated.requireImageAccess(any(), any()) } coAnswers { ownerWork() }
            coEvery { artifacts.readImage(any(), any()) } coAnswers { ownerWork(); byteArrayOf(1) }
            coEvery { generated.readImage(any(), any()) } coAnswers { ownerWork(); byteArrayOf(1) }
            val read = async { runCatching { if (checkOnly) service.imageSource(key).requireAccess() else service.imageSource(key).readBytes() } }
            entered.await()
            now = requireNotNull(ready.manifest.session).expiresAtMillis
            resume.complete(Unit)
            assertTrue(read.await().exceptionOrNull() is EnterpriseConfigurationException)
        }
    }

    @Test
    fun `cutoff is calculated once from the supported typed range`() {
        val now = 1_800_000_000_000L

        assertEquals(Long.MAX_VALUE, cutoffFor(FileCleanupRange.All, now))
        assertEquals(
            now - 14.days.inWholeMilliseconds,
            cutoffFor(FileCleanupRange.OlderThanDays(14), now),
        )
        assertTrue(runCatching { FileCleanupRange.OlderThanDays(1) }.isFailure)
    }

    @Test
    fun `application port routes cleanup to one owner and preserves domain result semantics`() = runTest {
        val gate = ApplicationRecoveryGate().also { it.ready() }
        val artifacts = mockk<ArtifactStore>()
        val generated = mockk<GeneratedMediaStore>()
        val service = FileManagementApplicationService(artifacts, generated, gate, sessions)
        coEvery { artifacts.deleteUserRequestedFolderCreatedBefore(ConfigurationScope.Personal, FileFolders.UPLOAD, Long.MAX_VALUE) } returns
            ArtifactCleanupResult(deleted = 2, cleanupPending = 1, skippedInProgress = 3, failed = 4)
        coEvery { generated.deleteCreatedBefore(ConfigurationScope.Personal, Long.MAX_VALUE) } returns
            GeneratedMediaCleanupResult(deleted = 5, cleanupPending = 6, failed = 7)

        assertEquals(
            FileCleanupResult(deleted = 2, cleanupPending = 1, skippedInProgress = 3, failed = 4),
            service.cleanup(selection, FileCleanupCategory.UPLOAD, FileCleanupRange.All),
        )
        coVerify(exactly = 0) { generated.deleteCreatedBefore(ConfigurationScope.Personal, any()) }

        assertEquals(
            FileCleanupResult(deleted = 5, cleanupPending = 6, skippedInProgress = 0, failed = 7),
            service.cleanup(selection, FileCleanupCategory.GENERATED_IMAGES, FileCleanupRange.All),
        )
        coVerify(exactly = 1) { artifacts.deleteUserRequestedFolderCreatedBefore(ConfigurationScope.Personal, FileFolders.UPLOAD, Long.MAX_VALUE) }
    }

    @Test
    fun `query port owns scoped candidate routing`() = runTest {
        val gate = ApplicationRecoveryGate().also { it.ready() }
        val artifacts = mockk<ArtifactStore>()
        val generated = mockk<GeneratedMediaStore>()
        val query = FileManagementQueryService(artifacts, generated, gate, sessions)
        coEvery { artifacts.countFolderCreatedBefore(ConfigurationScope.Personal, FileFolders.UPLOAD, Long.MAX_VALUE) } returns 11
        coEvery { generated.candidateCount(ConfigurationScope.Personal, Long.MAX_VALUE) } returns 12
        val generatedFile = File("generated.png")
        every { generated.isManagedFile(generatedFile) } returns true

        assertEquals(11, query.candidateCount(selection, FileCleanupCategory.UPLOAD, FileCleanupRange.All))
        assertEquals(12, query.candidateCount(selection, FileCleanupCategory.GENERATED_IMAGES, FileCleanupRange.All))
        assertTrue(query.isManagedGeneratedFile(generatedFile))
    }

    @Test
    fun `file commands remain fail closed until global recovery succeeds`() = runTest {
        val gate = ApplicationRecoveryGate()
        val artifacts = mockk<ArtifactStore>()
        val generated = mockk<GeneratedMediaStore>()
        val service = FileManagementApplicationService(artifacts, generated, gate, sessions)
        coEvery { artifacts.deleteUserRequestedFolderCreatedBefore(ConfigurationScope.Personal, FileFolders.UPLOAD, any()) } returns
            ArtifactCleanupResult(deleted = 0, cleanupPending = 0, skippedInProgress = 0, failed = 0)

        val result = async {
            runCatching { service.cleanup(selection, FileCleanupCategory.UPLOAD, FileCleanupRange.All) }
        }
        runCurrent()
        coVerify(exactly = 0) { artifacts.deleteUserRequestedFolderCreatedBefore(ConfigurationScope.Personal, FileFolders.UPLOAD, any()) }

        gate.failed(IllegalStateException("generated media recovery failed"))
        assertTrue(result.await().exceptionOrNull() is ApplicationRecoveryUnavailableException)
        coVerify(exactly = 0) { artifacts.deleteUserRequestedFolderCreatedBefore(ConfigurationScope.Personal, FileFolders.UPLOAD, any()) }
    }

    @Test
    fun `leaving and returning to same realm never revives old file commands`() = runTest {
        val artifacts = mockk<ArtifactStore>()
        val generated = mockk<GeneratedMediaStore>()
        val gate = ApplicationRecoveryGate().also { it.ready() }
        val application = FileManagementApplicationService(artifacts, generated, gate, sessions)
        val query = FileManagementQueryService(artifacts, generated, gate, sessions)
        val oldPersonal = selection
        sessions.enrollFixture(exampleEnterprisePackage())
        val enterprise = requireNotNull(sessions.observeSelectedRealmSelection().first())
        sessions.switchRealm(RealmSwitchRequest(enterprise, RealmAccess.Personal)) {}
        val personal = requireNotNull(sessions.observeSelectedRealmSelection().first())
        assertTrue(personal != oldPersonal)
        val targets = listOf(oldPersonal, enterprise)
        targets.forEach { old ->
            assertTrue(runCatching { application.deleteArtifact(ManagedFileKey.Artifact(1, old)) }.exceptionOrNull() is EnterpriseConfigurationException)
            assertTrue(runCatching { application.deleteGenerated(ManagedFileKey.Generated(1, old)) }.exceptionOrNull() is EnterpriseConfigurationException)
            assertTrue(runCatching { application.cleanup(old, FileCleanupCategory.UPLOAD, FileCleanupRange.All) }.exceptionOrNull() is EnterpriseConfigurationException)
            for (key in listOf(ManagedFileKey.Artifact(1, old), ManagedFileKey.Generated(1, old))) {
                assertTrue(runCatching { application.imageSource(key).requireAccess() }.exceptionOrNull() is EnterpriseConfigurationException)
                assertTrue(runCatching { application.imageSource(key).readBytes() }.exceptionOrNull() is EnterpriseConfigurationException)
            }
            assertTrue(runCatching { query.inspectArtifact(ManagedFileKey.Artifact(1, old)) }.exceptionOrNull() is EnterpriseConfigurationException)
            assertTrue(runCatching { query.candidateCount(old, FileCleanupCategory.GENERATED_IMAGES, FileCleanupRange.All) }.exceptionOrNull() is EnterpriseConfigurationException)
        }
        coVerify(exactly = 0) { artifacts.deleteUserRequested(any(), any()) }
        coVerify(exactly = 0) { generated.delete(any(), any()) }
        coEvery { artifacts.deleteUserRequested(ConfigurationScope.Personal, 1) } returns net.weero.measix.pilot.data.files.ArtifactDeleteResult.Completed(1)
        assertEquals(ArtifactDeleteOutcome.Deleted, application.deleteArtifact(ManagedFileKey.Artifact(1, personal)))
    }

    @Test
    fun `gallery replaces pages and discards an old lazy collection after realm switch`() = runTest {
        val artifacts = mockk<ArtifactStore>()
        val generated = mockk<GeneratedMediaStore>()
        val sources = mutableListOf<PagingSource<Int, GenMediaEntity>>()
        every { generated.pagingSource(any()) } answers {
            val scope = firstArg<ConfigurationScope>()
            object : PagingSource<Int, GenMediaEntity>() {
                override fun getRefreshKey(state: PagingState<Int, GenMediaEntity>): Int? = null
                override suspend fun load(params: LoadParams<Int>): LoadResult<Int, GenMediaEntity> =
                    LoadResult.Page(listOf(GenMediaEntity(id = 1, path = "images/image.png", modelId = "m",
                        prompt = if (scope == ConfigurationScope.Personal) "personal" else "enterprise", createAt = 1, scope = scope)), null, null)
            }.also(sources::add)
        }
        every { generated.resolveCanonicalFile(any()) } returns File(root, "images/image.png")
        val query = FileManagementQueryService(artifacts, generated, ApplicationRecoveryGate().also { it.ready() }, sessions)
        var pending: PagingData<GeneratedMediaUiModel>? = null
        val watcher = backgroundScope.launch { query.observeGeneratedPaging().collect { pending = it } }
        runCurrent()
        val old = requireNotNull(pending)
        val originalSource = sources.single()
        sessions.enrollFixture(exampleEnterprisePackage())
        runCurrent()
        assertTrue(originalSource.invalid)
        val dispatcher = StandardTestDispatcher(testScheduler)
        fun differ() = AsyncPagingDataDiffer(
            object : DiffUtil.ItemCallback<GeneratedMediaUiModel>() {
                override fun areItemsTheSame(oldItem: GeneratedMediaUiModel, newItem: GeneratedMediaUiModel) = oldItem.key == newItem.key
                override fun areContentsTheSame(oldItem: GeneratedMediaUiModel, newItem: GeneratedMediaUiModel) = oldItem == newItem
            },
            object : ListUpdateCallback {
                override fun onInserted(position: Int, count: Int) = Unit
                override fun onRemoved(position: Int, count: Int) = Unit
                override fun onMoved(fromPosition: Int, toPosition: Int) = Unit
                override fun onChanged(position: Int, count: Int, payload: Any?) = Unit
            }, mainDispatcher = dispatcher, workerDispatcher = dispatcher,
        )
        val stale = differ()
        val oldCollector = backgroundScope.launch { stale.submitData(old) }
        runCurrent()
        assertTrue(stale.snapshot().items.isEmpty())
        oldCollector.cancel()
        watcher.cancel()
        val current = differ()
        val collector = backgroundScope.launch { query.observeGeneratedPaging().collectLatest(current::submitData) }
        runCurrent()
        assertEquals(listOf("enterprise"), current.snapshot().items.map { it.prompt })
        val enterprise = requireNotNull(sessions.observeSelectedRealmSelection().first())
        sessions.switchRealm(RealmSwitchRequest(enterprise, RealmAccess.Personal)) {}
        runCurrent()
        assertEquals(listOf("personal"), current.snapshot().items.map { it.prompt })
        collector.cancel()
    }

    @Test
    fun `directory query reports failure and a new subscription retries the same realm`() = runTest {
        val artifacts = mockk<ArtifactStore>()
        val generated = mockk<GeneratedMediaStore>()
        every { artifacts.observe(ConfigurationScope.Personal) } returns kotlinx.coroutines.flow.flow { throw java.io.IOException("disk") }
        every { generated.observe(ConfigurationScope.Personal) } returns flowOf(emptyList())
        val query = FileManagementQueryService(artifacts, generated, ApplicationRecoveryGate().also { it.ready() }, sessions)
        assertTrue(query.observeDirectory().first { it.failed }.uploads.isEmpty())
        every { artifacts.observe(ConfigurationScope.Personal) } returns flowOf(emptyList())
        assertEquals(selection, query.observeDirectory().first { it.selection != null }.selection)
    }

    @Test
    fun `generated preview removes a partial file when writing fails`() = runTest {
        val tempDirectory = kotlin.io.path.createTempDirectory("generated-preview").toFile()
        var partialFile: File? = null
        val service = FileManagementApplicationService(
            artifactStore = mockk(),
            sessions = sessions,
            generatedMediaStore = mockk(),
            recoveryGate = ApplicationRecoveryGate().also { it.ready() },
            writeTemporaryImage = { file, _ ->
                partialFile = file
                file.writeText("partial")
                error("disk write failed")
            },
        )
        val item = me.rerere.ai.ui.ImageGenerationItem(
            data = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
            mimeType = "image/png",
        )

        try {
            val failure = runCatching {
                service.createGeneratedPreview(item, tempDirectory, selection, requireNotNull(currentCoroutineContext()[Job]))
            }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(partialFile != null)
            assertTrue(partialFile?.exists() == false)
        } finally {
            tempDirectory.deleteRecursively()
        }
    }

    @Test
    fun `generated preview retains ownership until dispatcher cancellation is observed`() = runTest {
        val tempDirectory = kotlin.io.path.createTempDirectory("generated-preview-cancel").toFile()
        var operationJob: Job? = null
        var candidate: File? = null
        val service = FileManagementApplicationService(
            artifactStore = mockk(),
            sessions = sessions,
            generatedMediaStore = mockk(),
            recoveryGate = ApplicationRecoveryGate().also { it.ready() },
            writeTemporaryImage = { file, bytes ->
                candidate = file
                file.writeBytes(bytes)
                operationJob?.cancel()
            },
        )
        val item = me.rerere.ai.ui.ImageGenerationItem(
            data = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
            mimeType = "image/png",
        )

        try {
            val operation = async {
                operationJob = currentCoroutineContext()[Job]
                service.createGeneratedPreview(item, tempDirectory, selection, requireNotNull(currentCoroutineContext()[Job]))
            }
            val failure = runCatching { operation.await() }.exceptionOrNull()
            assertTrue(failure is kotlinx.coroutines.CancellationException)
            assertTrue(candidate != null)
            assertTrue(candidate?.exists() == false)
        } finally {
            tempDirectory.deleteRecursively()
        }
    }
    companion object {
        private const val PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    }
}
