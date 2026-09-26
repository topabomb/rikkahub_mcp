package net.weero.measix.pilot.data.ai.transformers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.document.DocxParser
import me.rerere.document.EpubParser
import me.rerere.document.PdfParser
import me.rerere.document.PptxParser
import java.io.FileNotFoundException

/** Resolves documents exclusively through the current managed read lease. Failures remain diagnostic. */
class DocumentAsPromptTransformer : InputMessageTransformer {
    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> {
        val reads = requireNotNull(ctx.artifactReads)
        return withContext(Dispatchers.IO) {
            messages.map { message ->
                message.copy(parts = buildList {
                    for ((partIndex, part) in message.parts.withIndex()) {
                        if (part is UIMessagePart.Document) {
                            val admitted = ctx.requestOrigins.documentText(message.id, partIndex)
                            if (admitted != null) {
                                val text = UIMessagePart.Text(admitted)
                                ctx.requestOrigins.markPart(text, RequestPartSource.DocumentInput(part))
                                add(text)
                                add(part)
                                continue
                            }
                            val managed = reads.resolveUri(part.url)
                                ?: throw IllegalStateException("document_unavailable: ${part.fileName}")
                            check(managed.mimeType.equals(part.mime, ignoreCase = true)) {
                                "document_mime_mismatch: ${part.fileName} (${part.mime}, ${managed.mimeType})"
                            }
                            val file = reads.file(managed)
                                ?: throw FileNotFoundException("Managed document unavailable: ${part.fileName}")
                            if (!file.isFile) throw FileNotFoundException("Managed document not found: ${part.fileName}")
                            val content = when (part.mime.lowercase(java.util.Locale.ROOT)) {
                                "application/pdf" -> PdfParser.parserPdf(file)
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> DocxParser.parse(file)
                                "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> PptxParser.parse(file)
                                "application/epub+zip" -> EpubParser.parse(file)
                                else -> file.readText()
                            }
                            val text = UIMessagePart.Text(renderDocumentInput(part.fileName, managed.toolPath(), content))
                            ctx.requestOrigins.markPart(text, RequestPartSource.DocumentInput(part))
                            add(text)
                        }
                        add(part)
                    }
                })
            }
        }
    }
}

internal fun renderDocumentInput(name: String, path: String?, content: String): String {
    val longestRun = Regex("`+").findAll(content).maxOfOrNull { it.value.length } ?: 0
    val fence = "`".repeat(maxOf(3, longestRun + 1))
    val pathAttribute = path?.let { " path=\"${escapeDocumentAttribute(it)}\"" }.orEmpty()
    return "<UploadFile name=\"${escapeDocumentAttribute(name)}\"$pathAttribute>\n$fence\n$content\n$fence\n</UploadFile>"
}

private fun escapeDocumentAttribute(value: String): String = value
    .replace("&", "&amp;").replace("\"", "&quot;").replace("'", "&apos;")
    .replace("<", "&lt;").replace(">", "&gt;")
    .replace("\r", "&#13;").replace("\n", "&#10;").replace("\t", "&#9;")
