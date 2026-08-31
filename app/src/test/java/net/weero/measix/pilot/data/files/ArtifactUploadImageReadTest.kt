package net.weero.measix.pilot.data.files

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.weero.measix.pilot.data.ai.attachments.AttachmentResolveResult
import net.weero.measix.pilot.data.ai.attachments.AttachmentResolver
import net.weero.measix.pilot.data.datastore.EffectiveSettingsSnapshot
import net.weero.measix.pilot.data.datastore.ManagedConfigurationState
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsAccessIndex
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.db.RoomDatabaseTransactionRunner
import net.weero.measix.pilot.data.db.entity.ArtifactEntity
import net.weero.measix.pilot.data.db.entity.ArtifactOrigin
import net.weero.measix.pilot.data.db.entity.ArtifactState
import net.weero.measix.pilot.data.imggen.GeneratedMediaStore
import net.weero.measix.pilot.data.imggen.TINY_PNG
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtifactUploadImageReadTest {
    private lateinit var root: File
    private lateinit var database: AppDatabase
    private lateinit var store: ArtifactStore
    private lateinit var payloadStore: ArtifactPayloadStore

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        root = Files.createTempDirectory("artifact-image-read-").toFile()
        val context = object : ContextWrapper(application) {
            override fun getFilesDir(): File = root
        }
        database = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java).build()
        val settings = MutableStateFlow(
            EffectiveSettingsSnapshot(Settings(), SettingsAccessIndex(), 0L, ManagedConfigurationState.ABSENT),
        )
        val settingsStore = mockk<SettingsStore>()
        every { settingsStore.effectiveSettings } returns settings
        coEvery { settingsStore.updateLocal(any()) } coAnswers {
            firstArg<(Settings) -> Settings>()(settings.value.settings).also {
                settings.value = settings.value.copy(settings = it)
            }
        }
        payloadStore = spyk(ArtifactPayloadStore(context))
        store = ArtifactStore(
            payloadStore, database.artifactDao(), database.artifactReferenceDao(),
            database.systemMetaDao(), database.conversationDao(), database.messageNodeDao(),
            ArtifactSettingsCoordinator(settingsStore), RoomDatabaseTransactionRunner(database),
        )
    }

    @After
    fun tearDown() {
        database.close()
        check(root.deleteRecursively())
    }

    @Test
    fun `registered images need no conversation or workspace and preserve old names and duplicate order`() = runBlocking {
        val names = listOf("abc123.png", "809278de-6677-4bc1-9249-d94c85b0930c.png")
        val entities = names.map { register(it) }
        val paths = listOf("/upload/${names[1]}", "/upload/${names[0]}", "/upload/${names[1]}")

        val result = read(paths) as ArtifactImageReadResult.Success

        assertEquals(paths, result.images.map { it.reference.toolPath() })
        result.images.forEach { assertArrayEquals(TINY_PNG, it.bytes) }
        assertEquals(names.toSet(), File(root, "upload").list()!!.toSet())
        entities.forEach { assertFalse(database.artifactReferenceDao().existsByArtifactId(it.id)) }
        val resolver = AttachmentResolver(store)
        val firstConversationRequest = resolver.readImages(paths) as AttachmentResolveResult.Success
        val anotherConversationRequest = resolver.readImages(paths) as AttachmentResolveResult.Success
        assertEquals(firstConversationRequest.parts.map { it.url }, anotherConversationRequest.parts.map { it.url })
        assertTrue(firstConversationRequest.parts.all { it.url.startsWith("data:image/jpeg;base64,") })
    }

    @Test
    fun `unregistered missing deleting and unpublished files are not readable`() = runBlocking {
        File(root, "upload/orphan.png").apply { parentFile!!.mkdirs(); writeBytes(TINY_PNG) }
        register("missing.png").also { File(root, it.relativePath).delete() }
        register("deleting.png", state = ArtifactState.DELETING)
        val unpublished = store.createFromBytes(TINY_PNG, "new.png", "image/png", origin = ArtifactOrigin.USER)
        val paths = listOf("/upload/orphan.png", "/upload/missing.png", "/upload/deleting.png", unpublished.localRef.toolPath()!!)
        paths.forEach { assertFailure(ArtifactImageReadResult.Reason.NOT_FOUND, read(listOf(it))) }
        assertTrue(store.discardUnpublished(unpublished) is ArtifactDeleteResult.Completed)
    }

    @Test
    fun `path grammar and canonical root reject traversal and other file namespaces`() = runBlocking {
        register("image.png")
        val paths = listOf(
            "/upload/../image.png", "/upload/%2e%2e/image.png", "/upload/sub/image.png",
            "/images/image.png", "/workspace/image.png", "file:///upload/image.png",
            "attachment:11111111-1111-1111-1111-111111111111", "/upload/..\\image.png",
        )
        paths.forEach { assertFailure(ArtifactImageReadResult.Reason.NOT_FOUND, read(listOf(it))) }
    }

    @Test
    fun `content signature declared nonimage and oversized file fail before consumer receives images`() = runBlocking {
        register("invalid.png", bytes = "not an image".toByteArray())
        register("document.png", mime = "application/pdf", bytes = "%PDF-1.7".toByteArray())
        register("large.png")
        RandomAccessFile(File(root, "upload/large.png"), "rw").use {
            it.setLength(GeneratedMediaStore.MAX_IMAGE_BYTES.toLong() + 1)
        }
        assertFailure(ArtifactImageReadResult.Reason.UNSUPPORTED_TYPE, read(listOf("/upload/invalid.png")))
        assertFailure(ArtifactImageReadResult.Reason.UNSUPPORTED_TYPE, read(listOf("/upload/document.png")))
        assertFailure(ArtifactImageReadResult.Reason.TOO_LARGE, read(listOf("/upload/large.png")))
    }

    @Test
    fun `later invalid image fails whole batch and releases all pins`() = runBlocking {
        val first = register("first.png")
        val invalid = register("invalid.png", bytes = byteArrayOf(1, 2, 3))

        assertFailure(ArtifactImageReadResult.Reason.UNSUPPORTED_TYPE, read(listOf("/upload/first.png", "/upload/invalid.png")))

        assertTrue(store.deleteUserRequested(first.id) is ArtifactDeleteResult.Completed)
        assertTrue(store.deleteUserRequested(invalid.id) is ArtifactDeleteResult.Completed)
    }

    @Test
    fun `later missing metadata does not retain earlier valid image`() = runBlocking {
        val first = register("first.png")
        assertFailure(ArtifactImageReadResult.Reason.NOT_FOUND, read(listOf("/upload/first.png", "/upload/missing.png")))
        assertTrue(store.deleteUserRequested(first.id) is ArtifactDeleteResult.Completed)
    }

    @Test
    fun `overlapping readers share retention and cancellation releases only owning reader`() = runBlocking {
        withTimeout(10_000) {
            val entity = register("shared.png")
            val enteredFirst = CompletableDeferred<Unit>()
            val enteredSecond = CompletableDeferred<Unit>()
            val first = async(Dispatchers.Default) {
                store.withUploadImages(listOf("/upload/shared.png", "/upload/shared.png")) {
                    assertTrue(it is ArtifactImageReadResult.Success)
                    enteredFirst.complete(Unit)
                    awaitCancellation()
                }
            }
            val second = async(Dispatchers.Default) {
                store.withUploadImages(listOf("/upload/shared.png")) {
                    assertTrue(it is ArtifactImageReadResult.Success)
                    enteredSecond.complete(Unit)
                    awaitCancellation()
                }
            }
            try {
                enteredFirst.await()
                enteredSecond.await()
                assertInProgress(store.deleteUserRequested(entity.id))
                first.cancelAndJoin()
                assertInProgress(store.deleteUserRequested(entity.id))
                assertTrue(File(root, entity.relativePath).isFile)
            } finally {
                first.cancelAndJoin()
                second.cancelAndJoin()
            }
            assertTrue(store.deleteUserRequested(entity.id) is ArtifactDeleteResult.Completed)
            assertFalse(File(root, entity.relativePath).exists())
        }
    }

    @Test
    fun `consumer commit returns ownership before cancellation is observed`() = runBlocking {
        val entity = register("committed.png")
        val delivered = CompletableDeferred<String>()
        val consumer = launch(Dispatchers.Default) {
            val result = store.withUploadImages(listOf("/upload/committed.png")) {
                assertTrue(it is ArtifactImageReadResult.Success)
                currentCoroutineContext().cancel()
                "committed-child"
            }
            delivered.complete(result)
        }
        consumer.join()
        assertTrue("A committed result must reach its owner without a dispatcher return boundary", delivered.isCompleted)
        assertEquals("committed-child", delivered.await())
        assertTrue(store.deleteUserRequested(entity.id) is ArtifactDeleteResult.Completed)
    }

    @Test
    fun `consumer failure propagates and releases retention`() = runBlocking {
        val entity = register("failure.png")
        val expected = IllegalStateException("consumer failed")
        try {
            store.withUploadImages(listOf("/upload/failure.png")) {
                assertTrue(it is ArtifactImageReadResult.Success)
                throw expected
            }
            throw AssertionError("expected consumer failure")
        } catch (actual: IllegalStateException) {
            assertTrue(actual === expected)
        }
        assertTrue(store.deleteUserRequested(entity.id) is ArtifactDeleteResult.Completed)
    }

    @Test
    fun `inspection snapshot survives source deletion without adding a durable file`() = runBlocking {
        val entity = register("source.png")
        val snapshot = AttachmentResolver(store).readImages(listOf("/upload/source.png")) as AttachmentResolveResult.Success

        assertTrue(store.deleteUserRequested(entity.id) is ArtifactDeleteResult.Completed)
        val image = snapshot.parts.single()
        val bytes = kotlin.io.encoding.Base64.decode(image.url.substringAfter("base64,"))
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
        assertEquals(1, bitmap.width)
        assertEquals(1, bitmap.height)
        bitmap.recycle()
        assertTrue(store.list().isEmpty())
        assertTrue(File(root, "upload").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `payload read failure is explicit and releases pin`() = runBlocking {
        val entity = register("unreadable.png")
        coEvery { payloadStore.readBytes(entity.relativePath, GeneratedMediaStore.MAX_IMAGE_BYTES.toLong()) } throws
            java.io.IOException("read unavailable")

        assertFailure(ArtifactImageReadResult.Reason.READ_FAILED, read(listOf("/upload/unreadable.png")))
        assertTrue(store.deleteUserRequested(entity.id) is ArtifactDeleteResult.Completed)
    }

    @Test
    fun `cancellation while payload read is suspended releases acquired pin`() = runBlocking {
        withTimeout(10_000) {
            val entity = register("cancelled.png")
            val readStarted = CompletableDeferred<Unit>()
            coEvery { payloadStore.readBytes(entity.relativePath, GeneratedMediaStore.MAX_IMAGE_BYTES.toLong()) } coAnswers {
                readStarted.complete(Unit)
                awaitCancellation()
            }
            val reader = async(Dispatchers.Default) { read(listOf("/upload/cancelled.png")) }
            try {
                readStarted.await()
                assertInProgress(store.deleteUserRequested(entity.id))
            } finally {
                reader.cancelAndJoin()
            }
            assertTrue(store.deleteUserRequested(entity.id) is ArtifactDeleteResult.Completed)
        }
    }

    private suspend fun read(paths: List<String>): ArtifactImageReadResult = store.withUploadImages(paths) { it }

    private fun assertFailure(expected: ArtifactImageReadResult.Reason, result: ArtifactImageReadResult) {
        assertEquals(ArtifactImageReadResult.Failure(expected), result)
    }

    private fun assertInProgress(result: ArtifactDeleteResult) {
        assertTrue(result is ArtifactDeleteResult.Rejected)
        assertEquals(ArtifactDeleteResult.RejectionReason.IN_PROGRESS, (result as ArtifactDeleteResult.Rejected).reason)
    }

    private suspend fun register(
        name: String,
        state: ArtifactState = ArtifactState.ACTIVE,
        mime: String = "image/png",
        bytes: ByteArray = TINY_PNG,
    ): ArtifactEntity {
        File(root, "upload/$name").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        val entity = ArtifactEntity(
            folder = FileFolders.UPLOAD, relativePath = "upload/$name", displayName = name,
            mimeType = mime, sizeBytes = bytes.size.toLong(), createdAt = 1L, updatedAt = 1L,
            state = state.name, origin = ArtifactOrigin.USER.name,
        )
        return entity.copy(id = database.artifactDao().insert(entity))
    }
}
