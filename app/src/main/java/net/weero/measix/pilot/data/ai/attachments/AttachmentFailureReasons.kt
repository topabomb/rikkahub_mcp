package net.weero.measix.pilot.data.ai.attachments

object AttachmentFailureReasons {
    const val INVALID_ATTACHMENTS = "invalid_attachments"
    const val ATTACHMENT_NOT_FOUND = "attachment_not_found"
    const val UNSUPPORTED_ATTACHMENT_TYPE = "unsupported_attachment_type"
    const val ATTACHMENT_TOO_LARGE = "attachment_too_large"
    const val ATTACHMENT_READ_FAILED = "attachment_read_failed"
    const val UNSAFE_ATTACHMENT_URL = "unsafe_attachment_url"
    const val ATTACHMENT_FETCH_FAILED = "attachment_fetch_failed"

    /** 工具执行环境未提供受管图片路径读取能力。 */
    const val ATTACHMENT_RESOLUTION_UNAVAILABLE = "attachment_resolution_unavailable"
    const val INSPECTION_MODEL_UNAVAILABLE = "inspection_model_unavailable"
    const val INSPECTION_FAILED = "inspection_failed"

    /**
     * `inspect_attachments` Provider 调用失败的细分 reason，由统一分类器
     * [me.rerere.ai.util.classifyProviderFailure] 产出，与
     * [me.rerere.ai.util.ProviderFailureKind] 的 reason 字面一致（reason 表可全局搜索）。
     * `inspection_failed` 仅保留给「识别输出为空」兜底。
     */
    const val CONTENT_BLOCKED = "content_blocked"
    const val RATE_LIMITED = "rate_limited"
    const val QUOTA_EXHAUSTED = "quota_exhausted"
    const val AUTH_FAILED = "auth_failed"
    const val PERMISSION_DENIED = "permission_denied"
    const val INVALID_REQUEST = "invalid_request"
    const val PROVIDER_UNAVAILABLE = "provider_unavailable"
    const val PROVIDER_ERROR = "provider_error"
    const val RUNTIME_ERROR = "runtime_error"
}

const val MAX_ASSISTANT_CALL_ATTACHMENTS = 4

/** `inspect_attachments` 单次调用的输入上限。 */
const val MAX_INSPECTION_ATTACHMENTS = 4
