package me.rerere.tts.controller

/**
 * Split long text into speakable chunks with basic punctuation-aware grouping.
 */
class TextChunker(
    private val maxChunkLength: Int = 150
) {
    init {
        require(maxChunkLength > 1) { "max_tts_chunk_length_too_small" }
    }

    fun split(text: String): List<TtsChunk> {
        if (text.isBlank()) return emptyList()

        val paragraphs = text.split("\n\n")
        val punctuationRegex = "(?<=[。！？，、：;.!?:,\n])".toRegex()

        val chunks = paragraphs.flatMap { paragraph ->
            if (paragraph.isBlank()) emptyList() else {
                paragraph
                    .split(punctuationRegex)
                    .asSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .flatMap(::splitOversized)
                    .fold(mutableListOf<StringBuilder>()) { acc, seg ->
                        if (acc.isEmpty() || acc.last().length + seg.length > maxChunkLength) {
                            acc.add(StringBuilder(seg))
                        } else {
                            acc.last().append(seg)
                        }
                        acc
                    }
                    .map { it.toString() }
            }
        }

        return chunks.mapIndexed { index, value ->
            TtsChunk(text = value, index = index)
        }
    }

    /** Enforce the provider input bound without splitting a UTF-16 surrogate pair. */
    private fun splitOversized(value: String): Sequence<String> = sequence {
        var start = 0
        while (start < value.length) {
            var end = (start + maxChunkLength).coerceAtMost(value.length)
            if (end < value.length && Character.isHighSurrogate(value[end - 1]) && Character.isLowSurrogate(value[end])) {
                end--
            }
            yield(value.substring(start, end))
            start = end
        }
    }
}

data class TtsChunk(
    val id: java.util.UUID = java.util.UUID.randomUUID(),
    val index: Int,
    val text: String
)

