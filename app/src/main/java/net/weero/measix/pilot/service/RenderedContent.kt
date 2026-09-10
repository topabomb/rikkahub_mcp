package net.weero.measix.pilot.service

/** The rendered document borrows its original source; it never reconstructs permission from a route or URL. */
sealed interface RenderedContentSource {
    data class Conversation(val view: ConversationViewLease) : RenderedContentSource
    data class RealmConfiguration(val view: ConversationViewLease) : RenderedContentSource
    data object UserConfiguration : RenderedContentSource
    data object Static : RenderedContentSource
}

data class RenderedContent(val source: RenderedContentSource, val html: String)

/** Frozen at the user's click, before the system document picker can suspend the page. */
data class TextDocumentExport(
    val source: RenderedContentSource,
    val text: String,
    val fileName: String,
    val mimeType: String,
)

internal const val RENDERED_FILE_PATH = "/_managed_file"

/** Only the document's own virtual origin may interpret these paths. */
internal fun renderedLocalFileUrl(uri: android.net.Uri): String? = when {
    uri.path == RENDERED_FILE_PATH -> uri.getQueryParameter("url")?.takeIf { it.startsWith("file:", ignoreCase = true) }
    uri.path.orEmpty().startsWith("/upload/") && uri.query == null -> uri.path
    else -> null
}
