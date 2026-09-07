package net.weero.measix.pilot.service.turn

import android.content.Context
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ProviderUsageSnapshot
import me.rerere.ai.core.ToolResourceLease
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
import net.weero.measix.pilot.data.ai.attachments.AttachmentResolver
import net.weero.measix.pilot.data.ai.tools.ToolOutputStore
import net.weero.measix.pilot.data.ai.transformers.OutputMessageTransformer
import net.weero.measix.pilot.data.ai.transformers.StreamingMessageTransformer
import net.weero.measix.pilot.data.ai.transformers.TransformerContext
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.model.Assistant

/**
 * Turn 运行族测试（[TurnRunner]/[StepRunner]/[ToolBatchRunner]/[TurnCommitter]）共享的 Provider 桩与夹具。
 * 只装配被测 loop 需要的最小协作者；各 owner 测试按需覆盖 responseMessage / toolOutputStore / usage。
 */
internal data class ProviderHarness(
    val handler: TurnRunner,
    val settings: Settings,
    val model: Model,
    val assistant: Assistant,
    val providerMessages: CapturingSlot<List<ModelRequestMessage>>,
    val providerParams: CapturingSlot<TextGenerationParams>,
)

/** 非流式 Provider 桩：generateText 固定返回 [responseMessage]（finishReason=stop）。 */
internal fun createProviderHarness(
    responseMessage: UIMessage = UIMessage.assistant("done"),
    toolOutputStore: ToolOutputStore = mockk(relaxed = true),
): ProviderHarness {
    val model = Model(modelId = "test-model", displayName = "Test Model")
    val providerSetting = ProviderSetting.OpenAI(models = listOf(model))
    val providerManager = mockk<ProviderManager>()
    val provider = mockk<Provider<ProviderSetting.OpenAI>>()
    every {
        providerManager.getProviderByType(any<ProviderSetting.OpenAI>())
    } returns provider
    every { provider.requestMediaCapabilities(any(), any()) } returns RequestMediaCapabilities.NONE
    val providerMessages = slot<List<ModelRequestMessage>>()
    val providerParams = slot<TextGenerationParams>()
    coEvery {
        provider.generateText(
            providerSetting = providerSetting,
            messages = capture(providerMessages),
            params = capture(providerParams),
        )
    } returns MessageChunk(
        id = "response",
        model = model.modelId,
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = null,
                message = responseMessage,
                finishReason = "stop",
            )
        ),
    )
    val assistant = Assistant(enableMemory = false, streamOutput = false)
    return ProviderHarness(
        handler = TurnRunner(
            context = mockk<Context>(relaxed = true),
            providerManager = providerManager,
            json = Json,
            attachmentResolver = mockk<AttachmentResolver>(relaxed = true),
            toolOutputStore = toolOutputStore,
        ),
        settings = Settings(
            providers = listOf(providerSetting),
            assistants = listOf(assistant),
        ),
        model = model,
        assistant = assistant,
        providerMessages = providerMessages,
        providerParams = providerParams,
    )
}

/** 流式 Provider 桩：streamText 逐段 emit 文本 delta 并附带 [snapshots]，末尾可抛 [failure]。 */
internal fun createUsageStreamHarness(
    failure: Throwable? = null,
    snapshots: List<ProviderUsageSnapshot> = listOf(
        ProviderUsageSnapshot(
            inputTokens = 100,
            outputTokens = 20,
            cacheReadInputTokens = 0,
            totalTokens = 120,
        ),
    ),
): ProviderHarness {
    val model = Model(modelId = "test-model", displayName = "Test Model")
    val providerSetting = ProviderSetting.OpenAI(models = listOf(model))
    val providerManager = mockk<ProviderManager>()
    val provider = mockk<Provider<ProviderSetting.OpenAI>>()
    every { providerManager.getProviderByType(providerSetting) } returns provider
    every { provider.requestMediaCapabilities(any(), any()) } returns RequestMediaCapabilities.NONE
    coEvery { provider.streamText(providerSetting, any(), any()) } returns flow {
        snapshots.forEachIndexed { index, snapshot ->
            emit(textDelta(if (index == 0) "partial" else " continued").copy(usage = snapshot))
        }
        failure?.let { throw it }
    }
    val assistant = Assistant(enableMemory = false, streamOutput = true)
    val toolOutputStore = mockk<ToolOutputStore>()
    coEvery { toolOutputStore.stageCompaction(any()) } returns ToolOutputStore.StagedCompactionBatch(
        replacements = emptyMap(),
        lease = null,
    )
    return ProviderHarness(
        handler = TurnRunner(
            context = mockk<Context>(relaxed = true),
            providerManager = providerManager,
            json = Json,
            attachmentResolver = mockk<AttachmentResolver>(relaxed = true),
            toolOutputStore = toolOutputStore,
        ),
        settings = Settings(providers = listOf(providerSetting), assistants = listOf(assistant)),
        model = model,
        assistant = assistant,
        providerMessages = slot(),
        providerParams = slot(),
    )
}

internal fun textDelta(text: String, finishReason: String? = null) = MessageChunk(
    id = "response",
    model = "test-model",
    choices = listOf(
        UIMessageChoice(
            index = 0,
            delta = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text))),
            message = null,
            finishReason = finishReason,
        ),
    ),
)

/** 注册一个未发布资源 lease 的输出变换器，用于验证 checkpoint 失败/取消时精确回滚。 */
internal fun terminalResourceTransformer(discarded: AtomicBoolean): OutputMessageTransformer = object :
    OutputMessageTransformer,
    StreamingMessageTransformer {
    override suspend fun onStreamingFinish(
        ctx: TransformerContext,
        message: UIMessage,
        previousProjection: UIMessage?,
    ): UIMessage {
        ctx.registerUnpublishedResource(
            ToolResourceLease(publish = {}, discard = { discarded.set(true) })
        )
        return message
    }
}
