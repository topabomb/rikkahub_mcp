package net.weero.measix.pilot.data.repository

import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.data.model.collectFileUrlStrings

/**
 * 删除会话时扫描文件引用的纯函数。
 * 不改变表结构：只对已有 message_node.messages JSON 做 LIKE 探测 + 精确反序列化校验。
 */
internal object ConversationFileReferences {
    fun escapeLikeNeedle(raw: String): String = buildString(raw.length) {
        raw.forEach { ch ->
            when (ch) {
                '\\', '%', '_' -> {
                    append('\\')
                    append(ch)
                }
                else -> append(ch)
            }
        }
    }

    /**
     * messages 列是 JSON 字符串。LIKE 必须按写入时的转义去匹配，
     * 否则路径里的 `\` 会漏检并误删仍被引用的文件。
     */
    fun likeNeedleForUrl(url: String): String = escapeLikeNeedle(jsonEscapeForString(url))

    fun jsonEscapeForString(raw: String): String = buildString(raw.length) {
        raw.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }

    fun decodeFileUrlsOrNull(messagesJson: String, json: Json): Set<String>? = runCatching {
        json.decodeFromString<List<UIMessage>>(messagesJson).collectFileUrlStrings()
    }.getOrNull()

    fun decodeFileUrls(messagesJson: String, json: Json): Set<String> =
        decodeFileUrlsOrNull(messagesJson, json).orEmpty()

    fun isUrlRetained(
        url: String,
        matchingMessagesJson: List<String>,
        json: Json,
    ): Boolean = matchingMessagesJson.any { blob ->
        val urls = decodeFileUrlsOrNull(blob, json)
        urls == null || url in urls
    }
}
