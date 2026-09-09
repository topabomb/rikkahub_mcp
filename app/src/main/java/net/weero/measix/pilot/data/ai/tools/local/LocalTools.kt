package net.weero.measix.pilot.data.ai.tools.local

import android.content.Context
import me.rerere.ai.core.Tool
import net.weero.measix.pilot.data.event.AppEventBus

class LocalTools internal constructor(
    private val context: Context,
    private val eventBus: AppEventBus,
    private val speech: net.weero.measix.pilot.service.SpeechApplicationService,
    private val imageGenerationToolFactory: ImageGenerationToolFactory,
) {
    val javascriptTool by lazy { buildJavascriptTool() }

    val timeTool by lazy { buildTimeInfoTool() }

    val clipboardTool by lazy { buildClipboardTool(context) }

    val askUserTool by lazy { buildAskUserTool() }

    val screenTimeTool by lazy { buildScreenTimeTool(context, eventBus) }

    val calendarQueryTool by lazy { buildCalendarQueryTool(context) }

    internal fun getTools(
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
            // 每个 Turn 捕获一个 TtsToolPlaybackContext；冻结后的执行 binding 在全部 step 复用。
            tools.add(buildTextToSpeechTool(speech, requireNotNull(ttsPlaybackContext) { "turn_speech_capture_missing" }))
        }
        if (options.contains(LocalToolOption.AskUser)) {
            tools.add(askUserTool)
        }
        if (options.contains(LocalToolOption.ScreenTime)) {
            tools.add(screenTimeTool)
        }
        if (options.contains(LocalToolOption.Calendar)) {
            tools.add(calendarQueryTool)
            tools.add(buildCalendarCreateTool(context))
        }
        if (options.contains(LocalToolOption.TextToImage) && buildContext != null) {
            imageGenerationToolFactory.create(buildContext)?.let(tools::add)
        }
        return tools
    }
}
