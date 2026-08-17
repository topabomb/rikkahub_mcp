package net.weero.measix.pilot.data.imggen

import net.weero.measix.pilot.R

fun imageGenerationFailureStringRes(reason: String?): Int = when (reason) {
    "invalid_arguments" -> R.string.chat_message_tool_generate_image_failed_invalid_arguments
    "image_model_unavailable" -> R.string.chat_message_tool_generate_image_failed_model_unavailable
    "tool_revoked" -> R.string.chat_message_tool_generate_image_failed_revoked
    "image_model_changed" -> R.string.chat_message_tool_generate_image_failed_model_changed
    "content_blocked" -> R.string.chat_message_tool_generate_image_failed_content_blocked
    "rate_limited" -> R.string.chat_message_tool_generate_image_failed_rate_limited
    "quota_exhausted" -> R.string.chat_message_tool_generate_image_failed_quota_exhausted
    "auth_failed" -> R.string.chat_message_tool_generate_image_failed_auth
    "permission_denied" -> R.string.chat_message_tool_generate_image_failed_permission
    "invalid_request" -> R.string.chat_message_tool_generate_image_failed_invalid_request
    "provider_unavailable" -> R.string.chat_message_tool_generate_image_failed_unavailable
    "runtime_error" -> R.string.chat_message_tool_generate_image_failed_runtime
    "provider_error" -> R.string.chat_message_tool_generate_image_failed_provider
    "invalid_result" -> R.string.chat_message_tool_generate_image_failed_invalid_result
    "persistence_error" -> R.string.chat_message_tool_generate_image_failed_persistence
    "assistant_not_found" -> R.string.chat_message_tool_generate_image_failed_assistant_missing
    "artifact_missing" -> R.string.chat_message_tool_generate_image_failed_artifact_missing
    else -> R.string.chat_message_tool_generate_image_failed
}
