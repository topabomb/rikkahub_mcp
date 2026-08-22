package me.rerere.ai.ui

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.util.json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class MessageTest {

    // ==================== limitContext Tests ====================

    @Test
    fun `limitContext should preserve the original list when disabled or within threshold`() {
        val messages = createAlternatingMessages(10)

        assertEquals(messages, messages.limitContext(0))
        assertEquals(messages, messages.limitContext(-1))
        assertEquals(messages, messages.limitContext(10))
        assertEquals(emptyList<UIMessage>(), emptyList<UIMessage>().limitContext(10))
    }

    @Test
    fun `limitContext should keep a stable user-turn boundary within one step`() {
        val messages = createAlternatingMessages(30)

        val startsWithinStep = (11..14).map { size ->
            messages.subList(0, size).limitContext(10).first()
        }

        assertEquals(1, startsWithinStep.distinct().size)
        assertEquals(MessageRole.USER, startsWithinStep.first().role)
        assertEquals(messages[4], startsWithinStep.first())
        assertEquals(messages[10], messages.subList(0, 15).limitContext(10).first())
    }

    @Test
    fun `limitContext should align an assistant start to its preceding user turn`() {
        val messages = listOf(
            UIMessage.user("Old question"),
            UIMessage.assistant("Old answer"),
            UIMessage.user("Question with tool"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "call-1",
                        toolName = "test_tool",
                        input = "{}",
                        output = listOf(UIMessagePart.Text("result"))
                    )
                )
            ),
            UIMessage.assistant("Final answer"),
            UIMessage.user("Newest question")
        )

        // limit=4 produces a stepped start at index 4 (an assistant message);
        // the implementation must move it back to the user at index 2.
        val result = messages.limitContext(4)

        assertEquals(messages.subList(2, messages.size), result)
        assertEquals(MessageRole.USER, result.first().role)
    }

    @Test
    fun `findUserTurnStart should preserve complete turns`() {
        val messages = listOf(
            UIMessage.user("First question"),
            UIMessage.assistant("First answer"),
            UIMessage.user("Second question"),
            UIMessage.assistant("Second answer")
        )

        assertEquals(2, messages.findUserTurnStart(3))
        assertEquals(2, messages.findUserTurnStart(2))
        assertEquals(0, emptyList<UIMessage>().findUserTurnStart(3))
    }

    @Test
    fun `limitContext should remain a suffix and retain at least half the threshold`() {
        val messages = createAlternatingMessages(120)

        for (size in 11..120) {
            val source = messages.subList(0, size)
            val result = source.limitContext(10)

            assertTrue("size=$size retained ${result.size}", result.size >= 5)
            assertEquals(MessageRole.USER, result.first().role)
            assertEquals(source.takeLast(result.size), result)
        }
    }

    // ==================== isValidToUpload Tests ====================

    @Test
    fun `isValidToUpload should be true for non-empty reasoning with empty text`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "thinking"),
                UIMessagePart.Text("")
            )
        )

        assertTrue(message.isValidToUpload())
    }

    @Test
    fun `isValidToUpload should be false for blank reasoning with empty text`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "   "),
                UIMessagePart.Text("")
            )
        )

        assertFalse(message.isValidToUpload())
    }

    @Test
    fun `isValidToUpload should be true for non-empty text`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("ok"))
        )

        assertTrue(message.isValidToUpload())
    }

    @Test
    fun `isValidToUpload should keep tool-only message valid`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "search",
                    input = """{"q":"hello"}"""
                )
            )
        )

        assertTrue(message.isValidToUpload())
    }

    @Test
    fun `current assistant step should normalize out of order tool content and reasoning deltas`() {
        var messages = listOf(UIMessage.user("Use a tool"))

        messages = messages.handleMessageChunk(
            assistantChunk(
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "lookup",
                    input = "{}",
                )
            )
        )
        messages = messages.handleMessageChunk(assistantChunk(UIMessagePart.Text("Calling lookup")))
        messages = messages.handleMessageChunk(
            assistantChunk(UIMessagePart.Reasoning(reasoning = "Need lookup"))
        )

        val parts = messages.last().parts
        assertEquals(3, parts.size)
        assertTrue(parts[0] is UIMessagePart.Reasoning)
        assertTrue(parts[1] is UIMessagePart.Text)
        assertTrue(parts[2] is UIMessagePart.Tool)
        assertEquals("Need lookup", (parts[0] as UIMessagePart.Reasoning).reasoning)
        assertEquals("Calling lookup", (parts[1] as UIMessagePart.Text).text)
    }

    @Test
    fun `normalizing current assistant step should not move completed tool history`() {
        val completedTool = UIMessagePart.Tool(
            toolCallId = "call-1",
            toolName = "first",
            input = "{}",
            output = listOf(UIMessagePart.Text("first result")),
        )
        var messages = listOf(
            UIMessage.user("Use tools"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning(reasoning = "First reasoning"),
                    UIMessagePart.Text("First content"),
                    completedTool,
                ),
            ),
        )

        messages = messages.handleMessageChunk(
            assistantChunk(
                UIMessagePart.Tool(
                    toolCallId = "call-2",
                    toolName = "second",
                    input = "{}",
                )
            )
        )
        messages = messages.handleMessageChunk(assistantChunk(UIMessagePart.Text("Second content")))
        messages = messages.handleMessageChunk(
            assistantChunk(UIMessagePart.Reasoning(reasoning = "Second reasoning"))
        )

        val parts = messages.last().parts
        assertEquals(
            listOf(
                UIMessagePart.Reasoning::class,
                UIMessagePart.Text::class,
                UIMessagePart.Tool::class,
                UIMessagePart.Reasoning::class,
                UIMessagePart.Text::class,
                UIMessagePart.Tool::class,
            ),
            parts.map { it::class },
        )
        assertEquals("call-1", (parts[2] as UIMessagePart.Tool).toolCallId)
        assertEquals("call-2", (parts[5] as UIMessagePart.Tool).toolCallId)
    }

    @Test
    fun `blank tool delta should not merge into a completed tool step`() {
        val completedTool = UIMessagePart.Tool(
            toolCallId = "call-1",
            toolName = "first",
            input = "{}",
            output = listOf(UIMessagePart.Text("first result")),
        )
        var messages = listOf(
            UIMessage.user("Use another tool"),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(completedTool)),
        )

        messages = messages.handleMessageChunk(
            assistantChunk(
                UIMessagePart.Tool(
                    toolCallId = "",
                    toolName = "second",
                    input = "{",
                )
            )
        )

        val tools = messages.last().parts.filterIsInstance<UIMessagePart.Tool>()
        assertEquals(2, tools.size)
        assertEquals(completedTool, tools[0])
        assertEquals("second", tools[1].toolName)
    }

    @Test
    fun `reused nonblank tool id should not mutate a completed tool step`() {
        val completedTool = UIMessagePart.Tool(
            toolCallId = "reused-id",
            toolName = "first",
            input = "{}",
            output = listOf(UIMessagePart.Text("first result")),
        )
        var messages = listOf(
            UIMessage.user("Use another tool"),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(completedTool)),
        )

        messages = messages.handleMessageChunk(
            assistantChunk(
                UIMessagePart.Tool(
                    toolCallId = "reused-id",
                    toolName = "second",
                    input = "[]",
                )
            )
        )

        val tools = messages.last().parts.filterIsInstance<UIMessagePart.Tool>()
        assertEquals(2, tools.size)
        assertEquals(completedTool, tools[0])
        assertEquals("second", tools[1].toolName)
        assertEquals("[]", tools[1].input)
    }

    @Test
    fun `complete image urls should not be prefixed or concatenated`() {
        var messages = listOf(UIMessage.user("Draw two images"))

        messages = messages.handleMessageChunk(
            assistantChunk(UIMessagePart.Image(url = "data:image/jpeg;base64,AAA"))
        )
        messages = messages.handleMessageChunk(
            assistantChunk(UIMessagePart.Image(url = "data:image/png;base64,BBB"))
        )

        val images = messages.last().parts.filterIsInstance<UIMessagePart.Image>()
        assertEquals(2, images.size)
        assertEquals("data:image/jpeg;base64,AAA", images[0].url)
        assertEquals("data:image/png;base64,BBB", images[1].url)
    }

    @Test
    fun `android content and resource image urls should not be prefixed or concatenated`() {
        var messages = listOf(UIMessage.user("Show local images"))

        messages = messages.handleMessageChunk(
            assistantChunk(UIMessagePart.Image(url = "content://media/external/images/media/1"))
        )
        messages = messages.handleMessageChunk(
            assistantChunk(UIMessagePart.Image(url = "android.resource://net.weero.measix.pilot/drawable/icon"))
        )

        val images = messages.last().parts.filterIsInstance<UIMessagePart.Image>()
        assertEquals(2, images.size)
        assertEquals("content://media/external/images/media/1", images[0].url)
        assertEquals("android.resource://net.weero.measix.pilot/drawable/icon", images[1].url)
        assertEquals(
            "content://media/external/images/media/1",
            renderableImageUrl("content://media/external/images/media/1"),
        )
        assertEquals(
            "android.resource://net.weero.measix.pilot/drawable/icon",
            renderableImageUrl("android.resource://net.weero.measix.pilot/drawable/icon"),
        )
        assertTrue(isCompleteImageUrl("content://media/external/images/media/1"))
        assertTrue(isCompleteImageUrl("android.resource://net.weero.measix.pilot/drawable/icon"))
    }

    @Test
    fun `hasBase64Part walks nested tool output`() {
        val nested = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "c1",
                    toolName = "generate_image",
                    input = "{}",
                    output = listOf(UIMessagePart.Image(url = "data:image/png;base64,AAA")),
                )
            ),
        )
        val topLevel = UIMessage.assistant("ok").copy(
            parts = listOf(UIMessagePart.Image(url = "data:image/png;base64,BBB")),
        )
        val clean = UIMessage.assistant("ok").copy(
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "c1",
                    toolName = "generate_image",
                    input = "{}",
                    output = listOf(UIMessagePart.Image(url = "file:///tmp/a.png")),
                )
            ),
        )

        assertTrue(nested.hasBase64Part())
        assertTrue(topLevel.hasBase64Part())
        assertFalse(clean.hasBase64Part())
        val strippedMessage = nested.withoutUnpersistableBase64()
        assertFalse(strippedMessage.hasBase64Part())
        val placeholder = strippedMessage.getTools().single().output.single()
        assertTrue(placeholder is UIMessagePart.Text)
        assertEquals(
            MessageMediaFailureReason.PERSISTENCE_FAILED,
            placeholder.mediaFailureMetadataOrNull()?.reason,
        )
        assertEquals(1, strippedMessage.parts.countMediaPersistenceFailures())
    }

    @Test
    fun `replay safe projection removes terminal draft protocol state and keeps paired facts`() {
        val mediaFailure = mediaPersistenceFailurePart(
            UIMessagePart.Image(url = "data:image/png;base64,broken"),
        )
        val openTool = UIMessagePart.Tool(
            toolCallId = "open-call",
            toolName = "unfinished_tool",
            input = "{",
        )
        val completedTool = UIMessagePart.Tool(
            toolCallId = "done-call",
            toolName = "generate_image",
            input = "{}",
            output = listOf(mediaFailure),
        )
        val terminal = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("partial answer"),
                UIMessagePart.Reasoning(reasoning = "unfinished reasoning", finishedAt = null),
                openTool,
                completedTool,
                mediaFailure,
            ),
            providerMetadata = buildJsonObject { put("opaque", "draft") },
            terminalStatus = MessageTerminalStatus.FAILED,
            terminalReason = TurnTerminalReasons.PROVIDER_FAILED,
        )

        val projected = listOf(terminal).replaySafeProjection().single()

        assertEquals(null, projected.providerMetadata)
        assertEquals(null, projected.terminalStatus)
        assertEquals(null, projected.terminalReason)
        assertTrue(projected.parts.none { it is UIMessagePart.Reasoning })
        assertTrue(projected.parts.none { it === openTool })
        assertTrue(projected.toText().contains("partial answer"))
        assertTrue(projected.toText().contains("did not complete"))
        val projectedTool = projected.getTools().single()
        assertEquals("done-call", projectedTool.toolCallId)
        assertTrue(projectedTool.output.single().let { it is UIMessagePart.Text && it.text.contains("unavailable") })
        assertEquals(0, projected.parts.countMediaPersistenceFailures())
    }

    @Test
    fun `assistant chunk uses stable requested id and preserves an existing placeholder id`() {
        val requestedId = Uuid.random()
        var messages = listOf(UIMessage.user("hello"))

        messages = messages.handleMessageChunk(
            chunk = assistantChunk(UIMessagePart.Text("first")),
            assistantMessageId = requestedId,
        )
        assertEquals(requestedId, messages.last().id)

        messages = messages.handleMessageChunk(
            chunk = assistantChunk(UIMessagePart.Text(" second")),
            assistantMessageId = Uuid.random(),
        )
        assertEquals(requestedId, messages.last().id)
        assertEquals("first second", messages.last().toText())
    }

    @Test
    fun `media failure placeholder survives message serialization`() {
        val original = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                mediaPersistenceFailurePart(
                    UIMessagePart.Image(url = "data:image/png;base64,broken"),
                )
            ),
        )

        val restored = json.decodeFromString<UIMessage>(json.encodeToString(original))

        assertEquals(
            MessageMediaFailureReason.PERSISTENCE_FAILED,
            restored.parts.single().mediaFailureMetadataOrNull()?.reason,
        )
        assertEquals(MessageMediaKind.IMAGE, restored.parts.single().mediaFailureMetadataOrNull()?.mediaKind)
    }

    @Test
    fun `raw image fragments should append to the current data uri`() {
        var messages = listOf(UIMessage.user("Stream an image"))

        messages = messages.handleMessageChunk(assistantChunk(UIMessagePart.Image(url = "AAA")))
        messages = messages.handleMessageChunk(assistantChunk(UIMessagePart.Image(url = "BBB")))

        val images = messages.last().parts.filterIsInstance<UIMessagePart.Image>()
        assertEquals(1, images.size)
        assertEquals("data:image/png;base64,AAABBB", images[0].url)
    }

    @Test
    fun `openrouter reasoning details merge same id and append new items`() {
        val first = buildJsonArray {
            add(buildJsonObject {
                put("id", "rd-1")
                put("type", "reasoning.text")
                put("text", "hid")
            })
        }
        val second = buildJsonArray {
            add(buildJsonObject {
                put("id", "rd-1")
                put("type", "reasoning.text")
                put("text", "den")
            })
            add(buildJsonObject {
                put("id", "rd-2")
                put("type", "reasoning.summary")
                put("text", "sum")
            })
        }

        assertEquals(first, mergeOpenRouterReasoningDetails(first, null))
        assertEquals(
            buildJsonArray {
                add(buildJsonObject {
                    put("id", "rd-1")
                    put("type", "reasoning.text")
                    put("text", "hidden")
                })
                add(buildJsonObject {
                    put("id", "rd-2")
                    put("type", "reasoning.summary")
                    put("text", "sum")
                })
            },
            mergeOpenRouterReasoningDetails(first, second),
        )
    }

    @Test
    fun `openrouter reasoning details should accumulate across streamed chunks`() {
        var messages = listOf(UIMessage.user("Plan then call a tool"))

        messages = messages.handleMessageChunk(
            assistantChunk(
                reasoningWithDetails(
                    text = "hidden ",
                    details = buildJsonArray {
                        add(buildJsonObject {
                            put("id", "rd-1")
                            put("type", "reasoning.text")
                            put("text", "hidden ")
                            put("index", 0)
                        })
                    },
                )
            )
        )
        messages = messages.handleMessageChunk(
            assistantChunk(
                reasoningWithDetails(
                    text = "plan",
                    details = buildJsonArray {
                        add(buildJsonObject {
                            put("id", "rd-1")
                            put("type", "reasoning.text")
                            put("text", "plan")
                            put("index", 0)
                        })
                        add(buildJsonObject {
                            put("id", "rd-2")
                            put("type", "reasoning.summary")
                            put("text", "summary")
                            put("index", 1)
                        })
                    },
                )
            )
        )
        messages = messages.handleMessageChunk(
            assistantChunk(
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "lookup",
                    input = "{}",
                )
            )
        )

        val reasoning = messages.last().parts.filterIsInstance<UIMessagePart.Reasoning>().single()
        assertEquals("hidden plan", reasoning.reasoning)
        assertEquals(
            buildJsonArray {
                add(buildJsonObject {
                    put("id", "rd-1")
                    put("type", "reasoning.text")
                    put("text", "hidden plan")
                    put("index", 0)
                })
                add(buildJsonObject {
                    put("id", "rd-2")
                    put("type", "reasoning.summary")
                    put("text", "summary")
                    put("index", 1)
                })
            },
            reasoning.metadataAs<OpenRouterReasoningMetadata>()?.reasoningDetails,
        )
    }

    private fun reasoningWithDetails(
        text: String,
        details: kotlinx.serialization.json.JsonArray,
    ): UIMessagePart.Reasoning {
        return UIMessagePart.Reasoning(reasoning = text).also { part ->
            part.metadata = OpenRouterReasoningMetadata(reasoningDetails = details).toMetadata()
        }
    }

    private fun assistantChunk(vararg parts: UIMessagePart) = MessageChunk(
        id = "chunk",
        model = "test-model",
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList()),
                message = null,
                finishReason = null,
            )
        ),
    )

    private fun createAlternatingMessages(count: Int): List<UIMessage> = List(count) { index ->
        if (index % 2 == 0) {
            UIMessage.user("Question $index")
        } else {
            UIMessage.assistant("Answer $index")
        }
    }
}
