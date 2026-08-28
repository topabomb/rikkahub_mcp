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
import androidx.compose.material3.FilledTonalIconButton
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Codesandbox
import me.rerere.hugeicons.stroke.Edit03
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Wrench01
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.ai.tools.local.LocalToolOption
import net.weero.measix.pilot.data.ai.mcp.McpStatus
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.getChatModel
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.ui.adaptive.ChatLayoutMode
import net.weero.measix.pilot.ui.adaptive.LocalAdaptiveLayoutInfo
import net.weero.measix.pilot.ui.components.ui.UIAvatar
import net.weero.measix.pilot.ui.theme.LocalChatFontSizeRatio
import net.weero.measix.pilot.ui.theme.asChatChrome
import net.weero.measix.pilot.service.McpServerPresentation
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
    CONNECTING,
    AUTHORIZATION_REQUIRED,
    RECONNECTING,
    UNAVAILABLE,
    PARTIAL,
    READY,
}

internal enum class WorkspaceReadiness {
    NOT_CONFIGURED,
    NOT_BOUND,
    READY,
}

internal enum class MemoryReadiness {
    DISABLED,
    READY,
}

internal data class ConversationReadiness(
    val modelState: ModelReadiness,
    val modelName: String?,
    val mcpState: McpReadiness,
    val selectedMcpCount: Int,
    val readyMcpCount: Int,
    val enabledMcpCount: Int,
    val localToolCount: Int,
    val persistedLocalToolCount: Int = localToolCount,
    val memoryState: MemoryReadiness,
    val memoryCount: Int,
    val workspaceState: WorkspaceReadiness,
    val workspaceName: String?,
) {
    val canSend: Boolean
        get() = modelState == ModelReadiness.READY

    val requiresProviderConfiguration: Boolean
        get() = modelState == ModelReadiness.NOT_CONFIGURED
}

internal fun Settings.effectiveLocalToolCount(
    assistant: Assistant,
    imageGenerationAvailable: Boolean,
): Int = assistant.localTools.distinct().count { option ->
    option != LocalToolOption.TextToImage || imageGenerationAvailable
}

internal fun Settings.buildConversationReadiness(
    assistant: Assistant,
    workspaceNamesById: Map<Uuid, String>,
    memoryCount: Int,
    imageGenerationAvailable: Boolean = false,
    mcpServers: List<McpServerPresentation> = emptyList(),
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

    val enabledMcpServers = mcpServers.filter { it.enabled }
    val selectedMcpServers = enabledMcpServers.filter { it.serverId in assistant.mcpServers }
    val selectedMcpCount = selectedMcpServers.size
    val readyMcpCount = selectedMcpServers.count { it.isReady }
    val selectedStatuses = selectedMcpServers.map { it.status }
    val mcpState = when {
        mcpServers.isEmpty() -> McpReadiness.NOT_CONFIGURED
        enabledMcpServers.isEmpty() -> McpReadiness.ALL_DISABLED
        selectedMcpCount == 0 -> McpReadiness.NONE_SELECTED
        readyMcpCount > 0 && readyMcpCount < selectedMcpCount -> McpReadiness.PARTIAL
        readyMcpCount == 0 && selectedStatuses.any {
            it == McpStatus.NeedsAuthorization || it == McpStatus.Authorizing
        } -> McpReadiness.AUTHORIZATION_REQUIRED
        readyMcpCount == 0 && selectedStatuses.any {
            it is McpStatus.Reconnecting || it is McpStatus.RetryScheduled || it == McpStatus.WaitingNetwork
        } -> McpReadiness.RECONNECTING
        readyMcpCount == 0 && selectedStatuses.any {
            it == McpStatus.Connecting || it == McpStatus.Discovering
        } -> McpReadiness.CONNECTING
        readyMcpCount == 0 -> McpReadiness.UNAVAILABLE
        else -> McpReadiness.READY
    }

    val workspaceName = assistant.workspaceId?.let(workspaceNamesById::get)
    val workspaceState = when {
        workspaceNamesById.isEmpty() -> WorkspaceReadiness.NOT_CONFIGURED
        workspaceName == null -> WorkspaceReadiness.NOT_BOUND
        else -> WorkspaceReadiness.READY
    }

    val memoryState = if (assistant.enableMemory) MemoryReadiness.READY else MemoryReadiness.DISABLED

    return ConversationReadiness(
        modelState = modelState,
        modelName = selectedModel?.displayName?.ifBlank { selectedModel.modelId },
        mcpState = mcpState,
        selectedMcpCount = selectedMcpCount,
        readyMcpCount = readyMcpCount,
        enabledMcpCount = enabledMcpServers.size,
        localToolCount = effectiveLocalToolCount(assistant, imageGenerationAvailable),
        persistedLocalToolCount = assistant.localTools.distinct().size,
        memoryState = memoryState,
        memoryCount = memoryCount,
        workspaceState = workspaceState,
        workspaceName = workspaceName,
    )
}

@Composable
internal fun ConversationReadinessCard(
    readiness: ConversationReadiness,
    assistant: Assistant,
    compact: Boolean,
    onSwitchAssistant: () -> Unit,
    onManageAssistant: () -> Unit,
    onModelClick: () -> Unit,
    onMcpClick: () -> Unit,
    onLocalToolsClick: () -> Unit,
    onMemoryClick: () -> Unit,
    onWorkspaceClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rawScale = LocalChatFontSizeRatio.current
    val scale = (1.0f + (rawScale - 1.0f) * 0.4f).coerceIn(0.85f, 1.35f)
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer.asChatChrome(),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(if (compact) 4.dp else 8.dp),
        ) {
            if (!compact) {
                ReadinessTitleRow(
                    assistant = assistant,
                    scale = scale,
                    onSwitchAssistant = onSwitchAssistant,
                    onManageAssistant = onManageAssistant,
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
                scale = scale,
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

                        McpReadiness.CONNECTING ->
                            stringResource(R.string.chat_readiness_mcp_connecting)

                        McpReadiness.AUTHORIZATION_REQUIRED ->
                            stringResource(R.string.chat_readiness_mcp_authorization_required)

                        McpReadiness.RECONNECTING ->
                            stringResource(R.string.chat_readiness_mcp_reconnecting)

                        McpReadiness.UNAVAILABLE ->
                            stringResource(R.string.chat_readiness_mcp_unavailable)

                        McpReadiness.PARTIAL -> stringResource(
                            R.string.chat_readiness_mcp_partial,
                            readiness.readyMcpCount,
                            readiness.selectedMcpCount,
                        )

                        McpReadiness.READY -> stringResource(
                            R.string.chat_readiness_mcp_ready,
                            readiness.readyMcpCount,
                        )
                    },
                    description = stringResource(
                        R.string.chat_readiness_mcp_description,
                        stringResource(R.string.chat_readiness_mcp_highlight_term),
                    ),
                    highlightTerm = stringResource(R.string.chat_readiness_mcp_highlight_term),
                    onClick = onMcpClick,
                    scale = scale,
                )
                ReadinessRow(
                    icon = HugeIcons.Brain02,
                    label = stringResource(R.string.chat_readiness_memory_title),
                    status = when (readiness.memoryState) {
                        MemoryReadiness.DISABLED ->
                            stringResource(R.string.chat_readiness_memory_disabled)

                        MemoryReadiness.READY -> stringResource(
                            R.string.chat_readiness_memory_count,
                            readiness.memoryCount,
                        )
                    },
                    description = stringResource(R.string.chat_readiness_memory_description),
                    onClick = onMemoryClick,
                    scale = scale,
                )
                ReadinessRow(
                    icon = HugeIcons.Wrench01,
                    label = stringResource(R.string.chat_readiness_local_tools_title),
                    status = if (readiness.localToolCount < readiness.persistedLocalToolCount) {
                        stringResource(
                            R.string.chat_readiness_local_tools_unavailable,
                            readiness.localToolCount,
                            readiness.persistedLocalToolCount,
                        )
                    } else {
                        stringResource(
                            R.string.chat_readiness_local_tools_count,
                            readiness.localToolCount,
                        )
                    },
                    description = stringResource(R.string.chat_readiness_local_tools_description),
                    onClick = onLocalToolsClick,
                    scale = scale,
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
                    scale = scale,
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
    scale: Float,
    highlightTerm: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
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
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize * scale,
                        lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * scale,
                    ),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ReadinessStatus(
                    text = status,
                    blocked = blocked,
                    scale = scale,
                )
            }
            if (highlightTerm != null && description.contains(highlightTerm)) {
                val annotated = buildAnnotatedString {
                    val start = description.indexOf(highlightTerm)
                    append(description.substring(0, start))
                    withStyle(
                        SpanStyle(
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    ) {
                        append(highlightTerm)
                    }
                    append(description.substring(start + highlightTerm.length))
                }
                Text(
                    text = annotated,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = MaterialTheme.typography.bodySmall.fontSize * scale,
                        lineHeight = MaterialTheme.typography.bodySmall.lineHeight * scale,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = MaterialTheme.typography.bodySmall.fontSize * scale,
                        lineHeight = MaterialTheme.typography.bodySmall.lineHeight * scale,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
    scale: Float,
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
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = MaterialTheme.typography.labelMedium.fontSize * scale,
                lineHeight = MaterialTheme.typography.labelMedium.lineHeight * scale,
            ),
            modifier = Modifier
                .widthIn(max = 156.dp)
                .padding(horizontal = 7.dp, vertical = 2.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ReadinessTitleRow(
    assistant: Assistant,
    scale: Float,
    onSwitchAssistant: () -> Unit,
    onManageAssistant: () -> Unit,
) {
    val defaultAssistantName = stringResource(R.string.assistant_page_default_assistant)
    val displayName = assistant.name.ifEmpty { defaultAssistantName }
    val titlePrefix = stringResource(R.string.chat_readiness_title_prefix)
    val titleSuffix = stringResource(R.string.chat_readiness_title_suffix)
    val titleStyle = MaterialTheme.typography.titleMedium.copy(
        fontSize = MaterialTheme.typography.titleMedium.fontSize * scale,
        lineHeight = MaterialTheme.typography.titleMedium.lineHeight * scale,
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = 6.dp,
                end = 6.dp,
                top = 2.dp,
                bottom = 4.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左侧：prefix + 可点击的头像+名称 + suffix，作为一个不可分割的整体
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (titlePrefix.isNotBlank()) {
                Text(
                    text = titlePrefix,
                    style = titleStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            // 可点击的交互区：头像 + 助手名称（宽屏+useAssistantAvatar 时头像已显示在 TopAppBar，此处隐藏）
            Row(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onSwitchAssistant)
                    .padding(horizontal = 1.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val hideAvatar = assistant.useAssistantAvatar &&
                    LocalAdaptiveLayoutInfo.current.chatLayoutMode == ChatLayoutMode.ListDetail
                if (!hideAvatar) {
                    UIAvatar(
                        name = displayName,
                        value = assistant.avatar,
                        modifier = Modifier.size((20 * scale).dp),
                    )
                }
                Text(
                    text = displayName,
                    style = titleStyle,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (titleSuffix.isNotBlank()) {
                Text(
                    text = titleSuffix,
                    style = titleStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        // 右侧：配置助手按钮，右对齐
        FilledTonalIconButton(
            onClick = onManageAssistant,
            modifier = Modifier.size(30.dp),
        ) {
            Icon(
                imageVector = HugeIcons.Edit03,
                contentDescription = stringResource(R.string.assistant_picker_manage_current),
                modifier = Modifier.size(15.dp),
            )
        }
    }
}
