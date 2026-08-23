package net.weero.measix.pilot.data.ai.transformers

import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.data.files.FilesManager
import org.koin.java.KoinJavaComponent.getKoin

object Base64ImageToLocalFileTransformer : OutputMessageTransformer, StreamingMessageTransformer {
    // 流式期间不转换（chunk 可能携带不完整 base64）；终态统一落盘
    override suspend fun transformStreaming(
        ctx: TransformerContext,
        message: UIMessage,
    ): UIMessage = message

    override suspend fun onStreamingFinish(
        ctx: TransformerContext,
        message: UIMessage,
    ): UIMessage {
        val filesManager = getKoin().get<FilesManager>()
        return filesManager.convertBase64ImagePartToLocalFile(message)
    }
}
