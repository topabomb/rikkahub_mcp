package net.weero.measix.pilot.data.imggen

import net.weero.measix.pilot.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ImageGenerationFailureUiTest {
    @Test
    fun `classified reasons have dedicated copy`() {
        val generic = imageGenerationFailureStringRes("unknown")
        assertEquals(R.string.chat_message_tool_generate_image_failed, generic)
        val reasons = listOf(
            "content_blocked",
            "rate_limited",
            "quota_exhausted",
            "auth_failed",
            "permission_denied",
            "invalid_request",
            "provider_unavailable",
            "runtime_error",
            "provider_error",
        )
        val ids = reasons.map(::imageGenerationFailureStringRes)
        assertEquals(reasons.size, ids.toSet().size)
        ids.forEach { id ->
            assertNotEquals(generic, id)
        }
    }

    @Test
    fun `artifact_missing is an availability state not a failure reason`() {
        // artifact availability 与 execution failure 是两个维度：
        // artifact_missing 不再拥有 failure 专属文案，回落到通用失败文案。
        assertEquals(
            R.string.chat_message_tool_generate_image_failed,
            imageGenerationFailureStringRes("artifact_missing"),
        )
    }
}
