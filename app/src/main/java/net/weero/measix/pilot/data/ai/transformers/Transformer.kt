package net.weero.measix.pilot.data.ai.transformers

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.ai.provider.Model
import me.rerere.ai.core.ToolResourceLease
import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.model.Assistant
import kotlin.uuid.Uuid

class TransformerContext(
    val context: Context,
    val model: Model,
    val assistant: Assistant,
    val settings: Settings,
    val conversationModeInjectionIds: Set<Uuid> = emptySet(),
    val processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
    val workspaceCwd: String? = null,
    val registerUnpublishedResource: (ToolResourceLease) -> Unit,
)

interface MessageTransformer {
    /**
     * 消息转换器，用于对消息进行转换
     *
     * 对于输入消息，消息会转换被提供给API模块
     *
     * 对于输出消息，会对消息输出chunk进行转换
     */
    suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages
    }
}

interface InputMessageTransformer : MessageTransformer

interface OutputMessageTransformer : MessageTransformer

/**
 * 流式变换器：只处理 active assistant 消息（流式期间最后一条）。
 *
 * 历史消息在流式期间 immutable——由 GenerationHandler 保证 `dropLast(1)` 部分逐 chunk
 * 零次进入本接口（由 StreamingTransformScopeTest 锁定）。
 *
 * 与请求级 [OutputMessageTransformer.transform] 的分工：
 *  - [transformStreaming]：每个流式 chunk 对最新累积消息的最后一条做视觉变换
 *    （think 标签 → reasoning、流式正则替换等），不落库、可重复调用；
 *  - [onStreamingFinish]：step 终态收口（reasoning 补 finishedAt、base64 → 本地文件等）。
 */
interface StreamingMessageTransformer {
    suspend fun transformStreaming(ctx: TransformerContext, message: UIMessage): UIMessage = message

    suspend fun onStreamingFinish(ctx: TransformerContext, message: UIMessage): UIMessage = message
}

suspend fun List<UIMessage>.transforms(
    transformers: List<MessageTransformer>,
    context: Context,
    model: Model,
    assistant: Assistant,
    settings: Settings,
    conversationModeInjectionIds: Set<Uuid> = emptySet(),
    processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
    workspaceCwd: String? = null,
    registerUnpublishedResource: (ToolResourceLease) -> Unit,
): List<UIMessage> {
    val ctx = TransformerContext(
        context = context,
        model = model,
        assistant = assistant,
        settings = settings,
        conversationModeInjectionIds = conversationModeInjectionIds,
        processingStatus = processingStatus,
        workspaceCwd = workspaceCwd,
        registerUnpublishedResource = registerUnpublishedResource,
    )
    return transformers.fold(this) { acc, transformer ->
        transformer.transform(ctx, acc)
    }
}
