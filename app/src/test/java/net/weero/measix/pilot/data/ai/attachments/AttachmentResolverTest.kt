package net.weero.measix.pilot.data.ai.attachments

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.db.entity.ArtifactEntity
import net.weero.measix.pilot.data.db.entity.ArtifactOrigin
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallArtifact
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.mergeSubAssistantCallMetadata
import net.weero.measix.pilot.data.files.FilesManager
import net.weero.measix.pilot.data.files.LocalArtifactRef
import net.weero.measix.pilot.data.files.ManagedLocalArtifactStore
import net.weero.measix.pilot.data.files.ToolArtifactRewriter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class AttachmentResolverTest {
    @Test
    fun `attachment ref finds image inside tool output`() = runTest {
        val env = Env()
        val ref = AttachmentRefs.format(Uuid.parse("22222222-2222-2222-2222-222222222222"))
        val image = UIMessagePart.Image(
            url = AttachmentRefs.fileToFileUrl(env.png),
            metadata = buildJsonObject { put(AttachmentRefs.METADATA_KEY, ref) },
        )
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "g1",
                        toolName = "generate_image",
                        input = "{}",
                        output = listOf(image),
                    ),
                ),
            ),
        )
        val result = env.resolver.resolve(messages, listOf(ref))
        val parts = (result as AttachmentResolveResult.Success).parts
        assertEquals(1, parts.size)
        assertEquals(ref, AttachmentRefs.getRef(parts.single()))
        assertTrue(parts.single().url.startsWith("file:"))
        env.cleanup()
    }

    @Test
    fun `file url outside upload and images is not found`() = runTest {
        val env = Env()
        val outside = File(env.filesDir, "secret.png").apply { writeBytes(TINY_PNG) }
        val messages = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Image(url = AttachmentRefs.fileToFileUrl(outside))),
            ),
        )
        val result = env.resolver.resolve(messages, listOf(AttachmentRefs.fileToFileUrl(outside)))
        assertEquals(
            AttachmentFailureReasons.ATTACHMENT_NOT_FOUND,
            (result as AttachmentResolveResult.Failure).reason,
        )
        env.cleanup()
    }

    @Test
    fun `unreferenced file url inside upload is not found`() = runTest {
        val env = Env()
        val orphan = File(env.upload, "orphan.png").apply { writeBytes(TINY_PNG) }
        val result = env.resolver.resolve(emptyList(), listOf(AttachmentRefs.fileToFileUrl(orphan)))
        assertEquals(
            AttachmentFailureReasons.ATTACHMENT_NOT_FOUND,
            (result as AttachmentResolveResult.Failure).reason,
        )
        env.cleanup()
    }

    @Test
    fun `unknown attachment ref is not found`() = runTest {
        val env = Env()
        val result = env.resolver.resolve(
            masterMessages = emptyList(),
            refs = listOf(AttachmentRefs.format(Uuid.random())),
        )
        assertEquals(
            AttachmentFailureReasons.ATTACHMENT_NOT_FOUND,
            (result as AttachmentResolveResult.Failure).reason,
        )
        env.cleanup()
    }

    @Test
    fun `orphan upload path is not found even if registered`() = runTest {
        val env = Env()
        val result = env.resolver.resolve(
            masterMessages = emptyList(),
            refs = listOf("/upload/${env.png.name}"),
        )
        assertEquals(
            AttachmentFailureReasons.ATTACHMENT_NOT_FOUND,
            (result as AttachmentResolveResult.Failure).reason,
        )
        env.cleanup()
    }

    @Test
    fun `upload traversal is not found`() = runTest {
        val env = Env()
        val result = env.resolver.resolve(
            masterMessages = emptyList(),
            refs = listOf("/upload/../secret"),
        )
        assertEquals(
            AttachmentFailureReasons.ATTACHMENT_NOT_FOUND,
            (result as AttachmentResolveResult.Failure).reason,
        )
        env.cleanup()
    }

    @Test
    fun `upload path referenced by master image is resolved`() = runTest {
        val env = Env()
        val ref = AttachmentRefs.format(Uuid.random())
        val messages = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(
                    UIMessagePart.Image(
                        url = AttachmentRefs.fileToFileUrl(env.png),
                        metadata = buildJsonObject { put(AttachmentRefs.METADATA_KEY, ref) },
                    ),
                ),
            ),
        )
        val result = env.resolver.resolve(messages, listOf("/upload/${env.png.name}"))
        val parts = (result as AttachmentResolveResult.Success).parts
        assertEquals(ref, AttachmentRefs.getRef(parts.single()))
        env.cleanup()
    }

    @Test
    fun `https fetch is persisted as local file with a new ref`() = runTest {
        val env = Env()
        val remoteName = "remote.png"
        val persisted = File(env.upload, remoteName).apply { writeBytes(TINY_PNG) }
        io.mockk.coEvery { env.fetcher.fetch("https://cdn.example/a.png") } returns RemoteMediaFetchResult.Success(
            bytes = TINY_PNG,
            mimeType = "image/png",
            fileName = remoteName,
        )
        io.mockk.coEvery {
            env.filesManager.saveManagedFromBytes("upload", TINY_PNG, remoteName, "image/png", ArtifactOrigin.SYSTEM)
        } returns ArtifactEntity(
            id = 42,
            folder = "upload",
            relativePath = "upload/$remoteName",
            displayName = remoteName,
            mimeType = "image/png",
            sizeBytes = TINY_PNG.size.toLong(),
            createdAt = 1,
            updatedAt = 1,
        )
        io.mockk.every { env.filesManager.getFile(any()) } returns persisted
        val result = env.resolver.resolve(emptyList(), listOf("https://cdn.example/a.png"))
        val image = (result as AttachmentResolveResult.Success).parts.single()
        assertTrue(image.url.startsWith("file:"))
        assertTrue(!image.url.contains("https://cdn.example"))
        assertTrue(AttachmentRefs.getRef(image)!!.startsWith(AttachmentRefs.PREFIX))
        env.cleanup()
    }

    @Test
    fun `later resolve failure deletes files persisted earlier in the batch`() = runTest {
        val env = Env()
        val remoteName = "remote.png"
        val persisted = File(env.upload, remoteName).apply { writeBytes(TINY_PNG) }
        io.mockk.coEvery { env.fetcher.fetch("https://cdn.example/a.png") } returns RemoteMediaFetchResult.Success(
            bytes = TINY_PNG,
            mimeType = "image/png",
            fileName = remoteName,
        )
        io.mockk.coEvery {
            env.filesManager.saveManagedFromBytes("upload", TINY_PNG, remoteName, "image/png", ArtifactOrigin.SYSTEM)
        } returns ArtifactEntity(
            id = 42,
            folder = "upload",
            relativePath = "upload/$remoteName",
            displayName = remoteName,
            mimeType = "image/png",
            sizeBytes = TINY_PNG.size.toLong(),
            createdAt = 1,
            updatedAt = 1,
        )
        io.mockk.every { env.filesManager.getFile(any()) } returns persisted
        io.mockk.coEvery { env.filesManager.deleteManagedFilePermanently(42, true) } returns true
        val result = env.resolver.resolve(
            emptyList(),
            listOf("https://cdn.example/a.png", AttachmentRefs.format(Uuid.random())),
        )
        assertEquals(
            AttachmentFailureReasons.ATTACHMENT_NOT_FOUND,
            (result as AttachmentResolveResult.Failure).reason,
        )
        io.mockk.coVerify { env.filesManager.deleteManagedFilePermanently(42, true) }
        env.cleanup()
    }

    @Test
    fun `attachment ref in assistant_call metadata is resolved`() = runTest {
        val env = Env()
        val ref = AttachmentRefs.format(Uuid.parse("33333333-3333-3333-3333-333333333333"))
        val artifact = LocalArtifactRef(relativePath = "upload/${env.png.name}", mimeType = "image/png")
        val call = SubAssistantCallMetadata(
            runId = "run-1",
            targetAssistantId = Uuid.random().toString(),
            targetNameSnapshot = "Painter",
            artifacts = listOf(
                SubAssistantCallArtifact(
                    ref = ref,
                    type = "image",
                    mime = "image/png",
                    artifact = artifact,
                ),
            ),
        )
        val tool = UIMessagePart.Tool(
            toolCallId = "c1",
            toolName = "assistant_call",
            input = "{}",
        ).mergeSubAssistantCallMetadata(net.weero.measix.pilot.utils.JsonInstant, call)
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(tool),
            ),
        )
        val result = env.resolver.resolve(messages, listOf(ref))
        val parts = (result as AttachmentResolveResult.Success).parts
        assertEquals(1, parts.size)
        assertEquals(ref, AttachmentRefs.getRef(parts.single()))
        assertTrue(parts.single().url.startsWith("file:"))
        env.cleanup()
    }

    @Test
    fun `upload path referenced only by assistant_call metadata is resolved`() = runTest {
        val env = Env()
        val ref = AttachmentRefs.format(Uuid.random())
        val artifact = LocalArtifactRef(relativePath = "upload/${env.png.name}", mimeType = "image/png")
        val call = SubAssistantCallMetadata(
            runId = "run-2",
            targetAssistantId = Uuid.random().toString(),
            targetNameSnapshot = "Painter",
            artifacts = listOf(
                SubAssistantCallArtifact(
                    ref = ref,
                    type = "image",
                    mime = "image/png",
                    artifact = artifact,
                ),
            ),
        )
        val tool = UIMessagePart.Tool(
            toolCallId = "c2",
            toolName = "assistant_call",
            input = "{}",
        ).mergeSubAssistantCallMetadata(net.weero.measix.pilot.utils.JsonInstant, call)
        val messages = listOf(
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool)),
        )
        val result = env.resolver.resolve(messages, listOf("/upload/${env.png.name}"))
        val parts = (result as AttachmentResolveResult.Success).parts
        assertEquals(ref, AttachmentRefs.getRef(parts.single()))
        env.cleanup()
    }

    @Test
    fun `pdf bytes are unsupported`() = runTest {
        val env = Env()
        env.pdf.writeBytes("%PDF-1.4 fake".toByteArray())
        val messages = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Document(
                    url = AttachmentRefs.fileToFileUrl(env.pdf),
                    fileName = "doc.pdf",
                    mime = "application/pdf",
                    metadata = buildJsonObject {
                        put(AttachmentRefs.METADATA_KEY, AttachmentRefs.format(Uuid.random()))
                    },
                )),
            ),
        )
        val result = env.resolver.resolve(
            messages,
            listOf(AttachmentRefs.getRef(messages.single().parts.single())!!),
        )
        assertEquals(
            AttachmentFailureReasons.UNSUPPORTED_ATTACHMENT_TYPE,
            (result as AttachmentResolveResult.Failure).reason,
        )
        env.cleanup()
    }

    @Test
    fun `oversized local file is rejected before reading bytes`() = runTest {
        val env = Env()
        val big = File(env.upload, "big.png").apply {
            // 与远程路径 MAX_IMAGE_BYTES 对齐：超限的本地文件不允许整读进内存
            writeBytes(ByteArray(net.weero.measix.pilot.data.imggen.GeneratedMediaStore.MAX_IMAGE_BYTES + 1))
        }
        val messages = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Image(url = AttachmentRefs.fileToFileUrl(big))),
            ),
        )
        val result = env.resolver.resolve(messages, listOf("/upload/${big.name}"))
        assertEquals(
            AttachmentFailureReasons.UNSAFE_ATTACHMENT_URL,
            (result as AttachmentResolveResult.Failure).reason,
        )
        env.cleanup()
    }

    @Test
    fun `resolveImages keeps duplicate refs one to one in order`() = runTest {
        // inspect_attachments 的顺序契约：refs 与产出 1:1，重复 ref 不去重
        val env = Env()
        val ref = AttachmentRefs.format(Uuid.random())
        val messages = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(
                    UIMessagePart.Image(
                        url = AttachmentRefs.fileToFileUrl(env.png),
                        metadata = buildJsonObject { put(AttachmentRefs.METADATA_KEY, ref) },
                    ),
                ),
            ),
        )
        val result = env.resolver.resolveImages(messages, listOf(ref, ref))
        val parts = (result as AttachmentResolveResult.Success).parts
        assertEquals(2, parts.size)
        assertEquals(listOf(ref, ref), parts.map { AttachmentRefs.getRef(it) })
        env.cleanup()
    }

    @Test
    fun `resolveImages keeps two refs pointing to the same file`() = runTest {
        val env = Env()
        val refA = AttachmentRefs.format(Uuid.random())
        val refB = AttachmentRefs.format(Uuid.random())
        val messages = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(
                    UIMessagePart.Image(
                        url = AttachmentRefs.fileToFileUrl(env.png),
                        metadata = buildJsonObject { put(AttachmentRefs.METADATA_KEY, refA) },
                    ),
                    UIMessagePart.Image(
                        url = AttachmentRefs.fileToFileUrl(env.png),
                        metadata = buildJsonObject { put(AttachmentRefs.METADATA_KEY, refB) },
                    ),
                ),
            ),
        )
        val result = env.resolver.resolveImages(messages, listOf(refB, refA))
        val parts = (result as AttachmentResolveResult.Success).parts
        assertEquals(2, parts.size)
        assertEquals(listOf(refB, refA), parts.map { AttachmentRefs.getRef(it) })
        env.cleanup()
    }

    @Test
    fun `resolve keeps canonical file deduplication by default`() = runTest {
        // assistant_call 等通用入口维持既有去重语义
        val env = Env()
        val refA = AttachmentRefs.format(Uuid.random())
        val refB = AttachmentRefs.format(Uuid.random())
        val messages = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(
                    UIMessagePart.Image(
                        url = AttachmentRefs.fileToFileUrl(env.png),
                        metadata = buildJsonObject { put(AttachmentRefs.METADATA_KEY, refA) },
                    ),
                    UIMessagePart.Image(
                        url = AttachmentRefs.fileToFileUrl(env.png),
                        metadata = buildJsonObject { put(AttachmentRefs.METADATA_KEY, refB) },
                    ),
                ),
            ),
        )
        val result = env.resolver.resolve(messages, listOf(refA, refB))
        val parts = (result as AttachmentResolveResult.Success).parts
        assertEquals(1, parts.size)
        assertEquals(refA, AttachmentRefs.getRef(parts.single()))
        env.cleanup()
    }

    @Test
    fun `same remote url within one inspection batch is fetched once`() = runTest {
        val env = Env()
        val remoteName = "remote.png"
        val persisted = File(env.upload, remoteName).apply { writeBytes(TINY_PNG) }
        io.mockk.coEvery { env.fetcher.fetch("https://cdn.example/a.png") } returns RemoteMediaFetchResult.Success(
            bytes = TINY_PNG,
            mimeType = "image/png",
            fileName = remoteName,
        )
        io.mockk.coEvery {
            env.filesManager.saveManagedFromBytes("upload", TINY_PNG, remoteName, "image/png", ArtifactOrigin.SYSTEM)
        } returns ArtifactEntity(
            id = 42,
            folder = "upload",
            relativePath = "upload/$remoteName",
            displayName = remoteName,
            mimeType = "image/png",
            sizeBytes = TINY_PNG.size.toLong(),
            createdAt = 1,
            updatedAt = 1,
        )
        io.mockk.every { env.filesManager.getFile(any()) } returns persisted
        val refA = AttachmentRefs.format(Uuid.parse("44444444-4444-4444-4444-444444444444"))
        val refB = AttachmentRefs.format(Uuid.parse("55555555-5555-5555-5555-555555555555"))
        val messages = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(
                    UIMessagePart.Image(
                        url = "https://cdn.example/a.png",
                        metadata = buildJsonObject { put(AttachmentRefs.METADATA_KEY, refA) },
                    ),
                    UIMessagePart.Image(
                        url = "https://cdn.example/a.png",
                        metadata = buildJsonObject { put(AttachmentRefs.METADATA_KEY, refB) },
                    ),
                ),
            ),
        )
        val result = env.resolver.resolveImages(messages, listOf(refA, refB))
        val parts = (result as AttachmentResolveResult.Success).parts
        assertEquals(2, parts.size)
        // 各自的 preferredRef 保留在 metadata 上
        assertEquals(refA, AttachmentRefs.getRef(parts[0]))
        assertEquals(refB, AttachmentRefs.getRef(parts[1]))
        // 同一 url 单次批量解析内只 fetch/落盘一次
        io.mockk.coVerify(exactly = 1) { env.fetcher.fetch("https://cdn.example/a.png") }
        io.mockk.coVerify(exactly = 1) { env.filesManager.saveManagedFromBytes("upload", TINY_PNG, remoteName, "image/png", ArtifactOrigin.SYSTEM) }
        env.cleanup()
    }

    private class Env {
        val filesDir: File = createTempDirectory("attachment-resolver").toFile()
        val upload: File = File(filesDir, "upload").apply { mkdirs() }
        val png: File = File(upload, "ok.png").apply { writeBytes(TINY_PNG) }
        val pdf: File = File(upload, "doc.pdf")
        val context = mockk<Context>(relaxed = true)
        val filesManager = mockk<FilesManager>(relaxed = true)
        val artifactStore = mockk<ManagedLocalArtifactStore>()
        val fetcher = mockk<SafeRemoteMediaFetcher>(relaxed = true)
        val rewriter: ToolArtifactRewriter
        val resolver: AttachmentResolver

        init {
            every { context.filesDir } returns filesDir
            coEvery { filesManager.getByRelativePath(any()) } answers {
                val relative = firstArg<String>()
                val file = File(filesDir, relative)
                if (!file.isFile) null
                else ArtifactEntity(
                    id = 1,
                    folder = "upload",
                    relativePath = relative,
                    displayName = file.name,
                    mimeType = if (file.extension == "pdf") "application/pdf" else "image/png",
                    sizeBytes = file.length(),
                    createdAt = 1,
                    updatedAt = 1,
                )
            }
            every { filesManager.getFile(any()) } answers {
                val entity = firstArg<ArtifactEntity>()
                File(filesDir, entity.relativePath)
            }
            coEvery { artifactStore.resolveToolPath(any()) } answers {
                val path = firstArg<String>()
                val name = net.weero.measix.pilot.data.files.LocalToolPath.parseUploadToolPath(path)
                    ?: return@answers null
                File(upload, name).takeIf { it.isFile }
            }
            rewriter = ToolArtifactRewriter(filesDir, artifactStore)
            resolver = AttachmentResolver(context, filesManager, artifactStore, fetcher, rewriter)
        }

        fun cleanup() {
            filesDir.deleteRecursively()
        }
    }
}
