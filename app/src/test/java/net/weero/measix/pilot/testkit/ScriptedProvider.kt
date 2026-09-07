package net.weero.measix.pilot.testkit

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.EmbeddingGenerationResult
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.RequestMediaCapabilities
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.core.ModelRequestMessage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

/**
 * Deterministic Provider double for Runner-level tests. It replays scripted attempts without
 * touching real wire formats; adapter serialization stays covered by `:ai` contract tests.
 */
sealed class ProviderAttempt {
    /** Streaming response emitting [chunks]; optionally fails after them or before the first one. */
    data class Stream(
        val chunks: List<MessageChunk>,
        val failAfter: Throwable? = null,
        /** When set, emission suspends before the first chunk until the test completes it. */
        val beforeFirstOutput: CompletableDeferred<Unit>? = null,
    ) : ProviderAttempt()

    /** Single non-streaming response. */
    data class Complete(val chunk: MessageChunk) : ProviderAttempt()

    /** Fails before any output arrives; the Step stays the same Step. */
    data class Fail(val error: Throwable) : ProviderAttempt()
}

/** One recorded dispatch of [ScriptedProvider]. */
data class ScriptedDispatch(
    val messages: List<ModelRequestMessage>,
    val params: TextGenerationParams,
)

class ScriptedProvider(
    private val attempts: List<ProviderAttempt>,
    val mediaCapabilities: RequestMediaCapabilities = RequestMediaCapabilities.NONE,
) : Provider<ProviderSetting.OpenAI> {
    val dispatches = mutableListOf<ScriptedDispatch>()
    private var nextAttempt = 0

    override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> =
        error("not supported by ScriptedProvider")

    override fun requestMediaCapabilities(
        providerSetting: ProviderSetting.OpenAI,
        model: Model,
    ): RequestMediaCapabilities = mediaCapabilities

    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<ModelRequestMessage>,
        params: TextGenerationParams,
    ): MessageChunk {
        dispatches += ScriptedDispatch(messages, params)
        return when (val attempt = takeAttempt()) {
            is ProviderAttempt.Complete -> attempt.chunk
            is ProviderAttempt.Fail -> throw attempt.error
            is ProviderAttempt.Stream -> attempt.chunks.lastOrNull()
                ?: error("ScriptedProvider streaming attempt has no terminal chunk")
        }
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<ModelRequestMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk> {
        dispatches += ScriptedDispatch(messages, params)
        val attempt = takeAttempt()
        return when (attempt) {
            is ProviderAttempt.Stream -> flow {
                attempt.beforeFirstOutput?.await()
                attempt.chunks.forEach { emit(it) }
                attempt.failAfter?.let { throw it }
            }
            is ProviderAttempt.Complete -> flow { emit(attempt.chunk) }
            is ProviderAttempt.Fail -> flow { throw attempt.error }
        }
    }

    override suspend fun generateEmbedding(
        providerSetting: ProviderSetting.OpenAI,
        params: EmbeddingGenerationParams,
    ): EmbeddingGenerationResult = error("not supported by ScriptedProvider")

    private fun takeAttempt(): ProviderAttempt {
        check(nextAttempt < attempts.size) {
            "ScriptedProvider exhausted after $nextAttempt attempts"
        }
        return attempts[nextAttempt++]
    }
}

/** Wires [provider] into a ProviderManager double for OpenAI settings, like production lookup. */
fun scriptedProviderManager(provider: Provider<ProviderSetting.OpenAI>): ProviderManager {
    val manager = mockk<ProviderManager>()
    every { manager.getProviderByType(any<ProviderSetting.OpenAI>()) } returns provider
    return manager
}

// ---------------------------------------------------------------------------
// Chunk builders: keep Runner test bodies readable at the transcript level.
// ---------------------------------------------------------------------------

fun textDelta(text: String): MessageChunk = deltaChunk(listOf(UIMessagePart.Text(text)))

fun reasoningDelta(reasoning: String): MessageChunk =
    deltaChunk(listOf(UIMessagePart.Reasoning(reasoning)))

fun toolCallDelta(
    callId: String,
    name: String,
    arguments: String,
): MessageChunk = deltaChunk(
    listOf(UIMessagePart.Tool(localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = callId, toolName = name, input = arguments)),
)

fun finishChunk(reason: String = "stop"): MessageChunk = MessageChunk(
    id = "scripted",
    model = "scripted-model",
    choices = listOf(UIMessageChoice(index = 0, delta = null, message = null, finishReason = reason)),
)

fun deltaChunk(
    parts: List<UIMessagePart>,
    usage: me.rerere.ai.core.ProviderUsageSnapshot? = null,
): MessageChunk = MessageChunk(
    id = "scripted",
    model = "scripted-model",
    choices = listOf(
        UIMessageChoice(
            index = 0,
            delta = UIMessage(id = Uuid.random(), role = MessageRole.ASSISTANT, parts = parts),
            message = null,
            finishReason = null,
        ),
    ),
    usage = usage,
)
