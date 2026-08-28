package net.weero.measix.pilot.data.ai.mcp

sealed class McpStatus {
    data object Idle : McpStatus()
    data object Connecting : McpStatus()
    data object Discovering : McpStatus()
    data class Ready(val toolCount: Int, val catalogRevision: Long) : McpStatus()
    data class Reconnecting(
        val attempt: Int,
        val maxAttempts: Int,
        val maintenance: Boolean = false,
    ) : McpStatus()
    data class RetryScheduled(
        val attempt: Int,
        val maxAttempts: Int,
        val retryInMs: Long,
        val maintenance: Boolean = false,
    ) : McpStatus()
    data object WaitingNetwork : McpStatus()
    data object CatalogRejectedEmpty : McpStatus()
    data class CatalogStale(
        val lastKnownGoodCount: Int,
        val catalogRevision: Long,
        val message: String?,
    ) : McpStatus()

    /**
     * 连接/同步出错。
     *
     * @param message 简短摘要，用于列表内联展示
     * @param detail 完整错误信息（含 cause 链与堆栈），用于展开查看与复制；无异常来源时为 null
     */
    data class Error(val message: String?, val detail: String? = null) : McpStatus() {
        companion object {
            fun from(throwable: Throwable, fallbackMessage: String? = null): Error {
                val summary = throwable.message?.takeIf { it.isNotBlank() }
                    ?: fallbackMessage
                return Error(message = summary, detail = throwable.stackTraceToString())
            }
        }
    }

    /** 服务器返回 401，需要用户完成 OAuth 授权。 */
    data object NeedsAuthorization : McpStatus()

    /** 正在进行 OAuth 授权流程（等待浏览器回调 / 交换令牌）。 */
    data object Authorizing : McpStatus()
}
