package net.weero.measix.pilot.ui.pages.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Codesandbox
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Wrench01
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.getChatModel
import net.weero.measix.pilot.data.model.Assistant
import kotlin.uuid.Uuid

internal enum class ModelReadiness {
    NOT_CONFIGURED,
    NOT_SELECTED,
    READY,
}

internal enum class McpReadiness {
    NOT_CONFIGURED,
    ALL_DISABLED,
    NONE_SELECTED,
    READY,
}

internal enum class WorkspaceReadiness {
    NOT_CONFIGURED,
    NOT_BOUND,
    READY,
}

internal data class ConversationReadiness(
    val modelState: ModelReadiness,
    val modelName: String?,
    val mcpState: McpReadiness,
    val selectedMcpCount: Int,
    val enabledMcpCount: Int,
    val localToolCount: Int,
    val workspaceState: WorkspaceReadiness,
    val workspaceName: String?,
) {
    val canSend: Boolean
        get() = modelState == ModelReadiness.READY

    val requiresProviderConfiguration: Boolean
        get() = modelState == ModelReadiness.NOT_CONFIGURED
}

internal fun Settings.buildConversationReadiness(
    assistant: Assistant,
    workspaceNamesById: Map<Uuid, String>,
): ConversationReadiness {
    val hasAvailableChatModel = providers.any { provider ->
        provider.enabled && provider.models.any { it.type == ModelType.CHAT }
    }
    val selectedModel = getChatModel(assistant)
    val modelState = when {
        !hasAvailableChatModel -> ModelReadiness.NOT_CONFIGURED
        selectedModel == null -> ModelReadiness.NOT_SELECTED
        else -> ModelReadiness.READY
    }

    val enabledMcpServers = mcpServers.filter { it.commonOptions.enable }
    val selectedMcpCount = enabledMcpServers.count { it.id in assistant.mcpServers }
    val mcpState = when {
        mcpServers.isEmpty() -> McpReadiness.NOT_CONFIGURED
        enabledMcpServers.isEmpty() -> McpReadiness.ALL_DISABLED
        selectedMcpCount == 0 -> McpReadiness.NONE_SELECTED
        else -> McpReadiness.READY
    }

    val workspaceName = assistant.workspaceId?.let(workspaceNamesById::get)
    val workspaceState = when {
        workspaceNamesById.isEmpty() -> WorkspaceReadiness.NOT_CONFIGURED
        workspaceName == null -> WorkspaceReadiness.NOT_BOUND
        else -> WorkspaceReadiness.READY
    }

    return ConversationReadiness(
        modelState = modelState,
        modelName = selectedModel?.displayName?.ifBlank { selectedModel.modelId },
        mcpState = mcpState,
        selectedMcpCount = selectedMcpCount,
        enabledMcpCount = enabledMcpServers.size,
        localToolCount = assistant.localTools.distinct().size,
        workspaceState = workspaceState,
        workspaceName = workspaceName,
    )
}

@Composable
internal fun ConversationReadinessCard(
    readiness: ConversationReadiness,
    compact: Boolean,
    onModelClick: () -> Unit,
    onMcpClick: () -> Unit,
    onLocalToolsClick: () -> Unit,
    onWorkspaceClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(if (compact) 4.dp else 8.dp),
        ) {
            if (!compact) {
                Text(
                    text = stringResource(R.string.chat_readiness_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(
                        start = 6.dp,
                        end = 6.dp,
                        top = 2.dp,
                        bottom = 4.dp,
                    ),
                )
            }

            ReadinessRow(
                icon = HugeIcons.Settings03,
                label = stringResource(R.string.chat_readiness_model_title),
                status = when (readiness.modelState) {
                    ModelReadiness.NOT_CONFIGURED ->
                        stringResource(R.string.chat_readiness_model_not_configured)

                    ModelReadiness.NOT_SELECTED ->
                        stringResource(R.string.chat_readiness_model_not_selected)

                    ModelReadiness.READY -> readiness.modelName.orEmpty()
                },
                description = stringResource(R.string.chat_readiness_model_description),
                blocked = !readiness.canSend,
                onClick = onModelClick,
            )

            if (!compact) {
                ReadinessRow(
                    icon = HugeIcons.McpServer,
                    label = stringResource(R.string.chat_readiness_mcp_title),
                    status = when (readiness.mcpState) {
                        McpReadiness.NOT_CONFIGURED ->
                            stringResource(R.string.chat_readiness_mcp_not_configured)

                        McpReadiness.ALL_DISABLED ->
                            stringResource(R.string.chat_readiness_mcp_all_disabled)

                        McpReadiness.NONE_SELECTED ->
                            stringResource(R.string.chat_readiness_mcp_none_selected)

                        McpReadiness.READY -> stringResource(
                            R.string.chat_readiness_mcp_ready,
                            readiness.selectedMcpCount,
                        )
                    },
                    description = stringResource(R.string.chat_readiness_mcp_description),
                    onClick = onMcpClick,
                )
                ReadinessRow(
                    icon = HugeIcons.Wrench01,
                    label = stringResource(R.string.chat_readiness_local_tools_title),
                    status = stringResource(
                        R.string.chat_readiness_local_tools_count,
                        readiness.localToolCount,
                    ),
                    description = stringResource(R.string.chat_readiness_local_tools_description),
                    onClick = onLocalToolsClick,
                )
                ReadinessRow(
                    icon = HugeIcons.Codesandbox,
                    label = stringResource(R.string.chat_readiness_workspace_title),
                    status = when (readiness.workspaceState) {
                        WorkspaceReadiness.NOT_CONFIGURED ->
                            stringResource(R.string.chat_readiness_workspace_not_configured)

                        WorkspaceReadiness.NOT_BOUND ->
                            stringResource(R.string.chat_readiness_workspace_not_bound)

                        WorkspaceReadiness.READY -> readiness.workspaceName.orEmpty()
                    },
                    description = stringResource(R.string.chat_readiness_workspace_description),
                    onClick = onWorkspaceClick,
                )
            }
        }
    }
}

@Composable
private fun ReadinessRow(
    icon: ImageVector,
    label: String,
    status: String,
    description: String,
    blocked: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (blocked) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(18.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ReadinessStatus(
                    text = status,
                    blocked = blocked,
                )
            }
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = HugeIcons.ArrowRight01,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun ReadinessStatus(
    text: String,
    blocked: Boolean,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (blocked) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        contentColor = if (blocked) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .widthIn(max = 156.dp)
                .padding(horizontal = 7.dp, vertical = 2.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
