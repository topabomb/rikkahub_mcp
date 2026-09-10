package net.weero.measix.pilot.ui.components.ai

import me.rerere.common.configuration.ConfigurationReference
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Edit03
import net.weero.measix.pilot.R
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.ui.adaptive.AdaptiveModal
import net.weero.measix.pilot.ui.components.ui.Tag
import net.weero.measix.pilot.ui.components.ui.TagType
import net.weero.measix.pilot.ui.components.ui.UIAvatar
import net.weero.measix.pilot.ui.context.LocalNavController
import net.weero.measix.pilot.data.model.Avatar
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@Composable
internal fun AssistantPicker(
    settings: Settings,
    currentAssistantId: ConfigurationReference?,
    assistants: Map<ConfigurationReference, Assistant>,
    unavailableReasons: Map<ConfigurationReference, net.weero.measix.pilot.data.configuration.ConfigurationUnavailableReason?>,
    onSelectAssistant: suspend (ConfigurationReference) -> Boolean,
    modifier: Modifier = Modifier,
    onManageAssistant: (() -> Unit)?,
) {
    val currentAssistant = assistants[currentAssistantId]
    val scope = rememberCoroutineScope()
    var submitting by remember { mutableStateOf(false) }
    val defaultAssistantName = stringResource(R.string.assistant_page_default_assistant)
    var showPicker by remember { mutableStateOf(false) }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(MaterialTheme.shapes.medium)
                    .clickable(enabled = !submitting) { showPicker = true }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UIAvatar(
                    name = currentAssistant?.name?.ifEmpty { defaultAssistantName } ?: stringResource(R.string.safe_mode_switch_assistant),
                    value = currentAssistant?.avatar ?: Avatar.Dummy,
                    modifier = Modifier.size(36.dp),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        text = stringResource(R.string.assistant_picker_current),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                    Text(
                        text = currentAssistant?.name?.ifEmpty { defaultAssistantName } ?: stringResource(R.string.safe_mode_switch_assistant),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    imageVector = HugeIcons.ArrowDown01,
                    contentDescription = stringResource(R.string.safe_mode_switch_assistant),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (onManageAssistant != null) FilledTonalIconButton(
                onClick = onManageAssistant,
                enabled = !submitting,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.Edit03,
                    contentDescription = stringResource(R.string.assistant_picker_manage_current),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }

    if (showPicker) {
        AssistantPickerSheet(
            settings = settings,
            currentAssistantId = currentAssistantId,
            assistants = assistants.values.toList(),
            unavailableReasons = unavailableReasons,
            enabled = !submitting,
            onAssistantSelected = { assistant ->
                if (!submitting) {
                    submitting = true
                    scope.launch {
                        try { if (onSelectAssistant(assistant.id)) showPicker = false }
                        finally { submitting = false }
                    }
                }
            },
            onDismiss = {
                showPicker = false
            }
        )
    }
}

@Composable
internal fun AssistantPickerSheet(
    settings: Settings,
    currentAssistantId: ConfigurationReference?,
    assistants: List<Assistant>,
    unavailableReasons: Map<ConfigurationReference, net.weero.measix.pilot.data.configuration.ConfigurationUnavailableReason?> = emptyMap(),
    onAssistantSelected: (Assistant) -> Unit,
    onDismiss: () -> Unit,
    title: String? = null,
    forceDialog: Boolean = false,
    allowManage: Boolean = true,
    enabled: Boolean = true,
) {
    val defaultAssistantName = stringResource(R.string.assistant_page_default_assistant)
    val sheetTitle = title ?: stringResource(R.string.safe_mode_switch_assistant)

    // 标签过滤状态
    var selectedTagIds by remember { mutableStateOf(emptySet<ConfigurationReference>()) }
    // 搜索关键词状态
    var searchQuery by remember { mutableStateOf("") }

    // "显示子助手"筛选状态
    // 当前会话直接使用子助手时默认开启；不存在普通 Assistant 时自动显示全部
    val hasNormalAssistants = assistants.any { !it.allowAsSubAssistant }
    var showSubAssistants by remember {
        mutableStateOf((assistants.find { it.id == currentAssistantId }?.allowAsSubAssistant == true) || !hasNormalAssistants)
    }

    // 类型筛选先执行，再叠加 name/description 搜索和 Tag 筛选
    val filteredAssistants = remember(assistants, selectedTagIds, searchQuery, showSubAssistants) {
        assistants.filter { assistant ->
            val matchesType = showSubAssistants || !assistant.allowAsSubAssistant
            val matchesSearch = searchQuery.isBlank() ||
                assistant.name.contains(searchQuery, ignoreCase = true) ||
                assistant.description.contains(searchQuery, ignoreCase = true)
            val matchesTags = selectedTagIds.isEmpty() ||
                assistant.tags.any { tagId -> tagId in selectedTagIds }
            matchesType && matchesSearch && matchesTags
        }
    }

    AdaptiveModal(
        onDismissRequest = onDismiss,
        forceDialog = forceDialog,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = sheetTitle,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_card_close)) }
            }

            AssistantSearchFilterRow(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                showSubAssistants = showSubAssistants,
                onShowSubAssistantsChange = { showSubAssistants = it },
            )

            if (settings.assistantTags.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 8.dp),
                ) {
                    items(settings.assistantTags, key = { tag -> tag.id.toString() }) { tag ->
                        FilterChip(
                            onClick = {
                                selectedTagIds = if (tag.id in selectedTagIds) {
                                    selectedTagIds - tag.id
                                } else {
                                    selectedTagIds + tag.id
                                }
                            },
                            label = { Text(tag.name) },
                            selected = tag.id in selectedTagIds,
                            shape = RoundedCornerShape(50),
                        )
                    }
                }
            }

            // 助手列表
            val navController = LocalNavController.current
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filteredAssistants, key = { it.id.toString() }) { assistant ->
                    val checked = assistant.id == currentAssistantId
                    Card(
                        onClick = { onAssistantSelected(assistant) },
                        enabled = enabled && unavailableReasons[assistant.id] == null,
                        modifier = Modifier.animateItem(),
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(
                            containerColor = if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            contentColor = if (checked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        ),
                    ) {
                        unavailableReasons[assistant.id]?.let { reason ->
                            Text(configurationUnavailableText(reason), modifier = Modifier.padding(8.dp), color = MaterialTheme.colorScheme.error)
                        }
                        AssistantItem(
                            assistant = assistant,
                            defaultAssistantName = defaultAssistantName,
                            onEdit = if (enabled && allowManage && assistant.id is ConfigurationReference.User) {
                                {
                                    onDismiss()
                                    navController.navigate(Screen.AssistantDetail(assistant.id.toString()))
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantItem(
    assistant: Assistant,
    defaultAssistantName: String,
    onEdit: (() -> Unit)? = null,
) {
    ListItem(
        leadingContent = {
            UIAvatar(
                name = assistant.name.ifEmpty { defaultAssistantName },
                value = assistant.avatar,
                modifier = Modifier.size(32.dp)
            )
        },
        trailingContent = onEdit?.let { edit ->
            {
                IconButton(onClick = edit) {
                    Icon(
                        imageVector = HugeIcons.Edit03,
                        contentDescription = null
                    )
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = assistant.name.ifEmpty { defaultAssistantName },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (assistant.description.isNotBlank()) {
                Text(
                    text = assistant.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (assistant.allowAsSubAssistant) {
                Tag(type = TagType.INFO) {
                    Text(stringResource(R.string.assistant_page_sub_assistant_tag))
                }
            }
        }
    }
}
