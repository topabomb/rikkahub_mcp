package net.weero.measix.pilot.service.portal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import net.weero.measix.pilot.data.enterprise.RealmAccess

/** Tracks live document owners so enterprise exit can await their original requests and host teardown. */
internal class PortalDocumentRegistry {
    private val documents = mutableMapOf<String, PortalDocument>()

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
        val captured = synchronized(documents) { documents.values.filter { it.selection.access == access } }
        if (captured.isEmpty()) return
        withContext(Dispatchers.Main.immediate) {
            captured.forEach { it.close(reason) }
        }
        var failure: Exception? = null
        captured.forEach { document ->
            try { document.awaitClosed() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (failure == null) failure = error else if (error !== failure) failure?.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
}
