package net.weero.measix.pilot.architecture

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.GenerationChunk
import net.weero.measix.pilot.data.ai.GenerationHandler
import net.weero.measix.pilot.data.ai.attachments.AttachmentResolver
import net.weero.measix.pilot.data.ai.transformers.OutputMessageTransformer
import net.weero.measix.pilot.data.ai.transformers.StreamingMessageTransformer
import net.weero.measix.pilot.data.ai.transformers.TransformerContext
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.repository.MemoryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式单消息通道范围契约测试。
 *
 * 断言：5000 chunk 流式期间历史消息进入 `transformStreaming` 的次数为 0——
 * 仅 active assistant 消息（流式最后一条）进入；历史消息保持引用不变（immutable）。
 */
class StreamingTransformScopeTest {

    private class CountingStreamingTransformer : OutputMessageTransformer, StreamingMessageTransformer {
        val callsPerMessageId = ConcurrentHashMap<Uuid, AtomicLong>()

        override suspend fun transformStreaming(
            ctx: TransformerContext,
            message: UIMessage,
            previousProjection: UIMessage?,
        ): UIMessage {
            callsPerMessageId.getOrPut(message.id) { AtomicLong() }.incrementAndGet()
            return message
        }
    }

    @Test
    fun `history messages never enter transformStreaming during 5000 chunk stream`() = runTest {
        val chunkCount = 5_000
        val counting = CountingStreamingTransformer()

        val model = Model(modelId = "test-model", displayName = "Test Model")
        val providerSetting = ProviderSetting.OpenAI(models = listOf(model))
        val providerManager = mockk<ProviderManager>()
        val provider = mockk<Provider<ProviderSetting.OpenAI>>()
        every { providerManager.getProviderByType(any<ProviderSetting.OpenAI>()) } returns provider

        val assistantMessage = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
        coEvery {
            provider.streamText(providerSetting = providerSetting, messages = any(), params = any())
        } answers {
            flow {
                for (i in 0 until chunkCount) {
                    emit(
                        MessageChunk(
                            id = "stream",
                            model = model.modelId,
                            choices = listOf(
                                UIMessageChoice(
                                    index = 0,
                                    delta = UIMessage(
                                        role = MessageRole.ASSISTANT,
                                        parts = listOf(UIMessagePart.Text("chunk $i ")),
                                    ),
                                    message = null,
                                    finishReason = null,
                                )
                            ),
                        )
                    )
                }
                emit(
                    MessageChunk(
                        id = "stream",
                        model = model.modelId,
                        choices = listOf(
                            UIMessageChoice(
                                index = 0,
                                delta = null,
                                message = null,
                                finishReason = "stop",
                            )
                        ),
                    )
                )
            }
        }

        val handler = GenerationHandler(
            context = mockk<Context>(relaxed = true),
            providerManager = providerManager,
            json = Json,
            memoryRepo = mockk<MemoryRepository>(relaxed = true),
            attachmentResolver = mockk<AttachmentResolver>(relaxed = true),
        )
        val assistant = Assistant(enableMemory = false, streamOutput = true)
        val userMessage = UIMessage.user("hello")

        val chunks = handler.generateText(
            settings = Settings(providers = listOf(providerSetting), assistants = listOf(assistant)),
            model = model,
            messages = listOf(userMessage, assistantMessage),
            inputTransformers = emptyList(),
            outputTransformers = listOf(counting),
            assistant = assistant,
            assistantMessageId = assistantMessage.id,
            maxSteps = 1,
        ).toList()

        // 流式期间仅 active assistant 消息进入 transformStreaming
        // （5000 个 text chunk + 1 个 finishReason=stop 收尾 chunk 各触发一次 onUpdateMessages）
        assertEquals(setOf(assistantMessage.id), counting.callsPerMessageId.keys)
        assertEquals((chunkCount + 1).toLong(), counting.callsPerMessageId[assistantMessage.id]?.get())

        // 历史消息零次进入
        assertTrue(!counting.callsPerMessageId.containsKey(userMessage.id))

        // 历史消息保持引用不变（immutable 证据）：首个 emit 与终态 emit 的历史部分同一实例
        val emitted = chunks.filterIsInstance<GenerationChunk.Messages>()
        assertTrue(emitted.isNotEmpty())
        val firstEmit = emitted.first()
        val lastEmit = emitted.last()
        assertTrue(firstEmit.messages.isNotEmpty())
        assertTrue(lastEmit.messages.isNotEmpty())
        assertTrue(firstEmit.messages.first() === userMessage)
        assertTrue(lastEmit.messages.first() === userMessage)
    }
}
