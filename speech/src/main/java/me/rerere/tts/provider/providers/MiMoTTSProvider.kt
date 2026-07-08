package me.rerere.tts.provider.providers

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.common.http.SseEvent
import me.rerere.common.http.sseFlow
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderSetting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.concurrent.TimeUnit

// MiMo 流式音频按文档示例使用 24kHz PCM16LE
private const val MIMO_SAMPLE_RATE = 24000
private val JSON_MEDIA_TYPE = "application/json".toMediaType()
// 只关心 delta.audio.data 其余字段忽略
private val mimoJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class MiMoChunk(
    val choices: List<MiMoChoice> = emptyList()
)

@Serializable
private data class MiMoChoice(
    val delta: MiMoDelta? = null
)

@Serializable
private data class MiMoDelta(
    val audio: MiMoAudio? = null
)

@Serializable
private data class MiMoAudio(
    val data: String? = null
)

internal fun decodeMiMoAudioData(data: String): ByteArray? {
    val payload = data.trim()
    // [DONE] 表示流结束 不输出音频
    if (payload == "[DONE]") return null
    // 非 [DONE] 的 data 视为 JSON 片段 解析失败直接上抛
    val chunk = mimoJson.decodeFromString<MiMoChunk>(payload)
    val encoded = chunk.choices.firstOrNull()?.delta?.audio?.data ?: return null
    // 空字符串视为无音频片段
    if (encoded.isBlank()) return null
    return Base64.getDecoder().decode(encoded)
}

internal class MiMoSseProcessor(
    private val model: String,
    private val voice: String
) {
    private var hasAudio = false
    // metadata 只构造一次 贯穿整个流
    private val metadata = mapOf(
        "provider" to "mimo",
        "model" to model,
        "voice" to voice
    )

    fun process(event: SseEvent): AudioChunk? {
        return when (event) {
            is SseEvent.Open -> null
            is SseEvent.Event -> {
                // 只处理包含 audio.data 的增量事件 其他事件忽略
                val pcmData = decodeMiMoAudioData(event.data) ?: return null
                hasAudio = true
                AudioChunk(
                    data = pcmData,
                    format = AudioFormat.PCM,
                    sampleRate = MIMO_SAMPLE_RATE,
                    metadata = metadata
                )
            }

            is SseEvent.Closed -> {
                // 如果整段流没有任何音频片段 直接报错
                if (!hasAudio) {
                    throw IllegalStateException("MiMo TTS returned no audio chunks")
                }
                // 流关闭时补一个终结 chunk 便于播放器收尾
                AudioChunk(
                    data = byteArrayOf(),
                    format = AudioFormat.PCM,
                    sampleRate = MIMO_SAMPLE_RATE,
                    isLast = true,
                    metadata = metadata
                )
            }

            is SseEvent.Failure -> throw event.throwable ?: Exception("MiMo TTS streaming failed")
        }
    }
}

/**
 * 构造 MiMo TTS 请求体（纯函数，便于单测）。
 *
 * 官方协议（https://mimo.mi.com/docs/.../speech-synthesis-v2.5）：
 * - 待合成文本：放在 messages 中 role=assistant 的 content（不可放在 user）。
 * - Voice design：仅 mimo-v2.5-tts-voicedesign 模型生效；描述放在 messages 中
 *   role=user 的 content。voiceDesignPrompt 为空时不构造该 user 消息。
 * - audio.voice：标准模型需要；voicedesign 模型由 user message 描述决定音色，
 *   官方示例不传 voice，故 voicedesign 时省略。
 */
internal fun buildMiMoRequestBody(
    model: String,
    voice: String,
    text: String,
    voiceDesignPrompt: String
): JsonElement = buildJsonObject {
    val isVoiceDesign = model.contains("voicedesign")
    put("model", model)
    put("messages", buildJsonArray {
        // voicedesign：先放 user 消息（音色描述），非空才构造
        if (voiceDesignPrompt.isNotBlank()) {
            add(buildJsonObject {
                put("role", "user")
                put("content", voiceDesignPrompt)
            })
        }
        // 待合成文本始终放在 assistant 消息（官方限制：不可放在 user）
        add(buildJsonObject {
            put("role", "assistant")
            put("content", text)
        })
    })
    put("audio", buildJsonObject {
        put("format", "pcm16")
        // voicedesign 模型音色由 user message 描述决定，官方示例不传 voice
        if (!isVoiceDesign) {
            put("voice", voice)
        }
    })
    put("stream", true)
}

class MiMoTTSProvider : TTSProvider<TTSProviderSetting.MiMo> {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    // MiMo 支持在朗读文本中嵌入风格/音频标签控制语气与情感
    // 官方文档: https://mimo.mi.com/docs/zh-CN/quick-start/usage-guide/audio/speech-synthesis-v2.5 (音频标签控制)
    override val promptGuidance: String = """
        The active text-to-speech engine (MiMo) supports emotion and style control via embedded tags.
        When you call the text_to_speech tool, you MAY enrich the "text" argument with these tags to make the speech more expressive.
        Put tags ONLY inside the tool's text argument — never in your visible reply to the user.

        Two kinds of tags:
        1. Overall style tag — place ONE at the very beginning of the text: (style) . Combine multiple styles with spaces inside the same brackets, e.g. (开心 磁性) . Brackets may be () , （） or [] .
           Common styles: 开心/悲伤/愤怒/恐惧/惊讶/兴奋/委屈/平静/冷漠/怅然/欣慰/无奈/释然/温柔/高冷/活泼/严肃/慵懒/俏皮/深沉/磁性/醇厚/清亮/空灵/甜美/沙哑/御姐音/正太音/大叔音/台湾腔/东北话/四川话/河南话/粤语 . Custom styles are also allowed.
           For singing, the text MUST start with (唱歌) followed by lyrics (Chinese lyrics work best).
        2. Inline audio tags — insert [tag] anywhere to fine-tune delivery, e.g. [吸气] [深呼吸] [叹气] [笑] [轻笑] [大笑] [冷笑] [抽泣] [哽咽] [颤抖] [气声] [撒娇] [疲惫] [震惊] .

        IMPORTANT constraints (required by this app's text pipeline):
        - Do NOT put any punctuation (，。！？、：；…) INSIDE a tag's brackets. Separate multiple styles with spaces only, e.g. write (紧张 深呼吸) NOT (紧张，深呼吸).
        - Keep inline audio tags standalone like [笑]; do not immediately follow a [tag] with a (…) group.
        - Do not use markdown emphasis (*, _) — it will be stripped.
        - Use tags naturally and sparingly; don't over-annotate.

        Example text argument: (磁性)夜已经深了[叹气]城市还在呼吸。我是今晚陪你的人。
    """.trimIndent()

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.MiMo,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        val requestBody = buildMiMoRequestBody(
            model = providerSetting.model,
            voice = providerSetting.voice,
            text = request.text,
            voiceDesignPrompt = providerSetting.voiceDesignPrompt
        )

        // baseUrl 允许用户在设置页自定义 这里直接拼接路径
        val httpRequest = Request.Builder()
            .url("${providerSetting.baseUrl}/chat/completions")
            // MiMo 使用 api-key 头传 token
            .addHeader("api-key", providerSetting.apiKey)
            .addHeader("Content-Type", "application/json")
            // JsonObject 的 toString 会输出 JSON 字符串
            .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val processor = MiMoSseProcessor(
            model = providerSetting.model,
            voice = providerSetting.voice
        )

        httpClient.sseFlow(httpRequest).collect { event ->
            processor.process(event)?.let { emit(it) }
        }
    }
}
