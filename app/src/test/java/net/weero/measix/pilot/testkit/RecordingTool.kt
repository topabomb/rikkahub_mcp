package net.weero.measix.pilot.testkit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolArgumentsException
import me.rerere.ai.core.ToolAttachmentResolution
import me.rerere.ai.core.ToolExecutionFailure
import me.rerere.ai.core.ToolInteractionRequirement
import me.rerere.ai.core.ToolMetadataDelivery
import me.rerere.ai.core.ToolOutputPolicy
import me.rerere.ai.core.ToolResourceLease
import me.rerere.ai.ui.UIMessagePart
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Tool double that records every execution invocation and can hold execution at an explicit
 * barrier, so tests prove "STARTED commit precedes side effect" instead of guessing from timing.
 */
class RecordingTool(
    val name: String = "recording_tool",
    var outputPolicy: ToolOutputPolicy = ToolOutputPolicy.PRESERVE,
    var interactionRequirement: ToolInteractionRequirement = ToolInteractionRequirement.None,
    /** When set, execution suspends here until the test completes the deferred. */
    var executionBarrier: CompletableDeferred<Unit>? = null,
    var result: List<UIMessagePart> = listOf(UIMessagePart.Text("{\"status\":\"ok\"}")),
    var failure: Throwable? = null,
    /** Lease handed to the owner during execution; proves the resource-commit path. */
    var leaseToRegister: ToolResourceLease? = null,
    var metadataPatchOnExecute: JsonObject? = null,
) {
    var invocationCount = 0
        private set
    val observedArguments = mutableListOf<JsonElement>()
    var sawApprovedByUser: Boolean? = null
        private set

    fun asTool(): Tool = Tool(
        name = name,
        description = "recording tool",
        outputPolicy = outputPolicy,
        interactionRequirement = { interactionRequirement },
        validateArguments = { element ->
            if (element !is JsonObject) {
                buildJsonObject { put("error", JsonPrimitive("invalid_arguments")) }
            } else {
                null
            }
        },
        contextualExecute = { args ->
            invocationCount += 1
            observedArguments += args
            sawApprovedByUser = approvedByUser
            metadataPatchOnExecute?.let { patch ->
                reportMetadata(patch, ToolMetadataDelivery.DEFERRED)
            }
            leaseToRegister?.let { registerUnpublishedResource(it) }
            executionBarrier?.await()
            when (val configuredFailure = failure) {
                null -> result
                is ToolExecutionFailure -> throw configuredFailure
                else -> throw configuredFailure
            }
        },
        execute = { error("contextual execution only") },
    )
}

/** Tool whose argument parsing always fails with a replayable invalid-arguments envelope. */
fun invalidArgumentsTool(name: String = "invalid_tool"): Tool = Tool(
    name = name,
    description = "always rejects arguments",
    validateArguments = { buildJsonObject { put("error", JsonPrimitive("invalid_arguments")) } },
    execute = { error("must never execute") },
)

/**
 * Records the full lease lifecycle so ownership tests observe state transitions instead of
 * inferring order from file existence or verify(exactly = n).
 */
class ArtifactLeaseProbe(val id: String = "lease-1") {
    val events = mutableListOf<String>()

    fun asLease(): ToolResourceLease = ToolResourceLease(
        publish = { events += "published" },
        discard = { events += "discarded" },
    )

    val published: Boolean get() = events.contains("published")
    val discarded: Boolean get() = events.contains("discarded")
}

/** Attachment resolution double for hooks that read request images. */
fun attachmentsUnavailable(): ToolAttachmentResolution =
    ToolAttachmentResolution(failureReason = "attachment_unavailable")
