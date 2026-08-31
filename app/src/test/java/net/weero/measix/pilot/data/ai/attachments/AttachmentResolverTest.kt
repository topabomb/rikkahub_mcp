package net.weero.measix.pilot.data.ai.attachments

import android.net.Uri
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import me.rerere.ai.util.EncodedImage
import me.rerere.ai.util.encodeImageBytes
import net.weero.measix.pilot.data.files.ArtifactImageContent
import net.weero.measix.pilot.data.files.ArtifactImageReadResult
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.LocalArtifactRef
import net.weero.measix.pilot.data.imggen.TINY_PNG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Before
import org.junit.After
import kotlin.io.encoding.Base64

class AttachmentResolverTest {
    private val store = mockk<ArtifactStore>()
    private val resolver = AttachmentResolver(store)

    @Before
    fun setUpEncoder() {
        mockkStatic(::encodeImageBytes)
        coEvery { encodeImageBytes(any(), any()) } answers {
            EncodedImage("data:image/jpeg;base64," + Base64.encode(firstArg<ByteArray>()), "image/jpeg")
        }
    }

    @After
    fun tearDownEncoder() = unmockkStatic(::encodeImageBytes)

    @Test
    fun `only upload paths and one to four images are inspection inputs`() = runTest {
        val invalid = listOf(
            emptyList(),
            List(5) { "/upload/image.png" },
            listOf("attachment:11111111-1111-1111-1111-111111111111"),
            listOf("https://example.test/image.png"),
            listOf("file:///upload/image.png"),
            listOf("/workspace/image.png"),
            listOf("/upload/../secret.png"),
            listOf("/upload/%2e%2e.png"),
            listOf("image.png"),
            listOf(""),
        )
        for (paths in invalid) {
            assertEquals(
                AttachmentResolveResult.Failure(AttachmentFailureReasons.INVALID_ATTACHMENTS),
                resolver.readImages(paths),
            )
        }
        coVerify(exactly = 0) { store.withUploadImages<AttachmentResolveResult>(any(), any()) }
    }

    @Test
    fun `inspection preserves duplicate order as memory snapshots without file identity`() = runTest {
        val paths = listOf("/upload/abc123.png", "/upload/809278de-6677-4bc1-9249-d94c85b0930c.png", "/upload/abc123.png")
        val first = image("upload/abc123.png", TINY_PNG)
        val second = image("upload/809278de-6677-4bc1-9249-d94c85b0930c.png", TINY_PNG + byteArrayOf(9))
        coEvery { store.withUploadImages<AttachmentResolveResult>(paths, any()) } coAnswers {
            secondArg<suspend (ArtifactImageReadResult) -> AttachmentResolveResult>()(
                ArtifactImageReadResult.Success(listOf(first, second, first)),
            )
        }

        val result = resolver.readImages(paths) as AttachmentResolveResult.Success

        assertEquals(
            listOf(first, second, first).map { "data:image/jpeg;base64," + Base64.encode(it.bytes) },
            result.parts.map { it.url },
        )
        assertTrue(result.parts.all { it.metadata == null })
        coVerify(exactly = 1) { store.withUploadImages<AttachmentResolveResult>(paths, any()) }
    }

    @Test
    fun `delegation consumes unique local files inside owner scope and stamps internal identity`() = runTest {
        val path = "/upload/abc123.png"
        var insideOwner = false
        coEvery { store.withUploadImages<Unit>(listOf(path), any()) } coAnswers {
            insideOwner = true
            try {
                secondArg<suspend (ArtifactImageReadResult) -> Unit>()(
                    ArtifactImageReadResult.Success(listOf(image("upload/abc123.png", TINY_PNG))),
                )
            } finally {
                insideOwner = false
            }
        }

        resolver.withImages(listOf(path, path)) { result ->
            assertTrue(insideOwner)
            val parts = (result as AttachmentResolveResult.Success).parts
            assertEquals(listOf("file:///managed/upload/abc123.png"), parts.map { it.url })
            assertNotNull(AttachmentRefs.getStableRef(parts.single()))
        }
        assertTrue(!insideOwner)
    }

    @Test
    fun `empty delegation needs no resource access`() = runTest {
        resolver.withImages(emptyList()) { result ->
            assertEquals(AttachmentResolveResult.Success(emptyList()), result)
        }
        coVerify(exactly = 0) { store.withUploadImages<Unit>(any(), any()) }
    }

    @Test
    fun `owner failure reasons map without retries or substitute images`() = runTest {
        val mapping = mapOf(
            ArtifactImageReadResult.Reason.NOT_FOUND to AttachmentFailureReasons.ATTACHMENT_NOT_FOUND,
            ArtifactImageReadResult.Reason.TOO_LARGE to AttachmentFailureReasons.ATTACHMENT_TOO_LARGE,
            ArtifactImageReadResult.Reason.UNSUPPORTED_TYPE to AttachmentFailureReasons.UNSUPPORTED_ATTACHMENT_TYPE,
            ArtifactImageReadResult.Reason.READ_FAILED to AttachmentFailureReasons.ATTACHMENT_READ_FAILED,
        )
        for ((reason, expected) in mapping) {
            coEvery { store.withUploadImages<AttachmentResolveResult>(any(), any()) } coAnswers {
                secondArg<suspend (ArtifactImageReadResult) -> AttachmentResolveResult>()(
                    ArtifactImageReadResult.Failure(reason),
                )
            }
            assertEquals(AttachmentResolveResult.Failure(expected), resolver.readImages(listOf("/upload/abc123.png")))
        }
    }

    @Test
    fun `owner cancellation propagates unchanged`() = runTest {
        val cancelled = CancellationException("cancel read")
        coEvery { store.withUploadImages<AttachmentResolveResult>(any(), any()) } throws cancelled
        try {
            resolver.readImages(listOf("/upload/abc123.png"))
            throw AssertionError("expected cancellation")
        } catch (actual: CancellationException) {
            assertTrue(actual === cancelled)
        }
    }

    @Test
    fun `encoder cancellation is not converted to invalid image`() = runTest {
        val cancelled = CancellationException("cancel encoding")
        coEvery { store.withUploadImages<AttachmentResolveResult>(any(), any()) } coAnswers {
            secondArg<suspend (ArtifactImageReadResult) -> AttachmentResolveResult>()(
                ArtifactImageReadResult.Success(listOf(image("upload/abc123.png", TINY_PNG))),
            )
        }
        coEvery { encodeImageBytes(any(), any()) } throws cancelled
        try {
            resolver.readImages(listOf("/upload/abc123.png"))
            throw AssertionError("expected cancellation")
        } catch (actual: CancellationException) {
            assertTrue(actual === cancelled)
        }
    }

    private fun image(path: String, bytes: ByteArray): ArtifactImageContent {
        val uri = mockk<Uri>()
        every { uri.toString() } returns "file:///managed/$path"
        return ArtifactImageContent(LocalArtifactRef(relativePath = path, mimeType = "image/png"), uri, bytes, "image/png")
    }
}
