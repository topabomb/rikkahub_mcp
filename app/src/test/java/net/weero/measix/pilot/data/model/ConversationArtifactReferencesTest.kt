package net.weero.measix.pilot.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.db.entity.ArtifactReferenceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationArtifactReferencesTest {
    @Test
    fun `only a valid v1 tool output archive becomes a durable root`() {
        val valid = metadata(
            """{"ref":7,"artifact":{"relativePath":"tool_outputs/a.txt","mimeType":"text/plain"},"characters":12,"lines":2}""",
        )
        val reference = listOf(message(valid)).collectArtifactReferences().single()
        assertEquals("tool_outputs/a.txt", reference.token)
        assertEquals(ArtifactReferenceType.TOOL_OUTPUT, reference.type)
        assertEquals(7L, reference.expectedArtifactId)

        val empty = metadata(
            """{"ref":8,"artifact":{"relativePath":"tool_outputs/empty.txt","mimeType":"text/plain"},"characters":0,"lines":0}""",
        )
        assertEquals(8L, listOf(message(empty)).collectArtifactReferences().single().expectedArtifactId)

        listOf(
            metadata(
                """{"ref":0,"artifact":{"relativePath":"tool_outputs/a.txt","mimeType":"text/plain"},"characters":12,"lines":2}""",
            ),
            metadata(
                """{"ref":7,"artifact":{"relativePath":"upload/a.txt","mimeType":"text/plain"},"characters":12,"lines":2}""",
            ),
            metadata(
                """{"ref":7,"artifact":{"relativePath":"tool_outputs/../a.txt","mimeType":"text/plain"},"characters":12,"lines":2}""",
            ),
            metadata(
                """{"ref":7,"artifact":{"relativePath":"tool_outputs/a.txt","mimeType":"application/json"},"characters":12,"lines":2}""",
            ),
            buildJsonObject {
                put("tool_runtime", Json.parseToJsonElement(
                    """{"version":2,"archive":{"ref":7,"artifact":{"relativePath":"tool_outputs/a.txt","mimeType":"text/plain"},"characters":12,"lines":2}}""",
                ))
            },
            buildJsonObject {
                put("tool_runtime", Json.parseToJsonElement(
                    """{"version":1,"unexpected":true,"archive":{"ref":7,"artifact":{"relativePath":"tool_outputs/a.txt","mimeType":"text/plain"},"characters":12,"lines":2}}""",
                ))
            },
            metadata(
                """{"ref":7,"artifact":{"relativePath":"tool_outputs/a.txt","mimeType":"text/plain"},"characters":0,"lines":-1}""",
            ),
        ).forEach { invalid ->
            assertTrue(listOf(message(invalid)).collectArtifactReferences().isEmpty())
        }
    }

    private fun metadata(archive: String) = buildJsonObject {
        put("tool_runtime", Json.parseToJsonElement("""{"archive":$archive}"""))
    }

    private fun message(metadata: kotlinx.serialization.json.JsonObject) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Tool("call", "tool", "{}", metadata = metadata)),
    )
}
