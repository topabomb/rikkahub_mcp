package net.weero.measix.pilot.data.ai.attachments

import net.weero.measix.pilot.R
import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentInspectionFailureUiTest {
    @Test
    fun `classified reasons have dedicated copy`() {
        val generic = attachmentInspectionFailureStringRes("unknown")
        assertEquals(R.string.chat_message_tool_inspection_failed, generic)

        val attachmentResolution = R.string.chat_message_tool_inspection_failed_attachment
        val reasonToRes = mapOf(
            AttachmentFailureReasons.INVALID_ATTACHMENTS to
                R.string.chat_message_tool_inspection_failed_invalid_arguments,
            AttachmentFailureReasons.ATTACHMENT_NOT_FOUND to attachmentResolution,
            AttachmentFailureReasons.UNSUPPORTED_ATTACHMENT_TYPE to attachmentResolution,
            AttachmentFailureReasons.UNSAFE_ATTACHMENT_URL to attachmentResolution,
            AttachmentFailureReasons.ATTACHMENT_FETCH_FAILED to attachmentResolution,
            AttachmentFailureReasons.ATTACHMENT_RESOLUTION_UNAVAILABLE to attachmentResolution,
            AttachmentFailureReasons.INSPECTION_MODEL_UNAVAILABLE to
                R.string.chat_message_tool_inspection_failed_model_unavailable,
            AttachmentFailureReasons.RATE_LIMITED to
                R.string.chat_message_tool_inspection_failed_rate_limited,
            AttachmentFailureReasons.QUOTA_EXHAUSTED to
                R.string.chat_message_tool_inspection_failed_quota_exhausted,
            AttachmentFailureReasons.AUTH_FAILED to
                R.string.chat_message_tool_inspection_failed_auth,
            AttachmentFailureReasons.PERMISSION_DENIED to
                R.string.chat_message_tool_inspection_failed_permission,
            AttachmentFailureReasons.INVALID_REQUEST to
                R.string.chat_message_tool_inspection_failed_invalid_request,
            AttachmentFailureReasons.PROVIDER_UNAVAILABLE to
                R.string.chat_message_tool_inspection_failed_unavailable,
            AttachmentFailureReasons.PROVIDER_ERROR to
                R.string.chat_message_tool_inspection_failed_provider,
            AttachmentFailureReasons.CONTENT_BLOCKED to
                R.string.chat_message_tool_inspection_failed_content_blocked,
            AttachmentFailureReasons.RUNTIME_ERROR to
                R.string.chat_message_tool_inspection_failed_runtime,
        )
        reasonToRes.forEach { (reason, res) ->
            assertEquals("reason [$reason]", res, attachmentInspectionFailureStringRes(reason))
        }
    }

    @Test
    fun `fallback and empty-output reasons use the generic copy`() {
        assertEquals(
            R.string.chat_message_tool_inspection_failed,
            attachmentInspectionFailureStringRes(AttachmentFailureReasons.INSPECTION_FAILED),
        )
        assertEquals(
            R.string.chat_message_tool_inspection_failed,
            attachmentInspectionFailureStringRes(null),
        )
    }
}
