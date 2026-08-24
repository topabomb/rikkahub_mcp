package net.weero.measix.pilot.data.event

import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.data.ai.tts.TtsPlaybackSource
import kotlin.uuid.Uuid

sealed class AppEvent {
    data class Speak(
        val text: String,
        /** 主代理 turn 的队列边界；null 表示不属于工具 turn 的手动/自动朗读。 */
        val queueSessionId: String? = null,
        /** 同一 queue session 内是否替换；queue session 变化时播放器始终替换旧队列。 */
        val replaceWithinSession: Boolean = true,
        /**
         * 当前音频的 UI 播放来源。
         * null 表示无 session 的手动朗读/自动播放。
         */
        val source: TtsPlaybackSource? = null,
    ) : AppEvent()
    data object OpenUsageAccessSettings : AppEvent()

    /** MCP OAuth 授权完成后经 deep link 回传的结果。 */
    data class McpOAuthCallback(
        val state: String?,
        val code: String?,
        val error: String?,
    ) : AppEvent()

    /** 流式生成过程中的增量更新，由 MasterTurnCoordinator 发出。 */
    data class ChatGenerationUpdate(
        val conversationId: Uuid,
        val lastMessage: UIMessage,
        val senderName: String,
    ) : AppEvent()

    /** 生成结束（正常完成或失败），由 MasterTurnCoordinator 发出。 */
    data class ChatGenerationEnded(
        val conversationId: Uuid,
        val senderName: String,
        val contentPreview: String?,
    ) : AppEvent()
}
