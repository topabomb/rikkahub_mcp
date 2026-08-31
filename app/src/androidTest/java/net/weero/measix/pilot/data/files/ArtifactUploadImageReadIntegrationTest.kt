package net.weero.measix.pilot.data.files

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.ai.attachments.AttachmentResolveResult
import net.weero.measix.pilot.data.ai.attachments.AttachmentResolver
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.db.RoomDatabaseTransactionRunner
import net.weero.measix.pilot.data.db.entity.ArtifactEntity
import net.weero.measix.pilot.data.db.entity.ArtifactOrigin
import net.weero.measix.pilot.data.db.entity.ArtifactState
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room and Android file access; no conversation snapshot, workspace or Provider network. */
@RunWith(AndroidJUnit4::class)
class ArtifactUploadImageReadIntegrationTest {
    private lateinit var root: File
    private lateinit var database: AppDatabase
    private lateinit var appScope: AppScope
    private lateinit var store: ArtifactStore
    private val png: ByteArray = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).let { bitmap ->
        try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        root = Files.createTempDirectory(application.cacheDir.toPath(), "upload-read-test-").toFile()
        val payloadContext = object : ContextWrapper(application) {
            override fun getFilesDir(): File = root
        }
        database = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java).build()
        appScope = AppScope()
        store = ArtifactStore(
            payloadStore = ArtifactPayloadStore(payloadContext),
            artifactDAO = database.artifactDao(),
            artifactReferenceDAO = database.artifactReferenceDao(),
            systemMetaDAO = database.systemMetaDao(),
            conversationDAO = database.conversationDao(),
            messageNodeDAO = database.messageNodeDao(),
            settingsCoordinator = ArtifactSettingsCoordinator(SettingsStore(application, appScope)),
            transactionRunner = RoomDatabaseTransactionRunner(database),
        )
    }

    @After
    fun tearDown() {
        appScope.cancel()
        database.close()
        check(root.deleteRecursively())
    }

    @Test
    fun oldLongPathWorksWithoutConversationAndInspectionOwnsSnapshotAfterDeletion() = runBlocking {
        val entity = register("809278de-6677-4bc1-9249-d94c85b0930c.png")
        val path = "/${entity.relativePath}"
        val resolver = AttachmentResolver(store)

        val result = resolver.readImages(listOf(path, path)) as AttachmentResolveResult.Success

        assertEquals(2, result.parts.size)
        assertEquals(result.parts[0].url, result.parts[1].url)
        assertFalse(database.artifactReferenceDao().existsByArtifactId(entity.id))
        assertEquals(listOf(entity.relativePath), store.list().map { it.relativePath })
        assertTrue(store.deleteUserRequested(entity.id) is ArtifactDeleteResult.Completed)
        result.parts.forEach { part ->
            assertTrue(part.url.startsWith("data:image/jpeg;base64,"))
            val bytes = java.util.Base64.getDecoder().decode(part.url.substringAfter("base64,"))
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
            assertEquals(1, bitmap.width)
            assertEquals(1, bitmap.height)
            bitmap.recycle()
        }
        assertTrue(store.list().isEmpty())
        assertTrue(File(root, "upload").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun readScopeBlocksDeleteAndCancellationReleasesPin() = runBlocking {
        withTimeout(10_000) {
            val entity = register("active.png")
            val entered = CompletableDeferred<Unit>()
            val reader = async(Dispatchers.Default) {
                AttachmentResolver(store).withImages(listOf("/upload/active.png")) { result ->
                    val parts = (result as AttachmentResolveResult.Success).parts
                    assertEquals(store.file(entity).toURI().path, android.net.Uri.parse(parts.single().url).path)
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            try {
                entered.await()
                assertEquals(
                    ArtifactDeleteResult.Rejected(entity.id, ArtifactDeleteResult.RejectionReason.IN_PROGRESS),
                    store.deleteUserRequested(entity.id),
                )
                assertTrue(store.file(entity).isFile)
            } finally {
                reader.cancelAndJoin()
            }
            assertTrue(store.deleteUserRequested(entity.id) is ArtifactDeleteResult.Completed)
            assertFalse(store.file(entity).exists())
            assertEquals(
                AttachmentResolveResult.Failure("attachment_not_found"),
                AttachmentResolver(store).readImages(listOf("/upload/active.png")),
            )
        }
    }

    @Test
    fun failedBatchReleasesPinsAndUnpublishedFileRemainsProducerOwned() = runBlocking {
        val first = register("valid.png")
        val second = register("invalid.png", bytes = byteArrayOf(7, 8, 9))
        val resolver = AttachmentResolver(store)
        assertEquals(
            AttachmentResolveResult.Failure("unsupported_attachment_type"),
            resolver.readImages(listOf("/upload/valid.png", "/upload/invalid.png")),
        )
        assertTrue(store.deleteUserRequested(first.id) is ArtifactDeleteResult.Completed)
        assertTrue(store.deleteUserRequested(second.id) is ArtifactDeleteResult.Completed)
        val owned = store.createFromBytes(png, "unpublished.png", "image/png", origin = ArtifactOrigin.USER)
        assertEquals(
            AttachmentResolveResult.Failure("attachment_not_found"),
            resolver.readImages(listOf(owned.localRef.toolPath()!!)),
        )
        assertTrue(store.discardUnpublished(owned) is ArtifactDeleteResult.Completed)
    }

    @Test
    fun registeredSymlinkCannotEscapeUploadRoot() = runBlocking {
        val outside = File(root, "outside.png").apply { writeBytes(png) }
        val link = File(root, "upload/link.png")
        link.parentFile!!.mkdirs()
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        insertRow("link.png", png.size.toLong())
        val result = store.withUploadImages(listOf("/upload/link.png")) { it }
        assertEquals(ArtifactImageReadResult.Failure(ArtifactImageReadResult.Reason.NOT_FOUND), result)
        assertArrayEquals(png, outside.readBytes())
        assertTrue(link.delete())
    }

    private suspend fun register(name: String, bytes: ByteArray = png): ArtifactEntity {
        File(root, "upload/$name").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        return insertRow(name, bytes.size.toLong())
    }

    private suspend fun insertRow(name: String, sizeBytes: Long): ArtifactEntity {
        val entity = ArtifactEntity(
            folder = FileFolders.UPLOAD, relativePath = "upload/$name", displayName = name,
            mimeType = "image/png", sizeBytes = sizeBytes, createdAt = 1L, updatedAt = 1L,
            state = ArtifactState.ACTIVE.name, origin = ArtifactOrigin.USER.name,
        )
        return entity.copy(id = database.artifactDao().insert(entity))
    }
}
