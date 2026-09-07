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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Stable transcript behavior on [UIMessage] / [UIMessagePart]: visibility, replay-safe projection,
 * media handling and pure metadata normalization. Streaming chunk-merge lives in the app
 * `StepOutputAccumulatorTest`; the retired `handleMessageChunk` free function is gone.
 */
class MessageTest {

    private fun toolPart(
        providerCallId: String = "call-1",
        name: String = "search",
        input: String = "{}",
        output: List<UIMessagePart> = emptyList(),
        interaction: ToolInteractionState = ToolInteractionState.NotRequired,
    ): UIMessagePart.Tool = UIMessagePart.Tool(
        localCallId = Uuid.random(),
        stepId = Uuid.random(),
        providerCallId = providerCallId,
        toolName = name,
        input = input,
        output = output,
        interactionState = interaction,
    )

    @Test
    fun `findUserTurnStart should preserve complete turns`() {
        val messages = listOf(
            UIMessage.user("First question"),
            UIMessage.assistant("First answer"),
            UIMessage.user("Second question"),
            UIMessage.assistant("Second answer"),
        )
        assertEquals(2, messages.findUserTurnStart(3))
        assertEquals(2, messages.findUserTurnStart(2))
        assertEquals(0, emptyList<UIMessage>().findUserTurnStart(3))
    }

    @Test
    fun `isValidToUpload should be true for non-empty reasoning with empty text`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Reasoning(reasoning = "thinking"), UIMessagePart.Text("")),
        )
        assertTrue(message.isValidToUpload())
    }

    @Test
    fun `isValidToUpload should be false for blank reasoning with empty text`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Reasoning(reasoning = "   "), UIMessagePart.Text("")),
        )
        assertFalse(message.isValidToUpload())
    }

    @Test
    fun `isValidToUpload should be true for non-empty text`() {
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("ok")))
        assertTrue(message.isValidToUpload())
    }

    @Test
    fun `isValidToUpload should keep tool-only message valid`() {
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(toolPart(name = "search", input = """{"q":"hello"}""")))
        assertTrue(message.isValidToUpload())
    }

    @Test
    fun `complete image urls are recognized and renderable`() {
        assertEquals("content://media/external/images/media/1", renderableImageUrl("content://media/external/images/media/1"))
        assertTrue(isCompleteImageUrl("content://media/external/images/media/1"))
        assertTrue(isCompleteImageUrl("android.resource://net.weero.measix.pilot/drawable/icon"))
    }

    @Test
    fun `hasBase64Part walks nested tool output`() {
        val nested = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(toolPart(name = "generate_image", output = listOf(UIMessagePart.Image(url = "data:image/png;base64,AAA")))),
        )
        val topLevel = UIMessage.assistant("ok").copy(parts = listOf(UIMessagePart.Image(url = "data:image/png;base64,BBB")))
        val clean = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(toolPart(name = "generate_image", output = listOf(UIMessagePart.Image(url = "file:///tmp/a.png")))),
        )
        assertTrue(nested.hasBase64Part())
        assertTrue(topLevel.hasBase64Part())
        assertFalse(clean.hasBase64Part())
        val strippedMessage = nested.withoutUnpersistableBase64()
        assertFalse(strippedMessage.hasBase64Part())
        val placeholder = strippedMessage.getTools().single().output.single()
        assertTrue(placeholder is UIMessagePart.Text)
        assertEquals(MessageMediaFailureReason.PERSISTENCE_FAILED, placeholder.mediaFailureMetadataOrNull()?.reason)
        assertEquals(1, strippedMessage.parts.countMediaPersistenceFailures())
    }

    @Test
    fun `replay safe projection removes terminal draft protocol state and keeps paired facts`() {
        val mediaFailure = mediaPersistenceFailurePart(UIMessagePart.Image(url = "data:image/png;base64,broken"))
        val openTool = toolPart(providerCallId = "open-call", name = "unfinished_tool", input = "{")
        val completedTool = toolPart(providerCallId = "done-call", name = "generate_image", output = listOf(mediaFailure))
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
            terminalDetail = "sanitized provider detail",
        )
        val projected = listOf(terminal).replaySafeProjection().single()
        assertNull(projected.providerMetadata)
        assertNull(projected.terminalStatus)
        assertNull(projected.terminalReason)
        assertNull(projected.terminalDetail)
        assertTrue(projected.parts.none { it is UIMessagePart.Reasoning })
        assertTrue(projected.toText().contains("partial answer"))
        assertTrue(projected.toText().contains("did not complete"))
        assertTrue(projected.getTools().isEmpty())
        assertEquals(0, projected.parts.countMediaPersistenceFailures())
    }

    @Test
    fun `terminal replay projection preserves reasoning in complete steps and drops tail reasoning`() {
        val completedTool = toolPart(name = "search", output = listOf(UIMessagePart.Text("result")))
        val terminal = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "Step 1 reasoning"),
                UIMessagePart.Text("Calling search"),
                completedTool,
                UIMessagePart.Reasoning(reasoning = "Incomplete final reasoning"),
                UIMessagePart.Text("Partial final answer"),
            ),
            terminalStatus = MessageTerminalStatus.FAILED,
            terminalReason = TurnTerminalReasons.PROVIDER_FAILED,
            terminalDetail = "detail",
        )
        val projected = terminal.replaySafeProjection()!!
        val projection = projected.providerReplayProjection
        assertNotNull(projection)
        assertTrue(projection!!.hasIncompleteTail)
        assertEquals(3, projection.completePartCount)
        assertTrue(projected.parts.take(3).any { it is UIMessagePart.Reasoning })
        val tailParts = projected.parts.drop(3)
        assertTrue(tailParts.none { it is UIMessagePart.Reasoning })
        assertTrue(tailParts.any { it is UIMessagePart.Text && it.text.contains("Partial final answer") })
    }

    @Test
    fun `terminal replay with only partial reasoning and text has zero complete prefix`() {
        val terminal = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Reasoning(reasoning = "unfinished"), UIMessagePart.Text("partial")),
            terminalStatus = MessageTerminalStatus.INCOMPLETE,
            terminalReason = TurnTerminalReasons.PROVIDER_INCOMPLETE,
        )
        val projected = terminal.replaySafeProjection()!!
        val projection = projected.providerReplayProjection!!
        assertEquals(0, projection.completePartCount)
        assertTrue(projection.hasIncompleteTail)
        assertTrue(projected.parts.none { it is UIMessagePart.Reasoning })
        assertTrue(projected.parts.any { it is UIMessagePart.Text })
    }

    @Test
    fun `terminal replay strips google opaque state from partial text and image only`() {
        val textMetadata = buildJsonObject {
            GoogleThoughtMetadata(
                thoughtSignature = "text-signature",
                sourceModelId = "gemini-3-flash",
                sourceProfile = "google:developer:example",
                providerStepId = "step-1",
            ).toMetadata().forEach { (key, value) -> put(key, value) }
            AttachmentProjectionTextMetadata(attachmentProjectionText = true).toMetadata().forEach { (key, value) -> put(key, value) }
        }
        val imageMetadata = GoogleThoughtMetadata(
            thoughtSignature = "image-signature",
            sourceModelId = "gemini-3-flash",
            sourceProfile = "google:developer:example",
            providerStepId = "step-1",
        ).toMetadata()
        val terminal = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("partial", textMetadata),
                UIMessagePart.Image("https://example.com/image.png", imageMetadata),
            ),
            terminalStatus = MessageTerminalStatus.INCOMPLETE,
        )
        val projected = terminal.replaySafeProjection()!!
        val text = projected.parts.filterIsInstance<UIMessagePart.Text>().first()
        val image = projected.parts.filterIsInstance<UIMessagePart.Image>().single()
        assertNull(text.metadataAs<GoogleThoughtMetadata>()?.thoughtSignature)
        assertNull(image.metadataAs<GoogleThoughtMetadata>()?.thoughtSignature)
        assertEquals(true, text.metadataAs<AttachmentProjectionTextMetadata>()?.attachmentProjectionText)
    }

    @Test
    fun `terminal replay fail-closed at unsafe tool boundary drops subsequent steps`() {
        val safeTool = toolPart(providerCallId = "safe-1", name = "search", output = listOf(UIMessagePart.Text("ok")))
        val unsafeTool = toolPart(providerCallId = "unsafe", name = "broken", input = "{invalid")
        val laterSafeTool = toolPart(providerCallId = "later-safe", name = "search2", output = listOf(UIMessagePart.Text("ok2")))
        val terminal = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "First reasoning"),
                UIMessagePart.Text("First content"),
                safeTool,
                UIMessagePart.Reasoning(reasoning = "Second reasoning"),
                UIMessagePart.Text("Second content"),
                unsafeTool,
                UIMessagePart.Text("After unsafe"),
                laterSafeTool,
            ),
            terminalStatus = MessageTerminalStatus.FAILED,
            terminalReason = TurnTerminalReasons.PROVIDER_FAILED,
        )
        val projected = terminal.replaySafeProjection()!!
        val projection = projected.providerReplayProjection!!
        assertEquals(3, projection.completePartCount)
        assertTrue(projection.hasIncompleteTail)
        assertTrue(projected.parts.none { it is UIMessagePart.Tool && it.providerCallId == "later-safe" })
        assertTrue(projected.parts.any { it is UIMessagePart.Tool && it.providerCallId == "safe-1" })
    }

    @Test
    fun `terminal replay keeps complete prefix reasoning for deepseek v4 strict history`() {
        val completedTool = toolPart(name = "search", output = listOf(UIMessagePart.Text("result")))
        val terminal = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "Step reasoning"),
                UIMessagePart.Text("Calling search"),
                completedTool,
                UIMessagePart.Reasoning(reasoning = "Tail reasoning"),
                UIMessagePart.Text("Partial answer"),
            ),
            terminalStatus = MessageTerminalStatus.FAILED,
            terminalReason = TurnTerminalReasons.PROVIDER_FAILED,
        )
        val projected = terminal.replaySafeProjection()!!
        val projection = projected.providerReplayProjection!!
        val completePrefix = projected.parts.take(projection.completePartCount)
        assertTrue(completePrefix.any { it is UIMessagePart.Reasoning })
    }

    @Test
    fun `provider replay projection is not persisted through serialization`() {
        val terminal = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("partial")),
            terminalStatus = MessageTerminalStatus.FAILED,
            terminalReason = TurnTerminalReasons.PROVIDER_FAILED,
        )
        val projected = terminal.replaySafeProjection()!!
        assertNotNull(projected.providerReplayProjection)
        val restored = json.decodeFromString<UIMessage>(json.encodeToString(projected))
        assertNull(restored.providerReplayProjection)
    }

    @Test
    fun `media failure placeholder survives message serialization`() {
        val original = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(mediaPersistenceFailurePart(UIMessagePart.Image(url = "data:image/png;base64,broken"))),
        )
        val restored = json.decodeFromString<UIMessage>(json.encodeToString(original))
        assertEquals(MessageMediaFailureReason.PERSISTENCE_FAILED, restored.parts.single().mediaFailureMetadataOrNull()?.reason)
        assertEquals(MessageMediaKind.IMAGE, restored.parts.single().mediaFailureMetadataOrNull()?.mediaKind)
    }

    @Test
    fun `openrouter reasoning details merge same id and append new items`() {
        val first = buildJsonArray {
            add(buildJsonObject { put("id", "rd-1"); put("type", "reasoning.text"); put("text", "hid") })
        }
        val second = buildJsonArray {
            add(buildJsonObject { put("id", "rd-1"); put("type", "reasoning.text"); put("text", "den") })
            add(buildJsonObject { put("id", "rd-2"); put("type", "reasoning.summary"); put("text", "sum") })
        }
        assertEquals(first, mergeOpenRouterReasoningDetails(first, null))
        assertEquals(
            buildJsonArray {
                add(buildJsonObject { put("id", "rd-1"); put("type", "reasoning.text"); put("text", "hidden") })
                add(buildJsonObject { put("id", "rd-2"); put("type", "reasoning.summary"); put("text", "sum") })
            },
            mergeOpenRouterReasoningDetails(first, second),
        )
    }

    @Test
    fun `isEmptyUIMessage treats a tool call as visible content`() {
        listOf(
            ToolInteractionState.NotRequired,
            ToolInteractionState.AwaitingApproval,
            ToolInteractionState.AwaitingInput,
            ToolInteractionState.Approved,
            ToolInteractionState.Denied("denied"),
            ToolInteractionState.Answered("yes"),
        ).forEach { state ->
            val parts = listOf(toolPart(interaction = state))
            assertFalse("tool in state $state must be visible", parts.isEmptyUIMessage())
        }
    }

    @Test
    fun `isEmptyUIMessage keeps replay-safe input emptiness separate`() {
        val parts = listOf(toolPart())
        assertFalse(parts.isEmptyUIMessage())
        assertTrue(parts.isEmptyInputMessage())
    }

    @Test
    fun `isEmptyUIMessage still hides blank text and empty part lists`() {
        assertTrue(emptyList<UIMessagePart>().isEmptyUIMessage())
        assertTrue(listOf(UIMessagePart.Text("")).isEmptyUIMessage())
        assertTrue(listOf(UIMessagePart.Text("   \n")).isEmptyUIMessage())
        assertTrue(listOf(UIMessagePart.Image("")).isEmptyUIMessage())
        assertTrue(listOf(UIMessagePart.Reasoning("")).isEmptyUIMessage())
        assertFalse(listOf(UIMessagePart.Text("hello")).isEmptyUIMessage())
    }

    @Test
    fun `isEmptyUIMessage is false when any part is visible`() {
        val parts = listOf(UIMessagePart.Text(""), toolPart())
        assertFalse(parts.isEmptyUIMessage())
    }
}
