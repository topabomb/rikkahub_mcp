package net.weero.measix.pilot.ui.pages.assistant.detail

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ModelType
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.configuration.AssistantPreferenceChange
import net.weero.measix.pilot.data.configuration.ConfigurationCategory
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.files.SkillManager
import net.weero.measix.pilot.data.files.SkillMetadata
import net.weero.measix.pilot.service.*
import net.weero.measix.pilot.service.workspace.WorkspaceUiModel
import net.weero.measix.pilot.ui.components.ai.*
import org.koin.compose.koinInject

/** Borrows the open chat target; no navigation identity or second settings snapshot is persisted. */
@Composable
internal fun AssistantUsageEditor(
    configuration: ConversationConfigurationUiModel,
    view: ConversationViewLease,
    settings: Settings,
    workspaces: List<WorkspaceUiModel>,
    mcpChoices: List<AssistantMcpChoice>,
    onChange: suspend (AssistantPreferenceChange) -> Result<Unit>,
    onFailure: (Throwable) -> Unit,
    onImportImage: suspend (Uri, Boolean) -> Unit,
    requireOriginal: () -> Unit,
    onEditSharedDefinition: () -> Unit,
    onManageQuickMessages: () -> Unit,
    onManagePrompts: () -> Unit,
    onManageSkills: () -> Unit,
    onClose: () -> Unit,
    initialTab: Int = 0,
) {
    val assistant = configuration.assistant ?: return
    val scope = rememberCoroutineScope()
    val memory: MemoryService = koinInject()
    val files: FileManagementApplicationService = koinInject()
    val skillManager: SkillManager = koinInject()
    val skills by produceState<List<SkillMetadata>>(emptyList(), skillManager) { value = skillManager.listSkills() }
    val memories by remember(view, assistant.id) { memory.observe(view, assistant.id) }
        .collectAsStateWithLifecycle(MemoryView.Loading)
    val imageResolver: suspend (String) -> ImageSource? = remember(files, view) { { files.resolveConfigurationImage(it, view) } }
    val memoryError = stringResource(R.string.memory_operation_failed)
    var tab by remember { mutableIntStateOf(initialTab) }
    var reset by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    var editShared by remember { mutableStateOf(false) }
    var resetEpoch by remember { mutableIntStateOf(0) }
    val enterprise = view.access.scope is ConfigurationScope.Enterprise
    fun change(value: AssistantPreferenceChange) { scope.launch { onChange(value).onFailure(onFailure) } }
    suspend fun commit(value: AssistantPreferenceChange) { onChange(value).getOrThrow() }
    fun mutateMemory(action: suspend () -> Unit) {
        scope.launch {
            try { requireOriginal(); action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { onFailure(IllegalStateException(memoryError, error)) }
        }
    }
    val tabs = listOf(R.string.assistant_page_tab_basic, R.string.assistant_page_tab_prompt,
        R.string.assistant_page_tab_request, R.string.assistant_page_tab_memory,
        R.string.assistant_page_tab_local_tools, R.string.assistant_page_tab_mcp,
        R.string.assistant_extensions_page_tab_quick_messages, R.string.assistant_extensions_page_tab_mode_injections,
        R.string.assistant_extensions_page_tab_skills)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.assistant_usage_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text(stringResource(R.string.update_card_close)) }
        }
        Text(assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) }, style = MaterialTheme.typography.titleMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp))
        Text(stringResource(R.string.assistant_usage_description), style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (enterprise) TextButton(onClick = { reset = true }) { Text(stringResource(R.string.assistant_usage_reset)) }
            if (configuration.canEditDefinition) TextButton(onClick = { editShared = true }) { Text(stringResource(R.string.assistant_usage_shared_edit)) }
        }
        PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
            tabs.forEachIndexed { index, label -> Tab(selected = tab == index, onClick = { tab = index }, text = { Text(stringResource(label)) }) }
        }
        Box(Modifier.weight(1f)) {
            key(resetEpoch, tab) {
                val update: (net.weero.measix.pilot.data.model.Assistant) -> Unit = { change(AssistantPreferenceChange.EditUsage(assistant, it)) }
                when (tab) {
                    0 -> AssistantBasicContent(PaddingValues(0.dp), assistant, emptyList(), configuration.modelSelection.isAvailable,
                        settings.assistantTags, workspaces, update,
                        onUpdateTags = { selected, catalog -> change(AssistantPreferenceChange.Tags(selected, catalog)) },
                        onImportAvatar = { onImportImage(it, true) }, onImportBackground = { onImportImage(it, false) },
                        definitionEditable = false, imageResolver = imageResolver,
                        modelControl = {
                            if (!configuration.canChangeModel) {
                                Text(configuration.model?.displayName ?: configuration.modelSelection.reference.toString())
                                Text(stringResource(R.string.assistant_usage_fixed_model), style = MaterialTheme.typography.bodySmall)
                            } else {
                                val state = rememberModelListState(modelId = configuration.modelSelection.reference,
                                    catalog = configuration.modelCatalog, type = ModelType.CHAT)
                                ModelSelectorButton(state, onClear = { change(AssistantPreferenceChange.Model(null)) },
                                    placeholder = stringResource(R.string.assistant_page_follow_default_model))
                                val inherit = stringResource(R.string.assistant_usage_inherit_model)
                                ModelListSheet(state, onSelect = { commit(AssistantPreferenceChange.Model(it.id)) },
                                    additionalActions = if (enterprise) listOf(ModelSelectionAction(inherit) { commit(AssistantPreferenceChange.InheritModel) }) else emptyList())
                            }
                            configuration.modelSelection.unavailableReason?.let {
                                Text(configurationUnavailableText(it), color = MaterialTheme.colorScheme.error)
                            }
                        })
                    1 -> AssistantPromptContent(PaddingValues(0.dp), assistant, settings, update, definitionEditable = false, usageView = view)
                    2 -> AssistantRequestContent(PaddingValues(0.dp), assistant, update)
                    3 -> AssistantMemoryContent(PaddingValues(0.dp), assistant, memories, update,
                        memorySeeds = configuration.memorySeeds,
                        onAddMemory = { record -> mutateMemory { memory.add(record.access, record.content) } },
                        onUpdateMemory = { record -> mutateMemory { memory.update(record) } },
                        onDeleteMemory = { record -> mutateMemory { memory.delete(record) } })
                    4 -> AssistantLocalToolContent(PaddingValues(0.dp), assistant, configuration.assistants.values.toList(),
                        configuration.imageGenerationAvailable, onToggleLocalTool = { tool, enabled -> change(AssistantPreferenceChange.LocalTool(tool, enabled)) },
                        onUpdateSubAssistantIds = null,
                        onToggleSubAssistant = { id, enabled -> onChange(AssistantPreferenceChange.SubAssistant(id, enabled)).onFailure(onFailure).isSuccess },
                        inheritedSubAssistantIds = configuration.inheritedSubAssistantIds, imageResolver = imageResolver,
                        subAssistantAccess = configuration.resources.filter { it.key.category == ConfigurationCategory.ASSISTANT }.associate { it.key.reference to it.access })
                    5 -> McpPicker(mcpChoices, onToggle = { id, enabled -> change(AssistantPreferenceChange.Mcp(id, enabled)) })
                    6 -> QuickMessagesContent(settings.quickMessages, assistant.quickMessageIds,
                        onToggle = { id, enabled -> change(AssistantPreferenceChange.QuickMessage(id, enabled)) }, onManage = onManageQuickMessages)
                    7 -> ModeInjectionsContent(settings.modeInjections, assistant.modeInjectionIds,
                        onToggle = { id, enabled -> change(AssistantPreferenceChange.PromptInjection(id, enabled)) }, onManage = onManagePrompts)
                    8 -> SkillsContent(skills, assistant.enabledSkills,
                        onToggle = { name, enabled -> change(AssistantPreferenceChange.Skill(name, enabled)) }, onManage = onManageSkills)
                }
            }
        }
    }
    if (reset) AlertDialog(onDismissRequest = { if (!resetting) reset = false },
        title = { Text(stringResource(R.string.assistant_usage_reset)) },
        text = { Text(stringResource(R.string.assistant_usage_reset_description)) },
        confirmButton = { TextButton(enabled = !resetting, onClick = { resetting = true; scope.launch { try { if (onChange(AssistantPreferenceChange.ResetUsage).onFailure(onFailure).isSuccess) { reset = false; resetEpoch++ } } finally { resetting = false } } }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(enabled = !resetting, onClick = { reset = false }) { Text(stringResource(android.R.string.cancel)) } })
    if (editShared) AlertDialog(onDismissRequest = { editShared = false },
        title = { Text(stringResource(R.string.assistant_usage_shared_edit)) },
        text = { Text(stringResource(R.string.assistant_usage_shared_warning)) },
        confirmButton = { TextButton(onClick = { requireOriginal(); editShared = false; onEditSharedDefinition() }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = { editShared = false }) { Text(stringResource(android.R.string.cancel)) } })
}
