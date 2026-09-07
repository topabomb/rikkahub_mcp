package me.rerere.ai.ui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.ai.core.ToolOutputPolicy

/**
 * The user-gate state of a Tool Call, distinct from execution and result state.
 *
 * `NotRequired` is the default for calls that never pause. `AwaitingApproval` / `AwaitingInput`
 * are the two durable pause states (the Turn is `AWAITING_USER`). `Approved` / `Denied` /
 * `Answered` are resolved gates. Approval and user-input collection are kept as distinct states
 * rather than conflated into one, because they resolve through different protocols.
 */
@Serializable
sealed class ToolInteractionState {
    @Serializable
    @SerialName("not_required")
    data object NotRequired : ToolInteractionState()

    @Serializable
    @SerialName("awaiting_approval")
    data object AwaitingApproval : ToolInteractionState()

    @Serializable
    @SerialName("awaiting_input")
    data object AwaitingInput : ToolInteractionState()

    @Serializable
    @SerialName("approved")
    data object Approved : ToolInteractionState()

    @Serializable
    @SerialName("denied")
    data class Denied(val reason: String = "") : ToolInteractionState()

    @Serializable
    @SerialName("answered")
    data class Answered(val answer: String) : ToolInteractionState()
}

/**
 * The durable result classification of a Tool Call. `null` means no replayable result exists yet
 * (the call is still streaming, awaiting the user, or executing). A result always carries a status;
 * the status is the single source of truth for terminal presentation, not a JSON sniff of [Tool.output].
 */
@Serializable
enum class ToolResultStatus {
    @SerialName("completed")
    COMPLETED,

    @SerialName("failed")
    FAILED,

    @SerialName("denied")
    DENIED,

    @SerialName("answered")
    ANSWERED,

    @SerialName("cancelled")
    CANCELLED,

    @SerialName("interrupted")
    INTERRUPTED,

    @SerialName("unknown")
    UNKNOWN,
    ;

    /** Stable lowercase token used by the archived-output marker text. */
    val wireName: String
        get() = when (this) {
            COMPLETED -> "completed"
            FAILED -> "failed"
            DENIED -> "denied"
            ANSWERED -> "answered"
            CANCELLED -> "cancelled"
            INTERRUPTED -> "interrupted"
            UNKNOWN -> "unknown"
        }
}

/**
 * Reference to an archived Tool output payload. Moved from the app module so that the transcript
 * `Tool` part can carry its archive fact without a shadow JSON schema.
 */
@Serializable
data class ToolOutputArchiveRef(
    val relativePath: String,
    val mimeType: String,
)

/**
 * The durable archive fact of an `ARCHIVABLE_TEXT` Tool output that rolling compaction replaced
 * with a marker. `ref` is the Artifact reference id; the model can read/grep the payload back.
 */
@Serializable
data class ToolOutputArchive(
    val ref: Long,
    val artifact: ToolOutputArchiveRef,
    val characters: Long,
    val lines: Int,
)

/**
 * The typed runtime state of a Tool Call. This is the single home for the output policy and the
 * archive fact, replacing the old `metadata.tool_runtime` JSON shadow schema.
 */
@Serializable
data class ToolRuntimeState(
    val outputPolicy: ToolOutputPolicy,
    val archive: ToolOutputArchive? = null,
)
