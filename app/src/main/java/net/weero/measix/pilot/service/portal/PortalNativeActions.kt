package net.weero.measix.pilot.service.portal

import android.content.Context
import android.content.Intent
import androidx.annotation.MainThread
import androidx.core.net.toUri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.service.EnterpriseExitService
import java.net.URI
import kotlin.uuid.Uuid

internal sealed interface PortalNativePrompt {
    val id: Uuid
    data class Logout(override val id: Uuid = Uuid.random(), val enterpriseName: String) : PortalNativePrompt
    data class External(override val id: Uuid = Uuid.random(), val url: URI) : PortalNativePrompt
}

/** One document owns its native prompts; UI decisions never acquire authority from a replacement page. */
@MainThread
internal class PortalNativeActions(
    private val context: Context,
    private val document: PortalDocumentContext,
    private val sessions: EnterpriseSessionController,
    private val exits: EnterpriseExitService,
) {
    private data class Pending(val prompt: PortalNativePrompt, val decision: CompletableDeferred<Boolean>)
    private var pending: Pending? = null
    private var closed = false
    private val _prompt = MutableStateFlow<PortalNativePrompt?>(null)
    val prompt = _prompt.asStateFlow()
    val capabilities = listOf("logout", "openExternal")
    private val selection get() = document.selection

    suspend fun logout() {
        requireOpen()
        val original = exits.captureRequest() ?: throw PortalFailure("session_expired")
        if (original.selection != selection || original.access != selection.access) throw PortalFailure("session_expired")
        val name = requireNotNull(sessions.portalState(selection).manifest.session).identity.enterpriseName
        confirm(PortalNativePrompt.Logout(enterpriseName = name))
        // Exit owns its application task. Revoking this document cancels only this caller's wait.
        exits.exit(original)
    }

    suspend fun openExternal(url: URI) {
        confirm(PortalNativePrompt.External(url = url))
        sessions.withSelectedRealmSelection(selection) {
            requireOpen()
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toASCIIString().toUri()).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    fun decide(original: PortalNativePrompt, accepted: Boolean) {
        val current = pending ?: return
        if (closed || current.prompt != original) return
        current.decision.complete(accepted)
    }

    fun close() {
        closed = true
        pending?.decision?.cancel()
        _prompt.value = null
    }

    private suspend fun confirm(prompt: PortalNativePrompt) {
        requireOpen()
        if (pending != null) throw PortalFailure("resource_limit")
        val request = Pending(prompt, CompletableDeferred())
        pending = request
        _prompt.value = prompt
        try {
            if (!request.decision.await()) throw PortalFailure("user_cancelled")
            currentCoroutineContext().ensureActive()
            requireOpen()
        } finally {
            if (pending === request) { pending = null; _prompt.value = null }
        }
    }

    private fun requireOpen() {
        if (closed) throw PortalFailure("session_expired")
        document.requireUnexpired()
    }
}
