package net.weero.measix.pilot.data.ai.tts

import kotlin.uuid.Uuid

/**
 * TTS 播放来源。
 *
 * 标识一次 TTS 调用来自哪个角色和哪轮生成。
 * 来源信息只服务播放分段和 UI，不持久化到 Conversation，也不进入模型 Tool Result。
 */
data class TtsPlaybackSource(
    /** 来源 Assistant ID */
    val assistantId: Uuid?,
    /** 来源 Assistant 名称快照 */
    val assistantName: String,
    /** 来源类型 */
    val type: SourceType,
) {
    enum class SourceType {
        /** 主助手的 TTS 工具调用 */
        NORMAL,
        /** 子助手 Target Generation 中的 TTS 工具调用 */
        SUB_ASSISTANT,
    }

}
