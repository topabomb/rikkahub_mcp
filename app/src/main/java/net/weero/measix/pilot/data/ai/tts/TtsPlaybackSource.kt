package net.weero.measix.pilot.data.ai.tts

import kotlin.uuid.Uuid

/**
 * TTS 播放来源。
 *
 * 标识一次 TTS 调用来自哪个角色和哪轮生成。
 * 来源信息只服务播放仲裁和 UI，不持久化到 Conversation，也不进入模型 Tool Result。
 */
data class TtsPlaybackSource(
    /** 本轮 turn 的稳定 playback session ID（每轮 Master Generation 创建一个，Target 派生复用） */
    val sessionId: String,
    /** 来源 Assistant ID */
    val assistantId: Uuid?,
    /** 来源 Assistant 名称快照 */
    val assistantName: String,
    /** 来源类型 */
    val type: SourceType,
) {
    enum class SourceType {
        /** 普通用户会话中的自动播放或手动朗读 */
        NORMAL,
        /** 子助手 Target Generation 中的 TTS 工具调用 */
        SUB_ASSISTANT,
    }

    companion object {
        /**
         * 计算来源切换时的最终 flush 策略。
         *
         * - [flushCalled] 为工具自身根据顺序开关决定的 flush
         * - [currentSource] 为当前活跃来源（null 表示无音频或手动朗读）
         * - [incomingSource] 为本次 TTS 调用的来源
         *
         * 同一 turn 内 Master 和 Target 共享 sessionId，来源类型切换不触发 flush。
         * turn 切换（sessionId 不同）或来源有无状态变化时强制 flush，
         * 禁止不同 turn 共用队列。
         */
        fun computeEffectiveFlush(
            flushCalled: Boolean,
            currentSource: TtsPlaybackSource?,
            incomingSource: TtsPlaybackSource?,
        ): Boolean {
            val sessionChanged = incomingSource != null && currentSource != null &&
                incomingSource.sessionId != currentSource.sessionId
            val sourceTypeChanged = (incomingSource != null && currentSource == null) ||
                (incomingSource == null && currentSource != null)
            return flushCalled || sessionChanged || sourceTypeChanged
        }
    }
}
