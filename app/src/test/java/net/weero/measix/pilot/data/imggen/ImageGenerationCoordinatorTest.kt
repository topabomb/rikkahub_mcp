package net.weero.measix.pilot.data.imggen

import net.weero.measix.pilot.data.configuration.ConfigurationScope

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.CONTENT_BLOCKED_MODEL_DETAIL
import net.weero.measix.pilot.data.repository.GenMediaRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ImageGenerationCoordinatorTest {
    private val model = Model(modelId = "gpt-image-1", displayName = "GPT Image", type = ModelType.IMAGE)
    private val providerSetting = ProviderSetting.OpenAI(models = listOf(model))

    @OptIn(ExperimentalEncodingApi::class)
    private fun pngItem(partial: Boolean = false) = ImageGenerationItem(
        data = Base64.encode(TINY_PNG),
        mimeType = "image/png",
        partial = partial,
    )

    private val providerBindings = mutableMapOf<me.rerere.common.configuration.ConfigurationReference, Provider<*>>()
    private val providers = mockk<me.rerere.ai.provider.ProviderManager> {
        every { getProviderByType(any()) } answers { providerBindings.getValue(firstArg<ProviderSetting>().id) as Provider<ProviderSetting> }
    }
    private val sessions = mockk<net.weero.measix.pilot.data.enterprise.EnterpriseSessionController> {
        coEvery { withRealmAccess<Any?>(any(), any()) } coAnswers { secondArg<suspend () -> Any?>().invoke() }
    }

    private fun available(provider: Provider<*>): net.weero.measix.pilot.service.ModelExecutionSnapshot {
        val setting = providerSetting.copy(id = me.rerere.common.configuration.ConfigurationReference.random())
        providerBindings[setting.id] = provider
        return net.weero.measix.pilot.service.ModelExecutionSnapshot(model,
            net.weero.measix.pilot.service.runtime.ModelExecutionLease { accept ->
                accept(net.weero.measix.pilot.service.runtime.ModelRequestTarget.Remote(setting))
            }, "fixture", null)
    }

    private fun coordinator(scope: kotlinx.coroutines.CoroutineScope, store: GeneratedMediaStore): ImageGenerationCoordinator =
        ImageGenerationCoordinator(scope, store, mockk(), providers, sessions)

    @Test
    fun `page cleanup failure keeps committed media and original owner for exit retry`() = runTest {
        val access = net.weero.measix.pilot.data.enterprise.RealmAccess.Enterprise(
            ConfigurationScope.Enterprise(me.rerere.common.configuration.EnterpriseAuthority("local:example", "deployment"), "user"), "original")
        val selection = net.weero.measix.pilot.data.enterprise.RealmSelection(access, 7)
        coEvery { sessions.withSelectedRealmSelection<Any?>(selection, any()) } coAnswers { secondArg<suspend () -> Any?>().invoke() }
        val provider = mockk<Provider<ProviderSetting>>()
        coEvery { provider.generateImage(any(), any()) } returns flowOf(pngItem())
        val modelCapture = available(provider)
        var releases = 0
        val lease = net.weero.measix.pilot.service.runtime.ModelExecutionLease(releaseOwner = {
            releases++
            if (releases == 1) error("release pending")
        }) { accept -> modelCapture.requests.execute { accept(it) } }
        val models = mockk<net.weero.measix.pilot.service.ModelExecutionService>()
        coEvery { models.capturePageImage(selection, any(), model.id, any(), any()) } coAnswers {
            arg<(net.weero.measix.pilot.service.runtime.ModelExecutionLease) -> Unit>(4)(lease)
            modelCapture.copy(requests = lease)
        }
        val files = tempDir("image-page-release")
        val repository = mockk<GenMediaRepository> { coEvery { existsByPath(any()) } returns false }
        every { repository.insertMedia(any()) } returns 81L
        val coordinator = ImageGenerationCoordinator(this, GeneratedMediaStore(files, repository, mockk()), models, providers, sessions)
        val outcome = coordinator.enqueue(ImageGenerationRequest(source = ImageGenerationSource.Page(selection, model.id), prompt = "original", size = "auto")) as ImageGenerationOutcome.Success
        assertTrue(outcome.cleanupPending)
        assertTrue(outcome.media.single().canonicalFile.exists())
        assertEquals(1, releases)
        coordinator.cancelAndAwait(access)
        coordinator.cancelAndAwait(access)
        assertEquals(2, releases)
        verify(exactly = 1) { repository.insertMedia(match { it.scope == access.scope }) }
        files.deleteRecursively()
    }

    @Test
    fun `cancel during page capture retains failed release without replacing cancellation`() = runTest {
        val access = net.weero.measix.pilot.data.enterprise.RealmAccess.Enterprise(
            ConfigurationScope.Enterprise(me.rerere.common.configuration.EnterpriseAuthority("local:example", "deployment"), "user"), "original")
        val selection = net.weero.measix.pilot.data.enterprise.RealmSelection(access, 7)
        coEvery { sessions.withSelectedRealmSelection<Any?>(selection, any()) } coAnswers { secondArg<suspend () -> Any?>().invoke() }
        val entered = CompletableDeferred<Unit>()
        val unwinding = CompletableDeferred<Unit>()
        val allowStop = CompletableDeferred<Unit>()
        var releaseAllowed = false
        var releases = 0
        val lease = net.weero.measix.pilot.service.runtime.ModelExecutionLease(releaseOwner = {
            releases++
            check(releaseAllowed) { "release pending" }
        }) { error("capture never completed") }
        val models = mockk<net.weero.measix.pilot.service.ModelExecutionService>()
        coEvery { models.capturePageImage(selection, any(), model.id, any(), any()) } coAnswers {
            arg<(net.weero.measix.pilot.service.runtime.ModelExecutionLease) -> Unit>(4)(lease)
            entered.complete(Unit)
            try { awaitCancellation() } finally {
                withContext(kotlinx.coroutines.NonCancellable) { unwinding.complete(Unit); allowStop.await() }
            }
        }
        val store = mockk<GeneratedMediaStore>()
        val coordinator = ImageGenerationCoordinator(this, store, models, providers, sessions)
        val request = ImageGenerationRequest(source = ImageGenerationSource.Page(selection, model.id), prompt = "original", size = "auto")
        val caught = CompletableDeferred<Throwable>()
        val caller = async {
            try { coordinator.enqueue(request) } catch (error: Throwable) { caught.complete(error) }
        }
        entered.await()
        caller.cancel()
        unwinding.await()
        val exit = async { runCatching { coordinator.cancelAndAwait(access) } }
        runCurrent()
        assertFalse(exit.isCompleted)
        assertEquals(0, releases)
        allowStop.complete(Unit)
        caller.join()
        assertTrue(exit.await().isFailure)
        assertTrue(caught.await() is kotlinx.coroutines.CancellationException)
        assertTrue(caught.await().suppressed.any { it.message == "release pending" })
        releaseAllowed = true
        coordinator.cancelAndAwait(access)
        val released = releases
        coordinator.cancelAndAwait(access)
        assertEquals(released, releases)
        coVerify(exactly = 0) { store.commit(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `duplicate registration cannot cancel original tool or release its Turn resources`() = runTest {
        val provider = mockk<Provider<ProviderSetting>>()
        val entered = CompletableDeferred<Unit>()
        coEvery { provider.generateImage(any(), any()) } returns flow { entered.complete(Unit); awaitCancellation() }
        val captured = available(provider)
        val coordinator = coordinator(this, mockk())
        val request = ImageGenerationRequest(id = "same", source = ImageGenerationSource.Tool(net.weero.measix.pilot.data.enterprise.RealmAccess.Personal, captured), prompt = "first", size = "auto")
        val original = async { coordinator.enqueue(request) }
        entered.await()
        assertEquals("image_request_already_registered", runCatching { coordinator.enqueue(request.copy(prompt = "second")) }.exceptionOrNull()?.message)
        assertTrue(original.isActive)
        coordinator.cancel(request.id)
        original.join()
        captured.requests.execute { Unit }
        coVerify(exactly = 1) { provider.generateImage(any(), any()) }
    }

    private fun tempDir(prefix: String): File = createTempDirectory(prefix).toFile()

    private fun completedImageFiles(filesDir: File): List<File> =
        File(filesDir, "images").listFiles().orEmpty().filter { it.isFile && !it.name.endsWith(".pending") }

    @Test
    fun `queue is fifo and skips partials`() = runTest {
        val firstProvider = mockk<Provider<ProviderSetting>>()
        val secondProvider = mockk<Provider<ProviderSetting>>()
        val order = mutableListOf<String>()
        val enterprise = ConfigurationScope.Enterprise(me.rerere.common.configuration.EnterpriseAuthority("local:example", "deployment"), "user")
        coEvery { firstProvider.generateImage(any(), any()) } answers {
            flow {
                order.add("first")
                emit(pngItem(partial = true))
                emit(pngItem(partial = false))
            }
        }
        coEvery { secondProvider.generateImage(any(), any()) } answers {
            flow {
                order.add("second")
                emit(pngItem())
            }
        }
        val filesDir = tempDir("img-coord")
        val repository = mockk<GenMediaRepository> { coEvery { existsByPath(any()) } returns false }
        every { repository.insertMedia(any()) } returnsMany listOf(11L, 22L)
        val store = GeneratedMediaStore(
            filesDir = filesDir,
            genMediaRepository = repository,
            artifactStore = mockk(relaxed = true),
        )
        val coordinator = coordinator(this, store)
        val first = async {
            coordinator.enqueue(
                ImageGenerationRequest(
                    source = ImageGenerationSource.Tool(net.weero.measix.pilot.data.enterprise.RealmAccess.Enterprise(enterprise, "session-one"), available(firstProvider)),
                    prompt = "one",
                    size = "auto",
                )
            )
        }
        val second = async {
            coordinator.enqueue(
                ImageGenerationRequest(
                    source = ImageGenerationSource.Tool(net.weero.measix.pilot.data.enterprise.RealmAccess.Personal, available(secondProvider)),
                    prompt = "two",
                    size = "auto",
                )
            )
        }
        advanceUntilIdle()
        val firstResult = first.await() as ImageGenerationOutcome.Success
        val secondResult = second.await() as ImageGenerationOutcome.Success
        assertEquals(listOf("first", "second"), order)
        verify(exactly = 1) { repository.insertMedia(match { it.scope == enterprise && it.prompt == "one" }) }
        verify(exactly = 1) { repository.insertMedia(match { it.scope == ConfigurationScope.Personal && it.prompt == "two" }) }
        assertEquals(11L, firstResult.media.single().mediaId)
        assertEquals(22L, secondResult.media.single().mediaId)
        assertTrue(firstResult.media.single().canonicalFile.exists())
        filesDir.deleteRecursively()
    }

    @Test
    fun `provider failure does not block the next request`() = runTest {
        val failing = mockk<Provider<ProviderSetting>>()
        val succeeding = mockk<Provider<ProviderSetting>>()
        coEvery { failing.generateImage(any(), any()) } returns flow { error("boom") }
        coEvery { succeeding.generateImage(any(), any()) } returns flowOf(pngItem())
        val filesDir = tempDir("img-fail")
        val repository = mockk<GenMediaRepository> { coEvery { existsByPath(any()) } returns false }
        every { repository.insertMedia(any()) } returns 7L
        val coordinator = coordinator(
            this,
            GeneratedMediaStore(filesDir, repository, mockk(relaxed = true)),
        )
        val failed = coordinator.enqueue(
            ImageGenerationRequest(
                    source = ImageGenerationSource.Tool(net.weero.measix.pilot.data.enterprise.RealmAccess.Personal, available(failing)),
                prompt = "bad",
                size = "auto",
            )
        )
        val ok = coordinator.enqueue(
            ImageGenerationRequest(
                    source = ImageGenerationSource.Tool(net.weero.measix.pilot.data.enterprise.RealmAccess.Personal, available(succeeding)),
                prompt = "good",
                size = "auto",
            )
        )
        val failure = failed as ImageGenerationOutcome.Failure
        assertEquals("runtime_error", failure.reason)
        assertEquals("boom", failure.detail)
        assertTrue(ok is ImageGenerationOutcome.Success)
        filesDir.deleteRecursively()
    }

    @Test
    fun `provider HTTP policy and rate limit become distinct reasons`() = runTest {
        val policy = mockk<Provider<ProviderSetting>>()
        val limited = mockk<Provider<ProviderSetting>>()
        coEvery { policy.generateImage(any(), any()) } returns flow {
            throw HttpException(
                message = "Failed to get response: 400 moderation_blocked Your request was rejected as a result of our safety system.",
                statusCode = 400,
                errorCode = "moderation_blocked",
                errorType = "image_generation_user_error",
            )
        }
        coEvery { limited.generateImage(any(), any()) } returns flow {
            throw HttpException(
                message = "Failed to get response: 429 rate_limit_exceeded Please retry after 1 second.",
                statusCode = 429,
                errorCode = "rate_limit_exceeded",
            )
        }
        val filesDir = tempDir("img-classify")
        val repository = mockk<GenMediaRepository> { coEvery { existsByPath(any()) } returns false }
        val coordinator = coordinator(
            this,
            GeneratedMediaStore(filesDir, repository, mockk(relaxed = true)),
        )
        val blocked = coordinator.enqueue(
            ImageGenerationRequest(
                    source = ImageGenerationSource.Tool(net.weero.measix.pilot.data.enterprise.RealmAccess.Personal, available(policy)),
                prompt = "bad",
                size = "auto",
            )
        ) as ImageGenerationOutcome.Failure
        val rate = coordinator.enqueue(
            ImageGenerationRequest(
                    source = ImageGenerationSource.Tool(net.weero.measix.pilot.data.enterprise.RealmAccess.Personal, available(limited)),
                prompt = "later",
                size = "auto",
            )
        ) as ImageGenerationOutcome.Failure
        assertEquals("content_blocked", blocked.reason)
        assertEquals(CONTENT_BLOCKED_MODEL_DETAIL, blocked.detail)
        assertEquals("rate_limited", rate.reason)
        assertTrue(rate.detail.orEmpty().contains("1 second"))
        filesDir.deleteRecursively()
    }

    @Test
    fun `cancelled queued request never calls provider`() = runTest {
        val blocking = mockk<Provider<ProviderSetting>>()
        val skipped = mockk<Provider<ProviderSetting>>()
        val blockingStarted = CompletableDeferred<Unit>()
        coEvery { blocking.generateImage(any(), any()) } returns flow {
            blockingStarted.complete(Unit)
            awaitCancellation()
        }
        val filesDir = tempDir("img-cancel")
        val repository = mockk<GenMediaRepository> { coEvery { existsByPath(any()) } returns false }
        every { repository.insertMedia(any()) } returns 1L
        val coordinator = coordinator(
            this,
            GeneratedMediaStore(filesDir, repository, mockk(relaxed = true)),
        )
        val first = async {
            coordinator.enqueue(
                ImageGenerationRequest(
                    source = ImageGenerationSource.Tool(net.weero.measix.pilot.data.enterprise.RealmAccess.Personal, available(blocking)),
                    prompt = "run",
                    size = "auto",
                )
            )
        }
        val secondRequest = ImageGenerationRequest(
                    source = ImageGenerationSource.Tool(net.weero.measix.pilot.data.enterprise.RealmAccess.Personal, available(skipped)),
            prompt = "skip",
            size = "auto",
        )
        val second = async { coordinator.enqueue(secondRequest) }
        blockingStarted.await()
        advanceUntilIdle()
        coordinator.cancel(secondRequest.id)
        second.join()
        first.cancel()
        first.join()
        advanceUntilIdle()
        coVerify(exactly = 0) { skipped.generateImage(any(), any()) }
        filesDir.deleteRecursively()
    }

    @Test
    fun `enqueue after drain still starts a worker`() = runTest {
        val firstProvider = mockk<Provider<ProviderSetting>>()
        val secondProvider = mockk<Provider<ProviderSetting>>()
        coEvery { firstProvider.generateImage(any(), any()) } returns flowOf(pngItem())
        coEvery { secondProvider.generateImage(any(), any()) } returns flowOf(pngItem())
        val filesDir = tempDir("img-drain")
        val repository = mockk<GenMediaRepository> { coEvery { existsByPath(any()) } returns false }
        every { repository.insertMedia(any()) } returnsMany listOf(1L, 2L)
        val coordinator = coordinator(
            this,
            GeneratedMediaStore(filesDir, repository, mockk(relaxed = true)),
        )
        val first = coordinator.enqueue(
            ImageGenerationRequest(
                    source = ImageGenerationSource.Tool(net.weero.measix.pilot.data.enterprise.RealmAccess.Personal, available(firstProvider)),
                prompt = "one",
                size = "auto",
            )
        )
        assertTrue(first is ImageGenerationOutcome.Success)
        val second = coordinator.enqueue(
            ImageGenerationRequest(
                    source = ImageGenerationSource.Tool(net.weero.measix.pilot.data.enterprise.RealmAccess.Personal, available(secondProvider)),
                prompt = "two",
                size = "auto",
            )
        )
        assertTrue(second is ImageGenerationOutcome.Success)
        filesDir.deleteRecursively()
    }

    @Test
    fun `queued phase is recorded before the provider is called`() = runTest {
        val phases = mutableListOf<ImageGenerationPhase>()
        val provider = mockk<Provider<ProviderSetting>>()
        coEvery { provider.generateImage(any(), any()) } answers {
            assertEquals(
                listOf(ImageGenerationPhase.QUEUED, ImageGenerationPhase.GENERATING),
                phases.toList(),
            )
            flowOf(pngItem())
        }
        val filesDir = tempDir("img-phase-order")
        val repository = mockk<GenMediaRepository> { coEvery { existsByPath(any()) } returns false }
        every { repository.insertMedia(any()) } returns 1L
        val coordinator = coordinator(
            this,
            GeneratedMediaStore(filesDir, repository, mockk(relaxed = true)),
        )
        coordinator.enqueue(
            ImageGenerationRequest(
                    source = ImageGenerationSource.Tool(net.weero.measix.pilot.data.enterprise.RealmAccess.Personal, available(provider)),
                prompt = "one",
                size = "auto",
                onPhase = { phases.add(it) },
            )
        )
        assertEquals(
            listOf(
                ImageGenerationPhase.QUEUED,
                ImageGenerationPhase.GENERATING,
                ImageGenerationPhase.PERSISTING,
            ),
            phases,
        )
        filesDir.deleteRecursively()
    }

    @Test
    fun `cancel after provider starts does not persist a result`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val provider = mockk<Provider<ProviderSetting>>()
        coEvery { provider.generateImage(any(), any()) } returns flow {
            entered.complete(Unit)
            awaitCancellation()
        }
        val filesDir = tempDir("img-cancel-running")
        val repository = mockk<GenMediaRepository> { coEvery { existsByPath(any()) } returns false }
        val coordinator = coordinator(
            this,
            GeneratedMediaStore(filesDir, repository, mockk(relaxed = true)),
        )
        val request = ImageGenerationRequest(
                    source = ImageGenerationSource.Tool(net.weero.measix.pilot.data.enterprise.RealmAccess.Personal, available(provider)),
            prompt = "run",
            size = "auto",
        )
        val result = async { coordinator.enqueue(request) }
        entered.await()
        coordinator.cancel(request.id)
        advanceUntilIdle()
        assertTrue(result.getCompletionExceptionOrNull() is kotlinx.coroutines.CancellationException)
        verify(exactly = 0) { repository.insertMedia(any()) }
        filesDir.deleteRecursively()
    }

    @Test
    fun `extra finals beyond numOfImages are not persisted`() = runTest {
        val provider = mockk<Provider<ProviderSetting>>()
        coEvery { provider.generateImage(any(), any()) } returns flow {
            emit(pngItem())
            emit(pngItem())
            emit(pngItem())
        }
        val filesDir = tempDir("img-extra")
        val repository = mockk<GenMediaRepository> { coEvery { existsByPath(any()) } returns false }
        every { repository.insertMedia(any()) } returnsMany listOf(1L, 2L, 3L)
        val coordinator = coordinator(
            this,
            GeneratedMediaStore(filesDir, repository, mockk(relaxed = true)),
        )
        val outcome = coordinator.enqueue(
            ImageGenerationRequest(
                    source = ImageGenerationSource.Tool(net.weero.measix.pilot.data.enterprise.RealmAccess.Personal, available(provider)),
                prompt = "one",
                numOfImages = 1,
                size = "auto",
            )
        )
        assertTrue(outcome is ImageGenerationOutcome.Success)
        assertEquals(1, (outcome as ImageGenerationOutcome.Success).media.size)
        verify(exactly = 1) { repository.insertMedia(any()) }
        filesDir.deleteRecursively()
    }

    @Test
    fun `enough finals stop a hanging provider flow`() = runTest {
        val provider = mockk<Provider<ProviderSetting>>()
        val cancelled = CompletableDeferred<Unit>()
        coEvery { provider.generateImage(any(), any()) } returns flow {
            emit(pngItem())
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        val filesDir = tempDir("img-hang")
        val repository = mockk<GenMediaRepository> { coEvery { existsByPath(any()) } returns false }
        every { repository.insertMedia(any()) } returns 1L
        val coordinator = coordinator(
            this,
            GeneratedMediaStore(filesDir, repository, mockk(relaxed = true)),
        )
        val nextProvider = mockk<Provider<ProviderSetting>>()
        coEvery { nextProvider.generateImage(any(), any()) } returns flowOf(pngItem())
        val first = coordinator.enqueue(
            ImageGenerationRequest(
                    source = ImageGenerationSource.Tool(net.weero.measix.pilot.data.enterprise.RealmAccess.Personal, available(provider)),
                prompt = "one",
                numOfImages = 1,
                size = "auto",
            )
        )
        val second = coordinator.enqueue(
            ImageGenerationRequest(
                    source = ImageGenerationSource.Tool(net.weero.measix.pilot.data.enterprise.RealmAccess.Personal, available(nextProvider)),
                prompt = "two",
                size = "auto",
            )
        )
        assertTrue(first is ImageGenerationOutcome.Success)
        assertTrue(second is ImageGenerationOutcome.Success)
        assertTrue(cancelled.isCompleted)
        filesDir.deleteRecursively()
    }

    @Test
    fun `cancelling the enqueue caller aborts a queued request`() = runTest {
        val blocking = mockk<Provider<ProviderSetting>>()
        val skipped = mockk<Provider<ProviderSetting>>()
        val blockingStarted = CompletableDeferred<Unit>()
        val blockingCancelled = CompletableDeferred<Unit>()
        coEvery { blocking.generateImage(any(), any()) } returns flow {
            blockingStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                blockingCancelled.complete(Unit)
            }
        }
        val filesDir = tempDir("img-caller-queued")
        val repository = mockk<GenMediaRepository> { coEvery { existsByPath(any()) } returns false }
        every { repository.insertMedia(any()) } returns 1L
        val coordinator = coordinator(
            this,
            GeneratedMediaStore(filesDir, repository, mockk(relaxed = true)),
        )
        val first = async {
            coordinator.enqueue(
                ImageGenerationRequest(
                    source = ImageGenerationSource.Tool(net.weero.measix.pilot.data.enterprise.RealmAccess.Personal, available(blocking)),
                    prompt = "run",
                    size = "auto",
                )
            )
        }
        val second = async {
            coordinator.enqueue(
                ImageGenerationRequest(
                    source = ImageGenerationSource.Tool(net.weero.measix.pilot.data.enterprise.RealmAccess.Personal, available(skipped)),
                    prompt = "skip",
                    size = "auto",
                )
            )
        }
        blockingStarted.await()
        advanceUntilIdle()
        second.cancel()
        second.join()
        first.cancel()
        first.join()
        assertTrue(blockingCancelled.isCompleted)
        coVerify(exactly = 0) { skipped.generateImage(any(), any()) }
        verify(exactly = 0) { repository.insertMedia(any()) }
        filesDir.deleteRecursively()
    }

    @Test
    fun `cancelling the enqueue caller stops a running provider flow`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val flowCancelled = CompletableDeferred<Unit>()
        val provider = mockk<Provider<ProviderSetting>>()
        coEvery { provider.generateImage(any(), any()) } returns flow {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                flowCancelled.complete(Unit)
            }
        }
        val filesDir = tempDir("img-caller-running")
        val repository = mockk<GenMediaRepository> { coEvery { existsByPath(any()) } returns false }
        val coordinator = coordinator(
            this,
            GeneratedMediaStore(filesDir, repository, mockk(relaxed = true)),
        )
        val result = async {
            coordinator.enqueue(
                ImageGenerationRequest(
                    source = ImageGenerationSource.Tool(net.weero.measix.pilot.data.enterprise.RealmAccess.Personal, available(provider)),
                    prompt = "run",
                    size = "auto",
                )
            )
        }
        entered.await()
        result.cancel()
        result.join()
        advanceUntilIdle()
        assertTrue(flowCancelled.isCompleted)
        verify(exactly = 0) { repository.insertMedia(any()) }
        filesDir.deleteRecursively()
    }

    @Test
    fun `cancelling the enqueue caller at the database commit point keeps durable media`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val provider = mockk<Provider<ProviderSetting>>()
        coEvery { provider.generateImage(any(), any()) } returns flowOf(pngItem())
        val filesDir = tempDir("img-caller-persist")
        val repository = mockk<GenMediaRepository> { coEvery { existsByPath(any()) } returns false }
        every { repository.insertMedia(any()) } answers {
            entered.complete(Unit)
            release.await()
            51L
        }
        val coordinator = coordinator(
            this,
            GeneratedMediaStore(filesDir, repository, mockk(relaxed = true)),
        )
        val result = async {
            coordinator.enqueue(
                ImageGenerationRequest(
                    source = ImageGenerationSource.Tool(net.weero.measix.pilot.data.enterprise.RealmAccess.Personal, available(provider)),
                    prompt = "run",
                    size = "auto",
                )
            )
        }
        entered.await()
        result.cancel()
        release.countDown()
        result.join()
        advanceUntilIdle()
        assertTrue(result.getCompletionExceptionOrNull() is kotlinx.coroutines.CancellationException)
        // The image is atomically renamed from .pending to its final name before insertMedia runs
        // (that is where `entered` fires), so once the caller job has joined there is no async
        // cleanup left to wait for: the committed media file is already durable on disk.
        val leftovers = withContext(Dispatchers.IO) { completedImageFiles(filesDir) }
        assertEquals(1, leftovers.size)
        verify(exactly = 1) { repository.insertMedia(any()) }
        filesDir.deleteRecursively()
    }

    @Test
    fun `coordinator cancel at the database commit point is not rewritten as persistence error`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val provider = mockk<Provider<ProviderSetting>>()
        coEvery { provider.generateImage(any(), any()) } returns flowOf(pngItem())
        val filesDir = tempDir("img-persist-cancel")
        val repository = mockk<GenMediaRepository> { coEvery { existsByPath(any()) } returns false }
        every { repository.insertMedia(any()) } answers {
            entered.complete(Unit)
            release.await()
            52L
        }
        val coordinator = coordinator(
            this,
            GeneratedMediaStore(filesDir, repository, mockk(relaxed = true)),
        )
        val request = ImageGenerationRequest(
                    source = ImageGenerationSource.Tool(net.weero.measix.pilot.data.enterprise.RealmAccess.Personal, available(provider)),
            prompt = "run",
            size = "auto",
        )
        val result = async { coordinator.enqueue(request) }
        entered.await()
        val cancellation = async { coordinator.cancel(request.id) }
        runCurrent()
        assertFalse(cancellation.isCompleted)
        release.countDown()
        cancellation.await()
        advanceUntilIdle()
        assertTrue(result.getCompletionExceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertEquals(1, completedImageFiles(filesDir).size)
        verify(exactly = 1) { repository.insertMedia(any()) }
        filesDir.deleteRecursively()
    }

    @Test
    fun `phase callback failure cancels the queued request`() = runTest {
        val provider = mockk<Provider<ProviderSetting>>()
        coEvery { provider.generateImage(any(), any()) } returns flowOf(pngItem())
        val filesDir = tempDir("img-phase")
        val repository = mockk<GenMediaRepository> { coEvery { existsByPath(any()) } returns false }
        val coordinator = coordinator(
            this,
            GeneratedMediaStore(filesDir, repository, mockk(relaxed = true)),
        )
        runCatching {
            coordinator.enqueue(
                ImageGenerationRequest(
                    source = ImageGenerationSource.Tool(net.weero.measix.pilot.data.enterprise.RealmAccess.Personal, available(provider)),
                    prompt = "one",
                    size = "auto",
                    onPhase = { error("phase failed") },
                )
            )
        }
        advanceUntilIdle()
        coVerify(exactly = 0) { provider.generateImage(any(), any()) }
        filesDir.deleteRecursively()
    }

    @Test
    fun `edit requests share the persist path and do not call generateImage`() = runTest {
        val provider = mockk<Provider<ProviderSetting>>()
        coEvery { provider.editImage(any(), any()) } returns flowOf(pngItem())
        val filesDir = tempDir("img-edit")
        val repository = mockk<GenMediaRepository> { coEvery { existsByPath(any()) } returns false }
        every { repository.insertMedia(any()) } returns 33L
        val coordinator = coordinator(
            this,
            GeneratedMediaStore(filesDir, repository, mockk(relaxed = true)),
        )
        val result = coordinator.enqueue(
            ImageGenerationRequest(
                    source = ImageGenerationSource.Tool(net.weero.measix.pilot.data.enterprise.RealmAccess.Personal, available(provider)),
                prompt = "make it blue",
                size = "auto",
                mediaKind = GeneratedMediaKind.EDIT,
                editImages = listOf("/tmp/ref.png"),
            )
        )
        val success = result as ImageGenerationOutcome.Success
        assertEquals(33L, success.media.single().mediaId)
        assertTrue(success.media.single().canonicalFile.exists())
        assertTrue(success.media.single().canonicalFile.name.endsWith(".png"))
        assertFalse(success.media.single().canonicalFile.name.contains("GPT"))
        coVerify(exactly = 1) { provider.editImage(any(), any()) }
        coVerify(exactly = 0) { provider.generateImage(any(), any()) }
        filesDir.deleteRecursively()
    }
}
