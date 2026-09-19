package me.rerere.tts.model

import kotlinx.serialization.Serializable

@Serializable
data class TTSRequest(
    val text: String,
    @kotlinx.serialization.Transient val transport: me.rerere.speech.SpeechHttpTransport? = null,
)

@Serializable
enum class AudioFormat {
    MP3,
    WAV,
    OGG,
    AAC,
    OPUS,
    PCM
}
