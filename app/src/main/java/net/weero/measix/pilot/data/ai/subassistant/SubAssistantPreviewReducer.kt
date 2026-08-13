package net.weero.measix.pilot.data.ai.subassistant

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.text.BreakIterator
import java.util.Locale
import kotlin.uuid.Uuid

/**
 * 从本次 childTaskNodeId 范围的 UI 显示投影中，逆序提取 Target ASSISTANT 消息的顶层 UIMessagePart.Text。
 * 排除 Reasoning、Tool input/output、preset 和下一次 task。
 *
 * 返回逆序的 Text part 列表（最新在前）。
 */
fun extractTargetTextPartsInRange(
    messages: List<UIMessage>,
    childTaskNodeId: Uuid,
): List<String> {
    // 找到 childTaskNodeId 对应的消息索引
    val startIndex = messages.indexOfFirst { it.id == childTaskNodeId }
    if (startIndex == -1) return emptyList()

    // 范围终点：下一个 USER task 之前，或当前尾部
    var endIndex = messages.size
    for (i in (startIndex + 1) until messages.size) {
        if (messages[i].role == MessageRole.USER) {
            endIndex = i
            break
        }
    }

    // 从范围内逆序提取 ASSISTANT 消息的顶层 Text part
    val texts = mutableListOf<String>()
    for (i in (endIndex - 1) downTo startIndex) {
        val msg = messages[i]
        if (msg.role != MessageRole.ASSISTANT) continue
        // The function contract is newest-first. Parts inside one message therefore also need
        // reverse traversal; reducePreviewTexts() restores the original display order later.
        msg.parts.asReversed().forEach { part ->
            if (part is UIMessagePart.Text) {
                texts.add(part.text)
            }
        }
    }
    return texts
}

private const val MAX_BUFFER_CODEPOINTS = 2000
private const val MAX_TERMINAL_PREVIEW_CODEPOINTS = 480
private const val HEAD_SCAN_CODEPOINTS = 200

/**
 * 将文本列表从尾部向前收集，达到缓冲上限即停止。
 * 统一 CRLF，移除 NUL 和不可显示控制字符，连续空行最多保留一个空行。
 */
fun reducePreviewTexts(texts: List<String>): String {
    if (texts.isEmpty()) return ""

    val sb = StringBuilder()
    var codePointCount = 0

    for (text in texts) {
        if (codePointCount >= MAX_BUFFER_CODEPOINTS) break

        val cleaned = cleanPreviewText(text)
        if (cleaned.isEmpty()) continue

        val partCodePoints = cleaned.codePointCount(0, cleaned.length)
        if (codePointCount + partCodePoints > MAX_BUFFER_CODEPOINTS) {
            // 只取尾部需要的部分
            val remaining = MAX_BUFFER_CODEPOINTS - codePointCount
            val trimmed = takeTailByCodePoints(cleaned, remaining)
            if (sb.isNotEmpty()) {
                sb.insert(0, '\n')
            }
            sb.insert(0, trimmed)
            codePointCount += remaining
            break
        }

        if (sb.isNotEmpty()) {
            sb.insert(0, '\n')
            codePointCount += 1
        }
        sb.insert(0, cleaned)
        codePointCount += partCodePoints
    }

    if (codePointCount < MAX_BUFFER_CODEPOINTS) {
        return sb.toString()
    }

    // 超限时从理论切点向后寻找优先边界
    return addEllipsisAndTrim(sb.toString())
}

/**
 * 主入口：从消息列表计算当前预览文本。
 * 如果内容未变化（相同 revision），调用方应跳过 metadata 更新。
 */
fun computeSubAssistantPreview(
    messages: List<UIMessage>,
    childTaskNodeId: Uuid,
): String {
    val texts = extractTargetTextPartsInRange(messages, childTaskNodeId)
    return reducePreviewTexts(texts)
}

/**
 * Terminal 预览：保留 Target final 的开头摘要，限制行数和字符数。
 */
fun computeTerminalPreview(finalText: String, maxLines: Int = 3): String {
    val cleaned = cleanPreviewText(finalText).trim()
    if (cleaned.isEmpty()) return ""

    val lines = cleaned.split('\n').filter { it.isNotBlank() }
    val lineLimited = lines.take(maxLines).joinToString("\n")
    val codePointLimited = takeHeadByCodePoints(lineLimited, MAX_TERMINAL_PREVIEW_CODEPOINTS)
    val truncated = lines.size > maxLines || codePointLimited.length < lineLimited.length
    return if (truncated) "$codePointLimited…" else codePointLimited
}

internal fun takeHeadByCodePoints(text: String, maxCodePoints: Int): String {
    if (maxCodePoints <= 0) return ""
    if (text.codePointCount(0, text.length) <= maxCodePoints) return text

    var endIndex = 0
    var count = 0
    while (endIndex < text.length && count < maxCodePoints) {
        endIndex += Character.charCount(text.codePointAt(endIndex))
        count++
    }
    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
    while (endIndex > 0 && !isSafeGraphemeBoundary(text, endIndex, iterator)) {
        endIndex -= Character.charCount(text.codePointBefore(endIndex))
    }
    return text.substring(0, endIndex).trimEnd()
}

// ---- 内部工具 ----

internal fun cleanPreviewText(text: String): String {
    return text
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .replace(Regex("[\\u0000\\u0001-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]"), "")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trimEnd()
}

internal fun takeTailByCodePoints(text: String, maxCodePoints: Int): String {
    if (text.codePointCount(0, text.length) <= maxCodePoints) return text
    val endIdx = text.length
    var startIdx = endIdx
    var count = 0
    while (startIdx > 0 && count < maxCodePoints) {
        val charCount = Character.charCount(text.codePointBefore(startIdx))
        startIdx -= charCount
        count++
    }
    return text.substring(advanceToGraphemeBoundary(text, startIdx))
}

internal fun addEllipsisAndTrim(text: String): String {
    if (text.isEmpty()) return ""
    val totalCp = text.codePointCount(0, text.length)
    if (totalCp < MAX_BUFFER_CODEPOINTS) return text

    // 保留尾部 MAX_BUFFER_CODEPOINTS - 2 个 code point（为 "…\n" 留空间）
    val keepCount = MAX_BUFFER_CODEPOINTS - 2
    val tailStart = findBoundaryBefore(text, keepCount)
    val tail = text.substring(tailStart)
    return "…\n$tail"
}

internal fun findBoundaryBefore(text: String, targetCodePoints: Int): Int {
    // 从尾部向前数 targetCodePoints 个 code point，得到理论切点
    var pos = text.length
    var count = 0
    while (pos > 0 && count < targetCodePoints) {
        val charCount = Character.charCount(text.codePointBefore(pos))
        pos -= charCount
        count++
    }

    // 在首部 HEAD_SCAN_CODEPOINTS 范围内寻找优先边界：空行 → 换行 → 句末标点 → 空格
    val scanStart = pos
    val scanEnd = minOf(pos + HEAD_SCAN_CODEPOINTS, text.length)

    // 空行
    for (i in scanStart..scanEnd) {
        if (i + 1 < text.length && text[i] == '\n' && text[i + 1] == '\n') {
            return advanceToGraphemeBoundary(text, i + 2)
        }
    }

    // 换行
    for (i in scanStart..scanEnd) {
        if (i < text.length && text[i] == '\n') {
            return advanceToGraphemeBoundary(text, i + 1)
        }
    }

    // 句末标点
    val sentenceEnd = setOf('.', '!', '?', '。', '！', '？', '…')
    for (i in scanStart..scanEnd) {
        if (i < text.length && text[i] in sentenceEnd) {
            return advanceToGraphemeBoundary(text, i + 1)
        }
    }

    // 空格
    for (i in scanStart..scanEnd) {
        if (i < text.length && text[i] == ' ') {
            return advanceToGraphemeBoundary(text, i + 1)
        }
    }

    // 硬切
    return advanceToGraphemeBoundary(text, pos)
}

/**
 * Move a code-point boundary forward to the next extended-character boundary.
 *
 * [BreakIterator] covers combining scripts and platform Unicode rules. Some JDK/Android versions
 * still expose boundaries inside emoji ZWJ sequences or regional-indicator flags, so the explicit
 * no-break rules below repair those cases before accepting the platform boundary.
 */
internal fun advanceToGraphemeBoundary(text: String, startIndex: Int): Int {
    var index = startIndex.coerceIn(0, text.length)
    if (index in 1 until text.length && Character.isLowSurrogate(text[index]) &&
        Character.isHighSurrogate(text[index - 1])
    ) {
        index++
    }
    if (index == 0 || index == text.length) return index

    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
    while (index < text.length) {
        if (isSafeGraphemeBoundary(text, index, iterator)) return index
        index += Character.charCount(text.codePointAt(index))
    }
    return text.length
}

private fun isSafeGraphemeBoundary(
    text: String,
    index: Int,
    iterator: BreakIterator,
): Boolean {
    if (index <= 0 || index >= text.length) return true
    val previous = text.codePointBefore(index)
    val next = text.codePointAt(index)

    if (previous == '\r'.code && next == '\n'.code) return false
    if (previous == ZERO_WIDTH_JOINER || next == ZERO_WIDTH_JOINER) return false
    if (isGraphemeExtender(next)) return false
    if (isRegionalIndicator(previous) && isRegionalIndicator(next)) {
        var cursor = index
        var precedingRegionalIndicators = 0
        while (cursor > 0) {
            val codePoint = text.codePointBefore(cursor)
            if (!isRegionalIndicator(codePoint)) break
            precedingRegionalIndicators++
            cursor -= Character.charCount(codePoint)
        }
        if (precedingRegionalIndicators % 2 == 1) return false
    }
    return iterator.isBoundary(index)
}

private fun isGraphemeExtender(codePoint: Int): Boolean {
    val type = Character.getType(codePoint)
    return type == Character.NON_SPACING_MARK.toInt() ||
        type == Character.COMBINING_SPACING_MARK.toInt() ||
        type == Character.ENCLOSING_MARK.toInt() ||
        codePoint in 0xFE00..0xFE0F ||
        codePoint in 0xE0100..0xE01EF ||
        codePoint in 0x1F3FB..0x1F3FF ||
        codePoint in 0xE0020..0xE007F
}

private fun isRegionalIndicator(codePoint: Int): Boolean = codePoint in 0x1F1E6..0x1F1FF

private const val ZERO_WIDTH_JOINER = 0x200D
