package net.weero.measix.pilot.data.ai.attachments

object AttachmentFailureReasons {
    const val INVALID_ATTACHMENTS = "invalid_attachments"
    const val ATTACHMENT_NOT_FOUND = "attachment_not_found"
    const val UNSUPPORTED_ATTACHMENT_TYPE = "unsupported_attachment_type"
    const val UNSAFE_ATTACHMENT_URL = "unsafe_attachment_url"
    const val ATTACHMENT_FETCH_FAILED = "attachment_fetch_failed"
    const val ATTACHMENT_INPUT_UNAVAILABLE = "attachment_input_unavailable"
}

const val MAX_ASSISTANT_CALL_ATTACHMENTS = 4
