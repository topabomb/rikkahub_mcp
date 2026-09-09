package net.weero.measix.pilot.data.event

import me.rerere.ai.ui.UIMessage
import kotlin.uuid.Uuid

sealed class AppEvent {
    data object OpenUsageAccessSettings : AppEvent()

    /** 流式生成过程中的增量更新，由 ConversationTurnService 发出。 */
    data class ChatGenerationUpdate(
        val conversationId: Uuid,
        val lastMessage: UIMessage,
        val senderName: String,
        /** localCallId of the tool whose committed runtime phase is EXECUTING; null for every other phase. */
        val executingToolLocalCallId: Uuid?,
    ) : AppEvent()

    /** Turn 已稳定停在待审批态，不是生成完成。 */
    data class ChatGenerationAwaitingUser(
        val conversationId: Uuid,
        val lastMessage: UIMessage,
        val senderName: String,
        val pendingToolLocalCallId: Uuid,
    ) : AppEvent()

    /** Turn 已进入终态；只有正常完成才允许发送“已完成”通知。 */
    data class ChatGenerationEnded(
        val conversationId: Uuid,
        val senderName: String,
        val contentPreview: String?,
        val notifyCompletion: Boolean,
    ) : AppEvent()
}
