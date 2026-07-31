package net.weero.measix.pilot.data.ai.tools.local

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.getSelectedTTSProvider
import net.weero.measix.pilot.data.event.AppEvent
import net.weero.measix.pilot.data.event.AppEventBus
import me.rerere.tts.provider.TTSManager

internal fun buildTextToSpeechTool(
    eventBus: AppEventBus,
    ttsManager: TTSManager,
    settingsStore: SettingsStore,
    localTools: LocalTools,
): Tool = Tool(
    name = "text_to_speech",
    description = """
        Speak text aloud to the user using the device's text-to-speech engine.
        Use this when the user asks you to read something aloud, or when audio output is appropriate.
        The tool returns immediately; audio plays in the background on the device.
        Provide natural, readable text without markdown formatting.
    """.trimIndent().replace("\n", " "),
    systemPrompt = { _, _ ->
        // 当前选中的 TTS provider 若硬编码了语气标记引导，则注入 system prompt（否则为空）
        settingsStore.settingsFlow.value.getSelectedTTSProvider()
            ?.let { ttsManager.getPromptGuidance(it) }
            .orEmpty()
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "The text to speak aloud")
                })
            },
            required = listOf("text")
        )
    },
    execute = {
        val text = it.jsonObject["text"]?.jsonPrimitive?.contentOrNull
            ?: error("text is required")

        // 顺序播放：开启时首次调用 flush 打断之前播放，后续调用 append 追加到队列末尾；
        // 关闭时始终 flush，每次调用都打断重新播放。
        // 注意：朗读内容是 AI 指定的 text 参数，不经过 ttsOnlyReadQuoted /
        // ttsOnlyReadOutsideBrackets 过滤（与手动朗读/autoPlay 不同），
        // 因为 AI 已经在生成时决定了要朗读的内容。
        val sequentialEnabled = settingsStore.settingsFlow.value
            .displaySetting.ttsToolSequentialPlayback
        val flush = if (sequentialEnabled) {
            localTools.ttsCalledInCurrentGeneration.compareAndSet(false, true)
        } else {
            true
        }

        eventBus.emit(AppEvent.Speak(text, flush = flush))
        val payload = buildJsonObject {
            put("success", true)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
