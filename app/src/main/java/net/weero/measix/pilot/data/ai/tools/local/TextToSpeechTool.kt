package net.weero.measix.pilot.data.ai.tools.local

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.tts.TtsPlaybackSource
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.getSelectedTTSProvider
import net.weero.measix.pilot.data.event.AppEvent
import net.weero.measix.pilot.data.event.AppEventBus
import me.rerere.tts.provider.TTSManager

/**
 * 一轮用户 turn 内 Master 与 Target 共用的 TTS 播放上下文。
 * [sessionId] 独占一条队列；Target 只替换助手身份，不另开队列。
 */
data class TtsToolPlaybackContext(
    val sessionId: String,
    val assistantId: kotlin.uuid.Uuid?,
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
    eventBus: AppEventBus,
    ttsManager: TTSManager,
    settingsStore: SettingsStore,
    playbackContext: TtsToolPlaybackContext? = null,
): Tool {
    return Tool(
        name = "text_to_speech",
        description = """
            Speak text aloud when the user asks you to read something, or when audio is appropriate.
            Returns immediately; playback continues in the background.
            Provide natural speech text without markdown.
        """.trimIndent().replace("\n", " "),
        systemPrompt = { _, _ ->
            settingsStore.effectiveSettings.value.settings.getSelectedTTSProvider()
                ?.let { ttsManager.getPromptGuidance(it) }
                .orEmpty()
        },
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
            val sequentialEnabled = settingsStore.effectiveSettings.value.settings
                .displaySetting.ttsToolSequentialPlayback
            val text = it.jsonObject["text"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { value -> value.isNotBlank() }
                ?: error("text is required and must not be blank")

            eventBus.emit(AppEvent.Speak(
                text = text,
                queueSessionId = playbackContext?.sessionId,
                // 同一 turn 是否替换由设置决定；缺少 turn context 时保守地替换。
                replaceWithinSession = playbackContext == null ||
                    ttsToolReplacesWithinTurn(sequentialEnabled),
                source = playbackContext?.toPlaybackSource(),
            ))
            val payload = buildJsonObject {
                put("success", true)
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )
}

internal fun ttsToolReplacesWithinTurn(sequentialEnabled: Boolean): Boolean = !sequentialEnabled
