package net.weero.measix.pilot.data.ai.attachments

object AttachmentFailureReasons {
    const val INVALID_ATTACHMENTS = "invalid_attachments"
    const val ATTACHMENT_NOT_FOUND = "attachment_not_found"
    const val UNSUPPORTED_ATTACHMENT_TYPE = "unsupported_attachment_type"
    const val UNSAFE_ATTACHMENT_URL = "unsafe_attachment_url"
    const val ATTACHMENT_FETCH_FAILED = "attachment_fetch_failed"

    /**
     * 工具执行环境未提供附件解析能力（如无执行上下文的 fallback 路径）。
     * 与 ai 模块 [me.rerere.ai.core.ToolExecutionContext] 的默认 resolveAttachments
     * 返回值字面一致，保持 reason 表可全局搜索。
     */
    const val ATTACHMENT_RESOLUTION_UNAVAILABLE = "attachment_resolution_unavailable"
    const val INSPECTION_MODEL_UNAVAILABLE = "inspection_model_unavailable"
    const val INSPECTION_FAILED = "inspection_failed"
}

const val MAX_ASSISTANT_CALL_ATTACHMENTS = 4

/** `inspect_attachments` 单次调用的输入上限（设计文档 §8.4）。 */
const val MAX_INSPECTION_ATTACHMENTS = 4
