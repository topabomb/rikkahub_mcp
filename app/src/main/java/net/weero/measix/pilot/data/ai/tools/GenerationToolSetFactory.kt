package net.weero.measix.pilot.data.ai.tools

import android.util.Log
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.RequestImageSupport
import me.rerere.ai.provider.RequestMediaCapabilities
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.mcp.McpRuntimeCoordinator
import net.weero.measix.pilot.data.ai.mcp.TurnMcpCapabilitySnapshot
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
import net.weero.measix.pilot.service.ConversationQueryService
import net.weero.measix.pilot.service.workspace.WorkspaceApplicationService
import net.weero.measix.pilot.service.workspace.WorkspaceQueryService
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
    private val workspaceApplicationService: WorkspaceApplicationService,
    private val workspaceQueryService: WorkspaceQueryService,
    private val mcpManager: McpRuntimeCoordinator,
    private val providerManager: ProviderManager,
    private val artifactStore: ArtifactStore,
) {
    fun captureMcpCapabilities(assistant: Assistant): TurnMcpCapabilitySnapshot =
        mcpManager.captureTurnCapabilities(assistant)

    suspend fun prepareMcpCapabilities(assistant: Assistant): TurnMcpCapabilitySnapshot =
        mcpManager.prepareTurnCapabilities(assistant)

    /**
     * 构建指定 Assistant 的工具集（不含 Memory Tools，那些由 GenerationLoop 内部添加）。
     *
     * @param assistant 目标助手
     * @param settings 当前 step 构建时的有效设置快照
     * @param capabilityModel 本次 run 的实际模型，或非运行时检查中显式解析的配置模型。
     * @param capabilityMediaCapabilities 本次 run 固定的线协议容器契约；运行时调用方必须传入。
     * @param workspaceCwd 工作目录（可覆盖会话级别）
     * @param runMode Target Run 时过滤 Assistant Tools；ask_user 保留给 Coordinator 桥接
     */
    suspend fun buildTools(
        assistant: Assistant,
        settings: Settings,
        capabilityModel: Model?,
        capabilityMediaCapabilities: RequestMediaCapabilities? = null,
        workspaceCwd: String? = null,
        runMode: ToolSetRunMode = ToolSetRunMode.NORMAL,
        ttsPlaybackContext: TtsToolPlaybackContext? = null,
        additionalToolsBeforeMcp: List<Tool> = emptyList(),
        mcpCapabilities: TurnMcpCapabilitySnapshot,
        onInvalidMcpServerNames: (List<String>) -> Unit = {},
    ): List<Tool> {
        return buildList {
            if (shouldUseExternalWebSearch(assistant, capabilityModel)) {
                addAll(createSearchTools(settings))
            }

            val mediaCapabilities = capabilityMediaCapabilities ?: capabilityModel
                ?.findProvider(settings.providers)
                ?.let { providerSetting ->
                    providerManager.getProviderByType(providerSetting)
                        .requestMediaCapabilities(providerSetting, capabilityModel)
                }
            if (shouldInjectAttachmentInspection(capabilityModel, mediaCapabilities, settings)) {
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
                        skillManager = skillManager,
                    )
                )
            }

            addAll(additionalToolsBeforeMcp)

            val invalidNames = mcpCapabilities.tools
                .map { it.serverName }
                .distinct()
                .filter { name ->
                    name.isEmpty() || !name.all {
                        it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_'
                    }
                }.toSet()
            if (invalidNames.isNotEmpty()) {
                Log.w(TAG, "Invalid MCP tool names: $invalidNames")
                onInvalidMcpServerNames(invalidNames.sorted())
            }
            val invalidToolBindings = mcpCapabilities.tools
                .filterNot { tool -> validProviderToolBinding(tool.serverName, tool.name) }
                .map { "${it.serverName}/${it.name}" }
            if (invalidToolBindings.isNotEmpty()) {
                Log.w(TAG, "Ignoring invalid MCP tool bindings: $invalidToolBindings")
            }
            val validBindings = mcpCapabilities.tools.filterNot { tool ->
                    tool.serverName in invalidNames || !validProviderToolBinding(tool.serverName, tool.name)
                }
            val collidedProviderNames = validBindings
                .groupBy { tool -> providerToolName(tool.serverName, tool.name) }
                .filterValues { tools -> tools.size > 1 }
                .keys
            if (collidedProviderNames.isNotEmpty()) {
                Log.w(TAG, "Ignoring colliding MCP provider tool names: $collidedProviderNames")
            }
            validBindings
                .filterNot { tool -> providerToolName(tool.serverName, tool.name) in collidedProviderNames }
                .forEach { tool ->
                    add(
                        Tool(
                            name = providerToolName(tool.serverName, tool.name),
                            description = tool.description ?: "",
                            parameters = { tool.inputSchema },
                            needsApproval = { tool.needsApproval },
                            execute = { error("MCP tools require ToolExecutionContext") },
                            contextualExecute = {
                                mcpManager.callTool(
                                    serverId = tool.serverId,
                                    toolName = tool.name,
                                    expectedDefinitionDigest = tool.definitionDigest,
                                    expectedNeedsApproval = tool.needsApproval,
                                    args = it.jsonObject,
                                ) { owned ->
                                    registerUnpublishedResource(artifactStore.unpublishedLease(owned))
                                }
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

    private companion object {
        val MCP_PROVIDER_TOOL_NAME = Regex("[A-Za-z0-9_-]{1,64}")

        fun providerToolName(serverName: String, toolName: String): String =
            "mcp__${serverName}__${toolName}"

        fun validProviderToolBinding(serverName: String, toolName: String): Boolean =
            MCP_PROVIDER_TOOL_NAME.matches(providerToolName(serverName, toolName))
    }

    private suspend fun createWorkspaceToolsIfReady(workspaceId: String?, cwd: String? = null): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceQueryService.getWorkspace(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}"
            )
            return emptyList()
        }
        return createWorkspaceTools(
            workspaceId = workspaceId,
            workspaceApplicationService = workspaceApplicationService,
            approvalOverrides = workspace.toolApprovalOverrides,
            artifactStore = artifactStore,
            cwd = cwd,
        )
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
 * 识别模型场景注入 `inspect_attachments`：当前模型在其 Provider 协议下未能覆盖全部附件来源
 * 容器（USER / ASSISTANT / Tool.output）的原生 IMAGE，且配置了存在、Provider 可用且
 * 自身支持 IMAGE 输入的附件识别模型。不根据当前消息是否包含 Image 决定 tool schema。
 *
 * 模型能力唯一来源于 [Model.inputModalities]；[RequestMediaCapabilities] 只是协议适配器
 * 对三个来源容器的静态映射，不是第二套能力配置。注入判断在此派生结果上进行，
 * 不再根据 endpoint host 做第二次否决。
 */
fun shouldInjectAttachmentInspection(
    resolvedModel: Model?,
    currentCapabilities: RequestMediaCapabilities?,
    settings: Settings,
): Boolean {
    if (resolvedModel == null || currentCapabilities == null) return false
    val coversAllAttachmentImageSources =
        currentCapabilities.userImages == RequestImageSupport.STRUCTURED &&
            currentCapabilities.assistantImages == RequestImageSupport.STRUCTURED &&
            currentCapabilities.toolOutputImages == RequestImageSupport.STRUCTURED
    // OPAQUE_REPLAY_ONLY is not full coverage: not every ordinary assistant image carries
    // replayable Provider opaque metadata, so it still needs the inspection tool.
    if (coversAllAttachmentImageSources) return false

    val inspectionModel = settings.findModelById(settings.attachmentInspectionModelId) ?: return false
    inspectionModel.findProvider(settings.providers) ?: return false
    if (!inspectionModel.inputModalities.contains(Modality.IMAGE)) return false

    // The inspection model's own host is not vetoed here: if the gateway genuinely rejects
    // images, the Provider call inside executeInspection will surface a real classified error.
    return true
}

enum class ToolSetRunMode {
    /** 普通用户会话 */
    NORMAL,

    /** 子助手 Target Run：过滤 Assistant Tools，保留 ask_user */
    TARGET,
}
