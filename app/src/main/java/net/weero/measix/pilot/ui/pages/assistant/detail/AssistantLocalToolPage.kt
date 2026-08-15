package net.weero.measix.pilot.ui.pages.assistant.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import net.weero.measix.pilot.R
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.data.ai.tools.local.LocalToolOption
import net.weero.measix.pilot.data.imggen.ImageGenerationSelectionResolver
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.Avatar
import net.weero.measix.pilot.ui.components.nav.BackButton
import net.weero.measix.pilot.ui.context.LocalNavController
import net.weero.measix.pilot.ui.components.ui.CardGroup
import net.weero.measix.pilot.ui.components.ui.UIAvatar
import net.weero.measix.pilot.ui.components.ui.permission.PermissionInfo
import net.weero.measix.pilot.ui.components.ui.permission.PermissionManager
import net.weero.measix.pilot.ui.components.ui.permission.rememberPermissionState
import net.weero.measix.pilot.ui.context.LocalToaster
import net.weero.measix.pilot.ui.theme.CustomColors
import net.weero.measix.pilot.utils.hasUsageStatsPermission
import net.weero.measix.pilot.utils.openUsageAccessSettings
import org.koin.compose.koinInject
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

internal fun eligibleSubAssistantIds(settings: net.weero.measix.pilot.data.datastore.Settings, callerId: Uuid): Set<Uuid> =
    settings.assistants
        .asSequence()
        .filter { it.id != callerId && it.allowAsSubAssistant }
        .mapTo(mutableSetOf()) { it.id }

@Composable
fun AssistantLocalToolPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_tab_local_tools))
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
        AssistantLocalToolContent(
            innerPadding = innerPadding,
            assistant = assistant,
            onUpdate = { vm.update(it) }
        )
    }
}

@Composable
private fun AssistantLocalToolContent(
    innerPadding: PaddingValues,
    assistant: Assistant,
    onUpdate: (Assistant) -> Unit
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val navController = LocalNavController.current
    val settingsStore: net.weero.measix.pilot.data.datastore.SettingsStore = koinInject()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val imageSelectionResolver: ImageGenerationSelectionResolver = koinInject()
    val imageGenerationAvailable = remember(settings) { imageSelectionResolver.isAvailable(settings) }
    val textToImageEnabled = assistant.localTools.contains(LocalToolOption.TextToImage)
    var showAccessScopeDialog by remember { mutableStateOf(false) }
    val eligibleTargetIds = remember(settings.assistants, assistant.id) {
        eligibleSubAssistantIds(settings, assistant.id)
    }
    val permissionRequiredText =
        stringResource(R.string.assistant_page_local_tools_screen_time_permission_required)

    val calendarPermissionState = rememberPermissionState(
        permissions = setOf(
            PermissionInfo(
                permission = android.Manifest.permission.READ_CALENDAR,
                displayName = { Text(stringResource(R.string.permission_calendar_read)) },
                usage = { Text(stringResource(R.string.permission_calendar_read_desc)) },
                required = true
            ),
            PermissionInfo(
                permission = android.Manifest.permission.WRITE_CALENDAR,
                displayName = { Text(stringResource(R.string.permission_calendar_write)) },
                usage = { Text(stringResource(R.string.permission_calendar_write_desc)) },
                required = true
            ),
        )
    )
    PermissionManager(permissionState = calendarPermissionState)

    fun toggleLocalTool(option: LocalToolOption, enabled: Boolean) {
        if (enabled && option == LocalToolOption.ScreenTime && !context.hasUsageStatsPermission()) {
            toaster.show(message = permissionRequiredText, type = ToastType.Warning)
            context.openUsageAccessSettings()
            return
        }
        if (enabled && option == LocalToolOption.Calendar && !calendarPermissionState.allPermissionsGranted) {
            calendarPermissionState.requestPermissions()
            return
        }
        val newLocalTools = if (enabled) {
            assistant.localTools + option
        } else {
            assistant.localTools - option
        }
        onUpdate(assistant.copy(localTools = newLocalTools))
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
        // 助手协作 CardGroup
        CardGroup {
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_assistant_management_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_assistant_management_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.AssistantManagement),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.AssistantManagement, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_assistant_delegation_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_assistant_delegation_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.AssistantDelegation),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.AssistantDelegation, it) }
                    )
                }
            )
            // 子助手访问范围：仅在有子助手工具时显示
            if (assistant.localTools.contains(LocalToolOption.AssistantManagement) ||
                assistant.localTools.contains(LocalToolOption.AssistantDelegation)
            ) {
                item(
                    headlineContent = {
                        Text(stringResource(R.string.assistant_page_sub_assistant_access_scope))
                    },
                    supportingContent = {
                        val allowedCount = assistant.allowedSubAssistantIds.count { it in eligibleTargetIds }
                        Text(
                            if (allowedCount == 0) {
                                stringResource(R.string.assistant_page_sub_assistant_access_scope_empty)
                            } else {
                                stringResource(R.string.assistant_page_sub_assistant_access_scope_count, allowedCount)
                            }
                        )
                    },
                    trailingContent = {
                        AssistChip(
                            onClick = { showAccessScopeDialog = true },
                            label = { Text(stringResource(R.string.assistant_page_sub_assistant_access_scope)) }
                        )
                    }
                )
            }
        }

        CardGroup {
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_javascript_engine_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_javascript_engine_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.JavascriptEngine),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.JavascriptEngine, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_time_info_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_time_info_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.TimeInfo),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.TimeInfo, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_clipboard_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_clipboard_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.Clipboard),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Clipboard, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_tts_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_tts_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.Tts),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Tts, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_ask_user_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_ask_user_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.AskUser),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.AskUser, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_screen_time_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_screen_time_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.ScreenTime),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.ScreenTime, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_calendar_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_calendar_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.Calendar),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Calendar, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_text_to_image_title))
                },
                supportingContent = {
                    Text(
                        if (!imageGenerationAvailable && textToImageEnabled) {
                            stringResource(R.string.assistant_page_local_tools_text_to_image_unavailable)
                        } else {
                            stringResource(R.string.assistant_page_local_tools_text_to_image_desc)
                        }
                    )
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!imageGenerationAvailable) {
                            AssistChip(
                                onClick = { navController.navigate(Screen.SettingModels) },
                                label = {
                                    Text(stringResource(R.string.assistant_page_local_tools_text_to_image_configure_model))
                                },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Switch(
                            checked = textToImageEnabled,
                            enabled = imageGenerationAvailable || textToImageEnabled,
                            onCheckedChange = { enabled ->
                                if (enabled && !imageGenerationAvailable) return@Switch
                                toggleLocalTool(LocalToolOption.TextToImage, enabled)
                            },
                        )
                    }
                }
            )
        }
    }

    // 子助手访问范围多选对话框
    if (showAccessScopeDialog) {
        var accessSearchQuery by remember(showAccessScopeDialog) { mutableStateOf("") }
        val candidateSubAssistants = remember(settings, assistant.id) {
            settings.assistants.filter {
                it.id != assistant.id && it.allowAsSubAssistant
            }
        }
        val filteredCandidates = remember(candidateSubAssistants, accessSearchQuery) {
            candidateSubAssistants.filter { candidate ->
                accessSearchQuery.isBlank() ||
                    candidate.name.contains(accessSearchQuery, ignoreCase = true) ||
                    candidate.description.contains(accessSearchQuery, ignoreCase = true)
            }
        }
        val selectedIds = remember(assistant.allowedSubAssistantIds, eligibleTargetIds) {
            mutableStateOf(
                assistant.allowedSubAssistantIds
                    .filterTo(mutableSetOf()) { it in eligibleTargetIds }
            )
        }

        AlertDialog(
            onDismissRequest = { showAccessScopeDialog = false },
            title = { Text(stringResource(R.string.assistant_page_sub_assistant_access_scope)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(maxHeight = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.assistant_page_sub_assistant_access_scope_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = accessSearchQuery,
                        onValueChange = { accessSearchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.assistant_page_search_placeholder)) },
                        singleLine = true,
                    )
                    filteredCandidates.forEach { sub ->
                        SubAssistantScopeItem(
                            sub = sub,
                            checked = sub.id in selectedIds.value,
                            onCheckedChange = { checked ->
                                selectedIds.value = (if (checked) {
                                    selectedIds.value + sub.id
                                } else {
                                    selectedIds.value - sub.id
                                }).toMutableSet()
                            }
                        )
                    }
                    if (filteredCandidates.isEmpty()) {
                        Text(
                            text = stringResource(
                                if (candidateSubAssistants.isEmpty()) {
                                    R.string.assistant_page_sub_assistant_access_scope_empty
                                } else {
                                    R.string.search_page_no_results
                                }
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onUpdate(
                        assistant.copy(
                            allowedSubAssistantIds = selectedIds.value.filterTo(mutableSetOf()) {
                                it in eligibleTargetIds
                            }
                        )
                    )
                    showAccessScopeDialog = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showAccessScopeDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

/**
 * 子助手访问范围对话框中的单条子助手条目。
 *
 * 布局：Checkbox + 头像 + 名称/描述 + 全局可用标签
 * - 点击整行可切换选中状态
 * - 名称下方优先显示描述（描述为空时不占空间）
 * - 全局可见子助手显示标签提示
 */
@Composable
private fun SubAssistantScopeItem(
    sub: Assistant,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
        UIAvatar(
            name = sub.name,
            value = sub.avatar,
            modifier = Modifier.size(32.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = sub.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (sub.isSubAssistantGloballyVisible) {
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                    ) {
                        Text(
                            text = stringResource(R.string.sub_assistant_global_tag),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            // 描述非空时显示，为空时不占空间
            if (sub.description.isNotBlank()) {
                Text(
                    text = sub.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
