package net.weero.measix.pilot.ui.pages.assistant.detail

import androidx.annotation.StringRes
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.BookOpen01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.Message02
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Wrench01
import net.weero.measix.pilot.R
import net.weero.measix.pilot.ui.components.ui.CardGroup

internal enum class AssistantSettingsSection(
    @StringRes val title: Int,
    @StringRes val description: Int,
) {
    BASIC(R.string.assistant_page_tab_basic, R.string.assistant_detail_basic_desc),
    PROMPT(R.string.assistant_page_tab_prompt, R.string.assistant_detail_prompt_desc),
    EXTENSIONS(R.string.assistant_page_tab_extensions, R.string.assistant_detail_extensions_desc),
    MEMORY(R.string.assistant_page_tab_memory, R.string.assistant_detail_memory_desc),
    REQUEST(R.string.assistant_page_tab_request, R.string.assistant_detail_request_desc),
    MCP(R.string.assistant_page_tab_mcp, R.string.assistant_detail_mcp_desc),
    LOCAL_TOOLS(R.string.assistant_page_tab_local_tools, R.string.assistant_detail_local_tools_desc),
}

@Composable
internal fun AssistantSettingsSectionList(
    modifier: Modifier = Modifier,
    usageOnly: Boolean = false,
    onClick: (AssistantSettingsSection) -> Unit,
) {
    CardGroup(modifier = modifier) {
        AssistantSettingsSection.entries.forEach { section ->
            item(
                onClick = { onClick(section) },
                leadingContent = {
                    Icon(
                        imageVector = when (section) {
                            AssistantSettingsSection.BASIC -> HugeIcons.Settings03
                            AssistantSettingsSection.PROMPT -> HugeIcons.Message02
                            AssistantSettingsSection.EXTENSIONS -> HugeIcons.Puzzle
                            AssistantSettingsSection.MEMORY -> HugeIcons.Brain02
                            AssistantSettingsSection.REQUEST -> HugeIcons.Code
                            AssistantSettingsSection.MCP -> HugeIcons.Wrench01
                            AssistantSettingsSection.LOCAL_TOOLS -> HugeIcons.BookOpen01
                        },
                        contentDescription = null,
                    )
                },
                supportingContent = {
                    Text(stringResource(
                        when {
                            usageOnly && section == AssistantSettingsSection.BASIC -> R.string.assistant_usage_basic_desc
                            usageOnly && section == AssistantSettingsSection.PROMPT -> R.string.assistant_usage_prompt_read_only
                            else -> section.description
                        },
                    ))
                },
                headlineContent = { Text(stringResource(section.title)) },
                trailingContent = { Icon(HugeIcons.ArrowRight01, null) },
            )
        }
    }
}
