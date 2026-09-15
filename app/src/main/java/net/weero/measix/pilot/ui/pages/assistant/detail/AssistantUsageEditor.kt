package net.weero.measix.pilot.ui.pages.assistant.detail

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
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
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
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
    onOpenModelPicker: () -> Unit,
    onClose: () -> Unit,
    initialSection: AssistantSettingsSection? = null,
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
    var section by remember { mutableStateOf(initialSection) }
    var extensionTab by remember { mutableIntStateOf(0) }
    var reset by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    var editShared by remember { mutableStateOf(false) }
    var resetEpoch by remember { mutableIntStateOf(0) }
    val enterprise = view.access.scope is ConfigurationScope.Enterprise
    BackHandler {
        if (section != null) section = null else onClose()
    }
    fun change(value: AssistantPreferenceChange) { scope.launch { onChange(value).onFailure(onFailure) } }
    suspend fun commit(value: AssistantPreferenceChange) { onChange(value).getOrThrow() }
    fun mutateMemory(action: suspend () -> Unit) {
        scope.launch {
            try { requireOriginal(); action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { onFailure(IllegalStateException(memoryError, error)) }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (section != null) {
                IconButton(onClick = { section = null }) {
                    Icon(HugeIcons.ArrowLeft01, stringResource(R.string.back))
                }
                Spacer(Modifier.width(4.dp))
            }
            Text(
                stringResource(section?.title ?: R.string.assistant_usage_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClose) { Text(stringResource(R.string.update_card_close)) }
        }
        if (section == null) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    AssistantDetailsHome(
                        assistant = assistant,
                        sourceLabel = if (assistant.id is ConfigurationReference.Enterprise) stringResource(
                            R.string.assistant_details_enterprise_source,
                            configuration.enterpriseName ?: stringResource(R.string.enterprise_space),
                        ) else stringResource(R.string.configuration_source_user),
                        onSectionClick = { section = it },
                        onEdit = if (configuration.canEditDefinition) ({ editShared = true }) else null,
                    )
                }
                if (enterprise) {
                    item {
                        TextButton(
                            onClick = { reset = true },
                            contentPadding = PaddingValues(horizontal = 0.dp),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.assistant_usage_reset))
                        }
                    }
                }
            }
        } else {
            Box(Modifier.weight(1f)) {
            key(resetEpoch, section) {
                val update: (net.weero.measix.pilot.data.model.Assistant) -> Unit = { change(AssistantPreferenceChange.EditUsage(assistant, it)) }
                when (section) {
                    null -> Unit
                    AssistantSettingsSection.BASIC -> AssistantBasicContent(PaddingValues(0.dp), assistant, emptyList(), configuration.modelSelection.isAvailable,
                        settings.assistantTags, workspaces, update,
                        onUpdateTags = { selected, catalog -> change(AssistantPreferenceChange.Tags(selected, catalog)) },
                        onImportAvatar = { onImportImage(it, true) }, onImportBackground = { onImportImage(it, false) },
                        definitionEditable = false, usageOnly = true, imageResolver = imageResolver,
                        modelControl = {
                            val selectionUi = assistantModelSelectionUi(configuration, ::commit)
                            val selectionPresentation = modelSelectionReferencePresentation(
                                configuration.modelSelection.reference,
                                configuration.modelCatalog,
                            )
                            val selectedDefault = selectionUi.actions.firstOrNull { it.selected }
                            TextButton(onClick = onOpenModelPicker) {
                                Text(
                                    selectionPresentation.displayName ?: if (selectionPresentation.reasonAsValue) {
                                        configurationUnavailableText(requireNotNull(selectionPresentation.unavailableReason))
                                    } else {
                                        selectedDefault?.value
                                            ?: stringResource(R.string.chat_readiness_model_not_configured)
                                    },
                                )
                            }
                            selectionUi.modeLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            selectionPresentation.unavailableReason
                                ?.takeUnless {
                                    selectionPresentation.reasonAsValue || selectionPresentation.displayName == null
                                }
                                ?.let {
                                    Text(configurationUnavailableText(it), color = MaterialTheme.colorScheme.error)
                                }
                        }
                    )
                    AssistantSettingsSection.PROMPT -> Column {
                        Text(
                            stringResource(R.string.assistant_usage_prompt_read_only),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        AssistantPromptContent(PaddingValues(0.dp), assistant, settings, update,
                            definitionEditable = false, usageView = view)
                    }
                    AssistantSettingsSection.EXTENSIONS -> Column(Modifier.fillMaxSize()) {
                        val extensionLabels = listOf(
                            R.string.assistant_extensions_page_tab_quick_messages,
                            R.string.assistant_extensions_page_tab_mode_injections,
                            R.string.assistant_extensions_page_tab_skills,
                        )
                        PrimaryTabRow(selectedTabIndex = extensionTab) {
                            extensionLabels.forEachIndexed { index, label ->
                                Tab(selected = extensionTab == index, onClick = { extensionTab = index }, text = { Text(stringResource(label)) })
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            when (extensionTab) {
                                0 -> QuickMessagesContent(settings.quickMessages, assistant.quickMessageIds,
                                    onToggle = { id, enabled -> change(AssistantPreferenceChange.QuickMessage(id, enabled)) }, onManage = onManageQuickMessages)
                                1 -> ModeInjectionsContent(settings.modeInjections, assistant.modeInjectionIds,
                                    onToggle = { id, enabled -> change(AssistantPreferenceChange.PromptInjection(id, enabled)) }, onManage = onManagePrompts)
                                else -> SkillsContent(skills, assistant.enabledSkills,
                                    onToggle = { name, enabled -> change(AssistantPreferenceChange.Skill(name, enabled)) }, onManage = onManageSkills)
                            }
                        }
                    }
                    AssistantSettingsSection.MEMORY -> AssistantMemoryContent(PaddingValues(0.dp), assistant, memories, update,
                        memorySeeds = configuration.memorySeeds,
                        onAddMemory = { record -> mutateMemory { memory.add(record.access, record.content) } },
                        onUpdateMemory = { record -> mutateMemory { memory.update(record) } },
                        onDeleteMemory = { record -> mutateMemory { memory.delete(record) } })
                    AssistantSettingsSection.REQUEST -> AssistantRequestContent(PaddingValues(0.dp), assistant, update)
                    AssistantSettingsSection.MCP -> McpPicker(mcpChoices, onToggle = { id, enabled -> change(AssistantPreferenceChange.Mcp(id, enabled)) })
                    AssistantSettingsSection.LOCAL_TOOLS -> AssistantLocalToolContent(PaddingValues(0.dp), assistant, configuration.assistants.values.toList(),
                        configuration.imageGenerationAvailable, onToggleLocalTool = { tool, enabled -> change(AssistantPreferenceChange.LocalTool(tool, enabled)) },
                        onUpdateSubAssistantIds = null,
                        onToggleSubAssistant = { id, enabled -> onChange(AssistantPreferenceChange.SubAssistant(id, enabled)).onFailure(onFailure).isSuccess },
                        inheritedSubAssistantIds = configuration.inheritedSubAssistantIds, imageResolver = imageResolver,
                        subAssistantAccess = configuration.resources.filter { it.key.category == ConfigurationCategory.ASSISTANT }.associate { it.key.reference to it.access })
                }
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
        confirmButton = { TextButton(onClick = {
            try {
                requireOriginal()
                editShared = false
                onEditSharedDefinition()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { editShared = false; onFailure(error) }
        }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = { editShared = false }) { Text(stringResource(android.R.string.cancel)) } })
}
