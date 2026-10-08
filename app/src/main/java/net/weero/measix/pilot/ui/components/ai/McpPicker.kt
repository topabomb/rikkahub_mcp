package net.weero.measix.pilot.ui.components.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.util.fastFilter
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.Building03
import me.rerere.hugeicons.stroke.Unlink01
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.Settings03
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.ai.mcp.McpCatalogRefresh
import net.weero.measix.pilot.data.ai.mcp.McpNotificationHealth
import net.weero.measix.pilot.data.ai.mcp.McpStatus
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.service.AssistantMcpChoice
import net.weero.measix.pilot.service.McpServerPresentation
import net.weero.measix.pilot.ui.adaptive.AdaptiveModal
import net.weero.measix.pilot.data.ai.mcp.McpToolUnavailableReason
import net.weero.measix.pilot.service.McpToolPresentation
import net.weero.measix.pilot.ui.components.ui.DiagnosticDisclosure

@Composable
internal fun McpPickerListItem(
    servers: List<AssistantMcpChoice>,
    modifier: Modifier = Modifier,
    onNavigateToSettings: () -> Unit,
    onToggle: (ConfigurationReference, Boolean) -> Unit,
    feedback: com.dokar.sonner.ToasterState? = null,
    onPickerVisibilityChange: (Boolean) -> Unit = {},
) {
    var showMcpPicker by remember { mutableStateOf(false) }
    val enabledServers = servers.fastFilter {
        it.selected
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
                onPickerVisibilityChange(true)
            },
    ) {
        Text(stringResource(R.string.mcp_picker_title))
    }

    if (showMcpPicker) {
        McpPickerSheet(
            servers = servers,
            onToggle = onToggle,
            onNavigateToSettings = onNavigateToSettings,
            onDismiss = { showMcpPicker = false; onPickerVisibilityChange(false) },
            feedback = feedback,
        )
    }
}

@Composable
internal fun McpPickerSheet(
    servers: List<AssistantMcpChoice>,
    onToggle: (ConfigurationReference, Boolean) -> Unit,
    onNavigateToSettings: () -> Unit,
    onDismiss: () -> Unit,
    feedback: com.dokar.sonner.ToasterState? = null,
) {
    val selectedServers = servers.fastFilter {
        it.selected
    }
    val loading = selectedServers.any { it.isBusy }
    val hasEnabledServers = servers.isNotEmpty()
    AdaptiveModal(
        onDismissRequest = onDismiss,
        feedback = feedback,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PickerHeader(
                title = stringResource(R.string.mcp_picker_title),
                onDismiss = onDismiss,
                actions = {
                    IconButton(
                        onClick = {
                            onDismiss()
                            onNavigateToSettings()
                        },
                    ) {
                        Icon(
                            imageVector = HugeIcons.Settings03,
                            contentDescription = stringResource(R.string.mcp_picker_manage_servers),
                        )
                    }
                },
            )
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
                    servers = servers,
                    onToggle = onToggle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
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
internal fun McpPicker(
    servers: List<AssistantMcpChoice>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onToggle: (ConfigurationReference, Boolean) -> Unit
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(servers, key = { it.serverId.toString() }) { server ->
            val status = server.status
            Card {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val statusIconModifier = Modifier.size(20.dp)
                        when (status) {
                            McpStatus.Idle -> Icon(HugeIcons.Unlink01, stringResource(R.string.mcp_status_on_demand), statusIconModifier)
                            McpStatus.Connecting, McpStatus.Discovering -> if (server.hasCatalogTools || server.directoryConfirmed) {
                                Icon(HugeIcons.Clock02, null, statusIconModifier)
                            } else {
                                CircularProgressIndicator(modifier = statusIconModifier)
                            }
                            is McpStatus.Ready -> Icon(when {
                                server.notifications is McpNotificationHealth.Unavailable || server.catalogRefresh is McpCatalogRefresh.Failed -> HugeIcons.Alert01
                                server.connectionsPartiallyReady -> HugeIcons.Clock02
                                !server.sessionCallable -> HugeIcons.Unlink01
                                else -> HugeIcons.McpServer
                            }, null, statusIconModifier)
                            is McpStatus.Reconnecting -> if (status.maintenance || server.hasCatalogTools || server.directoryConfirmed) {
                                Icon(HugeIcons.Clock02, null, statusIconModifier)
                            } else {
                                CircularProgressIndicator(modifier = statusIconModifier)
                            }
                            is McpStatus.RetryScheduled, McpStatus.WaitingNetwork -> Icon(HugeIcons.Clock02, null, statusIconModifier)
                            is McpStatus.Error,
                            McpStatus.NeedsAuthorization -> Icon(HugeIcons.Alert01, null, statusIconModifier)
                            McpStatus.Authorizing -> if (server.hasCatalogTools || server.directoryConfirmed) {
                                Icon(HugeIcons.Clock02, null, statusIconModifier)
                            } else {
                                CircularProgressIndicator(modifier = statusIconModifier)
                            }
                        }
                        Text(
                            text = server.name,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // Keep the system's 48dp touch target without reserving it again inside the padded header.
                        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 32.dp) {
                            Switch(
                                modifier = Modifier.semantics { contentDescription = server.name },
                                checked = server.selected,
                                enabled = server.canToggle,
                                onCheckedChange = { onToggle(server.serverId, it) },
                            )
                        }
                    }
                    // Connection and admission describe the whole server; cached tools do not prove it is callable.
                    val diagnostic = server.connectionDiagnostic ?: (status as? McpStatus.Error)?.let {
                        it.detail ?: it.message ?: stringResource(R.string.error_title_operation)
                    }
                    val statusText = server.unavailableReason?.let { configurationUnavailableText(it) }
                        ?: if (status == McpStatus.Idle) null else mcpServerStatusText(
                            status, server.tools, server.directoryConfirmed, server.sessionCallable,
                            server.connectionsPartiallyReady, server.catalogRefresh, server.notifications,
                        )
                    val catalogText = if ((status !is McpStatus.Ready || server.connectionsPartiallyReady ||
                        server.notifications is McpNotificationHealth.Unavailable || server.catalogRefresh != McpCatalogRefresh.Idle) &&
                        server.directoryConfirmed) {
                        if (server.tools.isEmpty()) stringResource(R.string.mcp_managed_empty_directory)
                        else stringResource(R.string.mcp_enabled_tools_count, server.tools.count { it.enabled }, server.tools.size)
                    } else null
                    val summaryText = statusText ?: catalogText
                    val isEnterprise = server.serverId is ConfigurationReference.Enterprise
                    val supportingModifier = Modifier.padding(start = 28.dp)
                    if (summaryText != null || diagnostic != null || isEnterprise) {
                        Row(
                            modifier = supportingModifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (isEnterprise) {
                                Icon(
                                    imageVector = HugeIcons.Building03,
                                    contentDescription = stringResource(R.string.mcp_enterprise_definitions),
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (summaryText != null) {
                                Text(
                                    modifier = Modifier.weight(1f),
                                    text = summaryText,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = if (status is McpStatus.Error && server.unavailableReason == null) 3 else Int.MAX_VALUE,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (server.unavailableReason != null || status is McpStatus.Error ||
                                        status == McpStatus.NeedsAuthorization || server.catalogRefresh == McpCatalogRefresh.RejectedEmpty ||
                                        (status is McpStatus.Ready && server.sessionCallable && server.tools.any { it.unavailableReason != null } &&
                                            server.tools.none { it.enabled })) {
                                        MaterialTheme.colorScheme.error
                                    } else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else Spacer(Modifier.weight(1f))
                            if (diagnostic != null && server.unavailableReason == null) {
                                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 32.dp) {
                                    DiagnosticDisclosure(
                                        detail = diagnostic,
                                        title = server.name,
                                        label = stringResource(R.string.mcp_connection_details),
                                        modifier = Modifier.widthIn(max = 120.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp),
                                    )
                                }
                            }
                        }
                    }
                    if (statusText != null && catalogText != null) {
                        Text(
                            text = catalogText,
                            modifier = supportingModifier,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (server.selectsAllTools != null || server.fixedByDefinition) {
                        val scope = when (server.selectsAllTools) {
                            true -> stringResource(R.string.mcp_assistant_all_tools)
                            false -> stringResource(R.string.mcp_assistant_selected_tools)
                            null -> null
                        }
                        val fixed = if (server.fixedByDefinition) stringResource(R.string.mcp_assistant_fixed_binding) else null
                        Text(
                            listOfNotNull(scope, fixed).joinToString(" · "),
                            modifier = supportingModifier,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun mcpServerStatusText(
    status: McpStatus,
    tools: List<McpToolPresentation>,
    directoryConfirmed: Boolean,
    sessionCallable: Boolean,
    connectionsPartiallyReady: Boolean = false,
    catalogRefresh: McpCatalogRefresh = McpCatalogRefresh.Idle,
    notifications: McpNotificationHealth = McpNotificationHealth.NotEstablished,
): String = when {
    status is McpStatus.Ready && notifications is McpNotificationHealth.Unavailable ->
        stringResource(R.string.mcp_notification_unavailable)
    status is McpStatus.Ready && catalogRefresh is McpCatalogRefresh.Failed ->
        if (directoryConfirmed) stringResource(R.string.mcp_catalog_refresh_failed)
        else catalogRefresh.error.message ?: stringResource(R.string.error_title_operation)
    status is McpStatus.Ready && catalogRefresh == McpCatalogRefresh.RejectedEmpty ->
        stringResource(R.string.mcp_status_catalog_rejected_empty)
    status is McpStatus.Ready && catalogRefresh == McpCatalogRefresh.Refreshing ->
        stringResource(R.string.mcp_catalog_refreshing)
    else -> if (connectionsPartiallyReady) stringResource(R.string.mcp_status_partially_connected) else when (status) {
        McpStatus.Idle -> if (directoryConfirmed) stringResource(R.string.mcp_catalog_saved, tools.size)
            else stringResource(R.string.mcp_status_on_demand)
        McpStatus.Connecting -> stringResource(R.string.mcp_status_connecting)
        McpStatus.Discovering -> stringResource(R.string.mcp_status_discovering)
        is McpStatus.Ready -> when {
            directoryConfirmed && tools.isEmpty() -> stringResource(R.string.mcp_managed_empty_directory)
            !sessionCallable -> stringResource(R.string.mcp_enabled_tools_count, tools.count { it.enabled }, tools.size)
            tools.any { it.unavailableReason != null } -> stringResource(
                R.string.mcp_tools_partially_available,
                tools.count { it.enabled },
                tools.count { it.unavailableReason != null },
            )
            else -> stringResource(R.string.mcp_status_ready, tools.count { it.enabled })
        }
        is McpStatus.Reconnecting -> if (status.maintenance) stringResource(R.string.mcp_status_maintenance_reconnecting)
            else stringResource(R.string.mcp_status_reconnecting, status.attempt, status.maxAttempts)
        is McpStatus.RetryScheduled -> if (status.maintenance) {
            stringResource(R.string.mcp_status_maintenance_retry, (status.retryInMs / 1000).toInt())
        } else stringResource(R.string.mcp_status_retry_scheduled, status.attempt, status.maxAttempts, (status.retryInMs / 1000).toInt())
        McpStatus.WaitingNetwork -> stringResource(R.string.mcp_status_waiting_network)
        is McpStatus.Error -> status.message?.let { stringResource(R.string.mcp_status_error, it) }
            ?: stringResource(R.string.error_title_operation)
        McpStatus.NeedsAuthorization -> stringResource(R.string.mcp_status_needs_authorization)
        McpStatus.Authorizing -> stringResource(R.string.mcp_status_authorizing)
    }
}

@Composable
internal fun mcpToolUnavailableText(reason: McpToolUnavailableReason): String =
    stringResource(when (reason) {
        McpToolUnavailableReason.DIRECTORY_UNAVAILABLE -> R.string.mcp_tool_directory_unavailable
        McpToolUnavailableReason.NOT_ALLOWED -> R.string.mcp_tool_not_allowed
        McpToolUnavailableReason.NOT_SELECTED -> R.string.mcp_tool_not_selected
        McpToolUnavailableReason.MISSING -> R.string.mcp_tool_missing
        McpToolUnavailableReason.CONTRACT_CHANGED -> R.string.mcp_tool_contract_changed
    })
