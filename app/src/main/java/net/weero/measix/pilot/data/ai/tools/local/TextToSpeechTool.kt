package net.weero.measix.pilot.data.ai.tools.local

import me.rerere.common.configuration.ConfigurationReference
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.tts.TtsPlaybackSource

/**
 * 一轮用户 turn 内 Master 与 Target 共用的 TTS 播放上下文。
 * [sessionId] 独占一条队列；Target 只替换助手身份，不另开队列。
 */
data class TtsToolPlaybackContext(
    val sessionId: String,
    val capture: net.weero.measix.pilot.service.SpeechCapture,
    val assistantId: ConfigurationReference?,
    val assistantName: String,
    val sourceType: TtsPlaybackSource.SourceType,
) {
    fun toPlaybackSource(): TtsPlaybackSource = TtsPlaybackSource(
        assistantId = assistantId,
        assistantName = assistantName,
        type = sourceType,
    )
}

internal fun buildTextToSpeechTool(
    speech: net.weero.measix.pilot.service.SpeechApplicationService,
    playbackContext: TtsToolPlaybackContext,
): Tool {
    return Tool(
        name = "text_to_speech",
        description = """
            Speak text aloud when the user asks you to read something, or when audio is appropriate.
            Returns immediately; playback continues in the background.
            Provide natural speech text without markdown.
        """.trimIndent().replace("\n", " "),
        systemPromptContribution = playbackContext.capture.guidance,
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("text", buildJsonObject {
                        put("type", "string")
                        put("description", "Plain text to speak")
                    })
                },
                required = listOf("text")
            )
        },
        execute = {
            val text = it.jsonObject["text"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { value -> value.isNotBlank() }
                ?: error("text is required and must not be blank")

            speech.enqueue(playbackContext.capture, playbackContext.sessionId, text,
                ttsToolReplacesWithinTurn(playbackContext.capture.sequential), playbackContext.toPlaybackSource())
            val payload = buildJsonObject {
                put("success", true)
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )
}

internal fun ttsToolReplacesWithinTurn(sequentialEnabled: Boolean): Boolean = !sequentialEnabled
