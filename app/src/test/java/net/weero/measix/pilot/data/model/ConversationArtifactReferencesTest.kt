package net.weero.measix.pilot.data.model

import kotlin.uuid.Uuid
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ToolOutputPolicy
import me.rerere.ai.ui.ToolOutputArchive
import me.rerere.ai.ui.ToolOutputArchiveRef
import me.rerere.ai.ui.ToolRuntimeState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.db.entity.ArtifactReferenceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationArtifactReferencesTest {
    @Test
    fun `archived tool output becomes a durable TOOL_OUTPUT reference`() {
        val reference = listOf(messageWithArchive(7, "tool_outputs/a.txt")).collectArtifactReferences().single()
        assertEquals("tool_outputs/a.txt", reference.token)
        assertEquals(ArtifactReferenceType.TOOL_OUTPUT, reference.type)
        assertEquals(7L, reference.expectedArtifactId)
    }

    @Test
    fun `a tool without an archive contributes no durable reference`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call",
                    toolName = "tool",
                    input = "{}",
                ),
            ),
        )
        assertTrue(listOf(message).collectArtifactReferences().isEmpty())
    }

    private fun messageWithArchive(ref: Long, relativePath: String) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Tool(
                localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call",
                toolName = "tool",
                input = "{}",
                runtimeState = ToolRuntimeState(
                    outputPolicy = ToolOutputPolicy.ARCHIVABLE_TEXT,
                    archive = ToolOutputArchive(
                        ref = ref,
                        artifact = ToolOutputArchiveRef(relativePath, "text/plain"),
                        characters = 12,
                        lines = 2,
                    ),
                ),
            ),
        ),
    )
}
