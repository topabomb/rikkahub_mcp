package net.weero.measix.pilot.data.ai.transformers

import android.content.Context
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.RequestMediaCapabilities
import me.rerere.ai.core.ToolResourceLease
import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.model.Assistant
import kotlin.uuid.Uuid

/**
 * 单次请求内"本次请求自己合成"的消息身份集合。
 *
 * System、时间提醒、模式注入与 Workspace 提醒都是管线为本次请求生成或改写的内容，
 * 它们不能再被用户的 messageTemplate 二次包裹。
 *
 * 这个事实刻意**不进入** [UIMessage]：UIMessage 是持久化、Provider 与 UI 共用的模型，
 * 任何 `isSynthetic` 字段都会在 copy、反序列化、fork 与 replay 之后依赖偶然的对象路径，
 * 无法表达"本次请求"这一唯一生命周期。tracker 因此是 request-scoped capability：
 * 不序列化、不跨请求缓存、不是能力或状态源。
 */
class RequestMessageOriginTracker {
    private val syntheticIds = mutableSetOf<Uuid>()

    fun markSynthetic(messageId: Uuid) {
        syntheticIds += messageId
    }

    fun markSynthetic(message: UIMessage) {
        syntheticIds += message.id
    }

    fun isSynthetic(message: UIMessage): Boolean = isSynthetic(message.id)

    fun isSynthetic(messageId: Uuid): Boolean = messageId in syntheticIds

    /** 标记 [source] 中不存在于 [before] 的新增消息，供"注入型" transformer 复用。 */
    fun markNewMessages(before: List<UIMessage>, source: List<UIMessage>) {
        if (before.size == source.size && before === source) return
        val knownIds = before.mapTo(HashSet(before.size)) { it.id }
        source.forEach { message ->
            if (message.id !in knownIds) markSynthetic(message.id)
        }
    }
}

class TransformerContext(
    val context: Context,
    val model: Model,
    val assistant: Assistant,
    val settings: Settings,
    val requestOrigins: RequestMessageOriginTracker,
    val conversationModeInjectionIds: Set<Uuid> = emptySet(),
    val reportProcessingText: (String?) -> Unit = {},
    val workspaceCwd: String? = null,
    val mediaCapabilities: RequestMediaCapabilities = RequestMediaCapabilities.NONE,
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
 * 历史消息在流式期间 immutable——由 GenerationLoop 保证 `dropLast(1)` 部分逐 chunk
 * 零次进入本接口（由 StreamingTransformScopeTest 锁定）。
 *
 * 与请求级 [OutputMessageTransformer.transform] 的分工：
 *  - [transformStreaming]：每个流式 chunk 对最新累积消息的最后一条做视觉变换
 *    （think 标签 → reasoning、流式正则替换等），不落库、可重复调用；[previousProjection]
 *    是同一 active message 的上一次完整投影，只用于保留首次发生的投影事实；
 *  - [onStreamingFinish]：step 终态收口（reasoning 补 finishedAt、base64 → 本地文件等）。
 */
interface StreamingMessageTransformer {
    suspend fun transformStreaming(
        ctx: TransformerContext,
        message: UIMessage,
        previousProjection: UIMessage? = null,
    ): UIMessage = message

    suspend fun onStreamingFinish(
        ctx: TransformerContext,
        message: UIMessage,
        previousProjection: UIMessage? = null,
    ): UIMessage = message
}

suspend fun List<UIMessage>.transforms(
    transformers: List<MessageTransformer>,
    context: Context,
    model: Model,
    assistant: Assistant,
    settings: Settings,
    requestOrigins: RequestMessageOriginTracker,
    conversationModeInjectionIds: Set<Uuid> = emptySet(),
    reportProcessingText: (String?) -> Unit = {},
    workspaceCwd: String? = null,
    mediaCapabilities: RequestMediaCapabilities = RequestMediaCapabilities.NONE,
    registerUnpublishedResource: (ToolResourceLease) -> Unit,
): List<UIMessage> {
    val ctx = TransformerContext(
        context = context,
        model = model,
        assistant = assistant,
        settings = settings,
        requestOrigins = requestOrigins,
        conversationModeInjectionIds = conversationModeInjectionIds,
        reportProcessingText = reportProcessingText,
        workspaceCwd = workspaceCwd,
        mediaCapabilities = mediaCapabilities,
        registerUnpublishedResource = registerUnpublishedResource,
    )
    return transformers.fold(this) { acc, transformer ->
        transformer.transform(ctx, acc)
    }
}
