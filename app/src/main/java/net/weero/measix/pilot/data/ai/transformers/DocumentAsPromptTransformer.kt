package net.weero.measix.pilot.data.ai.transformers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.document.DocxParser
import me.rerere.document.EpubParser
import me.rerere.document.PdfParser
import me.rerere.document.PptxParser
import net.weero.measix.pilot.data.files.ArtifactReadLease
import net.weero.measix.pilot.data.files.LocalArtifactRef
import java.io.File

/** Converts managed document artifacts into model-visible text without reading arbitrary paths. */
class DocumentAsPromptTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val reads = requireNotNull(ctx.artifactReads)
        return withContext(Dispatchers.IO) {
            val transformed = ArrayList<UIMessage>(messages.size)
            for (message in messages) {
                val parts = message.parts.toMutableList()
                val documents = parts.filterIsInstance<UIMessagePart.Document>()
                for (document in documents) {
                    val managed = resolveManagedDocument(document, reads)
                    val content = readDocumentContent(document, managed?.let(reads::file))
                    val path = resolveWorkspacePath(managed)
                    val pathAttr = path?.let { " path=\"$it\"" } ?: ""
                    val prompt = """
                      <UploadFile name="${document.fileName}"$pathAttr>
                      ```
                      $content
                      ```
                      </UploadFile>
                      """.trimMargin()
                    parts.add(0, UIMessagePart.Text(prompt))
                }
                transformed += message.copy(parts = parts)
            }
            transformed
        }
    }

    private fun parsePdfAsText(file: File): String {
        return PdfParser.parserPdf(file)
    }

    private fun parseDocxAsText(file: File): String {
        return DocxParser.parse(file)
    }

    private fun parsePptxAsText(file: File): String {
        return PptxParser.parse(file)
    }

    private fun parseEpubAsText(file: File): String {
        return EpubParser.parse(file)
    }

    // Only the original read lease may expose a tool-readable upload path.
    private fun resolveWorkspacePath(artifact: LocalArtifactRef?): String? = artifact?.toolPath()

    private fun resolveManagedDocument(document: UIMessagePart.Document, reads: ArtifactReadLease): LocalArtifactRef? {
        val managed = reads.resolveUri(document.url) ?: return null
        if (!managed.mimeType.equals(document.mime, ignoreCase = true)) return null
        return managed
    }

    private fun readDocumentContent(document: UIMessagePart.Document, file: File?): String {
        if (file == null || !file.isFile) {
            return "[ERROR, file not found: ${document.fileName}]"
        }
        return try {
            when (document.mime) {
                "application/pdf" -> parsePdfAsText(file)
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> parseDocxAsText(file)
                "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> parsePptxAsText(file)
                "application/epub+zip" -> parseEpubAsText(file)
                else -> file.readText()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            "[ERROR, failed to read file: ${document.fileName}]"
        }
    }
}
