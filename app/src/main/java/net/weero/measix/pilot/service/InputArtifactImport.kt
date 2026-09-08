package net.weero.measix.pilot.service

import android.net.Uri
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessagePart

/** Only the newly created batch is released when its original input can no longer receive it. */
internal suspend fun ArtifactDraftScope.importInputUris(uris: List<Uri>, requireOwner: () -> Unit): List<ArtifactDraftItem> =
    receiveInputArtifacts(requireOwner, { importUrisOrThrow(uris) }, { items -> items.map { it.uri } })

internal suspend fun ArtifactDraftScope.createInputText(text: String, requireOwner: () -> Unit): UIMessagePart.Document =
    receiveInputArtifacts(requireOwner, { createTextDocument(text) }, { listOf(Uri.parse(it.url)) })

private suspend fun <T> ArtifactDraftScope.receiveInputArtifacts(
    requireOwner: () -> Unit,
    create: suspend () -> T,
    createdUris: (T) -> List<Uri>,
): T {
    requireOwner()
    val result = create()
    try {
        currentCoroutineContext().ensureActive()
        requireOwner()
        return result
    } catch (error: Throwable) {
        withContext(NonCancellable) {
            createdUris(result).forEach { uri ->
                try { discard(uri) } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            }
        }
        throw error
    }
}
