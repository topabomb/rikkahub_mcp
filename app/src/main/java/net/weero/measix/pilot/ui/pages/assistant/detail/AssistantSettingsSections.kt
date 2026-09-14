package net.weero.measix.pilot.ui.pages.assistant.detail

import androidx.annotation.StringRes
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.ui.components.ui.CardGroup
import net.weero.measix.pilot.ui.components.ui.UIAvatar

@Composable
internal fun AssistantDetailsHome(
    assistant: Assistant,
    sourceLabel: String,
    onSectionClick: (AssistantSettingsSection) -> Unit,
    modifier: Modifier = Modifier,
    onEdit: (() -> Unit)? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            UIAvatar(
                name = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                value = assistant.avatar,
                modifier = Modifier.size(48.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    sourceLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (assistant.description.isNotBlank()) Text(
            assistant.description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        onEdit?.let { edit ->
            TextButton(
                onClick = edit,
                contentPadding = PaddingValues(horizontal = 0.dp),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.assistant_usage_shared_edit)) }
        }
        AssistantSettingsSectionList(
            modifier = Modifier.fillMaxWidth(),
            onClick = onSectionClick,
        )
    }
}

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
                supportingContent = { Text(stringResource(section.description)) },
                headlineContent = { Text(stringResource(section.title)) },
                trailingContent = { Icon(HugeIcons.ArrowRight01, null) },
            )
        }
    }
}
