package net.weero.measix.pilot.data.ai.tools.local

import android.content.Context
import me.rerere.ai.core.Tool
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.event.AppEventBus
import me.rerere.tts.provider.TTSManager

class LocalTools(
    private val context: Context,
    private val eventBus: AppEventBus,
    private val ttsManager: TTSManager,
    private val settingsStore: SettingsStore,
    private val imageGenerationToolFactory: ImageGenerationToolFactory,
) {
    val javascriptTool by lazy { buildJavascriptTool() }

    val timeTool by lazy { buildTimeInfoTool() }

    val clipboardTool by lazy { buildClipboardTool(context) }

    val askUserTool by lazy { buildAskUserTool() }

    val screenTimeTool by lazy { buildScreenTimeTool(context, eventBus) }

    val calendarQueryTool by lazy { buildCalendarQueryTool(context) }

    val calendarCreateTool by lazy { buildCalendarCreateTool(context) }

    fun getTools(
        options: List<LocalToolOption>,
        ttsPlaybackContext: TtsToolPlaybackContext? = null,
        buildContext: AssistantToolBuildContext? = null,
    ): List<Tool> {
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
            // 每轮生成创建一个 TtsToolPlaybackContext，
            // toolProvider 在不同 LLM step 重建 Tool 时复用这个 context。
            tools.add(buildTextToSpeechTool(eventBus, ttsManager, settingsStore, ttsPlaybackContext))
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
        if (options.contains(LocalToolOption.TextToImage) && buildContext != null) {
            imageGenerationToolFactory.create(buildContext)?.let(tools::add)
        }
        return tools
    }
}
