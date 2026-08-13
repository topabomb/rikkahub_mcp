package net.weero.measix.pilot.ui.pages.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.ui.context.LocalTTSState
import net.weero.measix.pilot.utils.extractQuotedContentAsText
import net.weero.measix.pilot.utils.removeBracketedContent

@Composable
fun TTSAutoPlay(vm: ChatVM, setting: Settings, conversation: Conversation) {
    // Auto-play TTS after generation completes
    val tts = LocalTTSState.current
    val updatedSetting by rememberUpdatedState(setting)
    LaunchedEffect(vm, conversation.id) {
        vm.generationDoneFlow.collect { conversationId ->
            if (conversation.id != conversationId) return@collect
            // SharedFlow 完成事件可能先于 Compose 参数重组到达，直接读取 ChatService 的权威 StateFlow。
            val completedConversation = vm.conversation.value
            if (updatedSetting.displaySetting.autoPlayTTSAfterGeneration &&
                shouldAutoPlayTts(conversationId, completedConversation)
            ) {
                val lastMessage = completedConversation.currentMessages.lastOrNull()
                if (lastMessage != null && lastMessage.role == MessageRole.ASSISTANT) {
                    val text = lastMessage.toText()
                    var textToSpeak = text
                    if (updatedSetting.displaySetting.ttsOnlyReadQuoted) {
                        textToSpeak = textToSpeak.extractQuotedContentAsText() ?: textToSpeak
                    }
                    if (updatedSetting.displaySetting.ttsOnlyReadOutsideBrackets) {
                        textToSpeak = textToSpeak.removeBracketedContent() ?: textToSpeak
                    }
                    if (textToSpeak.isNotBlank()) {
                        val queueSessionId = vm.getTtsQueueSessionId(conversationId)
                        tts.speakWithSource(
                            text = textToSpeak,
                            replaceWithinSession = autoPlayReplacesWithinTurn(
                                queueSessionId = queueSessionId,
                                sequentialEnabled = updatedSetting.displaySetting.ttsToolSequentialPlayback,
                            ),
                            queueSessionId = queueSessionId,
                            source = null,
                        )
                    }
                }
            }
        }
    }
}

internal fun autoPlayReplacesWithinTurn(queueSessionId: String?, sequentialEnabled: Boolean): Boolean =
    queueSessionId == null || !sequentialEnabled

internal fun shouldAutoPlayTts(conversationId: kotlin.uuid.Uuid, conversation: Conversation): Boolean {
    if (conversation.id != conversationId) return false
    val lastMessage = conversation.currentMessages.lastOrNull() ?: return false
    if (lastMessage.role != MessageRole.ASSISTANT) return false
    return lastMessage.parts.none { part ->
        part is UIMessagePart.Tool && part.isPending
    }
}
