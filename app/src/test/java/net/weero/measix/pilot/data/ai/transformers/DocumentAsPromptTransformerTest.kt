package net.weero.measix.pilot.data.ai.transformers

import net.weero.measix.pilot.service.turn.resolveTurnAssistantSnapshot

import net.weero.measix.pilot.test.testPromptInputs

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.LocalArtifactRef
import net.weero.measix.pilot.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DocumentAsPromptTransformerTest {
    private val context = mockk<android.content.Context>(relaxed = true)
    private val model = mockk<Model>(relaxed = true)
    private val assistant = Assistant()

    private fun contextFor() = TransformerContext(
        context = context,
        model = model,
        assistant = resolveTurnAssistantSnapshot(assistant),
        promptInputs = testPromptInputs(),
        requestOrigins = RequestMessageOriginTracker(),
        registerUnpublishedResource = {},
    )

    @Test
    fun `unmanaged document is never read`() = runTest {
        val file = kotlin.io.path.createTempFile("unmanaged-document", ".txt").toFile().apply {
            writeText("must not be read")
        }
        val store = mockk<ArtifactStore>()
        coEvery { store.resolveManagedReference(any<File>()) } returns null

        val result = DocumentAsPromptTransformer(store).transform(
            contextFor(),
            listOf(
                UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(
                        UIMessagePart.Document(
                            url = "file://${file.absolutePath}",
                            fileName = file.name,
                            mime = "text/plain",
                        ),
                    ),
                ),
            ),
        )

        val prompt = (result.single().parts.first() as UIMessagePart.Text).text
        assertTrue(prompt.contains("file not found"))
        assertTrue(!prompt.contains("must not be read"))
        file.delete()
    }

    @Test
    fun `active managed document is parsed through artifact owner`() = runTest {
        val file = kotlin.io.path.createTempFile("managed-document", ".txt").toFile().apply {
            writeText("managed content")
        }
        val ref = LocalArtifactRef(relativePath = "upload/${file.name}", mimeType = "text/plain")
        val store = mockk<ArtifactStore>()
        coEvery { store.resolveManagedReference(any<File>()) } returns ref
        coEvery { store.materialize(ref) } returns ref
        every { store.file(ref) } returns file

        val result = DocumentAsPromptTransformer(store).transform(
            contextFor(),
            listOf(
                UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Document(
                        url = "file://${file.absolutePath}",
                        fileName = file.name,
                        mime = "text/plain",
                    )),
                ),
            ),
        )

        val prompt = (result.single().parts.first() as UIMessagePart.Text).text
        assertTrue(prompt.contains("managed content"))
        assertTrue(prompt.contains("path=\"/upload/${file.name}\""))
        file.delete()
    }

    @Test
    fun `cancellation from artifact owner propagates`() = runTest {
        val store = mockk<ArtifactStore>()
        coEvery { store.resolveManagedReference(any<File>()) } throws CancellationException("cancel")
        val message = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Document(
                url = "file:///data/data/app/upload/doc.txt",
                fileName = "doc.txt",
                mime = "text/plain",
            )),
        )

        try {
            DocumentAsPromptTransformer(store).transform(contextFor(), listOf(message))
            org.junit.Assert.fail("cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(true, true)
        }
    }
}
