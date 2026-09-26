package net.weero.measix.pilot.data.ai.transformers

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.files.ArtifactReadLease
import net.weero.measix.pilot.data.files.LocalArtifactRef
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.service.turn.resolveTurnAssistantSnapshot
import net.weero.measix.pilot.test.testPromptInputs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class DocumentAsPromptTransformerTest {
    private fun contextFor(reads: ArtifactReadLease) = TransformerContext(
        realmAccess = RealmAccess.Personal, context = mockk(), model = Model(modelId = "test", displayName = "Test"),
        assistant = resolveTurnAssistantSnapshot(Assistant()),
        promptInputs = testPromptInputs(messageTemplate = "PREFIX[{{ message }}]", placeholderValues = mapOf("char" to "REPLACED")),
        requestOrigins = RequestMessageOriginTracker(), artifactReads = reads, registerUnpublishedResource = {},
    )

    private fun document(name: String = "doc.txt") = UIMessagePart.Document(
        url = "file:///managed/$name", fileName = name, mime = "text/plain",
    )

    @Test
    fun `unmanaged document is never read or treated as successful background`() = runTest {
        val reads = mockk<ArtifactReadLease>()
        coEvery { reads.resolveUri(any()) } returns null
        try {
            DocumentAsPromptTransformer().transform(contextFor(reads), listOf(UIMessage(role = MessageRole.USER, parts = listOf(document()))))
            throw AssertionError("unavailable document must fail")
        } catch (failure: IllegalStateException) {
            assertTrue(failure.message.orEmpty().contains("document_unavailable: doc.txt"))
        }
        verify(exactly = 0) { reads.file(any()) }
    }

    @Test
    fun `mime mismatch does not open a file`() = runTest {
        val reads = mockk<ArtifactReadLease>()
        coEvery { reads.resolveUri(any()) } returns LocalArtifactRef(relativePath = "upload/doc.txt", mimeType = "application/pdf")
        try {
            DocumentAsPromptTransformer().transform(contextFor(reads), listOf(UIMessage(role = MessageRole.USER, parts = listOf(document()))))
            throw AssertionError("mime mismatch must fail")
        } catch (failure: IllegalStateException) {
            assertTrue(failure.message.orEmpty().contains("document_mime_mismatch"))
        }
        verify(exactly = 0) { reads.file(any()) }
    }

    @Test
    fun `document attributes escape metacharacters and fences contain every backtick run`() {
        val text = renderDocumentInput("a\"<&>\n.txt", "/upload/a\"&.txt", "inside ``` and ````` fences {{char}}")
        assertTrue(text.startsWith("<UploadFile name=\"a&quot;&lt;&amp;&gt;&#10;.txt\" path=\"/upload/a&quot;&amp;.txt\">"))
        assertTrue(text.contains("\n``````\ninside ``` and ````` fences {{char}}\n``````\n"))
        assertTrue(!renderDocumentInput("a", null, "").contains("path="))
    }

    @Test
    fun `multiple documents retain attachment order and are opaque to templates`() = runTest {
        val files = listOf("first {{char}} {% if true %}literal{% endif %}", "second ````")
            .map { content -> kotlin.io.path.createTempFile("doc-input", ".txt").toFile().apply { writeText(content) } }
        try {
            val reads = mockk<ArtifactReadLease>()
            val documents = files.mapIndexed { index, file ->
                val doc = document("doc$index.txt")
                val ref = LocalArtifactRef(relativePath = "upload/${file.name}", mimeType = "text/plain")
                coEvery { reads.resolveUri(doc.url) } returns ref
                every { reads.file(ref) } returns file
                doc
            }
            val ctx = contextFor(reads)
            val original = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hello {char}")) + documents)
            val result = DocumentAsPromptTransformer().transform(ctx, listOf(original))
            val parts = result.single().parts
            assertEquals(listOf("doc0.txt", "doc1.txt"), parts.filterIsInstance<UIMessagePart.Document>().map { it.fileName })
            assertTrue((parts[1] as UIMessagePart.Text).text.contains("first {{char}}"))
            assertTrue((parts[3] as UIMessagePart.Text).text.contains("second ````"))
            assertEquals(RequestPartSource.DocumentInput(documents[0]), ctx.requestOrigins.source(parts[1]))
            val afterPlaceholder = PlaceholderTransformer.transform(ctx, result)
            val afterTemplate = TemplateTransformer(PebbleEngine.Builder().autoEscaping(false).build()).transform(ctx, afterPlaceholder)
            assertEquals("PREFIX[hello REPLACED]", (afterTemplate.single().parts[0] as UIMessagePart.Text).text)
            assertSame(parts[1], afterTemplate.single().parts[1])
            assertSame(parts[3], afterTemplate.single().parts[3])
        } finally { files.forEach(File::delete) }
    }

    @Test
    fun `read owner diagnostics and cancellation propagate unchanged`() = runTest {
        val cause = IOException("disk failure")
        val failures = listOf(IOException("read denied", cause), CancellationException("cancel read"))
        for (expected in failures) {
            val reads = mockk<ArtifactReadLease>()
            coEvery { reads.resolveUri(any()) } throws expected
            try {
                DocumentAsPromptTransformer().transform(contextFor(reads), listOf(UIMessage(role = MessageRole.USER, parts = listOf(document()))))
                throw AssertionError("read must fail")
            } catch (actual: Exception) {
                assertEquals(expected::class, actual::class)
                assertEquals(expected.message, actual.message)
                val chain = generateSequence<Throwable>(actual) { it.cause }.toList()
                assertTrue("the original failure must remain visible", chain.any { it === expected })
                expected.cause?.let { originalCause ->
                    assertTrue("the original cause must remain visible", chain.any { it === originalCause })
                }
                if (expected is CancellationException) assertTrue(actual is CancellationException)
            }
        }
    }
    @Test
    fun `missing lease file fails with the original document identity`() = runTest {
        val reads = mockk<ArtifactReadLease>()
        val ref = LocalArtifactRef(relativePath = "upload/doc.txt", mimeType = "text/plain")
        coEvery { reads.resolveUri(any()) } returns ref
        every { reads.file(ref) } returns null
        try {
            DocumentAsPromptTransformer().transform(contextFor(reads), listOf(UIMessage(role = MessageRole.USER, parts = listOf(document()))))
            throw AssertionError("missing lease file must fail")
        } catch (failure: java.io.FileNotFoundException) {
            assertEquals("Managed document unavailable: doc.txt", failure.message)
        }
    }
}
