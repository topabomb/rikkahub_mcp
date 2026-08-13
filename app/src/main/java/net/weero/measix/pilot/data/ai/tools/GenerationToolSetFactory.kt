package net.weero.measix.pilot.data.ai.tools

import android.util.Log
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.mcp.McpManager
import net.weero.measix.pilot.data.ai.subassistant.filterTargetLocalTools
import net.weero.measix.pilot.data.ai.subassistant.filterTargetTools
import net.weero.measix.pilot.data.ai.tools.local.LocalTools
import net.weero.measix.pilot.data.ai.tools.local.TtsToolPlaybackContext
import net.weero.measix.pilot.data.ai.tts.TtsPlaybackSource
import net.weero.measix.pilot.data.datastore.Settings
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
) {
    /**
     * 构建指定 Assistant 的工具集（不含 Memory Tools，那些由 GenerationHandler 内部添加）。
     *
     * @param assistant 目标助手
     * @param settings 当前设置
     * @param workspaceCwd 工作目录（可覆盖会话级别）
     * @param runMode Target Run 时过滤 Assistant Tools；ask_user 保留给 Coordinator 桥接
     */
    suspend fun buildTools(
        assistant: Assistant,
        settings: Settings,
        workspaceCwd: String? = null,
        runMode: ToolSetRunMode = ToolSetRunMode.NORMAL,
        ttsPlaybackContext: TtsToolPlaybackContext? = null,
    ): List<Tool> {
        return buildList {
            if (assistant.enableWebSearch) {
                addAll(createSearchTools(settings))
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
            addAll(localTools.getTools(localToolOptions, ttsContext))

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

            mcpManager.getAllAvailableTools(assistant).also { allTools ->
                val invalidNames = allTools
                    .map { it.second }
                    .distinct()
                    .filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' } }
                if (invalidNames.isNotEmpty()) {
                    Log.w(TAG, "Invalid MCP tool names: $invalidNames")
                    return@also
                }
            }.forEach { (serverId, serverName, tool) ->
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

enum class ToolSetRunMode {
    /** 普通用户会话 */
    NORMAL,

    /** 子助手 Target Run：过滤 Assistant Tools，保留 ask_user */
    TARGET,
}
