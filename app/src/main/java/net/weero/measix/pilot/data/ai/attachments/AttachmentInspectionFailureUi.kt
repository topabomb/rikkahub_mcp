package net.weero.measix.pilot.data.ai.attachments

import net.weero.measix.pilot.R

/**
 * `inspect_attachments` 失败 reason → 本地化文案映射（UI 折叠标题 / 详情共用）。
 *
 * Provider 细分 reason 与 `me.rerere.ai.util.ProviderFailureKind` 的 reason 字面一致；
 * 未识别 reason 回落通用失败文案。按 AGENTS.md 规则，底层错误诊断文案仅在
 * `values/strings.xml` 保留英文源语言。
 */
fun attachmentInspectionFailureStringRes(reason: String?): Int = when (reason) {
    AttachmentFailureReasons.INVALID_ATTACHMENTS ->
        R.string.chat_message_tool_inspection_failed_invalid_arguments

    AttachmentFailureReasons.ATTACHMENT_NOT_FOUND,
    AttachmentFailureReasons.UNSUPPORTED_ATTACHMENT_TYPE,
    AttachmentFailureReasons.ATTACHMENT_TOO_LARGE,
    AttachmentFailureReasons.ATTACHMENT_READ_FAILED,
    AttachmentFailureReasons.ATTACHMENT_RESOLUTION_UNAVAILABLE,
        -> R.string.chat_message_tool_inspection_failed_attachment

    AttachmentFailureReasons.INSPECTION_MODEL_UNAVAILABLE ->
        R.string.chat_message_tool_inspection_failed_model_unavailable

    AttachmentFailureReasons.RATE_LIMITED ->
        R.string.chat_message_tool_inspection_failed_rate_limited

    AttachmentFailureReasons.QUOTA_EXHAUSTED ->
        R.string.chat_message_tool_inspection_failed_quota_exhausted

    AttachmentFailureReasons.AUTH_FAILED ->
        R.string.chat_message_tool_inspection_failed_auth

    AttachmentFailureReasons.PERMISSION_DENIED ->
        R.string.chat_message_tool_inspection_failed_permission

    AttachmentFailureReasons.INVALID_REQUEST ->
        R.string.chat_message_tool_inspection_failed_invalid_request

    AttachmentFailureReasons.PROVIDER_UNAVAILABLE ->
        R.string.chat_message_tool_inspection_failed_unavailable

    AttachmentFailureReasons.PROVIDER_ERROR ->
        R.string.chat_message_tool_inspection_failed_provider

    AttachmentFailureReasons.CONTENT_BLOCKED ->
        R.string.chat_message_tool_inspection_failed_content_blocked

    AttachmentFailureReasons.RUNTIME_ERROR ->
        R.string.chat_message_tool_inspection_failed_runtime

    else -> R.string.chat_message_tool_inspection_failed
}
