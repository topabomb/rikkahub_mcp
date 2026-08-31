package me.rerere.ai.provider

import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.util.HttpException

/**
 * A decoded response that ended unsuccessfully after producing content or usage.
 * The payload is data, never exception text; callers still observe the original failure semantics.
 */
class ProviderResponseException(
    val response: MessageChunk,
    cause: HttpException,
) : RuntimeException(cause.message, cause)
