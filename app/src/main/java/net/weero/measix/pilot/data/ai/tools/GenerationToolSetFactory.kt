package net.weero.measix.pilot.data.ai.tools

import android.util.Log
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.mcp.McpManager
import net.weero.measix.pilot.data.ai.subassistant.filterTargetLocalTools
import net.weero.measix.pilot.data.ai.subassistant.filterTargetTools
import net.weero.measix.pilot.data.ai.tools.local.AssistantToolBuildContext
import net.weero.measix.pilot.data.ai.tools.local.LocalTools
import net.weero.measix.pilot.data.ai.tools.local.TtsToolPlaybackContext
import net.weero.measix.pilot.data.ai.tts.TtsPlaybackSource
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.findModelById
import net.weero.measix.pilot.data.datastore.findProvider
import net.weero.measix.pilot.data.files.SkillManager
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.repository.WorkspaceRepository
import net.weero.measix.pilot.service.ConversationQueryService
import me.rerere.workspace.WorkspaceShellStatus
import kotlinx.serialization.json.jsonObject
import kotlin.uuid.Uuid

private const val TAG = "GenerationToolSetFactory"

/**
 * 按 Assistant、资源和 Run Mode 统一装配 Search/Local/Conversation/Workspace/Skill/MCP 工具。
 * 供 Master 和 Child（Target Run）共用的工具装配 owner。
 */
class GenerationToolSetFactory(
    private val localTools: LocalTools,
    private val conversationQueryService: ConversationQueryService,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
    private val mcpManager: McpManager,
    private val providerManager: ProviderManager,
    private val artifactStore: ArtifactStore,
) {
    /**
     * 构建指定 Assistant 的工具集（不含 Memory Tools，那些由 GenerationHandler 内部添加）。
     *
     * @param assistant 目标助手
     * @param settings 当前设置（本 run 的 snapshot）
     * @param capabilityModel 本次 run 的实际模型，或非运行时检查中显式解析的配置模型。
     * @param workspaceCwd 工作目录（可覆盖会话级别）
     * @param runMode Target Run 时过滤 Assistant Tools；ask_user 保留给 Coordinator 桥接
     */
    suspend fun buildTools(
        assistant: Assistant,
        settings: Settings,
        capabilityModel: Model?,
        workspaceCwd: String? = null,
        runMode: ToolSetRunMode = ToolSetRunMode.NORMAL,
        ttsPlaybackContext: TtsToolPlaybackContext? = null,
        additionalToolsBeforeMcp: List<Tool> = emptyList(),
        onInvalidMcpServerNames: (List<String>) -> Unit = {},
    ): List<Tool> {
        return buildList {
            if (shouldUseExternalWebSearch(assistant, capabilityModel)) {
                addAll(createSearchTools(settings))
            }

            if (shouldInjectAttachmentInspection(capabilityModel, settings)) {
                add(createAttachmentInspectionTool(settings, providerManager))
            }

            val localToolOptions = if (runMode == ToolSetRunMode.TARGET) {
                filterTargetLocalTools(assistant.localTools)
            } else {
                assistant.localTools
            }
            // 每轮生成创建一个 TtsToolPlaybackContext，step 重建时复用
            val ttsContext = ttsPlaybackContext ?: TtsToolPlaybackContext(
                sessionId = Uuid.random().toString(),
                assistantId = assistant.id,
                assistantName = assistant.name,
                sourceType = if (runMode == ToolSetRunMode.TARGET) {
                    TtsPlaybackSource.SourceType.SUB_ASSISTANT
                } else {
                    TtsPlaybackSource.SourceType.NORMAL
                },
            )
            addAll(
                localTools.getTools(
                    options = localToolOptions,
                    ttsPlaybackContext = ttsContext,
                    buildContext = AssistantToolBuildContext(
                        ownerAssistantId = assistant.id,
                        settings = settings,
                    ),
                )
            )

            if (assistant.enableRecentChatsReference) {
                addAll(createConversationTools(conversationQueryService, assistant.id))
            }

            addAll(createWorkspaceToolsIfReady(assistant.workspaceId?.toString(), workspaceCwd))

            if (assistant.enabledSkills.isNotEmpty()) {
                addAll(
                    createSkillTools(
                        enabledSkills = assistant.enabledSkills,
                        allSkills = skillManager.listSkills(),
                    )
                )
            }

            addAll(additionalToolsBeforeMcp)

            val allMcpTools = mcpManager.getAllAvailableTools(assistant)
            val invalidNames = allMcpTools
                .map { it.second }
                .distinct()
                .filter { name ->
                    name.isEmpty() || !name.all {
                        it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_'
                    }
                }
            if (invalidNames.isNotEmpty()) {
                Log.w(TAG, "Invalid MCP tool names: $invalidNames")
                onInvalidMcpServerNames(invalidNames)
            } else {
                allMcpTools.forEach { (serverId, serverName, tool) ->
                    add(
                        Tool(
                            name = "mcp__${serverName}__${tool.name}",
                            description = tool.description ?: "",
                            parameters = { tool.inputSchema },
                            needsApproval = { tool.needsApproval },
                            execute = { error("MCP tools require ToolExecutionContext") },
                            contextualExecute = {
                                mcpManager.callTool(serverId, tool.name, it.jsonObject) { owned ->
                                    registerUnpublishedResource(artifactStore.unpublishedLease(owned))
                                }
                            },
                        )
                    )
                }
            }
        }.let { tools ->
            if (runMode == ToolSetRunMode.TARGET) {
                filterTargetTools(tools)
            } else {
                tools
            }
        }
    }

    private suspend fun createWorkspaceToolsIfReady(workspaceId: String?, cwd: String? = null): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}"
            )
            return emptyList()
        }
        return createWorkspaceTools(workspaceId, workspaceRepository, artifactStore, cwd)
    }
}

/**
 * Local search tools stay off when the selected model already has provider built-in search.
 * SearchMode UI is exclusive, but older settings or Target snapshots can still have both flags.
 */
fun shouldUseExternalWebSearch(assistant: Assistant, model: Model?): Boolean {
    return assistant.enableWebSearch && model?.tools?.contains(BuiltInTools.Search) != true
}

/**
 * 识别模型场景注入 `inspect_attachments`：当前模型不原生接收 IMAGE，
 * 且配置了存在、Provider 可用且自身支持 IMAGE 输入的附件识别模型。
 * 不根据当前消息是否包含 Image 决定 tool schema。
 */
fun shouldInjectAttachmentInspection(resolvedModel: Model?, settings: Settings): Boolean {
    if (resolvedModel == null) return false
    if (resolvedModel.inputModalities.contains(Modality.IMAGE)) return false
    val inspectionModel = settings.findModelById(settings.attachmentInspectionModelId) ?: return false
    if (inspectionModel.findProvider(settings.providers) == null) return false
    return inspectionModel.inputModalities.contains(Modality.IMAGE)
}

enum class ToolSetRunMode {
    /** 普通用户会话 */
    NORMAL,

    /** 子助手 Target Run：过滤 Assistant Tools，保留 ask_user */
    TARGET,
}
