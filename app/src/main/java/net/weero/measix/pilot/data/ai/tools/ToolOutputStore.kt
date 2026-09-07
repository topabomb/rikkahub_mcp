package net.weero.measix.pilot.data.ai.tools

import com.google.re2j.Pattern
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid
import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.core.ToolOutputPolicy
import me.rerere.ai.core.ToolResourceLease
import me.rerere.ai.ui.ToolOutputArchive
import me.rerere.ai.ui.ToolOutputArchiveRef
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.request.ContextBudget
import net.weero.measix.pilot.data.ai.request.estimateStableTextTokens
import net.weero.measix.pilot.data.db.entity.ArtifactOrigin
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.FileFolders
import net.weero.measix.pilot.data.files.OwnedArtifact
import net.weero.measix.pilot.data.files.requireDiscarded

/** Tool Result 压缩暂存与 conversation-scoped 有界 read/grep 的唯一 owner。 */
class ToolOutputStore(private val artifactStore: ArtifactStore) {
    internal data class CompactionReplacement(
        val marker: UIMessagePart.Text,
        val archive: ToolOutputArchive?,
    )

    internal data class StagedCompactionBatch(
        val replacements: Map<ToolCallLocator, CompactionReplacement>,
        /** 整批 Artifact 的单一所有权交接；空计划或纯可再生折叠没有 lease。 */
        val lease: ToolResourceLease?,
    )

    /** 暂存全部压缩候选；只有可归档文本创建 Artifact，可再生回查结果只生成 marker。 */
    internal suspend fun stageCompaction(plan: ToolOutputCompactionPlan): StagedCompactionBatch {
        if (plan.candidates.isEmpty()) return StagedCompactionBatch(emptyMap(), null)
        require(plan.candidates.map { it.locator }.distinct().size == plan.candidates.size) {
            "Tool output compaction plan contains duplicate locators"
        }
        require(
            plan.netReclaimedEstimatedTokens == plan.candidates.sumOf { it.netReclaimEstimatedTokens }
        ) {
            "Tool output compaction plan net reclaim does not match its candidates"
        }
        val owned = mutableListOf<OwnedArtifact>()
        return try {
            val replacements = linkedMapOf<ToolCallLocator, CompactionReplacement>()
            for (candidate in plan.candidates) {
                require(candidate.terminalStatus == "completed" || candidate.terminalStatus == "failed") {
                    "Only completed or failed Tool Results can be compacted"
                }
                require(candidate.characters == candidate.text.length.toLong()) {
                    "Tool output compaction candidate character count does not match"
                }
                require(candidate.originalEstimatedTokens == estimateStableTextTokens(candidate.text)) {
                    "Tool output compaction candidate token estimate does not match"
                }
                val expectedMarkerTokens = when (candidate.outputPolicy) {
                    ToolOutputPolicy.ARCHIVABLE_TEXT -> estimatedToolOutputMarkerTokens(
                        candidate.terminalStatus,
                        candidate.text,
                    )
                    ToolOutputPolicy.REGENERABLE_TEXT -> estimateStableTextTokens(
                        REGENERABLE_TOOL_OUTPUT_FOLDED_MARKER,
                    )
                    ToolOutputPolicy.PRESERVE -> error("Preserved Tool Result cannot be compacted")
                }
                require(candidate.markerEstimatedTokens == expectedMarkerTokens) {
                    "Tool output compaction marker token estimate does not match"
                }
                require(
                    candidate.netReclaimEstimatedTokens ==
                        candidate.originalEstimatedTokens - candidate.markerEstimatedTokens &&
                        candidate.netReclaimEstimatedTokens >=
                        ContextBudget.TOOL_OUTPUT_MINIMUM_RESULT_NET_RECLAIM_ESTIMATED_TOKENS
                ) {
                    "Tool output compaction candidate net reclaim does not match"
                }
                val canonical = canonicalizeToolOutput(candidate.text)
                val archive = if (candidate.outputPolicy == ToolOutputPolicy.ARCHIVABLE_TEXT) {
                    val artifact = artifactStore.createText(
                        text = canonical,
                        displayName = "tool_output.txt",
                        mimeType = MIME_TYPE,
                        folder = FileFolders.TOOL_OUTPUTS,
                        origin = ArtifactOrigin.SYSTEM,
                    )
                    owned += artifact
                    ToolOutputArchive(
                        ref = artifact.entity.id,
                        artifact = ToolOutputArchiveRef(artifact.entity.relativePath, MIME_TYPE),
                        characters = canonical.length.toLong(),
                        lines = virtualLineCount(canonical),
                    )
                } else {
                    null
                }
                val marker = UIMessagePart.Text(if (archive == null) {
                    REGENERABLE_TOOL_OUTPUT_FOLDED_MARKER
                } else {
                    buildToolOutputMarker(archive, candidate.terminalStatus, canonical)
                })
                replacements[candidate.locator] = CompactionReplacement(
                    marker = marker,
                    archive = archive,
                )
            }
            StagedCompactionBatch(
                replacements = replacements,
                lease = owned.takeIf { it.isNotEmpty() }?.let { artifacts -> ToolResourceLease(
                    // 同一 checkpoint 建立的全部归档 root 必须一次校验、一次交接，不能部分发布。
                    publish = { artifactStore.publishAllUnpublished(artifacts) },
                    discard = { discardArchiveBatch(artifacts, "tool output archive rollback") },
                ) },
            )
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                try {
                    discardArchiveBatch(owned, "tool output archive staging rollback")
                } catch (cleanup: Throwable) {
                    error.addSuppressed(cleanup)
                }
            }
            throw error
        }
    }

    /** 逆序释放整批 creation pin，并把所有清理错误聚合为一个失败。 */
    private suspend fun discardArchiveBatch(owned: List<OwnedArtifact>, operation: String) {
        var failure: Throwable? = null
        owned.asReversed().forEach { artifact ->
            try {
                artifactStore.discardUnpublished(artifact).requireDiscarded(operation)
            } catch (error: Throwable) {
                val previous = failure
                if (previous == null) failure = error else previous.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    /** 在会话授权范围内流式读取稳定虚拟行，并在格式化前预留 header 字节。 */
    suspend fun read(conversationId: Uuid, ref: Long, startLine: Int, lineCount: Int): ToolOutputReadResult {
        if (ref <= 0) return ToolOutputReadResult.Unavailable
        val from = startLine.coerceAtLeast(1)
        val requested = lineCount.coerceIn(1, ToolOutputProtocol.TOOL_OUTPUT_MAX_READ_LINES)
        val contentByteLimit = ToolOutputProtocol.TOOL_OUTPUT_MAX_RESPONSE_BYTES -
            ToolOutputProtocol.TOOL_OUTPUT_RESPONSE_FORMAT_RESERVE_BYTES
        val scanContext = currentCoroutineContext()
        val scanned = artifactStore.withToolOutputText(conversationId, ref) { reader ->
            var total = 0
            var bytes = 0
            var byteLimited = false
            val collected = mutableListOf<ToolOutputLine>()
            reader.forEachLine { physical ->
                scanContext.ensureActive()
                for (chunk in virtualLinesOf(physical)) {
                    total++
                    if (!byteLimited && total in from until from + requested) {
                        val cost = utf8Length("$total: $chunk\n")
                        if (bytes + cost > contentByteLimit) byteLimited = true
                        else { bytes += cost; collected += ToolOutputLine(total, chunk) }
                    }
                }
            }
            Triple(total, collected, byteLimited)
        } ?: return ToolOutputReadResult.Unavailable
        val (totalLines, collected, byteLimited) = scanned
        return ToolOutputReadResult.Success(
            from, collected.lastOrNull()?.number ?: from - 1, totalLines, collected,
            (from + collected.size).takeIf { it <= totalLines }, byteLimited,
        )
    }

    /** 在会话授权范围内用 RE2 流式搜索；只保留命中附近的小窗口，不把全文载入内存。 */
    suspend fun grep(
        conversationId: Uuid,
        ref: Long,
        pattern: String,
        ignoreCase: Boolean,
        contextLines: Int,
        maxMatches: Int,
    ): ToolOutputGrepResult {
        if (pattern.isBlank()) return ToolOutputGrepResult.InvalidPattern
        if (pattern.length > ToolOutputProtocol.TOOL_OUTPUT_MAX_PATTERN_CHARS) {
            return ToolOutputGrepResult.InvalidPattern
        }
        if (ref <= 0) return ToolOutputGrepResult.Unavailable
        val context = contextLines.coerceIn(0, ToolOutputProtocol.TOOL_OUTPUT_MAX_CONTEXT_LINES)
        val matchLimit = maxMatches.coerceIn(1, ToolOutputProtocol.TOOL_OUTPUT_MAX_GREP_MATCHES)
        val regex = try {
            Pattern.compile(pattern, if (ignoreCase) Pattern.CASE_INSENSITIVE else 0)
        } catch (_: Exception) {
            return ToolOutputGrepResult.InvalidPattern
        }
        val scanContext = currentCoroutineContext()
        val contentByteLimit = ToolOutputProtocol.TOOL_OUTPUT_MAX_RESPONSE_BYTES -
            ToolOutputProtocol.TOOL_OUTPUT_RESPONSE_FORMAT_RESERVE_BYTES
        val scanned = artifactStore.withToolOutputText(conversationId, ref) { reader ->
            val preceding = ArrayDeque<ToolOutputLine>(context)
            val blocks = mutableListOf<ToolOutputGrepBlock>()
            var activeLines: MutableList<ToolOutputLine>? = null
            var activeEndLine = 0
            var totalLines = 0
            var matches = 0
            val acceptedMatchLines = mutableSetOf<Int>()
            var bytes = 0
            var byteLimited = false
            var matchLimited = false

            fun appendBounded(line: ToolOutputLine) {
                if (byteLimited) return
                val cost = utf8Length("${line.number}: ${line.text}\n")
                if (bytes + cost > contentByteLimit) {
                    byteLimited = true
                } else {
                    bytes += cost
                    activeLines?.add(line)
                }
            }

            fun flushActive() {
                val lines = activeLines
                if (!lines.isNullOrEmpty()) {
                    blocks += ToolOutputGrepBlock(lines.first().number, lines.last().number, lines.toList())
                }
                activeLines = null
                activeEndLine = 0
            }

            reader.forEachLine { physical ->
                scanContext.ensureActive()
                virtualLinesOf(physical).forEach { text ->
                    val line = ToolOutputLine(++totalLines, text)
                    val isMatch = regex.matcher(text).find()
                    val acceptedMatch = isMatch && matches < matchLimit
                    val matchStart = if (acceptedMatch) (line.number - context).coerceAtLeast(1) else null

                    if (activeLines != null && line.number > activeEndLine &&
                        (matchStart == null || matchStart > activeEndLine + 1)
                    ) {
                        flushActive()
                    }
                    if (isMatch) {
                        if (acceptedMatch) {
                            matches++
                            acceptedMatchLines += line.number
                            if (activeLines == null) {
                                activeLines = mutableListOf()
                                preceding.filter { it.number >= requireNotNull(matchStart) }
                                    .forEach(::appendBounded)
                            }
                            activeEndLine = maxOf(activeEndLine, line.number + context)
                        } else {
                            matchLimited = true
                        }
                    }
                    if (activeLines != null && line.number <= activeEndLine) {
                        appendBounded(line)
                    }
                    if (context > 0) {
                        preceding.addLast(line)
                        while (preceding.size > context) preceding.removeFirst()
                    }
                }
            }
            flushActive()
            val returnedLineNumbers = blocks.asSequence()
                .flatMap { it.lines.asSequence() }
                .map(ToolOutputLine::number)
                .toSet()
            GrepScan(
                totalLines,
                acceptedMatchLines.count { it in returnedLineNumbers },
                blocks,
                byteLimited || matchLimited,
            )
        } ?: return ToolOutputGrepResult.Unavailable
        return ToolOutputGrepResult.Success(scanned.blocks, scanned.matches, scanned.totalLines,
            scanned.limited)
    }

    private data class GrepScan(val totalLines: Int, val matches: Int, val blocks: List<ToolOutputGrepBlock>, val limited: Boolean)
}

data class ToolOutputLine(val number: Int, val text: String)
sealed interface ToolOutputReadResult {
    data object Unavailable : ToolOutputReadResult
    data class Success(
        val startLine: Int, val endLine: Int, val totalLines: Int,
        val lines: List<ToolOutputLine>, val nextStartLine: Int?, val byteLimited: Boolean,
    ) : ToolOutputReadResult
}
data class ToolOutputGrepBlock(val startLine: Int, val endLine: Int, val lines: List<ToolOutputLine>)
sealed interface ToolOutputGrepResult {
    data object Unavailable : ToolOutputGrepResult
    data object InvalidPattern : ToolOutputGrepResult
    data class Success(
        val blocks: List<ToolOutputGrepBlock>, val matchCount: Int, val totalLines: Int,
        val truncated: Boolean,
    ) : ToolOutputGrepResult
}

/** 生成稳定、单行的归档标记；只有失败结果携带一小段尾部诊断。 */
internal fun buildToolOutputMarker(archive: ToolOutputArchive, terminalStatus: String, canonicalText: String): String = buildString {
    require(terminalStatus == "completed" || terminalStatus == "failed") {
        "Unsupported tool output terminal status: $terminalStatus"
    }
    append("[Archived tool result: ref=").append(archive.ref)
        .append("; status=").append(terminalStatus)
        .append("; lines=").append(archive.lines)
        // 2026-9-2 15:01：追加 canonical 字符数，供模型评估回查成本；一致性校验与 token 估算
        // 共用本 builder 自动跟随，历史已落库 marker 保持旧格式不变。
        .append("; chars=").append(archive.characters)
    if (terminalStatus == "failed") {
        canonicalText.split('\n').lastOrNull { it.isNotBlank() }
            ?.takeLast(ToolOutputProtocol.TOOL_OUTPUT_MARKER_TAIL_CHARS)
            ?.replace('"', '\'')?.replace('\t', ' ')?.let { append("; tail=\"").append(it).append('"') }
    }
    append(']')
}

/** 使用最长合法 Artifact id 估算 marker；实际 ref 不会更长，可用于提前证明净缩短。 */
internal fun estimatedToolOutputMarkerTokens(terminalStatus: String, rawText: String): Long =
    canonicalizeToolOutput(rawText).let { canonical -> estimateStableTextTokens(
        buildToolOutputMarker(
            archive = ToolOutputArchive(
                ref = Long.MAX_VALUE,
                artifact = ToolOutputArchiveRef("tool_outputs/estimate.txt", MIME_TYPE),
                characters = canonical.length.toLong(),
                lines = virtualLineCount(canonical),
            ),
            terminalStatus = terminalStatus,
            canonicalText = canonical,
        ),
    ) }

/** 可再生回查结果的稳定无 Artifact marker；原调用入参仍保留在同一 Tool part。 */
internal const val REGENERABLE_TOOL_OUTPUT_FOLDED_MARKER = "[Derived tool result folded]"

internal fun canonicalizeToolOutput(raw: String): String = ANSI_PATTERN.matcher(
    raw.replace("\r\n", "\n").replace('\r', '\n')
).replaceAll("")
internal fun virtualLinesOf(physicalLine: String): List<String> =
    if (physicalLine.codePointCount(0, physicalLine.length) <= ToolOutputProtocol.TOOL_OUTPUT_VIRTUAL_LINE_CHARS) {
        listOf(physicalLine)
    } else {
        buildList {
            var start = 0
            var remainingCodePoints = physicalLine.codePointCount(0, physicalLine.length)
            while (remainingCodePoints > 0) {
                val chunkCodePoints = minOf(
                    remainingCodePoints,
                    ToolOutputProtocol.TOOL_OUTPUT_VIRTUAL_LINE_CHARS,
                )
                val end = physicalLine.offsetByCodePoints(start, chunkCodePoints)
                add(physicalLine.substring(start, end))
                start = end
                remainingCodePoints -= chunkCodePoints
            }
        }
    }
internal fun virtualLineCount(canonicalText: String): Int =
    if (canonicalText.isEmpty()) {
        0
    } else {
        canonicalText.split('\n')
            .let { lines -> if (canonicalText.endsWith('\n')) lines.dropLast(1) else lines }
            .sumOf { virtualLinesOf(it).size }
    }
private fun utf8Length(text: String): Int = text.toByteArray(Charsets.UTF_8).size
/** Tool Output Artifact 唯一允许的 MIME。 */
private const val MIME_TYPE = "text/plain"
private val ANSI_PATTERN = Pattern.compile(
    "\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)|\u001B[@-Z\\\\-_]",
)
