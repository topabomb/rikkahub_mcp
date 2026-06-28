package net.weero.measix.pilot.data.ai.mcp

sealed class McpStatus {
    data object Idle : McpStatus()
    data object Connecting : McpStatus()
    data object Connected : McpStatus()
    data class Reconnecting(val attempt: Int, val maxAttempts: Int) : McpStatus()
    data class Dormant(val nextRetryInMs: Long) : McpStatus()
    data class Error(val message: String) : McpStatus()
}
