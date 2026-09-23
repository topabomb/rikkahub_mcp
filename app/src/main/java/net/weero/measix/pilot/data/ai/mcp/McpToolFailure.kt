package net.weero.measix.pilot.data.ai.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.ToolExecutionFailure
import me.rerere.ai.core.ToolErrorProtocol
import me.rerere.ai.ui.UIMessagePart

/** Stable model-facing MCP failure categories; bounded cause details are projected separately. */
internal enum class McpToolFailureKind(
    val status: String,
    val reason: String,
    val detail: String? = null,
) {
    TOOL_UNAVAILABLE(
        status = "unavailable",
        reason = "tool_unavailable",
    ),
    SERVER_UNAVAILABLE(
        status = "unavailable",
        reason = "server_unavailable",
        detail = "The MCP session is unavailable; connection recovery is in progress. Try again later.",
    ),
    AUTHORIZATION_REQUIRED(
        status = "unavailable",
        reason = "authorization_required",
    ),
    REMOTE_ERROR(
        status = "failed",
        reason = "remote_error",
    ),
    PROTOCOL_INCOMPATIBLE(
        status = "failed",
        reason = "protocol_incompatible",
    ),
    RESULT_PROCESSING_FAILED(
        status = "failed",
        reason = "result_processing_failed",
        detail = "The MCP result was received but could not be processed locally; verify remote state before retrying.",
    ),
    OUTCOME_UNKNOWN(
        status = "unknown",
        reason = "outcome_unknown",
        detail = "The MCP call may have completed. Verify remote state before retrying.",
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
        val detail: String? = when {
            kind == McpToolFailureKind.REMOTE_ERROR && !remoteMessage.isNullOrBlank() &&
                remoteContent.isEmpty() && structuredContent == null -> remoteMessage
            cause != null && kind == McpToolFailureKind.OUTCOME_UNKNOWN ->
                "Verify remote state before retrying. ${ToolErrorProtocol.exceptionDetail(cause)}"
            cause != null && kind == McpToolFailureKind.RESULT_PROCESSING_FAILED ->
                "Remote result received; verify before retrying. ${ToolErrorProtocol.exceptionDetail(cause)}"
            cause != null && kind == McpToolFailureKind.PROTOCOL_INCOMPATIBLE ->
                "Invalid MCP result: ${ToolErrorProtocol.exceptionDetail(cause)}"
            cause != null && kind == McpToolFailureKind.SERVER_UNAVAILABLE ->
                "MCP session unavailable: ${ToolErrorProtocol.exceptionDetail(cause)}"
            else -> kind.detail
        }
        val envelope = buildJsonObject {
            ToolErrorProtocol.envelope(kind.status, kind.reason, detail).forEach { (key, value) -> put(key, value) }
            structuredContent?.let { put("structured_content", it) }
        }
        return ToolExecutionFailure(
            output = listOf(UIMessagePart.Text(envelope.toString())) + remoteContent,
            message = "MCP tool failure: ${kind.reason}",
            cause = cause,
        )
    }

}
