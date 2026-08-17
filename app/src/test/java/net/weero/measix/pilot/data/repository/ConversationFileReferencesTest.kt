package net.weero.measix.pilot.data.repository

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationFileReferencesTest {
    @Test
    fun `escapeLikeNeedle escapes wildcard characters`() {
        assertEquals(
            "file:///data/user/0/net.weero\\_app/upload/a\\%b.txt",
            ConversationFileReferences.escapeLikeNeedle("file:///data/user/0/net.weero_app/upload/a%b.txt"),
        )
    }

    @Test
    fun `likeNeedleForUrl matches JSON escaped backslash`() {
        assertEquals(
            "file:///tmp/a\\\\\\\\b.txt",
            ConversationFileReferences.likeNeedleForUrl("file:///tmp/a\\b.txt"),
        )
    }

    @Test
    fun `undecodable json is treated as retained`() {
        val url = "file:///tmp/keep.txt"
        assertTrue(
            ConversationFileReferences.isUrlRetained(url, listOf("not-json"), JsonInstant),
        )
    }

    @Test
    fun `decodeFileUrls reads nested tool output files`() {
        val json = JsonInstant.encodeToString(
            listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Tool(
                            toolCallId = "t1",
                            toolName = "workspace_read_file",
                            input = "{}",
                            output = listOf(
                                UIMessagePart.Image(url = "file:///tmp/nested.png"),
                                UIMessagePart.Text("not a file"),
                            ),
                        ),
                        UIMessagePart.Document(
                            url = "https://example.com/remote.pdf",
                            fileName = "remote.pdf",
                            mime = "application/pdf",
                        ),
                    ),
                ),
            ),
        )

        val urls = ConversationFileReferences.decodeFileUrls(json, JsonInstant)
        assertEquals(setOf("file:///tmp/nested.png"), urls)
    }

    @Test
    fun `isUrlRetained verifies decoded parts not just substring`() {
        val url = "file:///tmp/keep.txt"
        val matching = JsonInstant.encodeToString(
            listOf(
                UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(
                        UIMessagePart.Document(
                            url = url,
                            fileName = "keep.txt",
                            mime = "text/plain",
                        ),
                    ),
                ),
            ),
        )
        val mentionedOnly = JsonInstant.encodeToString(
            listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text("see file:///tmp/keep.txt")),
                ),
            ),
        )

        assertTrue(ConversationFileReferences.isUrlRetained(url, listOf(matching), JsonInstant))
        assertFalse(ConversationFileReferences.isUrlRetained(url, listOf(mentionedOnly), JsonInstant))
        assertFalse(ConversationFileReferences.isUrlRetained(url, emptyList(), JsonInstant))
    }

    @Test
    fun `metadata artifact refs count as file references`() {
        val relative = "upload/gen-1.png"
        val artifactJson = buildJsonObject {
            put("version", 1)
            put("relativePath", relative)
            put("mimeType", "image/png")
        }
        val json = JsonInstant.encodeToString(
            listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Tool(
                            toolCallId = "t1",
                            toolName = "assistant_call",
                            input = "{}",
                            output = listOf(UIMessagePart.Text("{}")),
                            metadata = buildJsonObject {
                                // generate_image 的顶层 artifact 键
                                put("artifact", artifactJson)
                                put("sub_assistant_call", buildJsonObject {
                                    put("artifacts", buildJsonArray {
                                        add(buildJsonObject {
                                            put("ref", "attachment:11111111-1111-1111-1111-111111111111")
                                            put("type", "image")
                                            put("mime", "image/png")
                                            put("artifact", artifactJson)
                                        })
                                    })
                                })
                            },
                        ),
                    ),
                ),
            ),
        )

        val tokens = ConversationFileReferences.decodeFileReferenceTokens(json, JsonInstant)!!
        assertTrue(relative in tokens)

        // 相对路径 token 命中即视为保留（Master 卡片只有 metadata 引用、没有 file:// URL）
        assertTrue(
            ConversationFileReferences.isFileRetained(
                setOf("file:///data/user/0/app/files/$relative", relative),
                listOf(json),
                JsonInstant,
            ),
        )
        // 无命中不保留
        assertFalse(
            ConversationFileReferences.isFileRetained(
                setOf("file:///data/user/0/app/files/$relative", relative),
                emptyList(),
                JsonInstant,
            ),
        )
        // URL 形态仍照旧生效
        assertTrue(
            ConversationFileReferences.isFileRetained(
                setOf("file:///data/user/0/app/files/$relative"),
                listOf(
                    JsonInstant.encodeToString(
                        listOf(
                            UIMessage(
                                role = MessageRole.USER,
                                parts = listOf(
                                    UIMessagePart.Image(url = "file:///data/user/0/app/files/$relative"),
                                ),
                            ),
                        ),
                    ),
                ),
                JsonInstant,
            ),
        )
    }
}