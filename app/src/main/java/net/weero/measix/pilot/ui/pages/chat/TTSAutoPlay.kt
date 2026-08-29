package net.weero.measix.pilot.ui.pages.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.service.runtime.ConversationSnapshot
import net.weero.measix.pilot.ui.context.LocalTTSState
import net.weero.measix.pilot.utils.extractQuotedContentAsText
import net.weero.measix.pilot.utils.removeBracketedContent

@Composable
fun TTSAutoPlay(vm: ChatVM, setting: Settings, snapshot: ConversationSnapshot) {
    // Auto-play TTS after generation completes
    val tts = LocalTTSState.current
    val updatedSetting by rememberUpdatedState(setting)
    LaunchedEffect(vm, snapshot.conversationId) {
        vm.generationDoneFlow.collect { conversationId ->
            if (snapshot.conversationId != conversationId) return@collect
            // SharedFlow 完成事件可能先于 Compose 参数重组到达，直接读取内存快照（权威事实源）。
            val completedSnapshot = vm.currentSnapshot()
            if (updatedSetting.displaySetting.autoPlayTTSAfterGeneration &&
                shouldAutoPlayTts(conversationId, completedSnapshot)
            ) {
                val lastMessage = completedSnapshot.currentMessages().lastOrNull()
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

internal fun shouldAutoPlayTts(conversationId: kotlin.uuid.Uuid, snapshot: ConversationSnapshot): Boolean {
    if (snapshot.conversationId != conversationId) return false
    val lastMessage = snapshot.currentMessages().lastOrNull() ?: return false
    if (lastMessage.role != MessageRole.ASSISTANT) return false
    val hasPendingTools = lastMessage.parts.any { it is UIMessagePart.Tool && it.isPending }
    if (hasPendingTools) return false
    // 模型已通过 text_to_speech 入队播放时，不再把整条可见回复追加朗读一遍。
    val alreadySpokenByTool = lastMessage.parts.any {
        it is UIMessagePart.Tool && it.toolName == "text_to_speech" && it.hasReplayResult
    }
    return !alreadySpokenByTool
}
