package net.weero.measix.pilot.ui.components.message.tools

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.FileView
import me.rerere.hugeicons.stroke.Search01
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.ai.tools.ToolOutputToolNames

object ReadToolOutputUI : ToolUIRenderer {
    override val toolName: String = ToolOutputToolNames.READ

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.FileView

    @Composable
    override fun displayName(context: ToolUIContext): String =
        stringResource(R.string.chat_message_tool_read_trimmed_result)

    @Composable
    override fun title(context: ToolUIContext): String = displayName(context)
}

object GrepToolOutputUI : ToolUIRenderer {
    override val toolName: String = ToolOutputToolNames.GREP

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Search01

    @Composable
    override fun displayName(context: ToolUIContext): String =
        stringResource(R.string.chat_message_tool_search_trimmed_result)

    @Composable
    override fun title(context: ToolUIContext): String = displayName(context)
}
