package net.weero.measix.pilot.data.ai.transformers

import net.weero.measix.pilot.data.files.ArtifactReadLease
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.service.turn.TurnAssistantSnapshot
import net.weero.measix.pilot.service.turn.TurnPromptSnapshot

import android.content.Context
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.RequestMediaCapabilities
import me.rerere.ai.core.ToolResourceLease
import me.rerere.ai.ui.UIMessage

class TransformerContext(
    val realmAccess: RealmAccess,
    val context: Context,
    val model: Model,
    val assistant: TurnAssistantSnapshot,
    val promptInputs: TurnPromptSnapshot,
    val requestOrigins: RequestMessageOriginTracker,
    val reportProcessingText: (String?) -> Unit = {},
    val mediaCapabilities: RequestMediaCapabilities = RequestMediaCapabilities.NONE,
    val registerUnpublishedResource: (ToolResourceLease) -> Unit,
    val artifactReads: ArtifactReadLease? = null,
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
 * 历史消息在流式期间 immutable——由 TurnRunner 保证 `dropLast(1)` 部分逐 chunk
 * 零次进入本接口（由 TurnStreamProjectionTest 锁定）。
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
    realmAccess: RealmAccess,
    transformers: List<MessageTransformer>,
    context: Context,
    model: Model,
    assistant: TurnAssistantSnapshot,
    promptInputs: TurnPromptSnapshot,
    requestOrigins: RequestMessageOriginTracker,
    reportProcessingText: (String?) -> Unit = {},
    mediaCapabilities: RequestMediaCapabilities = RequestMediaCapabilities.NONE,
    registerUnpublishedResource: (ToolResourceLease) -> Unit,
    artifactReads: ArtifactReadLease? = null,
): List<UIMessage> {
    val ctx = TransformerContext(
        realmAccess = realmAccess,
        context = context,
        model = model,
        assistant = assistant,
        promptInputs = promptInputs,
        requestOrigins = requestOrigins,
        reportProcessingText = reportProcessingText,
        mediaCapabilities = mediaCapabilities,
        registerUnpublishedResource = registerUnpublishedResource,
        artifactReads = artifactReads,
    )
    return transformers.fold(this) { acc, transformer ->
        transformer.transform(ctx, acc)
    }
}

/** Output transforms may change only the currently open sampling region, never committed Step content. */
internal fun UIMessage.openStepPartsStart(): Int? {
    if (role != me.rerere.ai.core.MessageRole.ASSISTANT) return null
    val marker = parts.indexOfLast { it is me.rerere.ai.ui.UIMessagePart.Step }
    require(marker >= 0) { "Assistant output requires an explicit Step" }
    return (marker + 1).takeIf { (parts[marker] as me.rerere.ai.ui.UIMessagePart.Step).outcome == null }
}
