package net.weero.measix.pilot.ui.pages.enterprise

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.service.*
import net.weero.measix.pilot.ui.components.ai.configurationUnavailableText
import net.weero.measix.pilot.ui.pages.assistant.detail.AssistantMemoryContent
import org.koin.compose.koinInject

internal enum class EnterpriseExperienceMode { STARTERS, ASSISTANTS }

/** Native configuration consumers; the embedded Portal receives no additional bridge authority. */
@Composable
internal fun EnterpriseExperienceDialog(
    selection: RealmSelection,
    mode: EnterpriseExperienceMode,
    onOpenDraft: (StarterDraftRequest) -> Unit,
    onDismiss: () -> Unit,
) {
    val queries: ConfigurationQueryService = koinInject()
    val conversations: ConversationApplicationService = koinInject()
    val state by remember(queries, selection) { queries.observeEnterpriseExperience(selection) }
        .collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()
    var selectedStarter by remember { mutableStateOf<ConfigurationReference.Enterprise?>(null) }
    var selectedAssistant by remember { mutableStateOf<ConfigurationReference?>(null) }
    var opening by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val title = stringResource(if (mode == EnterpriseExperienceMode.STARTERS) R.string.enterprise_start_conversation else R.string.enterprise_assistants)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_card_close)) }
                }
                val available = (state as? EnterpriseExperienceReadState.Available)?.value
                if (available == null) {
                    if (state == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else Text(stringResource(R.string.enterprise_source_changed), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                } else {
                    Text(available.enterpriseName, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium)
                    if (error) Text(stringResource(R.string.enterprise_source_changed), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                    if (opening) LinearProgressIndicator(Modifier.fillMaxWidth())
                    val starter = available.starters.singleOrNull { it.target.reference == selectedStarter }
                    val assistant = available.assistants.singleOrNull { it.assistant.id == selectedAssistant }
                    when {
                        mode == EnterpriseExperienceMode.STARTERS && starter != null -> {
                            TextButton(onClick = { selectedStarter = null; error = false }, enabled = !opening) { Text(stringResource(R.string.back)) }
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(starter.title, style = MaterialTheme.typography.titleLarge)
                                Text(starter.assistantName, style = MaterialTheme.typography.labelLarge)
                                starter.description?.let { Text(it) }
                                Text(stringResource(R.string.enterprise_starter_preview), style = MaterialTheme.typography.titleMedium)
                                SelectionContainer { Text(starter.prompt) }
                                Text(stringResource(R.string.enterprise_starters_description), style = MaterialTheme.typography.bodySmall)
                            }
                            Button(onClick = {
                                if (!opening) {
                                    opening = true
                                    error = false
                                    scope.launch {
                                        try {
                                            val request = conversations.newStarterDraftRequest(starter.target)
                                            queries.requireSelection(selection)
                                            onOpenDraft(request)
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (_: Exception) { error = true }
                                        finally { opening = false }
                                    }
                                }
                            }, enabled = !opening, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                                Text(stringResource(R.string.enterprise_starter_open))
                            }
                        }
                        mode == EnterpriseExperienceMode.ASSISTANTS && assistant != null -> {
                            TextButton(onClick = { selectedAssistant = null }) { Text(stringResource(R.string.back)) }
                            key(assistant.assistant.id) {
                                EnterpriseAssistantDetails(selection, assistant, Modifier.weight(1f))
                            }
                        }
                        else -> {
                            if (mode == EnterpriseExperienceMode.STARTERS) {
                                Text(stringResource(R.string.enterprise_starters_description), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    if (available.starters.isEmpty()) item { Text(stringResource(R.string.enterprise_starters_empty)) }
                                    items(available.starters, key = { it.target.reference.toString() }) { entry ->
                                        OutlinedCard(onClick = { selectedStarter = entry.target.reference; error = false }, modifier = Modifier.fillMaxWidth()) {
                                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text(entry.title, style = MaterialTheme.typography.titleMedium)
                                                Text(entry.assistantName, style = MaterialTheme.typography.labelMedium)
                                                entry.description?.let { Text(it, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                                            }
                                        }
                                    }
                                }
                            } else {
                                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    if (available.assistants.isEmpty()) item { Text(stringResource(R.string.enterprise_assistants_empty)) }
                                    items(available.assistants, key = { it.assistant.id.toString() }) { entry ->
                                        OutlinedCard(onClick = { selectedAssistant = entry.assistant.id }, modifier = Modifier.fillMaxWidth()) {
                                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text(entry.assistant.name, style = MaterialTheme.typography.titleMedium)
                                                Text(entry.assistant.description, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                                entry.unavailableReason?.let { Text(configurationUnavailableText(it), color = MaterialTheme.colorScheme.error) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EnterpriseAssistantDetails(selection: RealmSelection, model: EnterpriseAssistantUiModel, modifier: Modifier) {
    val memory: MemoryService = koinInject()
    val memories by remember(memory, selection, model.assistant.id) { memory.observe(selection, model.assistant.id) }
        .collectAsStateWithLifecycle(MemoryView.Loading)
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var failure by remember { mutableStateOf(false) }
    fun mutate(action: suspend () -> Unit) {
        scope.launch {
            failure = false
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failure = true }
        }
    }
    Column(modifier) {
        Text(model.assistant.name, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleLarge)
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.enterprise_assistant_configuration)) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.assistant_page_tab_memory)) })
        }
        if (failure) Text(stringResource(R.string.memory_operation_failed), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
        if (tab == 0) Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.enterprise_assistant_definition_description), style = MaterialTheme.typography.bodySmall)
            model.unavailableReason?.let { Text(configurationUnavailableText(it), color = MaterialTheme.colorScheme.error) }
            SelectionContainer { Text(model.assistant.description) }
            Text(stringResource(R.string.assistant_page_chat_model), style = MaterialTheme.typography.titleMedium)
            Text(model.modelName)
            Text(stringResource(R.string.assistant_page_system_prompt), style = MaterialTheme.typography.titleMedium)
            var expanded by remember(model.assistant.systemPrompt) { mutableStateOf(false) }
            SelectionContainer { Text(model.assistant.systemPrompt, maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis) }
            TextButton(onClick = { expanded = !expanded }) { Text(stringResource(if (expanded) R.string.code_block_collapse else R.string.code_block_expand)) }
            if (model.mcpNames.isNotEmpty()) {
                Text(stringResource(R.string.assistant_page_tab_mcp), style = MaterialTheme.typography.titleMedium)
                Text(model.mcpNames.joinToString("\n"))
            }
            if (model.subAssistantNames.isNotEmpty()) {
                Text(stringResource(R.string.assistant_page_sub_assistant_access_scope), style = MaterialTheme.typography.titleMedium)
                Text(model.subAssistantNames.joinToString("\n"))
            }
        } else AssistantMemoryContent(PaddingValues(0.dp), model.assistant, memories,
            onUpdateAssistant = null,
            onAddMemory = { record -> mutate { memory.add(record.access, record.content) } },
            onUpdateMemory = { record -> mutate { memory.update(record) } },
            onDeleteMemory = { record -> mutate { memory.delete(record) } },
            memorySeeds = model.memorySeeds)
    }
}
