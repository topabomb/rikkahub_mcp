package net.weero.measix.pilot.ui.components.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFilter
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.Icon1stBracket
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.Settings03
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.ai.mcp.McpStatus
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.service.McpServerPresentation
import net.weero.measix.pilot.ui.adaptive.AdaptiveModal
import net.weero.measix.pilot.ui.components.ui.Tag
import net.weero.measix.pilot.ui.components.ui.TagType

@Composable
fun McpPickerListItem(
    assistant: Assistant,
    servers: List<McpServerPresentation>,
    modifier: Modifier = Modifier,
    onNavigateToSettings: () -> Unit,
    onUpdateAssistant: (Assistant) -> Unit
) {
    var showMcpPicker by remember { mutableStateOf(false) }
    val enabledServers = servers.fastFilter {
        it.enabled && assistant.mcpServers.contains(it.serverId)
    }
    val loading = enabledServers.any { it.isBusy }

    ListItem(
        leadingContent = {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else {
                Icon(
                    imageVector = HugeIcons.McpServer,
                    contentDescription = stringResource(R.string.mcp_picker_title),
                )
            }
        },
        trailingContent = {
            if (enabledServers.isNotEmpty()) {
                Text(
                    text = enabledServers.size.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .clickable {
                showMcpPicker = true
            },
    ) {
        Text(stringResource(R.string.mcp_picker_title))
    }

    if (showMcpPicker) {
        McpPickerSheet(
            assistant = assistant,
            servers = servers,
            onUpdateAssistant = onUpdateAssistant,
            onNavigateToSettings = onNavigateToSettings,
            onDismiss = { showMcpPicker = false },
        )
    }
}

@Composable
internal fun McpPickerSheet(
    assistant: Assistant,
    servers: List<McpServerPresentation>,
    onUpdateAssistant: (Assistant) -> Unit,
    onNavigateToSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val selectedServers = servers.fastFilter {
        it.enabled && assistant.mcpServers.contains(it.serverId)
    }
    val loading = selectedServers.any { it.isBusy }
    val hasEnabledServers = servers.any { it.enabled }
    AdaptiveModal(
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(id = R.string.mcp_picker_title),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        onDismiss()
                        onNavigateToSettings()
                    },
                ) {
                    Icon(
                        imageVector = HugeIcons.Settings03,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.mcp_picker_manage_servers))
                }
            }
            AnimatedVisibility(loading) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    LinearWavyProgressIndicator()
                    Text(
                        text = stringResource(id = R.string.mcp_picker_syncing),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
            if (hasEnabledServers) {
                McpPicker(
                    assistant = assistant,
                    servers = servers,
                    onUpdateAssistant = onUpdateAssistant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(
                            if (servers.isEmpty()) {
                                R.string.setting_mcp_page_no_mcp_servers_found
                            } else {
                                R.string.chat_readiness_mcp_all_disabled
                            }
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun McpPicker(
    assistant: Assistant,
    servers: List<McpServerPresentation>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onUpdateAssistant: (Assistant) -> Unit
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(servers.fastFilter { it.enabled }, key = { it.serverId }) { server ->
            val status = server.status
            Card {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when (status) {
                        McpStatus.Idle -> Icon(HugeIcons.Icon1stBracket, null)
                        McpStatus.Connecting -> if (server.isReady) {
                            Icon(HugeIcons.McpServer, null)
                        } else {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }

                        McpStatus.Discovering -> if (server.isReady) {
                            Icon(HugeIcons.McpServer, null)
                        } else {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                        is McpStatus.Ready -> Icon(HugeIcons.McpServer, null)
                        is McpStatus.Reconnecting -> if (status.maintenance || server.isReady) {
                            Icon(HugeIcons.Clock02, null)
                        } else {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                        is McpStatus.RetryScheduled,
                        McpStatus.WaitingNetwork,
                        is McpStatus.CatalogStale -> Icon(HugeIcons.Clock02, null)
                        McpStatus.CatalogRejectedEmpty,
                        is McpStatus.Error -> Icon(HugeIcons.Alert01, null)
                        McpStatus.NeedsAuthorization -> Icon(HugeIcons.Alert01, null)
                        McpStatus.Authorizing -> if (server.isReady) {
                            Icon(HugeIcons.Clock02, null)
                        } else {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = server.name,
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = when (val s = status) {
                                is McpStatus.Idle -> stringResource(R.string.mcp_status_idle)
                                is McpStatus.Connecting -> stringResource(R.string.mcp_status_connecting)
                                is McpStatus.Discovering -> stringResource(R.string.mcp_status_discovering)
                                is McpStatus.Ready -> stringResource(R.string.mcp_status_ready, s.toolCount)
                                is McpStatus.Reconnecting -> if (s.maintenance) {
                                    stringResource(R.string.mcp_status_maintenance_reconnecting)
                                } else {
                                    stringResource(R.string.mcp_status_reconnecting, s.attempt, s.maxAttempts)
                                }
                                is McpStatus.RetryScheduled -> if (s.maintenance) {
                                    stringResource(
                                        R.string.mcp_status_maintenance_retry,
                                        (s.retryInMs / 1000).toInt(),
                                    )
                                } else {
                                    stringResource(
                                        R.string.mcp_status_retry_scheduled,
                                        s.attempt,
                                        s.maxAttempts,
                                        (s.retryInMs / 1000).toInt(),
                                    )
                                }
                                is McpStatus.WaitingNetwork -> stringResource(R.string.mcp_status_waiting_network)
                                McpStatus.CatalogRejectedEmpty -> stringResource(R.string.mcp_status_catalog_rejected_empty)
                                is McpStatus.CatalogStale -> stringResource(R.string.mcp_status_catalog_stale, s.lastKnownGoodCount)
                                is McpStatus.Error -> s.message?.let {
                                    stringResource(R.string.mcp_status_error, it)
                                } ?: stringResource(R.string.error_title_operation)
                                is McpStatus.NeedsAuthorization -> stringResource(R.string.mcp_status_needs_authorization)
                                is McpStatus.Authorizing -> stringResource(R.string.mcp_status_authorizing)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalContentColor.current.copy(alpha = 0.8f),
                            maxLines = 5
                        )
                        if (server.tools.isNotEmpty()) {
                            val tools = server.tools
                            val enabledTools = tools.fastFilter { it.enabled }
                            Tag(
                                type = TagType.INFO
                            ) {
                                Text("${enabledTools.size}/${tools.size} tools")
                            }
                        }
                    }
                    Switch(
                        checked = server.serverId in assistant.mcpServers,
                        onCheckedChange = {
                            if (it) {
                                val newServers = assistant.mcpServers.toMutableSet()
                                newServers.add(server.serverId)
                                newServers.removeIf { selectedId -> servers.none { it.serverId == selectedId } }
                                onUpdateAssistant(
                                    assistant.copy(
                                        mcpServers = newServers.toSet()
                                    )
                                )
                            } else {
                                val newServers = assistant.mcpServers.toMutableSet()
                                newServers.remove(server.serverId)
                                newServers.removeIf { selectedId -> servers.none { it.serverId == selectedId } }
                                onUpdateAssistant(
                                    assistant.copy(
                                        mcpServers = newServers.toSet()
                                    )
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}
