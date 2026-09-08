package net.weero.measix.pilot.service

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.RealmSelection
import kotlin.uuid.Uuid

/** Navigation preserves the original principal and intent across page/process recreation. */
@Serializable
sealed interface ConversationOpenRequest {
    val id: Uuid
    val access: RealmAccess

    @Serializable
    data class NewDraft(
        override val id: Uuid,
        override val access: RealmAccess,
        val assistantId: ConfigurationReference,
    ) : ConversationOpenRequest

    @Serializable
    data class OpenExisting(
        override val id: Uuid,
        override val access: RealmAccess,
    ) : ConversationOpenRequest
}

/** Opaque page ownership; closing or revoking it cannot authorize a later subscription. */
class ConversationViewLease internal constructor(
    val conversationId: Uuid,
    internal val access: RealmAccess,
    internal val selectionRevision: Long,
    private val closeAction: () -> Unit,
) : AutoCloseable {
    internal val imageReadIdentity = Uuid.random()
    val commandTarget = ConversationCommandTarget(conversationId, RealmSelection(access, selectionRevision), ::requireOpen)
    private val closedOnce = AtomicBoolean(false)
    private val _closed = MutableStateFlow(false)
    internal val closed = _closed.asStateFlow()

    internal fun requireOpen() {
        check(!closedOnce.get()) { "conversation_view_closed" }
    }

    override fun close() {
        if (closedOnce.compareAndSet(false, true)) {
            _closed.value = true
            closeAction()
        }
    }
}

/** An original UI command target, never reconstructed from the currently selected realm. */
class ConversationCommandTarget internal constructor(
    val conversationId: Uuid,
    internal val selection: RealmSelection,
    private val checkOwner: () -> Unit,
) {
    internal fun requireOpen() = checkOwner()
}
