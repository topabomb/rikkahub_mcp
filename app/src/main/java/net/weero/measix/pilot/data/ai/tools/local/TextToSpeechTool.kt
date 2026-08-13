package net.weero.measix.pilot.data.ai.tools.local

import java.util.concurrent.atomic.AtomicBoolean
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
 * TTS 工具播放上下文。
 *
 * 每轮 Master Generation（一个用户 turn）创建一个 turn-level context，包含本轮稳定的
 * playback session ID、Assistant ID、名称快照和来源类型。该 context 在整轮 turn 内被
 * Master 和所有 Target 共享：Target 派生 context 复用同一 sessionId 和同一
 * TtsToolPlaybackState，仅替换 assistantId/assistantName/sourceType。
 *
 * Target 的 toolProvider 在不同 LLM step 重建 Tool 时复用这个派生 context；
 * 它不能放入单例 LocalTools，也不能随每步 Tool 列表一起重建。
 *
 * [TtsToolPlaybackState] 用于跨 LLM step 和跨 Master/Target 维护顺序播放的 "已播放" 标记。
 * 如果 state 不跨 step 和跨角色复用，顺序播放配置会失效——每个 step 或每个角色的
 * 第一次 TTS 调用都会 flush 队列，打断上一段音频。
 */
data class TtsToolPlaybackContext(
    val sessionId: String,
    val assistantId: kotlin.uuid.Uuid?,
    val assistantName: String,
    val sourceType: TtsPlaybackSource.SourceType,
    val playbackState: TtsToolPlaybackState = TtsToolPlaybackState(),
) {
    fun toPlaybackSource(): TtsPlaybackSource = TtsPlaybackSource(
        sessionId = sessionId,
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
    // 复用 context 中的 state，跨 step 维护顺序播放标记
    val playbackState = playbackContext?.playbackState ?: TtsToolPlaybackState()
    return Tool(
        name = "text_to_speech",
        description = """
            Speak text aloud when the user asks you to read something, or when audio is appropriate.
            Returns immediately; playback continues in the background.
            Provide natural speech text without markdown.
        """.trimIndent().replace("\n", " "),
        systemPrompt = { _, _ ->
            settingsStore.settingsFlow.value.getSelectedTTSProvider()
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
            val sequentialEnabled = settingsStore.settingsFlow.value
                .displaySetting.ttsToolSequentialPlayback
            val request = playbackState.prepare(
                text = it.jsonObject["text"]?.jsonPrimitive?.contentOrNull,
                sequentialEnabled = sequentialEnabled,
            )

            eventBus.emit(AppEvent.Speak(
                text = request.text,
                flush = request.flush,
                source = playbackContext?.toPlaybackSource(),
            ))
            val payload = buildJsonObject {
                put("success", true)
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )
}

class TtsToolPlaybackState {
    private val hasSpoken = AtomicBoolean(false)

    fun prepare(text: String?, sequentialEnabled: Boolean): TtsToolPlaybackRequest {
        val speakableText = text?.takeIf { it.isNotBlank() }
            ?: error("text is required and must not be blank")
        val flush = !sequentialEnabled || hasSpoken.compareAndSet(false, true)
        return TtsToolPlaybackRequest(text = speakableText, flush = flush)
    }
}

data class TtsToolPlaybackRequest(
    val text: String,
    val flush: Boolean,
)
