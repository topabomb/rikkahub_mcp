package net.weero.measix.pilot.ui.pages.assistant.detail

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.MemoryOwner
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.service.MemoryRecord
import net.weero.measix.pilot.service.MemoryView
import net.weero.measix.pilot.ui.components.nav.BackButton
import net.weero.measix.pilot.ui.components.ui.CardGroup
import net.weero.measix.pilot.ui.components.ui.ConfirmDialog
import net.weero.measix.pilot.ui.hooks.EditStateContent
import net.weero.measix.pilot.ui.hooks.useEditState
import net.weero.measix.pilot.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun AssistantMemoryPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    AssistantLockedChangeEffect(vm)
    val toaster = net.weero.measix.pilot.ui.context.LocalToaster.current
    val memoryFailureMessage = stringResource(R.string.memory_operation_failed)
    androidx.compose.runtime.LaunchedEffect(vm, memoryFailureMessage) {
        vm.memoryFailures.collect { toaster.show(memoryFailureMessage, type = com.dokar.sonner.ToastType.Error) }
    }
    val assistant = vm.assistant.collectAsStateWithLifecycle().value
    val memories by vm.memories.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_tab_memory))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        if (assistant.id.toString() != id) {
            Text(stringResource(R.string.sub_assistant_reason_assistant_not_found), Modifier.padding(innerPadding))
            return@Scaffold
        }
        AssistantMemoryContent(
            innerPadding = innerPadding,
            assistant = assistant,
            memories = memories,
            onUpdateAssistant = { vm.update(assistant, it) },
            onDeleteMemory = { vm.deleteMemory(it) },
            onAddMemory = { vm.addMemory(it) },
            onUpdateMemory = { vm.updateMemory(it) },
            sharedDefaults = true,
        )
    }
}

@Composable
internal fun AssistantMemoryContent(
    innerPadding: PaddingValues,
    assistant: Assistant,
    memories: MemoryView,
    onUpdateAssistant: ((Assistant) -> Unit)?,
    onAddMemory: (MemoryRecord) -> Unit,
    onUpdateMemory: (MemoryRecord) -> Unit,
    onDeleteMemory: (MemoryRecord) -> Unit,
    memorySeeds: List<net.weero.measix.pilot.service.AssistantMemorySeedUiModel> = emptyList(),
    sharedDefaults: Boolean = false,
) {
    val memoryDialogState = useEditState<MemoryRecord> {
        if (it.id == 0) {
            onAddMemory(it)
        } else {
            onUpdateMemory(it)
        }
    }
    var pendingDeleteMemory by remember { mutableStateOf<MemoryRecord?>(null) }

    // 记忆对话框
    memoryDialogState.EditStateContent { memory, update ->
        AlertDialog(
            onDismissRequest = {
                memoryDialogState.dismiss()
            },
            title = {
                Text(stringResource(R.string.assistant_page_manage_memory_title))
            },
            text = {
                TextField(
                    value = memory.content,
                    onValueChange = {
                        update(memory.copy(content = it))
                    },
                    label = {
                        Text(stringResource(R.string.assistant_page_manage_memory_title))
                    },
                    minLines = 2,
                    maxLines = 8
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        memoryDialogState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.assistant_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        memoryDialogState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.assistant_page_cancel))
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(innerPadding)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (onUpdateAssistant != null) {
            if (sharedDefaults) {
                Text(stringResource(R.string.assistant_memory_shared_defaults), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.assistant_memory_shared_defaults_notice), style = MaterialTheme.typography.bodySmall)
            }
            CardGroup {
                item(
                    headlineContent = { Text(stringResource(R.string.assistant_page_memory)) },
                    supportingContent = {
                        Text(
                            text = stringResource(R.string.assistant_page_memory_desc),
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = assistant.enableMemory,
                            onCheckedChange = {
                                onUpdateAssistant(
                                    assistant.copy(
                                        enableMemory = it
                                    )
                                )
                            }
                        )
                    }
                )
                item(
                    headlineContent = { Text(stringResource(R.string.assistant_page_global_memory)) },
                    supportingContent = {
                        Text(
                            text = stringResource(R.string.assistant_page_global_memory_desc),
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = assistant.useGlobalMemory,
                            onCheckedChange = {
                                onUpdateAssistant(
                                    assistant.copy(
                                        useGlobalMemory = it
                                    )
                                )
                            },
                            enabled = assistant.enableMemory
                        )
                    }
                )
                item(
                    headlineContent = { Text(stringResource(R.string.assistant_page_recent_chats)) },
                    supportingContent = {
                        Text(
                            text = stringResource(R.string.assistant_page_recent_chats_desc),
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = assistant.enableRecentChatsReference,
                            onCheckedChange = {
                                onUpdateAssistant(
                                    assistant.copy(
                                        enableRecentChatsReference = it
                                    )
                                )
                            }
                        )
                    }
                )
                item(
                    headlineContent = { Text(stringResource(R.string.assistant_page_time_reminder)) },
                    supportingContent = {
                        Text(
                            text = stringResource(R.string.assistant_page_time_reminder_desc),
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = assistant.enableTimeReminder,
                            onCheckedChange = {
                                onUpdateAssistant(
                                    assistant.copy(
                                        enableTimeReminder = it
                                    )
                                )
                            }
                        )
                    }
                )
            }

        }
        if (memorySeeds.isNotEmpty() || onUpdateAssistant == null) {
            Text(stringResource(R.string.assistant_memory_seed_count, memorySeeds.size), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.assistant_enterprise_memory_seed_description), style = MaterialTheme.typography.bodySmall)
            if (memorySeeds.isEmpty()) Text(stringResource(R.string.assistant_memory_seed_empty), style = MaterialTheme.typography.bodySmall)
            memorySeeds.forEach { seed ->
                key(seed.id) {
                    var expanded by remember(seed.content) { mutableStateOf(false) }
                    Card(colors = CustomColors.cardColorsOnSurfaceContainer, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            androidx.compose.foundation.text.selection.SelectionContainer {
                                Text(seed.content, maxLines = if (expanded) Int.MAX_VALUE else 3,
                                    overflow = TextOverflow.Ellipsis)
                            }
                            TextButton(onClick = { expanded = !expanded }) {
                                Text(stringResource(if (expanded) R.string.assistant_memory_seed_collapse else R.string.assistant_memory_seed_expand))
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.assistant_runtime_memory_count, memories.records.size),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .weight(1f)
            )

            IconButton(
                onClick = {
                    memories.access?.let { memoryDialogState.open(MemoryRecord(it, 0, "")) }
                },
                enabled = memories.access != null,
            ) {
                Icon(
                    imageVector = HugeIcons.Add01,
                    contentDescription = stringResource(R.string.assistant_runtime_memory_add)
                )
            }
        }

        memories.access?.address?.let { address ->
            Text(stringResource(R.string.assistant_runtime_memory_scope,
                stringResource(if (address.scope == ConfigurationScope.Personal) R.string.enterprise_personal else R.string.enterprise_space),
                stringResource(if (address.owner == MemoryOwner.RealmShared) R.string.assistant_memory_owner_shared else R.string.assistant_memory_owner_private)),
                style = MaterialTheme.typography.bodySmall)
        }
        if (onUpdateAssistant == null) {
            Text(stringResource(if (assistant.enableMemory) R.string.assistant_runtime_memory_enabled
                else R.string.assistant_runtime_memory_disabled), style = MaterialTheme.typography.bodySmall)
        }
        Text(stringResource(R.string.assistant_runtime_memory_notice), style = MaterialTheme.typography.bodySmall)

        if (memories.unavailableReason != null) {
            Text(stringResource(R.string.memory_access_unavailable), color = MaterialTheme.colorScheme.error)
        }
        if (memories.access != null && memories.records.isEmpty()) {
            Text(stringResource(R.string.assistant_runtime_memory_empty), style = MaterialTheme.typography.bodySmall)
        }
        memories.records.fastForEach { memory ->
            key(memory.id) {
                MemoryItem(
                    memory = memory,
                    onEditMemory = {
                        memoryDialogState.open(it)
                    },
                    onDeleteMemory = {
                        pendingDeleteMemory = it
                    }
                )
            }
        }
    }

    ConfirmDialog(
        show = pendingDeleteMemory != null,
        title = stringResource(R.string.confirm_delete),
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            pendingDeleteMemory?.let(onDeleteMemory)
            pendingDeleteMemory = null
        },
        onDismiss = { pendingDeleteMemory = null },
        text = {
            Text(
                text = pendingDeleteMemory?.content.orEmpty(),
                maxLines = 8,
                overflow = TextOverflow.Ellipsis
            )
        }
    )
}

@Composable
private fun MemoryItem(
    memory: MemoryRecord,
    onEditMemory: (MemoryRecord) -> Unit,
    onDeleteMemory: (MemoryRecord) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "#${memory.id}",
                    style = MaterialTheme.typography.titleMediumEmphasized,
                )
                Text(
                    text = memory.content,

                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(
                onClick = { onEditMemory(memory) }
            ) {
                Icon(HugeIcons.PencilEdit01, stringResource(R.string.edit))
            }
            IconButton(
                onClick = { onDeleteMemory(memory) }
            ) {
                Icon(
                    HugeIcons.Delete01,
                    stringResource(R.string.assistant_page_delete)
                )
            }
        }
    }
}
