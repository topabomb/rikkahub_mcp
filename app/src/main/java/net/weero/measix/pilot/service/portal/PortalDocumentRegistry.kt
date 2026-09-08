package net.weero.measix.pilot.service.portal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import net.weero.measix.pilot.data.enterprise.RealmAccess

/** Tracks live document owners so enterprise exit can await their original requests and host teardown. */
internal class PortalDocumentRegistry {
    private val documents = mutableMapOf<String, PortalDocument>()

    /** All local documents share one browser site; an old deletion must finish before another host opens. */
    suspend fun awaitHostAvailable() {
        val previous = synchronized(documents) { documents.values.filterNot { it.isHostClosed } }
        if (previous.any { !it.isClosed }) throw PortalFailure("source_unavailable")
        previous.forEach { it.awaitHostClosed() }
    }

    internal fun requireHostAvailable() = synchronized(documents) {
        if (documents.values.any { !it.isHostClosed }) throw PortalFailure("source_unavailable")
    }

    internal fun register(document: PortalDocument) = synchronized(documents) {
        check(documents.putIfAbsent(document.id, document) == null)
        document.invokeOnCompletion {
            synchronized(documents) {
                documents.remove(document.id, document)
            }
        }
    }

    /** Admission must already be revoked by the Session owner; never wait while holding its lock. */
    suspend fun closeAndAwait(access: RealmAccess.Enterprise, reason: PortalCloseReason) {
        val receipt = capture(access)
        receipt.revoke(reason)
        receipt.awaitClosed()
    }

    fun capture(access: RealmAccess.Enterprise): PortalCloseReceipt = synchronized(documents) {
        PortalCloseReceipt(documents.values.filter { it.selection.access == access })
    }
}

/** Captured before revocation, so a failed host close still has an owner for request cleanup. */
internal class PortalCloseReceipt internal constructor(private val documents: List<PortalDocument>) {
    suspend fun revoke(reason: PortalCloseReason) {
        if (documents.isEmpty()) return
        withContext(Dispatchers.Main.immediate) {
            documents.forEach { it.close(reason) }
        }
    }

    suspend fun awaitHostsClosed() = awaitEvery { it.awaitHostClosed() }
    suspend fun awaitClosed() = awaitEvery { it.awaitClosed() }

    private suspend fun awaitEvery(operation: suspend (PortalDocument) -> Unit) {
        var failure: Exception? = null
        documents.forEach { document ->
            try { operation(document) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (failure == null) failure = error else if (error !== failure) failure?.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
}
