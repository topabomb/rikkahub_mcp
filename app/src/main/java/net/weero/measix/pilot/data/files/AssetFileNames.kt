package net.weero.measix.pilot.data.files

import java.util.concurrent.ThreadLocalRandom

/** Generates names only; durable owners check collisions and reserve their payload paths. */
internal object AssetFileNames {
    private const val LOWERCASE = "0123456789abcdefghijklmnopqrstuvwxyz"
    private const val MIXED_CASE = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

    fun candidates(): List<String> = candidates { bound -> ThreadLocalRandom.current().nextLong(bound) }

    internal fun candidates(randomBelow: (Long) -> Long): List<String> = listOf(
        encode(randomBelow(2_176_782_336L), LOWERCASE, 6, 2_176_782_336L),
        encode(randomBelow(78_364_164_096L), LOWERCASE, 7, 78_364_164_096L),
        encode(randomBelow(2_821_109_907_456L), LOWERCASE, 8, 2_821_109_907_456L),
        encode(randomBelow(218_340_105_584_896L), MIXED_CASE, 8, 218_340_105_584_896L),
    )

    fun fileName(stem: String, extension: String, ordinal: Int = 1): String {
        require(stem.matches(Regex("[0-9a-z]{6,8}|[0-9a-zA-Z]{8}")))
        require(extension.matches(Regex("[a-z0-9]{1,10}")))
        require(ordinal > 0)
        return if (ordinal == 1) "$stem.$extension" else "$stem-$ordinal.$extension"
    }

    private fun encode(value: Long, alphabet: String, width: Int, bound: Long): String {
        require(value in 0 until bound) { "Asset name random value is out of range" }
        var remaining = value
        val result = CharArray(width)
        for (index in result.lastIndex downTo 0) {
            result[index] = alphabet[(remaining % alphabet.length).toInt()]
            remaining /= alphabet.length
        }
        return result.concatToString()
    }
}
