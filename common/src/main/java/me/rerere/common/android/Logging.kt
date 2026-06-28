package me.rerere.common.android

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

private const val MAX_TEXT_LOGS = 400
private const val MAX_REQUEST_LOGS = 100
private const val MAX_MESSAGE_LENGTH = 500

@Serializable
sealed class LogEntry {
    abstract val id: Uuid
    abstract val timestamp: Long
    abstract val tag: String

    @Serializable
    data class TextLog(
        override val id: Uuid = Uuid.random(),
        override val timestamp: Long = System.currentTimeMillis(),
        override val tag: String,
        val message: String
    ) : LogEntry()

    @Serializable
    data class RequestLog(
        override val id: Uuid = Uuid.random(),
        override val timestamp: Long = System.currentTimeMillis(),
        override val tag: String,
        val url: String,
        val method: String,
        val requestHeaders: Map<String, String> = emptyMap(),
        val requestBody: String? = null,
        val responseCode: Int? = null,
        val responseHeaders: Map<String, String> = emptyMap(),
        val durationMs: Long? = null,
        val error: String? = null
    ) : LogEntry()
}

object Logging {
    private val recentLogs = arrayListOf<LogEntry>()
    @Volatile
    private var requestLoggingEnabled = false

    fun log(tag: String, message: String) {
        val safe = if (message.length > MAX_MESSAGE_LENGTH) {
            message.take(MAX_MESSAGE_LENGTH) + "…"
        } else {
            message
        }
        addLog(LogEntry.TextLog(tag = tag, message = safe))
    }

    fun logRequest(entry: LogEntry.RequestLog) {
        if (!requestLoggingEnabled) return
        addLog(entry)
    }

    fun isRequestLoggingEnabled(): Boolean = requestLoggingEnabled

    fun setRequestLoggingEnabled(enabled: Boolean) {
        requestLoggingEnabled = enabled
    }

    private fun addLog(entry: LogEntry) {
        synchronized(recentLogs) {
            recentLogs.add(0, entry)
            // 按类型分别裁剪: TextLog 和 RequestLog 各有独立上限
            // 避免大量 HTTP 请求日志挤掉 MCP 生命周期日志（或反之）
            trimByType<LogEntry.TextLog>(MAX_TEXT_LOGS)
            trimByType<LogEntry.RequestLog>(MAX_REQUEST_LOGS)
        }
    }

    /**
     * 保留最新的 [maxCount] 条指定类型日志，移除多余的旧日志。
     * 日志按 timestamp 降序排列（最新在前），保留前 maxCount 条。
     */
    private inline fun <reified T : LogEntry> trimByType(maxCount: Int) {
        val typedIndices = recentLogs.indices.filter { recentLogs[it] is T }
        if (typedIndices.size > maxCount) {
            // 从尾部开始删除（最旧的），避免索引偏移
            val toRemove = typedIndices.drop(maxCount)
            for (i in toRemove.reversed()) {
                recentLogs.removeAt(i)
            }
        }
    }

    fun getRecentLogs(): List<LogEntry> {
        synchronized(recentLogs) {
            return recentLogs.toList()
        }
    }

    fun getTextLogs(): List<LogEntry.TextLog> {
        synchronized(recentLogs) {
            return recentLogs.filterIsInstance<LogEntry.TextLog>()
        }
    }

    fun getRequestLogs(): List<LogEntry.RequestLog> {
        synchronized(recentLogs) {
            return recentLogs.filterIsInstance<LogEntry.RequestLog>()
        }
    }

    fun clear() {
        synchronized(recentLogs) {
            recentLogs.clear()
        }
    }
}

