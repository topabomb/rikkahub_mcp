package net.weero.measix.pilot.ui.components.ai

import me.rerere.common.configuration.ConfigurationReference
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
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
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Edit03
import me.rerere.hugeicons.stroke.Tick02
import me.rerere.hugeicons.stroke.User
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.configuration.ConfigurationUnavailableReason
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.service.AssistantModelSummary
import net.weero.measix.pilot.ui.adaptive.AdaptiveModal
import net.weero.measix.pilot.ui.components.ui.UIAvatar
import net.weero.measix.pilot.ui.components.ui.OrchelmLogo
import net.weero.measix.pilot.ui.components.ui.Tooltip
import net.weero.measix.pilot.ui.pages.assistant.detail.AssistantDetailsHome
import net.weero.measix.pilot.ui.pages.assistant.detail.AssistantSettingsSection
import net.weero.measix.pilot.data.model.Avatar
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

internal fun assistantsForPicker(
    assistants: List<Assistant>,
    unavailableReasons: Map<ConfigurationReference, net.weero.measix.pilot.data.configuration.ConfigurationUnavailableReason?>,
    currentAssistantId: ConfigurationReference?,
): List<Assistant> = assistants.filter { assistant ->
    unavailableReasons[assistant.id] == null || assistant.id == currentAssistantId
}

internal fun currentAssistantUnavailableReason(
    assistants: Map<ConfigurationReference, Assistant>,
    unavailableReasons: Map<ConfigurationReference, ConfigurationUnavailableReason?>,
    currentAssistantId: ConfigurationReference?,
): ConfigurationUnavailableReason? = when {
    currentAssistantId == null -> null
    assistants[currentAssistantId] == null -> unavailableReasons[currentAssistantId]
        ?: ConfigurationUnavailableReason.REFERENCE_MISSING
    else -> unavailableReasons[currentAssistantId]
}

@Composable
internal fun AssistantPicker(
    settings: Settings,
    currentAssistantId: ConfigurationReference?,
    assistants: Map<ConfigurationReference, Assistant>,
    unavailableReasons: Map<ConfigurationReference, net.weero.measix.pilot.data.configuration.ConfigurationUnavailableReason?>,
    onSelectAssistant: suspend (ConfigurationReference) -> Boolean,
    modifier: Modifier = Modifier,
    onManageAssistant: (() -> Unit)?,
    onViewAssistant: ((Assistant) -> Unit)? = null,
) {
    val currentAssistant = assistants[currentAssistantId]
    val currentUnavailableReason = currentAssistantUnavailableReason(assistants, unavailableReasons, currentAssistantId)
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
                    subAssistant = currentAssistant?.allowAsSubAssistant == true,
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
                        text = currentAssistant?.name?.ifEmpty { defaultAssistantName }
                            ?: if (currentAssistantId != null) configurationUnavailableText(
                                currentUnavailableReason ?: ConfigurationUnavailableReason.REFERENCE_MISSING,
                            ) else stringResource(R.string.safe_mode_switch_assistant),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (currentAssistant == null && currentAssistantId != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    HugeIcons.ArrowDown01,
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
                    HugeIcons.Edit03,
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
            },
            onViewAssistant = onViewAssistant?.let { view -> { assistant ->
                showPicker = false
                view(assistant)
            } },
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
    onViewAssistant: ((Assistant) -> Unit)? = null,
    enabled: Boolean = true,
) {
    val defaultAssistantName = stringResource(R.string.assistant_page_default_assistant)
    val sheetTitle = title ?: stringResource(R.string.safe_mode_switch_assistant)
    // 标签过滤状态
    var selectedTagIds by remember { mutableStateOf(emptySet<ConfigurationReference>()) }
    // 搜索关键词状态
    var searchQuery by remember { mutableStateOf("") }

    val pickerAssistants = remember(assistants, unavailableReasons, currentAssistantId) {
        assistantsForPicker(assistants, unavailableReasons, currentAssistantId)
    }
    // 当前会话直接使用子助手时默认开启；不存在可选的普通 Assistant 时自动显示全部。
    val hasNormalAssistants = pickerAssistants.any { !it.allowAsSubAssistant }
    val hasPickerSubAssistants = pickerAssistants.any { it.allowAsSubAssistant }
    var showSubAssistants by remember {
        mutableStateOf(
            (pickerAssistants.find { it.id == currentAssistantId }?.allowAsSubAssistant == true) ||
                !hasNormalAssistants,
        )
    }

    // 类型筛选先执行，再叠加 name/description 搜索和 Tag 筛选
    val showSource = pickerAssistants.any { it.id is ConfigurationReference.Enterprise } &&
        pickerAssistants.any { it.id is ConfigurationReference.User }
    val visibleTagIds = remember(pickerAssistants) { pickerAssistants.flatMap { it.tags }.toSet() }
    val visibleTags = remember(settings.assistantTags, visibleTagIds) {
        settings.assistantTags.filter { it.id in visibleTagIds }
    }
    val filteredAssistants = remember(pickerAssistants, selectedTagIds, searchQuery, showSubAssistants) {
        pickerAssistants.filter { assistant ->
            val matchesType = showSubAssistants || !assistant.allowAsSubAssistant
            val matchesSearch = searchQuery.isBlank() ||
                assistant.name.contains(searchQuery, ignoreCase = true) ||
                assistant.description.contains(searchQuery, ignoreCase = true)
            val matchesTags = selectedTagIds.isEmpty() ||
                assistant.tags.any { tagId -> tagId in selectedTagIds }
            matchesType && matchesSearch && matchesTags
        }
    }
    val hasActiveFilters = searchQuery.isNotBlank() || selectedTagIds.isNotEmpty() ||
        (hasPickerSubAssistants && !showSubAssistants)

    AdaptiveModal(
        onDismissRequest = onDismiss,
        forceDialog = forceDialog,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PickerHeader(title = sheetTitle, onDismiss = onDismiss)

            AssistantSearchFilterRow(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                showSubAssistants = showSubAssistants,
                onShowSubAssistantsChange = { showSubAssistants = it },
                showSubAssistantFilter = hasPickerSubAssistants,
            )

            if (visibleTags.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 8.dp),
                ) {
                    items(visibleTags, key = { tag -> tag.id.toString() }) { tag ->
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
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (currentAssistantId != null && assistants.none { it.id == currentAssistantId }) {
                    item(key = "missing-current-assistant") {
                        Text(
                            configurationUnavailableText(
                                unavailableReasons[currentAssistantId] ?: ConfigurationUnavailableReason.REFERENCE_MISSING,
                            ),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                        )
                    }
                }
                if (filteredAssistants.isEmpty()) {
                    item(key = "no-assistant-results") {
                        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                stringResource(R.string.search_page_no_results),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (hasActiveFilters) {
                                TextButton(onClick = {
                                    searchQuery = ""
                                    selectedTagIds = emptySet()
                                    showSubAssistants = true
                                }) {
                                    Text(stringResource(R.string.clear_search))
                                }
                            }
                        }
                    }
                }
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
                            showSource = showSource,
                            selected = checked,
                            onView = onViewAssistant?.takeIf {
                                enabled && unavailableReasons[assistant.id] == null
                            }?.let { view ->
                                { view(assistant) }
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
    showSource: Boolean,
    selected: Boolean,
    onView: (() -> Unit)? = null,
) {
    ListItem(
        leadingContent = {
            UIAvatar(
                name = assistant.name.ifEmpty { defaultAssistantName },
                value = assistant.avatar,
                modifier = Modifier.size(36.dp),
                subAssistant = assistant.allowAsSubAssistant,
            )
        },
        trailingContent = if (selected || onView != null) {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selected) {
                        Icon(
                            imageVector = HugeIcons.Tick02,
                            contentDescription = stringResource(R.string.assistant_picker_current),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    val view = onView
                    if (view != null) {
                        IconButton(onClick = view) {
                            Icon(
                                imageVector = HugeIcons.ArrowRight01,
                                contentDescription = stringResource(R.string.assistant_picker_view_details),
                            )
                        }
                    }
                }
            }
        } else null,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = assistant.name.ifEmpty { defaultAssistantName },
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showSource) {
                val enterprise = assistant.id is ConfigurationReference.Enterprise
                val source = stringResource(
                    if (enterprise) R.string.configuration_source_enterprise
                    else R.string.configuration_source_user,
                )
                Tooltip(tooltip = { Text(source) }) {
                    if (enterprise) {
                        OrchelmLogo(
                            contentDescription = source,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                    } else {
                        Icon(
                            imageVector = HugeIcons.User,
                            contentDescription = source,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Read-only catalog detail for an assistant that has not been selected into a conversation. */
@Composable
internal fun AssistantCatalogDetails(
    assistant: Assistant,
    modelSummary: AssistantModelSummary,
    enterpriseName: String?,
    onDismiss: () -> Unit,
    onEdit: (() -> Unit)? = null,
) {
    var section by remember(assistant.id) { mutableStateOf<AssistantSettingsSection?>(null) }
    BackHandler(enabled = section != null) { section = null }
    AdaptiveModal(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PickerHeader(
                title = stringResource(section?.title ?: R.string.assistant_details_title),
                onDismiss = onDismiss,
                leadingContent = {
                    if (section != null) {
                        IconButton(onClick = { section = null }) {
                            Icon(HugeIcons.ArrowLeft01, contentDescription = stringResource(R.string.back))
                        }
                    }
                },
            )
            if (section == null) {
                AssistantDetailsHome(
                    assistant = assistant,
                    sourceLabel = stringResource(
                        if (assistant.id is ConfigurationReference.Enterprise) R.string.assistant_details_enterprise_source
                        else R.string.configuration_source_user,
                        *if (assistant.id is ConfigurationReference.Enterprise) {
                            arrayOf(enterpriseName ?: stringResource(R.string.enterprise_space))
                        } else emptyArray(),
                    ),
                    onSectionClick = { section = it },
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    onEdit = onEdit,
                )
            } else {
                Column(
                    modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (section) {
                        AssistantSettingsSection.BASIC -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            AssistantSummaryRow(
                                label = stringResource(R.string.assistant_page_chat_model),
                                value = when {
                                    modelSummary.reference == null -> stringResource(R.string.assistant_page_follow_default_model)
                                    modelSummary.displayName != null -> modelSummary.displayName
                                    else -> modelSummary.reference.toString()
                                },
                                maxLines = 3,
                            )
                            modelSummary.unavailableReason?.let { reason ->
                                Text(
                                    configurationUnavailableText(reason),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        AssistantSettingsSection.PROMPT -> AssistantSummaryRow(
                            label = stringResource(R.string.assistant_page_system_prompt),
                            value = assistant.systemPrompt.ifBlank { stringResource(R.string.configuration_reason_missing) },
                            maxLines = 12,
                        )
                        AssistantSettingsSection.EXTENSIONS -> AssistantSummaryRow(
                            label = stringResource(R.string.assistant_page_tab_extensions),
                            value = stringResource(
                                R.string.prompt_page_entries_count_format,
                                assistant.quickMessageIds.size + assistant.modeInjectionIds.size + assistant.enabledSkills.size,
                            ),
                        )
                        AssistantSettingsSection.MEMORY -> AssistantSummaryRow(
                            label = stringResource(R.string.chat_readiness_memory_title),
                            value = stringResource(
                                if (assistant.enableMemory) R.string.setting_provider_page_enabled
                                else R.string.setting_provider_page_disabled,
                            ),
                        )
                        AssistantSettingsSection.REQUEST -> AssistantSummaryRow(
                            label = stringResource(R.string.assistant_page_tab_request),
                            value = stringResource(
                                R.string.prompt_page_entries_count_format,
                                assistant.customHeaders.size + assistant.customBodies.size,
                            ),
                        )
                        AssistantSettingsSection.MCP -> AssistantSummaryRow(
                            label = "MCP",
                            value = stringResource(R.string.chat_readiness_local_tools_count, assistant.mcpServers.size),
                        )
                        AssistantSettingsSection.LOCAL_TOOLS -> AssistantSummaryRow(
                            label = stringResource(R.string.chat_readiness_local_tools_title),
                            value = stringResource(R.string.chat_readiness_local_tools_count, assistant.localTools.distinct().size),
                        )
                        null -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantSummaryRow(label: String, value: String, maxLines: Int = 2) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}
