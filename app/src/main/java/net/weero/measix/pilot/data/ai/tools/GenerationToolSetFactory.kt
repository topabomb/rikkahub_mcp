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
import net.weero.measix.pilot.data.datastore.getChatModel
import net.weero.measix.pilot.data.files.SkillManager
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus
import kotlinx.serialization.json.jsonObject
import kotlin.uuid.Uuid

private const val TAG = "GenerationToolSetFactory"

/**
 * 按 Assistant、资源和 Run Mode 统一装配 Search/Local/Conversation/Workspace/Skill/MCP 工具。
 * 从 ChatService 抽取，供 Master 和 Child（Target Run）共用。
 */
class GenerationToolSetFactory(
    private val localTools: LocalTools,
    private val conversationRepo: ConversationRepository,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
    private val mcpManager: McpManager,
    private val providerManager: ProviderManager,
) {
    /**
     * 构建指定 Assistant 的工具集（不含 Memory Tools，那些由 GenerationHandler 内部添加）。
     *
     * @param assistant 目标助手
     * @param settings 当前设置（本 run 的 snapshot）
     * @param resolvedModel 本次 run 实际使用的 resolved chat model；Master/Target 都必须传真实模型，
     *   不能回退到 `settings.getChatModel(assistant)` 猜测（Target 可在运行时继承 Caller model）。
     * @param workspaceCwd 工作目录（可覆盖会话级别）
     * @param runMode Target Run 时过滤 Assistant Tools；ask_user 保留给 Coordinator 桥接
     */
    suspend fun buildTools(
        assistant: Assistant,
        settings: Settings,
        resolvedModel: Model? = null,
        workspaceCwd: String? = null,
        runMode: ToolSetRunMode = ToolSetRunMode.NORMAL,
        ttsPlaybackContext: TtsToolPlaybackContext? = null,
        additionalToolsBeforeMcp: List<Tool> = emptyList(),
        onInvalidMcpServerNames: (List<String>) -> Unit = {},
    ): List<Tool> {
        // 能力判断优先使用本次 run 的真实模型；缺省时回退静态 Assistant 设置（仅测试/旧调用）。
        val effectiveModel = resolvedModel ?: settings.getChatModel(assistant)
        return buildList {
            if (shouldUseExternalWebSearch(assistant, effectiveModel)) {
                addAll(createSearchTools(settings))
            }

            if (shouldInjectAttachmentInspection(effectiveModel, settings)) {
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
                addAll(createConversationTools(conversationRepo, assistant.id))
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
                            execute = {
                                mcpManager.callTool(serverId, tool.name, it.jsonObject)
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
        return createWorkspaceTools(workspaceId, workspaceRepository, cwd)
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
 * B 场景注入 `inspect_attachments`（设计文档 §9）：当前模型不原生接收 IMAGE，
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
