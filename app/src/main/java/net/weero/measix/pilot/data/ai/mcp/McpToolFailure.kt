package net.weero.measix.pilot.data.ai.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.ToolExecutionFailure
import me.rerere.ai.ui.UIMessagePart

/** Stable model-facing MCP failure categories. Transport diagnostics never enter this contract. */
internal enum class McpToolFailureKind(
    val status: String,
    val reason: String,
    val message: String?,
) {
    TOOL_UNAVAILABLE(
        status = "unavailable",
        reason = "tool_unavailable",
        message = null,
    ),
    SERVER_UNAVAILABLE(
        status = "unavailable",
        reason = "server_unavailable",
        message = "Try again later.",
    ),
    AUTHORIZATION_REQUIRED(
        status = "unavailable",
        reason = "authorization_required",
        message = "User authorization is required.",
    ),
    REMOTE_ERROR(
        status = "failed",
        reason = "remote_error",
        message = null,
    ),
    PROTOCOL_INCOMPATIBLE(
        status = "failed",
        reason = "protocol_incompatible",
        message = null,
    ),
    OUTCOME_UNKNOWN(
        status = "unknown",
        reason = "outcome_unknown",
        message = "The request may have completed.",
    ),
}

/** The only projection from internal MCP failures to Agent-visible Tool output. */
internal object McpToolFailureProjector {
    fun project(
        kind: McpToolFailureKind,
        remoteContent: List<UIMessagePart> = emptyList(),
        structuredContent: JsonObject? = null,
        remoteMessage: String? = null,
        cause: Throwable? = null,
    ): ToolExecutionFailure {
        require(kind == McpToolFailureKind.REMOTE_ERROR || remoteContent.isEmpty())
        require(kind == McpToolFailureKind.REMOTE_ERROR || structuredContent == null)
        require(kind == McpToolFailureKind.REMOTE_ERROR || remoteMessage == null)
        val envelope = buildJsonObject {
            put("status", kind.status)
            put("reason", kind.reason)
            val fallbackMessage = if (
                kind == McpToolFailureKind.REMOTE_ERROR &&
                remoteContent.isEmpty() &&
                structuredContent == null
            ) {
                remoteMessage
                    ?.replace(Regex("[\\p{Cc}&&[^\\r\\n\\t]]"), " ")
                    ?.trim()
                    ?.take(MAX_REMOTE_MESSAGE_CHARS)
                    ?.takeIf(String::isNotEmpty)
                    ?: "The MCP server reported an error."
            } else {
                kind.message
            }
            fallbackMessage?.let { put("message", it) }
            structuredContent?.let { put("structured_content", it) }
        }
        return ToolExecutionFailure(
            output = listOf(UIMessagePart.Text(envelope.toString())) + remoteContent,
            message = "MCP tool failure: ${kind.reason}",
            cause = cause,
        )
    }

    private const val MAX_REMOTE_MESSAGE_CHARS = 500
}
