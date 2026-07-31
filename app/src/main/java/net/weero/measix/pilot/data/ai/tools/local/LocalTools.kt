package net.weero.measix.pilot.data.ai.tools.local

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import me.rerere.ai.core.Tool
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.event.AppEventBus
import me.rerere.tts.provider.TTSManager

class LocalTools(
    private val context: Context,
    private val eventBus: AppEventBus,
    private val ttsManager: TTSManager,
    private val settingsStore: SettingsStore,
) {
    val javascriptTool by lazy { buildJavascriptTool() }

    val timeTool by lazy { buildTimeInfoTool() }

    val clipboardTool by lazy { buildClipboardTool(context) }

    /**
     * 标记当前生成轮次中 text_to_speech 工具是否已被调用过。
     *
     * - 每轮生成开始时由 ChatService.handleMessageComplete() 重置为 false
     * - 首次工具调用 compareAndSet(false, true) 返回 true → flush=true（打断之前的播放）
     * - 后续工具调用返回 false → flush=false（追加到队列末尾顺序播放）
     *
     * 已知限制：LocalTools 是 Koin 单例，此标志跨对话共享。若用户同时在
     * 两个对话中生成，第二轮的 handleMessageComplete 会重置标志，导致第一轮
     * 的后续工具调用误判为"首次"而 flush。最坏后果是少追加一次、多打断一次，
     * 不影响数据正确性。若未来需精确隔离，可将标志改为 Map<Uuid, Boolean>
     * 按 conversationId 隔离，但需将 conversationId 透传到 Tool.execute，
     * 改动较大，当前不值得。
     */
    val ttsCalledInCurrentGeneration = AtomicBoolean(false)

    val ttsTool by lazy { buildTextToSpeechTool(eventBus, ttsManager, settingsStore, this) }

    val askUserTool by lazy { buildAskUserTool() }

    val screenTimeTool by lazy { buildScreenTimeTool(context, eventBus) }

    val calendarQueryTool by lazy { buildCalendarQueryTool(context) }

    val calendarCreateTool by lazy { buildCalendarCreateTool(context) }

    fun getTools(options: List<LocalToolOption>): List<Tool> {
        val tools = mutableListOf<Tool>()
        if (options.contains(LocalToolOption.JavascriptEngine)) {
            tools.add(javascriptTool)
        }
        if (options.contains(LocalToolOption.TimeInfo)) {
            tools.add(timeTool)
        }
        if (options.contains(LocalToolOption.Clipboard)) {
            tools.add(clipboardTool)
        }
        if (options.contains(LocalToolOption.Tts)) {
            tools.add(ttsTool)
        }
        if (options.contains(LocalToolOption.AskUser)) {
            tools.add(askUserTool)
        }
        if (options.contains(LocalToolOption.ScreenTime)) {
            tools.add(screenTimeTool)
        }
        if (options.contains(LocalToolOption.Calendar)) {
            tools.add(calendarQueryTool)
            tools.add(calendarCreateTool)
        }
        return tools
    }
}
