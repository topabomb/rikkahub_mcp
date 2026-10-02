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
        val starter: StarterOpeningReference? = null,
    ) : ConversationOpenRequest

    @Serializable
    data class OpenExisting(
        override val id: Uuid,
        override val access: RealmAccess,
        val startupResume: StartupConversationResume? = null,
    ) : ConversationOpenRequest
}

/** Only a verified implicit startup may recover a deletion between lookup and opening. */
@Serializable
data class StartupConversationResume(
    val assistantId: ConfigurationReference,
    val selectionRevision: Long,
)

/** The database deletion is committed even when subsequent preference maintenance failed. */
class ConversationDeletionReceipt internal constructor(
    val conversationId: Uuid,
    val assistantId: ConfigurationReference,
    internal val selection: RealmSelection,
    val maintenanceFailure: Throwable? = null,
)

@ConsistentCopyVisibility
data class ConversationContinuation internal constructor(
    val request: ConversationOpenRequest.NewDraft?,
    val unavailableReason: String? = null,
)

/** Navigation carries content identity and provenance, never enterprise prompt bodies. */
@Serializable
data class StarterOpeningReference(
    val reference: ConfigurationReference.Enterprise,
    val definitionHash: String,
    val releaseId: String,
    val generation: Long,
    val snapshotHash: String,
) {
    init {
        require(definitionHash.matches(Regex("[0-9a-f]{64}")) && generation > 0 &&
            releaseId.matches(Regex("rel_[0-9a-f-]{36}")) && snapshotHash.matches(Regex("sha256:[0-9a-f]{64}"))) {
            "invalid_starter_opening_reference"
        }
    }
}

/** Opaque page ownership; closing or revoking it cannot authorize a later subscription. */
class ConversationViewLease internal constructor(
    val conversationId: Uuid,
    internal val access: RealmAccess,
    internal val selectionRevision: Long,
    private val draftArtifacts: () -> List<net.weero.measix.pilot.data.files.OwnedArtifact> = { emptyList() },
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

    internal fun ownedArtifact(id: Long): net.weero.measix.pilot.data.files.OwnedArtifact? {
        requireOpen()
        return draftArtifacts().find { it.entity.id == id }
    }

    internal fun ownedArtifact(ref: net.weero.measix.pilot.data.files.LocalArtifactRef): net.weero.measix.pilot.data.files.OwnedArtifact? {
        requireOpen()
        return draftArtifacts().find { it.localRef == ref }
    }

    internal fun ownedArtifact(file: java.io.File): net.weero.measix.pilot.data.files.OwnedArtifact? {
        requireOpen()
        val path = file.canonicalPath
        return draftArtifacts().find { net.weero.measix.pilot.data.ai.attachments.AttachmentRefs.parseFileUrl(it.uri.toString())?.canonicalPath == path }
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
