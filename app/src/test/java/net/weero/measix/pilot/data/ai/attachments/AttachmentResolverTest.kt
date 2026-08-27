package net.weero.measix.pilot.data.ai.attachments

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.db.entity.ArtifactEntity
import net.weero.measix.pilot.data.db.entity.ArtifactOrigin
import net.weero.measix.pilot.data.db.entity.ArtifactState
import net.weero.measix.pilot.data.files.ArtifactDeleteResult
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.LocalArtifactRef
import net.weero.measix.pilot.data.files.OwnedArtifact
import net.weero.measix.pilot.data.files.ToolArtifactRewriter
import net.weero.measix.pilot.data.imggen.TINY_PNG
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallArtifact
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallState
import net.weero.measix.pilot.data.ai.subassistant.mergeSubAssistantCallMetadata
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class AttachmentResolverTest {
    @Test
    fun `inspection accepts only stable attachment handles`() = runTest {
        val env = Env()

        val result = env.resolver.resolveImages(emptyList(), listOf("/upload/file.png"))

        assertEquals(AttachmentFailureReasons.INVALID_ATTACHMENTS, (result as AttachmentResolveResult.Failure).reason)
        coVerify(exactly = 0) { env.fetcher.fetch(any()) }
        env.close()
    }

    @Test
    fun `registered upload path still requires a master message reference`() = runTest {
        val env = Env()
        val file = env.file("upload/source.png")
        coEvery { env.store.resolveToolPath("/upload/source.png") } returns file
        coEvery { env.store.getByRelativePath("upload/source.png") } returns env.entity(file)

        val absent = env.resolver.resolve(emptyList(), listOf("/upload/source.png"))
        assertEquals(AttachmentFailureReasons.ATTACHMENT_NOT_FOUND, (absent as AttachmentResolveResult.Failure).reason)

        val source = AttachmentRefs.ensureAttachmentRef(UIMessagePart.Image(AttachmentRefs.fileToFileUrl(file)))
        val present = env.resolver.resolve(
            listOf(UIMessage(role = MessageRole.USER, parts = listOf(source))),
            listOf("/upload/source.png"),
        )
        assertTrue(present is AttachmentResolveResult.Success)
        env.close()
    }

    @Test
    fun `sub-assistant deliverable handle resolves direct image with same manifest file`() = runTest {
        val env = Env()
        val ref = AttachmentRefs.format(Uuid.random())
        val managed = LocalArtifactRef(relativePath = "upload/generated.png", mimeType = "image/png")
        val file = env.file(managed.relativePath)
        coEvery { env.store.materialize(managed) } returns managed
        coEvery { env.store.getByRelativePath(managed.relativePath) } returns env.entity(file)
        every { env.store.file(managed) } returns file
        val tool = UIMessagePart.Tool(
            toolCallId = "assistant-call",
            toolName = "assistant_call",
            input = "{}",
            output = listOf(
                AttachmentRefs.withMetadata(
                    UIMessagePart.Image(AttachmentRefs.fileToFileUrl(file)),
                    AttachmentRefs.mergeMetadata(
                        null,
                        mapOf(AttachmentRefs.METADATA_KEY to kotlinx.serialization.json.JsonPrimitive(ref)),
                    ),
                ) as UIMessagePart.Image,
            ),
        ).mergeSubAssistantCallMetadata(
            JsonInstant,
            SubAssistantCallMetadata(
                runId = Uuid.random().toString(),
                targetAssistantId = Uuid.random().toString(),
                targetNameSnapshot = "Image assistant",
                state = SubAssistantCallState.COMPLETED,
                artifacts = listOf(
                    SubAssistantCallArtifact(
                        ref = ref,
                        type = "image",
                        mime = "image/png",
                        artifact = managed,
                    ),
                ),
            ),
        )
        val messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool)))

        val result = env.resolver.resolveImages(messages, listOf(ref)) as AttachmentResolveResult.Success

        assertEquals(1, result.parts.size)
        assertEquals(ref, AttachmentRefs.getRef(result.parts.single()))
        assertEquals(AttachmentRefs.fileToFileUrl(file), result.parts.single().url)
        env.close()
    }

    @Test
    fun `sub-assistant deliverable handle uses materialized manifest when output is absent`() = runTest {
        val env = Env()
        val ref = AttachmentRefs.format(Uuid.random())
        val managed = LocalArtifactRef(relativePath = "upload/generated-only.png", mimeType = "image/png")
        val file = env.file(managed.relativePath)
        coEvery { env.store.materialize(managed) } returns managed
        every { env.store.file(managed) } returns file
        val tool = UIMessagePart.Tool(
            toolCallId = "assistant-call",
            toolName = "assistant_call",
            input = "{}",
        ).mergeSubAssistantCallMetadata(
            JsonInstant,
            SubAssistantCallMetadata(
                runId = Uuid.random().toString(),
                targetAssistantId = Uuid.random().toString(),
                targetNameSnapshot = "Image assistant",
                state = SubAssistantCallState.COMPLETED,
                artifacts = listOf(
                    SubAssistantCallArtifact(ref, "image", "image/png", managed),
                ),
            ),
        )

        val result = env.resolver.resolveImages(
            listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool))),
            listOf(ref),
        ) as AttachmentResolveResult.Success

        assertEquals(1, result.parts.size)
        assertEquals(ref, AttachmentRefs.getRef(result.parts.single()))
        coVerify(exactly = 1) { env.store.materialize(managed) }
        env.close()
    }

    @Test
    fun `managed artifact handle fails closed when metadata is stale`() = runTest {
        val env = Env()
        val ref = AttachmentRefs.format(Uuid.random())
        val managed = LocalArtifactRef(relativePath = "upload/stale.png", mimeType = "image/png")
        val file = env.file(managed.relativePath)
        every { env.store.file(managed) } returns file
        coEvery { env.store.materialize(managed) } returns null
        val tool = UIMessagePart.Tool(
            toolCallId = "assistant-call",
            toolName = "assistant_call",
            input = "{}",
        ).mergeSubAssistantCallMetadata(
            JsonInstant,
            SubAssistantCallMetadata(
                runId = Uuid.random().toString(),
                targetAssistantId = Uuid.random().toString(),
                targetNameSnapshot = "Image assistant",
                state = SubAssistantCallState.COMPLETED,
                artifacts = listOf(SubAssistantCallArtifact(ref, "image", "image/png", managed)),
            ),
        )

        val result = env.resolver.resolveImages(
            listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool))),
            listOf(ref),
        )

        assertEquals(AttachmentFailureReasons.ATTACHMENT_NOT_FOUND, (result as AttachmentResolveResult.Failure).reason)
        env.close()
    }

    @Test
    fun `direct file handle fails closed when artifact metadata is missing`() = runTest {
        val env = Env()
        val ref = AttachmentRefs.format(Uuid.random())
        val file = env.file("upload/stale-direct.png")
        coEvery { env.store.getByRelativePath("upload/stale-direct.png") } returns null
        val image = AttachmentRefs.withMetadata(
            UIMessagePart.Image(AttachmentRefs.fileToFileUrl(file)),
            AttachmentRefs.mergeMetadata(
                null,
                mapOf(AttachmentRefs.METADATA_KEY to kotlinx.serialization.json.JsonPrimitive(ref)),
            ),
        ) as UIMessagePart.Image

        val result = env.resolver.resolveImages(
            listOf(UIMessage(role = MessageRole.USER, parts = listOf(image))),
            listOf(ref),
        )

        assertEquals(AttachmentFailureReasons.ATTACHMENT_NOT_FOUND, (result as AttachmentResolveResult.Failure).reason)
        env.close()
    }

    @Test
    fun `malformed sub-assistant manifest ref cannot authorize upload path`() = runTest {
        val env = Env()
        val file = env.file("upload/malformed-manifest.png")
        coEvery { env.store.resolveToolPath("/upload/malformed-manifest.png") } returns file
        coEvery { env.store.getByRelativePath("upload/malformed-manifest.png") } returns env.entity(file)
        val tool = UIMessagePart.Tool(
            toolCallId = "assistant-call",
            toolName = "assistant_call",
            input = "{}",
        ).mergeSubAssistantCallMetadata(
            JsonInstant,
            SubAssistantCallMetadata(
                runId = Uuid.random().toString(),
                targetAssistantId = Uuid.random().toString(),
                targetNameSnapshot = "Image assistant",
                state = SubAssistantCallState.COMPLETED,
                artifacts = listOf(
                    SubAssistantCallArtifact("not-an-attachment", "image", "image/png", LocalArtifactRef(
                        relativePath = file.relativeTo(env.filesDir).path.replace('\\', '/'),
                        mimeType = "image/png",
                    )),
                ),
            ),
        )

        val result = env.resolver.resolve(
            listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool))),
            listOf("/upload/malformed-manifest.png"),
        )

        assertEquals(AttachmentFailureReasons.ATTACHMENT_NOT_FOUND, (result as AttachmentResolveResult.Failure).reason)
        env.close()
    }

    @Test
    fun `remote image returns an explicit unpublished ownership token`() = runTest {
        val env = Env()
        val remote = "https://example.test/image.png"
        val owned = env.owned("upload/remote.png")
        coEvery { env.fetcher.fetch(remote) } returns RemoteMediaFetchResult.Success(TINY_PNG, "image/png", "remote.png")
        coEvery {
            env.store.createFromBytes(TINY_PNG, "remote.png", "image/png", "upload", ArtifactOrigin.SYSTEM)
        } returns owned
        every { env.store.file(owned.entity) } returns env.file(owned.entity.relativePath)
        coEvery { env.store.getByRelativePath(owned.entity.relativePath) } returns owned.entity

        val result = env.resolver.resolve(emptyList(), listOf(remote)) as AttachmentResolveResult.Success

        assertEquals(listOf(owned), result.createdArtifacts)
        assertEquals(1, result.parts.size)
        env.close()
    }

    @Test
    fun `batch failure compensates every artifact created earlier in the batch`() = runTest {
        val env = Env()
        val first = "https://example.test/first.png"
        val second = "https://example.test/missing.png"
        val owned = env.owned("upload/first.png")
        coEvery { env.fetcher.fetch(first) } returns RemoteMediaFetchResult.Success(TINY_PNG, "image/png", "first.png")
        coEvery { env.fetcher.fetch(second) } returns RemoteMediaFetchResult.Failure(
            AttachmentFailureReasons.ATTACHMENT_FETCH_FAILED
        )
        coEvery {
            env.store.createFromBytes(TINY_PNG, "first.png", "image/png", "upload", ArtifactOrigin.SYSTEM)
        } returns owned
        every { env.store.file(owned.entity) } returns env.file(owned.entity.relativePath)
        coEvery { env.store.getByRelativePath(owned.entity.relativePath) } returns owned.entity
        coEvery { env.store.discardUnpublished(owned) } returns ArtifactDeleteResult.Completed(owned.entity.id)

        val result = env.resolver.resolve(emptyList(), listOf(first, second))

        assertTrue(result is AttachmentResolveResult.Failure)
        coVerify(exactly = 1) { env.store.discardUnpublished(owned) }
        env.close()
    }

    @Test
    fun `domain failure remains visible when compensation fails`() = runTest {
        val env = Env()
        val first = "https://example.test/first.png"
        val second = "https://example.test/missing.png"
        val owned = env.owned("upload/first.png")
        val cleanupFailure = IllegalStateException("cleanup failed")
        coEvery { env.fetcher.fetch(first) } returns RemoteMediaFetchResult.Success(TINY_PNG, "image/png", "first.png")
        coEvery {
            env.store.createFromBytes(TINY_PNG, "first.png", "image/png", "upload", ArtifactOrigin.SYSTEM)
        } returns owned
        every { env.store.file(owned.entity) } returns env.file(owned.entity.relativePath)
        coEvery { env.store.getByRelativePath(owned.entity.relativePath) } returns owned.entity
        coEvery { env.store.discardUnpublished(owned) } throws cleanupFailure
        coEvery { env.fetcher.fetch(second) } returns RemoteMediaFetchResult.Failure(
            AttachmentFailureReasons.ATTACHMENT_FETCH_FAILED,
        )

        val result = env.resolver.resolve(emptyList(), listOf(first, second))

        assertEquals(AttachmentFailureReasons.ATTACHMENT_FETCH_FAILED, (result as AttachmentResolveResult.Failure).reason)
        env.close()
    }

    @Test
    fun `batch cancellation compensates created artifacts and rethrows`() = runTest {
        val env = Env()
        val first = "https://example.test/first.png"
        val second = "https://example.test/cancelled.png"
        val owned = env.owned("upload/first.png")
        val cancelled = CancellationException("stop")
        coEvery { env.fetcher.fetch(first) } returns RemoteMediaFetchResult.Success(TINY_PNG, "image/png", "first.png")
        coEvery {
            env.store.createFromBytes(TINY_PNG, "first.png", "image/png", "upload", ArtifactOrigin.SYSTEM)
        } returns owned
        every { env.store.file(owned.entity) } returns env.file(owned.entity.relativePath)
        coEvery { env.store.getByRelativePath(owned.entity.relativePath) } returns owned.entity
        coEvery { env.store.discardUnpublished(owned) } returns ArtifactDeleteResult.Completed(owned.entity.id)
        coEvery { env.fetcher.fetch(second) } throws cancelled

        try {
            env.resolver.resolve(emptyList(), listOf(first, second))
            throw AssertionError("expected cancellation")
        } catch (actual: CancellationException) {
            assertEquals(cancelled, actual)
        }
        coVerify(exactly = 1) { env.store.discardUnpublished(owned) }
        env.close()
    }

    @Test
    fun `batch cancellation remains the primary error when compensation fails`() = runTest {
        val env = Env()
        val first = "https://example.test/first.png"
        val second = "https://example.test/cancelled.png"
        val owned = env.owned("upload/first.png")
        val cancelled = CancellationException("stop")
        val cleanupFailure = IllegalStateException("cleanup failed")
        coEvery { env.fetcher.fetch(first) } returns RemoteMediaFetchResult.Success(TINY_PNG, "image/png", "first.png")
        coEvery {
            env.store.createFromBytes(TINY_PNG, "first.png", "image/png", "upload", ArtifactOrigin.SYSTEM)
        } returns owned
        every { env.store.file(owned.entity) } returns env.file(owned.entity.relativePath)
        coEvery { env.store.getByRelativePath(owned.entity.relativePath) } returns owned.entity
        coEvery { env.store.discardUnpublished(owned) } throws cleanupFailure
        coEvery { env.fetcher.fetch(second) } throws cancelled

        val actual = try {
            env.resolver.resolve(emptyList(), listOf(first, second))
            throw AssertionError("expected cancellation")
        } catch (error: CancellationException) {
            error
        }
        assertEquals(cancelled, actual)
        assertEquals(listOf(cleanupFailure), actual.suppressed.toList())
        env.close()
    }

    private class Env {
        val filesDir = createTempDirectory("attachment-resolver-v1c").toFile()
        val context = mockk<Context>()
        val store = mockk<ArtifactStore>(relaxed = true)
        val fetcher = mockk<SafeRemoteMediaFetcher>()
        val rewriter = ToolArtifactRewriter(filesDir, store)
        val resolver: AttachmentResolver

        init {
            every { context.filesDir } returns filesDir
            resolver = AttachmentResolver(context, store, fetcher, rewriter)
        }

        fun file(relativePath: String): File = File(filesDir, relativePath).apply {
            parentFile?.mkdirs()
            if (!exists()) writeBytes(TINY_PNG)
        }

        fun entity(file: File) = ArtifactEntity(
            id = 7,
            folder = "upload",
            relativePath = file.relativeTo(filesDir).path.replace('\\', '/'),
            displayName = file.name,
            mimeType = "image/png",
            sizeBytes = file.length(),
            createdAt = 1,
            updatedAt = 1,
            state = ArtifactState.ACTIVE.name,
            origin = ArtifactOrigin.SYSTEM.name,
        )

        fun owned(relativePath: String): OwnedArtifact {
            val file = file(relativePath)
            val entity = entity(file)
            val uri = mockk<android.net.Uri>()
            every { uri.toString() } returns AttachmentRefs.fileToFileUrl(file)
            return OwnedArtifact(
                entity = entity,
                uri = uri,
                localRef = LocalArtifactRef(relativePath = relativePath, mimeType = "image/png"),
            )
        }

        fun close() {
            filesDir.deleteRecursively()
        }
    }
}
