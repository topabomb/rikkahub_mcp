package net.weero.measix.pilot.service

import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.finishInterruptedTools
import me.rerere.ai.ui.finishPendingTools
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.ToolApprovalState
import net.weero.measix.pilot.data.ai.GenerationChunk
import net.weero.measix.pilot.data.ai.FinishedReason
import net.weero.measix.pilot.data.ai.GenerationHandler
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallPhase
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallState
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantUserInteraction
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantRunStateReducer
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantRunSpecResolution
import net.weero.measix.pilot.data.ai.subassistant.buildInitialSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.buildSubAssistantCallResult
import net.weero.measix.pilot.data.ai.subassistant.computeSubAssistantPreview
import net.weero.measix.pilot.data.ai.subassistant.computeTerminalPreview
import net.weero.measix.pilot.data.ai.subassistant.intersectTargetToolCapabilities
import net.weero.measix.pilot.data.ai.subassistant.cloneLineagePrefix
import net.weero.measix.pilot.data.ai.subassistant.findPreviousCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.getSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.mergeSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.resolveLineage
import net.weero.measix.pilot.data.ai.subassistant.resolveActiveRunStopReason
import net.weero.measix.pilot.data.ai.subassistant.resolvePreWriteBlockReason
import net.weero.measix.pilot.data.ai.subassistant.resolveSubAssistantRunSpec
import net.weero.measix.pilot.data.ai.subassistant.validateReadiness
import net.weero.measix.pilot.data.ai.tools.GenerationToolSetFactory
import net.weero.measix.pilot.data.ai.tools.ToolSetRunMode
import net.weero.measix.pilot.data.ai.tools.local.TtsToolPlaybackContext
import net.weero.measix.pilot.data.ai.tts.TtsPlaybackSource
import net.weero.measix.pilot.data.ai.transformers.Base64ImageToLocalFileTransformer
import net.weero.measix.pilot.data.ai.transformers.DocumentAsPromptTransformer
import net.weero.measix.pilot.data.ai.transformers.InputMessageTransformer
import net.weero.measix.pilot.data.ai.transformers.OcrTransformer
import net.weero.measix.pilot.data.ai.transformers.OutputMessageTransformer
import net.weero.measix.pilot.data.ai.transformers.PlaceholderTransformer
import net.weero.measix.pilot.data.ai.transformers.PromptInjectionTransformer
import net.weero.measix.pilot.data.ai.transformers.RegexOutputTransformer
import net.weero.measix.pilot.data.ai.transformers.TemplateTransformer
import net.weero.measix.pilot.data.ai.transformers.ThinkTagTransformer
import net.weero.measix.pilot.data.ai.transformers.TimeReminderTransformer
import net.weero.measix.pilot.data.ai.transformers.WorkspaceReminderTransformer
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.getAssistantById
import net.weero.measix.pilot.data.files.FilesManager
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.AssistantAffectScope
import net.weero.measix.pilot.data.model.replaceRegexes
import net.weero.measix.pilot.data.model.toMessageNode
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.data.repository.MemoryRepository
import net.weero.measix.pilot.data.repository.WorkspaceRepository
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "SubAssistantCoordinator"
private const val PREVIEW_THROTTLE_MS = 100L
private const val MAX_SUB_ASSISTANT_INTERACTIONS = 16
private const val FINALIZATION_TIMEOUT_MS = 5_000L

internal data class InterruptedRunFinalizationFailures(
    val child: Throwable?,
    val metadata: Throwable?,
)

internal suspend fun finalizeInterruptedRunSafely(
    timeoutMillis: Long,
    finalizeChild: suspend () -> Unit,
    finalizeMetadata: suspend () -> Unit,
): InterruptedRunFinalizationFailures = withContext(NonCancellable) {
    val childFailure = runCatching {
        withTimeout(timeoutMillis) { finalizeChild() }
    }.exceptionOrNull()
    val metadataFailure = runCatching {
        withTimeout(timeoutMillis) { finalizeMetadata() }
    }.exceptionOrNull()
    InterruptedRunFinalizationFailures(childFailure, metadataFailure)
}

/**
 * Target Generation 运行结果。
 * 包含最终消息列表和 Finished 原因（可能为 null 兼容旧路径）。
 */
private data class TargetGenerationResult(
    val messages: List<me.rerere.ai.ui.UIMessage>,
    val finishReason: FinishedReason?,
)

/**
 * 子助手调用协调器。
 *
 * 职责：Readiness、RunSpec、Child create/reuse/clone、Target Generation、
 * 进度桥接、终态与恢复。不依赖 [ChatService]，避免循环依赖。
 *
 * 依赖方向：
 * ```
 * AssistantToolFactory
 *   └─> SubAssistantCoordinator
 *           ├─> GenerationToolSetFactory
 *           ├─> ConversationSessionRegistry
 *           ├─> ConversationRepository
 *           └─> GenerationHandler
 * ```
 */
class SubAssistantCoordinator(
    private val generationHandler: GenerationHandler,
    private val conversationRepo: ConversationRepository,
    private val sessionRegistry: ConversationSessionRegistry,
    private val toolSetFactory: GenerationToolSetFactory,
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val templateTransformer: TemplateTransformer,
    workspaceRepository: WorkspaceRepository,
    private val filesManager: FilesManager,
    private val json: Json,
) {
    private data class PendingUserInteraction(
        val interactionId: String,
        val answer: CompletableDeferred<String> = CompletableDeferred(),
    )

    private val runLeases = SubAssistantRunLeaseRegistry()
    private val pendingUserInteractions = ConcurrentHashMap<String, PendingUserInteraction>()
    private val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)

    /** 删除 Target 前取消并等待其所有正在执行的 Target Run 停止写回。 */
    suspend fun cancelRunsForAssistant(assistantId: Uuid) {
        runLeases.cancelForAssistant(assistantId)
    }

    /** 主聊天 UI 回答子助手当前 ask_user；过期或重复 interaction 会被拒绝。 */
    fun answerUserInteraction(runId: String, interactionId: String, answer: String): Boolean {
        val pending = pendingUserInteractions[runId] ?: return false
        if (pending.interactionId != interactionId) return false
        return pending.answer.complete(answer)
    }

    /**
     * 执行 assistant_call。
     *
     * @return Tool Result parts
     */
    suspend fun executeCall(
        callerAssistantId: Uuid,
        masterConversationId: Uuid,
        targetAssistantId: Uuid,
        task: String,
        execContext: ToolExecutionContext,
        turnTtsContext: TtsToolPlaybackContext? = null,
    ): List<UIMessagePart> {
        val settings = settingsStore.settingsFlow.value
        val targetAssistant = settings.getAssistantById(targetAssistantId)
        val callerAssistant = settings.getAssistantById(callerAssistantId)
        val runSpecResolution = if (targetAssistant != null && callerAssistant != null) {
            resolveSubAssistantRunSpec(
                settings = settings,
                caller = callerAssistant,
                target = targetAssistant,
            )
        } else {
            SubAssistantRunSpecResolution.Blocked("target_model_unavailable")
        }
        val resolvedRunSpec = (runSpecResolution as? SubAssistantRunSpecResolution.Ready)?.spec
        val modelUnavailableReason =
            (runSpecResolution as? SubAssistantRunSpecResolution.Blocked)?.reason
                ?: "target_model_unavailable"

        // 1. Readiness 验证
        // key 为 (masterConversationId, targetAssistantId) 组合，允许不同 Master 并发调用同一 Target
        val activeRunKey = SubAssistantRunKey(masterConversationId, targetAssistantId)
        val isActiveRun = runLeases.isBusy(activeRunKey)
        val callerAllowedIds = callerAssistant?.allowedSubAssistantIds ?: emptySet()
        val callerHasDelegation = callerAssistant?.localTools?.any {
            it == net.weero.measix.pilot.data.ai.tools.local.LocalToolOption.AssistantDelegation
        } ?: false
        val readiness = validateReadiness(
            targetAssistant = targetAssistant,
            callerAssistantId = callerAssistantId,
            callerAllowedSubAssistantIds = callerAllowedIds,
            callerHasDelegation = callerHasDelegation,
            settingsChatModel = resolvedRunSpec?.model,
            isActiveRun = isActiveRun,
            modelUnavailableReason = modelUnavailableReason,
        )
        if (readiness is net.weero.measix.pilot.data.ai.subassistant.ReadinessResult.Blocked) {
            return unavailableResult(
                execContext = execContext,
                targetAssistantId = targetAssistantId,
                assistantName = targetAssistant?.name ?: targetAssistantId.toString(),
                reason = readiness.reason,
            )
        }

        val runSpec = requireNotNull(resolvedRunSpec)
        val target = runSpec.assistant
        val model = runSpec.model
        val processedTask = preprocessSubAssistantTask(task, target)

        // 2. 从 session registry 获取 Master 当前消息（用于 lineage 解析）
        val masterMessages = sessionRegistry.getSession(masterConversationId)?.state?.value?.currentMessages
            ?: emptyList()

        // 3. 解析 previous run 与 Child lineage
        val previousMeta = findPreviousCallMetadata(
            masterMessages = masterMessages,
            currentMessageId = execContext.messageId,
            currentToolOrdinal = execContext.toolOrdinal,
            targetAssistantId = targetAssistantId,
            json = json,
        )

        val runId = Uuid.random().toString()

        // 4. 根据 lineage 决策创建/复用/克隆 Child
        val lineageDecision = when (previousMeta) {
            null -> net.weero.measix.pilot.data.ai.subassistant.LineageDecision.CreateNew
            else -> {
                val childConversation = previousMeta.childConversationId?.let { childIdStr ->
                    runCatching { Uuid.parse(childIdStr) }.getOrNull()?.let { conversationRepo.getConversationById(it) }
                }
                resolveLineage(
                    previousMeta = previousMeta,
                    childConversation = childConversation,
                    expectedMasterConversationId = masterConversationId,
                    expectedTargetAssistantId = targetAssistantId,
                )
            }
        }

        // 所有路径先得到最终 Child ID 并取得 lease，
        // 再创建/克隆持久化数据或追加 request。
        // 对于 ReuseChild，lineage 已知 Child ID，先按该 ID 检查是否已有活跃 Job。
        // 对于 CreateNew/CreateNewDueToError/CloneChild，使用 lineage key 检查
        // （新 Child 不存在同 Child 冲突，但同一 Master+Target 不应并发）。
        // runLeases 已在 preflight 阶段检查过，这里再次确认并在执行前原子获取 lease。
        val activeTargetRun = runLeases.tryAcquire(
            key = activeRunKey,
            runId = runId,
            callerAssistantId = callerAssistantId,
            parentJob = kotlin.coroutines.coroutineContext[Job],
        )
        if (activeTargetRun == null) {
            // preflight 与此处之间可能产生了并发调用
            return unavailableResult(
                execContext = execContext,
                targetAssistantId = targetAssistantId,
                assistantName = target.name,
                reason = "target_busy",
                runId = runId,
            )
        }

        // 写入 request 前再做一次访问校验，
        // 关闭 preflight 与持久化之间的竞态窗口。
        val latestSettingsCheck = settingsStore.settingsFlow.value
        val latestBlockReason = resolvePreWriteBlockReason(
            settings = latestSettingsCheck,
            callerAssistantId = callerAssistantId,
            targetAssistantId = targetAssistantId,
            runSpec = runSpec,
        )
        if (latestBlockReason != null) {
            runLeases.release(activeRunKey, activeTargetRun)
            return unavailableResult(
                execContext = execContext,
                targetAssistantId = targetAssistantId,
                assistantName = target.name,
                reason = latestBlockReason,
                runId = runId,
            )
        }
        val runJob = activeTargetRun.job

        val childConversationId: Uuid
        val childTaskNodeId: Uuid

        try {
            when (lineageDecision) {
                is net.weero.measix.pilot.data.ai.subassistant.LineageDecision.CreateNew,
                is net.weero.measix.pilot.data.ai.subassistant.LineageDecision.CreateNewDueToError -> {
                    val result = createNewChild(target, masterConversationId, processedTask)
                    childConversationId = result.first
                    childTaskNodeId = result.second
                }

                is net.weero.measix.pilot.data.ai.subassistant.LineageDecision.ReuseChild -> {
                    val result = reuseChild(target, lineageDecision.childConversationId, processedTask)
                    childConversationId = lineageDecision.childConversationId
                    childTaskNodeId = result
                }

                is net.weero.measix.pilot.data.ai.subassistant.LineageDecision.CloneChild -> {
                    val result = cloneChild(
                        target = target,
                        masterConversationId = masterConversationId,
                        sourceChildId = lineageDecision.sourceChildConversationId,
                        throughTaskMessageId = lineageDecision.throughTaskMessageId,
                        task = processedTask,
                    )
                    childConversationId = result.first
                    childTaskNodeId = result.second
                }
            }
        } catch (e: Exception) {
            // Child 创建失败时释放 lease
            runLeases.release(activeRunKey, activeTargetRun)
            throw e
        }

        // Child 与 Master 使用同一 Registry。该 Job 也是 run lease 中的结构化子 Job，
        // 主回合取消、Target 删除或 App 清理都会命中同一个生命周期对象。
        sessionRegistry.getOrCreateSession(childConversationId).setJob(runJob)

        // 5. 写初始 metadata 到 Master tool（start link）
        val initialMeta = buildInitialSubAssistantCallMetadata(
            runId = runId,
            targetAssistantId = targetAssistantId,
            targetNameSnapshot = target.name,
            previousRunId = when (lineageDecision) {
                is net.weero.measix.pilot.data.ai.subassistant.LineageDecision.ReuseChild ->
                    lineageDecision.previousRunId
                is net.weero.measix.pilot.data.ai.subassistant.LineageDecision.CloneChild ->
                    lineageDecision.sourceRunId
                else -> null
            },
        ).copy(
            childConversationId = childConversationId.toString(),
            childTaskNodeId = childTaskNodeId.toString(),
            state = SubAssistantCallState.RUNNING,
            phase = SubAssistantCallPhase.PREPARING,
        )
        val runState = SubAssistantRunStateReducer(initialMeta)

        try {
            reportMetadataPatch(execContext, initialMeta, checkpoint = true)
        } catch (e: Exception) {
            // The lease is already visible to deletion/cancellation callers. If the
            // initial Master checkpoint fails, release and signal it just like the
            // Child creation failure path so callers cannot wait forever.
            runLeases.release(activeRunKey, activeTargetRun)
            throw e
        }

        // 运行中 Settings 撤权监听器
        // 监听最新 Settings，重算 caller/Target 关系；变化时立即取消 Child Job
        val runScope = CoroutineScope(kotlin.coroutines.coroutineContext + runJob)
        val settingsWatcher = runScope.launch {
            settingsStore.settingsFlow.collect { latestSettings ->
                resolveActiveRunStopReason(
                    settings = latestSettings,
                    callerAssistantId = callerAssistantId,
                    targetAssistantId = targetAssistantId,
                    runSpec = runSpec,
                )?.let { reason ->
                    runJob.cancel(reason)
                }
            }
        }

        try {
            // 6. 运行 Target Generation
            runJob.ensureActive()
            val genResult = withContext(runJob) {
                runTargetGeneration(
                    target = target,
                    model = model,
                    childConversationId = childConversationId,
                    childTaskNodeId = childTaskNodeId,
                    execContext = execContext,
                    runId = runId,
                    runState = runState,
                    turnTtsContext = turnTtsContext,
                )
            }

            // StateFlow watcher 与 final 可能同时到达；提交 completed 前同步重验，
            // 防止撤权或模型失效期间到达的迟到结果被误记为成功。
            resolveActiveRunStopReason(
                settings = settingsStore.settingsFlow.value,
                callerAssistantId = callerAssistantId,
                targetAssistantId = targetAssistantId,
                runSpec = runSpec,
            )?.let { reason -> throw CancellationException(reason) }

            // 7. 根据 FinishedReason 确定终态
            when (genResult.finishReason) {
                FinishedReason.STEP_LIMIT_REACHED -> {
                    val terminalMeta = runState.updateTerminalState(
                        state = SubAssistantCallState.FAILED,
                        reason = "step_limit_reached",
                    )
                    reportMetadataPatch(execContext, terminalMeta, checkpoint = false)
                    val resultJson = buildSubAssistantCallResult(
                        json = json,
                        status = "failed",
                        assistantName = target.name,
                        content = "",
                        reason = "step_limit_reached",
                    )
                    return listOf(UIMessagePart.Text(resultJson))
                }
                FinishedReason.INTERACTION_LIMIT_REACHED -> {
                    val terminalMeta = runState.updateTerminalState(
                        state = SubAssistantCallState.FAILED,
                        reason = "interaction_limit_reached",
                    )
                    reportMetadataPatch(execContext, terminalMeta, checkpoint = false)
                    val resultJson = buildSubAssistantCallResult(
                        json = json,
                        status = "failed",
                        assistantName = target.name,
                        content = "",
                        reason = "interaction_limit_reached",
                    )
                    return listOf(UIMessagePart.Text(resultJson))
                }
                FinishedReason.AWAITING_APPROVAL -> {
                    // 只有无法桥接 Pending ask_user 或达到交互上限时才会返回到这里。
                    val terminalMeta = runState.updateTerminalState(
                        state = SubAssistantCallState.FAILED,
                        reason = "approval_blocked",
                    )
                    reportMetadataPatch(execContext, terminalMeta, checkpoint = false)
                    val resultJson = buildSubAssistantCallResult(
                        json = json,
                        status = "failed",
                        assistantName = target.name,
                        content = "",
                        reason = "approval_blocked",
                    )
                    return listOf(UIMessagePart.Text(resultJson))
                }
                else -> {
                    // COMPLETED 或 null（兼容旧路径）
                    val finalText = extractFinalAnswer(genResult.messages, childTaskNodeId)
                    val hasNonTextOutput = checkNonTextOutput(genResult.messages, childTaskNodeId)

                    val terminalMeta = runState.updateTerminalState(
                        state = SubAssistantCallState.COMPLETED,
                        preview = computeTerminalPreview(finalText).ifEmpty { null },
                        hasNonTextOutput = hasNonTextOutput,
                    )
                    reportMetadataPatch(execContext, terminalMeta, checkpoint = false)

                    val resultJson = buildSubAssistantCallResult(
                        json = json,
                        status = "completed",
                        assistantName = target.name,
                        content = finalText,
                        hasNonTextOutput = hasNonTextOutput,
                    )
                    return listOf(UIMessagePart.Text(resultJson))
                }
            }

        } catch (e: CancellationException) {
            // 区分撤权取消和用户主动取消
            // 撤权监听器取消时 message 为 target_removed/target_disabled/target_access_revoked/
            // target_model_unavailable/caller_model_unavailable
            // 用户主动取消时 message 为 null 或 "user_cancelled"
            val cancelReason = normalizeSubAssistantCancellationReason(e.message)
            val masterCancelled = kotlin.coroutines.coroutineContext[Job]?.isActive == false
            val terminalMeta = runState.updateTerminalState(
                state = SubAssistantCallState.STOPPED,
                reason = cancelReason,
            )
            finalizeInterruptedRun(childConversationId, cancelReason, execContext, terminalMeta)
            if (masterCancelled) throw e
            val resultJson = buildSubAssistantCallResult(
                json = json,
                status = "stopped",
                assistantName = target.name,
                content = "",
                reason = cancelReason,
            )
            return listOf(UIMessagePart.Text(resultJson))

        } catch (e: Exception) {
            Log.e(TAG, "Target generation failed", e)
            val terminalMeta = runState.updateTerminalState(
                state = SubAssistantCallState.FAILED,
                reason = "runtime_error",
            )
            finalizeInterruptedRun(childConversationId, "runtime_error", execContext, terminalMeta)

            val resultJson = buildSubAssistantCallResult(
                json = json,
                status = "failed",
                assistantName = target.name,
                content = "",
                reason = "runtime_error",
            )
            return listOf(UIMessagePart.Text(resultJson))

        } finally {
            settingsWatcher.cancel()
            runScope.cancel()
            runLeases.release(activeRunKey, activeTargetRun)
        }
    }

    // ---- Child Conversation 管理 ----

    private suspend fun createNewChild(
        target: net.weero.measix.pilot.data.model.Assistant,
        masterConversationId: Uuid,
        task: String,
    ): Pair<Uuid, Uuid> {
        val childId = Uuid.random()
        // 首次创建时只写入 Target 的 preset messages
        val presetMessages = target.presetMessages
        val taskMessage = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(task)))
        val conversation = Conversation(
            id = childId,
            assistantId = target.id,
            title = target.name,
            messageNodes = presetMessages.map { it.toMessageNode() } + taskMessage.toMessageNode(),
            parentConversationId = masterConversationId,
        )
        conversationRepo.insertConversation(conversation)
        sessionRegistry.getOrCreateSessionWithConversation(childId, conversation)

        // 使用 UIMessage.id（而非 MessageNode.id），因为 extractFinalAnswer /
        // checkNonTextOutput / computeSubAssistantPreview 均在 List<UIMessage> 中按
        // UIMessage.id 搜索。Lineage Resolver 也已适配为按 UIMessage.id 搜索。
        val taskNodeId = taskMessage.id
        Log.i(TAG, "createNewChild: child=$childId, taskMsg=$taskNodeId")
        return childId to taskNodeId
    }

    private suspend fun reuseChild(
        target: net.weero.measix.pilot.data.model.Assistant,
        childConversationId: Uuid,
        task: String,
    ): Uuid {
        val conversation = conversationRepo.getConversationById(childConversationId)
            ?: throw IllegalStateException("Child conversation not found: $childConversationId")

        val taskMessage = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(task)))
        val updatedConversation = conversation.copy(
            messageNodes = conversation.messageNodes + taskMessage.toMessageNode()
        )
        conversationRepo.updateConversation(updatedConversation)
        sessionRegistry.updateConversationState(childConversationId, updatedConversation)

        val taskNodeId = taskMessage.id
        Log.i(TAG, "reuseChild: child=$childConversationId, taskMsg=$taskNodeId")
        return taskNodeId
    }

    private suspend fun cloneChild(
        target: net.weero.measix.pilot.data.model.Assistant,
        masterConversationId: Uuid,
        sourceChildId: Uuid,
        throughTaskMessageId: Uuid,
        task: String,
    ): Pair<Uuid, Uuid> {
        val sourceConversation = conversationRepo.getConversationById(sourceChildId)
            ?: throw IllegalStateException("Source child conversation not found: $sourceChildId")

        // 只克隆所选 previous run 的前缀；源 Child 的后续 task 属于另一条分支。
        val newChildId = Uuid.random()
        val sourcePrefix = cloneLineagePrefix(sourceConversation, throughTaskMessageId)
            ?: throw IllegalStateException("Source child lineage endpoint is invalid: $throughTaskMessageId")
        val clonedNodes = sourcePrefix.map { node ->
            node.copy(
                id = Uuid.random(),
                messages = node.messages.map { message ->
                    message.copy(parts = message.parts.map(::copyPartForChildClone))
                },
            )
        }
        val taskMessage = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(task)))
        val conversation = Conversation(
            id = newChildId,
            assistantId = target.id,
            title = target.name,
            messageNodes = clonedNodes + taskMessage.toMessageNode(),
            parentConversationId = masterConversationId,
        )
        conversationRepo.insertConversation(conversation)
        sessionRegistry.getOrCreateSessionWithConversation(newChildId, conversation)

        val taskNodeId = taskMessage.id
        Log.i(TAG, "cloneChild: source=$sourceChildId, new=$newChildId, taskMsg=$taskNodeId")
        return newChildId to taskNodeId
    }

    // ---- Target Generation ----

    private suspend fun runTargetGeneration(
        target: net.weero.measix.pilot.data.model.Assistant,
        model: me.rerere.ai.provider.Model,
        childConversationId: Uuid,
        childTaskNodeId: Uuid,
        execContext: ToolExecutionContext,
        runId: String,
        runState: SubAssistantRunStateReducer,
        turnTtsContext: TtsToolPlaybackContext? = null,
    ): TargetGenerationResult {
        val settings = settingsStore.settingsFlow.value
        val session = sessionRegistry.getOrCreateSession(childConversationId)
        val conversation = session.state.value

        // Target uses its own complete Assistant-level transformer pipeline. Conversation-level
        // overrides remain disabled below; PromptInjection receives the Target's own mode IDs.
        val targetInputTransformers = listOf<InputMessageTransformer>(
            TimeReminderTransformer,
            PromptInjectionTransformer,
            PlaceholderTransformer,
            DocumentAsPromptTransformer,
            OcrTransformer,
            templateTransformer,
            workspaceReminderTransformer,
        )
        val targetOutputTransformers = listOf<OutputMessageTransformer>(
            ThinkTagTransformer,
            Base64ImageToLocalFileTransformer,
            RegexOutputTransformer,
        )

        // Target memories
        val memories = if (target.enableMemory) {
            if (target.useGlobalMemory) {
                memoryRepository.getGlobalMemories()
            } else {
                memoryRepository.getMemoriesOfAssistant(target.id.toString())
            }
        } else {
            emptyList()
        }

        // 复用 turn-level TtsToolPlaybackContext 的 sessionId，
        // 使整轮 turn 内的 Master 和所有 Target 的 TTS 调用归属同一条播放队列。
        // 无 turnTtsContext 时（测试或旧调用路径）回退到独立 context。
        val ttsPlaybackContext = if (turnTtsContext != null) {
            TtsToolPlaybackContext(
                sessionId = turnTtsContext.sessionId,
                assistantId = target.id,
                assistantName = target.name,
                sourceType = TtsPlaybackSource.SourceType.SUB_ASSISTANT,
            )
        } else {
            TtsToolPlaybackContext(
                sessionId = Uuid.random().toString(),
                assistantId = target.id,
                assistantName = target.name,
                sourceType = TtsPlaybackSource.SourceType.SUB_ASSISTANT,
            )
        }

        // 每个 step 重新解析资源并应用“运行快照 ∩ 当前配置”，但复用 TTS 播放上下文。
        val toolProvider: suspend () -> List<Tool> = {
            val currentSettings = settingsStore.settingsFlow.value
            val latestTarget = currentSettings.getAssistantById(target.id)
            if (latestTarget == null) {
                emptyList()
            } else {
                toolSetFactory.buildTools(
                    assistant = intersectTargetToolCapabilities(target, latestTarget),
                    settings = currentSettings,
                    workspaceCwd = conversation.workspaceCwd,
                    runMode = ToolSetRunMode.TARGET,
                    ttsPlaybackContext = ttsPlaybackContext,
                )
            }
        }

        var lastMessages = conversation.currentMessages
        var lastPreviewUpdate = 0L
        var finishReason: FinishedReason? = null
        var interactionCount = 0

        while (true) {
            finishReason = null
            generationHandler.generateText(
                settings = settings,
                model = model,
                messages = lastMessages,
                inputTransformers = targetInputTransformers,
                outputTransformers = targetOutputTransformers,
                assistant = target,
                memories = memories,
                tools = toolProvider(), // 初始工具列表
                toolProvider = toolProvider,
                nonInteractive = true,
                interactiveToolNames = setOf("ask_user"),
                memoryToolAllowed = {
                    val latestTarget = settingsStore.settingsFlow.value.getAssistantById(target.id)
                    latestTarget?.enableMemory == true &&
                        latestTarget.useGlobalMemory == target.useGlobalMemory
                },
                processingStatus = session.processingStatus,
                conversationSystemPrompt = null, // Child 不设置对话级 System Prompt
                // Child does not inherit the Master's mode injection. Passing the Target IDs also
                // keeps Target injections active when allowConversationPromptInjection is enabled.
                conversationModeInjectionIds = target.modeInjectionIds,
                workspaceCwd = conversation.workspaceCwd,
                maxSteps = 256,
            ).collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        lastMessages = chunk.messages
                        // 更新 Child session
                        val updatedConversation = session.state.value.updateCurrentMessages(chunk.messages)
                        sessionRegistry.updateConversationState(childConversationId, updatedConversation)

                        // 节流回写 preview 到 Master；内容未变则不 patch，避免主聊天无意义重组。
                        val now = System.currentTimeMillis()
                        if (now - lastPreviewUpdate >= PREVIEW_THROTTLE_MS) {
                            lastPreviewUpdate = now
                            val preview = computeSubAssistantPreview(chunk.messages, childTaskNodeId)
                            val before = runState.snapshot()
                            val meta = runState.updatePreview(preview.ifEmpty { null })
                            if (meta !== before) {
                                reportMetadataPatch(execContext, meta, checkpoint = false)
                            }
                        }
                    }

                    is GenerationChunk.Phase -> {
                        // 立即更新 card 状态；phase/tool 未变则不回写 Master。
                        val phase = mapPhase(chunk.phase)
                        if (phase != null) {
                            val before = runState.snapshot()
                            val meta = runState.updatePhase(phase, chunk.toolName)
                            if (meta !== before) {
                                reportMetadataPatch(execContext, meta, checkpoint = false)
                            }
                        }
                    }

                    is GenerationChunk.Checkpoint -> {
                        // 在明确边界保存 Child
                        val currentConversation = session.state.value
                        val updatedConversation = currentConversation.updateCurrentMessages(lastMessages)
                        conversationRepo.updateConversation(updatedConversation)
                    }

                    is GenerationChunk.Finished -> {
                        // 记录结束原因，用于确定终态
                        finishReason = chunk.reason
                    }
                }
            }

            if (finishReason != FinishedReason.AWAITING_APPROVAL) break
            if (interactionCount++ >= MAX_SUB_ASSISTANT_INTERACTIONS) {
                finishReason = FinishedReason.INTERACTION_LIMIT_REACHED
                break
            }
            val resumedMessages = awaitPendingAskUser(
                childConversationId = childConversationId,
                childTaskNodeId = childTaskNodeId,
                runId = runId,
                messages = lastMessages,
                execContext = execContext,
                runState = runState,
            ) ?: break
            lastMessages = resumedMessages
        }

        // 最终保存 Child
        val finalConversation = session.state.value.updateCurrentMessages(lastMessages)
        sessionRegistry.updateConversationState(childConversationId, finalConversation)
        conversationRepo.updateConversation(finalConversation)

        return TargetGenerationResult(lastMessages, finishReason)
    }

    private suspend fun awaitPendingAskUser(
        childConversationId: Uuid,
        childTaskNodeId: Uuid,
        runId: String,
        messages: List<UIMessage>,
        execContext: ToolExecutionContext,
        runState: SubAssistantRunStateReducer,
    ): List<UIMessage>? {
        val message = messages.lastOrNull() ?: return null
        val toolOrdinal = message.getTools().indexOfFirst { tool ->
            !tool.isExecuted && tool.toolName == "ask_user" &&
                tool.approvalState is ToolApprovalState.Pending
        }
        if (toolOrdinal < 0) return null
        val tool = message.getTools()[toolOrdinal]
        val interactionId = "${runId}_${message.id}_$toolOrdinal"
        val pending = PendingUserInteraction(interactionId)
        check(pendingUserInteractions.putIfAbsent(runId, pending) == null) {
            "Run $runId already has a pending user interaction"
        }

        val pendingConversation = sessionRegistry.getOrCreateSession(childConversationId)
            .state.value.updateCurrentMessages(messages)
        sessionRegistry.updateConversationState(childConversationId, pendingConversation)
        conversationRepo.updateConversation(pendingConversation)

        val interaction = SubAssistantUserInteraction(
            interactionId = interactionId,
            messageId = message.id.toString(),
            toolOrdinal = toolOrdinal,
            toolName = tool.toolName,
            input = tool.input,
        )
        val waitingMetadata = runState.awaitUserInteraction(
            interaction = interaction,
            preview = computeSubAssistantPreview(messages, childTaskNodeId).ifEmpty { null },
        )
        reportMetadataPatch(execContext, waitingMetadata, checkpoint = true)

        return try {
            val answer = pending.answer.await()
            val answeredMessages = answerToolAtLocator(
                messages = messages,
                messageId = message.id,
                toolOrdinal = toolOrdinal,
                answer = answer,
            ) ?: error("Pending ask_user locator is no longer valid")
            val answeredConversation = pendingConversation.updateCurrentMessages(answeredMessages)
            sessionRegistry.updateConversationState(childConversationId, answeredConversation)
            conversationRepo.updateConversation(answeredConversation)
            reportMetadataPatch(
                execContext = execContext,
                meta = runState.clearUserInteraction(),
                checkpoint = true,
            )
            answeredMessages
        } finally {
            pendingUserInteractions.remove(runId, pending)
        }
    }

    // ---- 恢复与清理 ----

    /**
     * App 启动时执行 recovery。
     * - metadata 仍是 starting/running 且没有 active Job：标记 stopped/app_restarted
     * - 未被任何 Master run 引用的 Child：删除
     */
    suspend fun performRecovery() {
        withTimeout(FINALIZATION_TIMEOUT_MS) {
            runLeases.cancelAll("app_restarted")
        }
        pendingUserInteractions.values.forEach { it.answer.cancel() }
        pendingUserInteractions.clear()
        val settings = settingsStore.settingsFlow.value
        val masters = conversationRepo.getAllTopLevelConversationsSync()
        val allChildIds = conversationRepo.getAllChildConversationIds().toSet()
        val linkedChildIds = masters.asSequence()
            .flatMap { master -> master.messageNodes.asSequence() }
            .flatMap { node -> node.messages.asSequence() }
            .flatMap { message -> message.parts.filterIsInstance<UIMessagePart.Tool>().asSequence() }
            .mapNotNull { tool -> tool.getSubAssistantCallMetadata(json)?.childConversationId }
            .mapNotNull { id -> runCatching { Uuid.parse(id) }.getOrNull() }
            .filter { it in allChildIds }
            .toSet()
        val childrenById = linkedChildIds.mapNotNull { childId ->
            conversationRepo.getConversationById(childId)?.let { childId to it }
        }.toMap()
        val referencedChildIds = mutableSetOf<Uuid>()
        val childStopReasons = mutableMapOf<Uuid, String>()

        masters.forEach { master ->
            val result = recoverMasterSubAssistantCalls(master, settings, childrenById, json)
            referencedChildIds += result.referencedChildIds
            result.childStopReasons.forEach { (childId, reason) ->
                childStopReasons.putIfAbsent(childId, reason)
            }
            if (result.master != master) {
                Log.i(TAG, "performRecovery: finalized stale runs in ${master.id}")
                conversationRepo.updateConversation(result.master)
                sessionRegistry.getSession(master.id)?.let {
                    sessionRegistry.updateConversationState(master.id, result.master)
                }
            }
        }

        // Every valid referenced Child is protocol-complete before it can be reused.
        referencedChildIds.forEach { childId ->
            val child = childrenById[childId] ?: return@forEach
            val reason = childStopReasons[childId] ?: "app_restarted"
            val recoveredNodes = child.messageNodes.map { node ->
                node.copy(
                    messages = node.messages.map { message ->
                        message.finishReasoning().recoverSubAssistantToolsAfterInterruption(reason)
                    }
                )
            }
            if (recoveredNodes != child.messageNodes) {
                val recoveredChild = child.copy(messageNodes = recoveredNodes)
                conversationRepo.updateConversation(recoveredChild)
                sessionRegistry.getSession(childId)?.let {
                    sessionRegistry.updateConversationState(childId, recoveredChild)
                }
            }
        }

        // Only a unique, structurally valid Master link retains a Child.
        val orphanChildIds = allChildIds - referencedChildIds
        for (orphanId in orphanChildIds) {
            Log.i(TAG, "performRecovery: deleting orphan child $orphanId")
            runCatching {
                conversationRepo.getConversationById(orphanId)?.let {
                    conversationRepo.deleteConversation(it)
                }
                sessionRegistry.evictSession(orphanId)
            }
        }
    }

    /** Finalizes stale runs and orphan links before a Master tree mutation such as fork/delete. */
    suspend fun recoverMasterForMutation(master: Conversation): Conversation {
        require(master.parentConversationId == null)
        val children = conversationRepo.getChildConversations(master.id).associateBy { it.id }
        val result = recoverMasterSubAssistantCalls(
            master = master,
            settings = settingsStore.settingsFlow.value,
            childrenById = children,
            json = json,
        )
        if (result.master != master) {
            conversationRepo.updateConversation(result.master)
            sessionRegistry.updateConversationState(master.id, result.master)
        }
        result.referencedChildIds.forEach { childId ->
            val child = children[childId] ?: return@forEach
            val reason = result.childStopReasons[childId] ?: "app_restarted"
            val recoveredNodes = child.messageNodes.map { node ->
                node.copy(
                    messages = node.messages.map { message ->
                        message.finishReasoning().recoverSubAssistantToolsAfterInterruption(reason)
                    }
                )
            }
            if (recoveredNodes != child.messageNodes) {
                val recovered = child.copy(messageNodes = recoveredNodes)
                conversationRepo.updateConversation(recovered)
                sessionRegistry.getSession(childId)?.let {
                    sessionRegistry.updateConversationState(childId, recovered)
                }
            }
        }
        (children.keys - result.referencedChildIds).forEach { orphanId ->
            conversationRepo.deleteConversation(children.getValue(orphanId))
            sessionRegistry.evictSession(orphanId)
        }
        return result.master
    }

    // ---- 工具函数 ----

    private fun mapPhase(phase: String): SubAssistantCallPhase? = when (phase) {
        "preparing" -> SubAssistantCallPhase.PREPARING
        "model_waiting" -> SubAssistantCallPhase.MODEL_WAITING
        "reasoning_streaming" -> SubAssistantCallPhase.REASONING_STREAMING
        "answer_streaming" -> SubAssistantCallPhase.ANSWER_STREAMING
        "tool_executing" -> SubAssistantCallPhase.TOOL_EXECUTING
        "between_steps" -> SubAssistantCallPhase.BETWEEN_STEPS
        else -> null
    }

    private suspend fun reportMetadataPatch(
        execContext: ToolExecutionContext,
        meta: SubAssistantCallMetadata,
        checkpoint: Boolean,
    ) {
        val patch = JsonObject(mapOf("sub_assistant_call" to json.encodeToJsonElement(
            SubAssistantCallMetadata.serializer(), meta
        )))
        execContext.reportMetadata(patch, checkpoint)
    }

    private fun extractFinalAnswer(
        messages: List<UIMessage>,
        childTaskNodeId: Uuid,
    ): String = extractFinalAnswerInternal(messages, childTaskNodeId)

    private fun checkNonTextOutput(
        messages: List<UIMessage>,
        childTaskNodeId: Uuid,
    ): Boolean = checkNonTextOutputInternal(messages, childTaskNodeId)

    private fun copyPartForChildClone(part: UIMessagePart): UIMessagePart {
        fun copyUrl(url: String): String {
            if (!url.startsWith("file:")) return url
            return filesManager.createChatFilesByContents(listOf(url.toUri()))
                .firstOrNull()?.toString() ?: url
        }
        return when (part) {
            is UIMessagePart.Image -> part.copy(url = copyUrl(part.url))
            is UIMessagePart.Document -> part.copy(url = copyUrl(part.url))
            is UIMessagePart.Video -> part.copy(url = copyUrl(part.url))
            is UIMessagePart.Audio -> part.copy(url = copyUrl(part.url))
            is UIMessagePart.Tool -> part.copy(output = part.output.map(::copyPartForChildClone))
            else -> part
        }
    }

    private suspend fun recoverInterruptedChild(childConversationId: Uuid, reason: String) {
        val session = sessionRegistry.getSession(childConversationId) ?: run {
            val persisted = conversationRepo.getConversationById(childConversationId)
            if (persisted == null) {
                Log.w(TAG, "recoverInterruptedChild: child $childConversationId no longer exists")
                return
            }
            sessionRegistry.getOrCreateSessionWithConversation(childConversationId, persisted)
        }
        val conversation = session.state.value
        val recoveredNodes = conversation.messageNodes.map { node ->
            node.copy(
                messages = node.messages.map { message ->
                    message.finishReasoning().recoverSubAssistantToolsAfterInterruption(reason)
                }
            )
        }
        if (recoveredNodes == conversation.messageNodes) return

        val recovered = conversation.copy(messageNodes = recoveredNodes)
        sessionRegistry.updateConversationState(childConversationId, recovered)
        conversationRepo.updateConversation(recovered)
    }

    private suspend fun finalizeInterruptedRun(
        childConversationId: Uuid,
        reason: String,
        execContext: ToolExecutionContext,
        terminalMeta: SubAssistantCallMetadata,
    ) {
        val failures = finalizeInterruptedRunSafely(
            timeoutMillis = FINALIZATION_TIMEOUT_MS,
            finalizeChild = { recoverInterruptedChild(childConversationId, reason) },
            finalizeMetadata = { reportMetadataPatch(execContext, terminalMeta, checkpoint = false) },
        )
        failures.child?.let { error ->
            Log.e(TAG, "Unable to finalize interrupted child $childConversationId", error)
        }
        failures.metadata?.let { error ->
            Log.e(TAG, "Unable to persist terminal metadata for ${terminalMeta.runId}", error)
        }
    }

    private suspend fun unavailableResult(
        execContext: ToolExecutionContext,
        targetAssistantId: Uuid,
        assistantName: String,
        reason: String,
        runId: String = Uuid.random().toString(),
    ): List<UIMessagePart> {
        val metadata = buildInitialSubAssistantCallMetadata(
            runId = runId,
            targetAssistantId = targetAssistantId,
            targetNameSnapshot = assistantName,
        ).copy(
            state = SubAssistantCallState.UNAVAILABLE,
            reason = reason,
        )
        reportMetadataPatch(execContext, metadata, checkpoint = false)
        val resultJson = buildSubAssistantCallResult(
            json = json,
            status = "unavailable",
            assistantName = assistantName,
            content = "",
            reason = reason,
        )
        return listOf(UIMessagePart.Text(resultJson))
    }
}

internal fun normalizeSubAssistantCancellationReason(message: String?): String = when (message) {
    "target_removed",
    "target_disabled",
    "target_access_revoked",
    "target_model_unavailable",
    "caller_model_unavailable",
    "app_restarted",
    "child_missing",
    "user_cancelled" -> message
    "assistant_removed" -> "target_removed"
    else -> "user_cancelled"
}

/**
 * 从 Child 会话消息中提取 final answer。
 *
 * 优先取最后一个 Target ASSISTANT step 中、最后一个“工作工具”
 * 之后的顶层可见 Text。`text_to_speech` 等副作用工具不挡住答案。
 * 最后一步只有 Reasoning/空 Text 时，回退到更早 step 的 post-tool 文本；
 * 仍为空时取最后一条有文本的 ASSISTANT 消息的末段 Text island，避免主助手拿到空 content。
 */
internal fun extractFinalAnswerInternal(
    messages: List<UIMessage>,
    childTaskNodeId: Uuid,
): String {
    val range = messagesInRunRange(messages, childTaskNodeId) ?: return ""
    val assistants = range.filter { it.role == MessageRole.ASSISTANT }
    if (assistants.isEmpty()) return ""

    extractTextAfterLastWorkTool(assistants.last())
        .takeIf { it.isNotBlank() }
        ?.let { return it }

    for (i in assistants.lastIndex - 1 downTo 0) {
        extractTextAfterLastWorkTool(assistants[i])
            .takeIf { it.isNotBlank() }
            ?.let { return it }
    }

    for (msg in assistants.asReversed()) {
        lastTextIsland(msg).takeIf { it.isNotBlank() }?.let { return it }
    }
    return ""
}

/**
 * 检查最后一个 ASSISTANT step 是否有非文本输出（排除 Text、Tool、Reasoning）。
 */
internal fun checkNonTextOutputInternal(
    messages: List<UIMessage>,
    childTaskNodeId: Uuid,
): Boolean {
    val range = messagesInRunRange(messages, childTaskNodeId) ?: return false
    val lastAssistant = range.lastOrNull { it.role == MessageRole.ASSISTANT } ?: return false
    return lastAssistant.parts.any {
        it !is UIMessagePart.Text && it !is UIMessagePart.Tool && it !is UIMessagePart.Reasoning
    }
}

private fun messagesInRunRange(
    messages: List<UIMessage>,
    childTaskNodeId: Uuid,
): List<UIMessage>? {
    val startIndex = messages.indexOfFirst { it.id == childTaskNodeId }
    if (startIndex == -1) return null
    var endIndex = messages.size
    for (i in (startIndex + 1) until messages.size) {
        if (messages[i].role == MessageRole.USER) {
            endIndex = i
            break
        }
    }
    return messages.subList(startIndex, endIndex)
}

private val SUB_ASSISTANT_SIDE_EFFECT_TOOLS = setOf("text_to_speech")

private fun extractTextAfterLastWorkTool(message: UIMessage): String {
    val parts = message.parts
    var lastWorkToolEnd = 0
    for ((idx, part) in parts.withIndex()) {
        if (part is UIMessagePart.Tool &&
            part.isExecuted &&
            part.toolName !in SUB_ASSISTANT_SIDE_EFFECT_TOOLS
        ) {
            lastWorkToolEnd = idx + 1
        }
    }
    return parts.drop(lastWorkToolEnd)
        .filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .trim()
}

private fun lastTextIsland(message: UIMessage): String {
    val parts = message.parts
    var end = parts.lastIndex
    while (end >= 0 && parts[end] !is UIMessagePart.Text) end--
    if (end < 0) return ""
    var start = end
    while (start >= 0 && parts[start] is UIMessagePart.Text) start--
    return parts.subList(start + 1, end + 1)
        .filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .trim()
}

internal fun preprocessSubAssistantTask(
    task: String,
    target: net.weero.measix.pilot.data.model.Assistant,
): String = task.replaceRegexes(
    assistant = target,
    scope = AssistantAffectScope.USER,
    visual = false,
)

internal fun UIMessage.recoverSubAssistantToolsAfterInterruption(reason: String): UIMessage {
    fun markInterrupted(tool: UIMessagePart.Tool): UIMessagePart.Tool = tool.copy(
        output = listOf(
            UIMessagePart.Text(
                """{"status":"interrupted","reason":"$reason"}"""
            )
        ),
        approvalState = if (tool.approvalState is ToolApprovalState.Pending) {
            ToolApprovalState.Denied(reason)
        } else {
            tool.approvalState
        },
    )
    return finishPendingTools(::markInterrupted).finishInterruptedTools(::markInterrupted)
}

internal fun UIMessage.recoverSubAssistantToolsAfterRestart(): UIMessage =
    recoverSubAssistantToolsAfterInterruption("app_restarted")

/** 精确回答 Child Assistant message 中指定 ordinal 的待处理 ask_user。 */
internal fun answerToolAtLocator(
    messages: List<UIMessage>,
    messageId: Uuid,
    toolOrdinal: Int,
    answer: String,
): List<UIMessage>? {
    var matched = false
    val updated = messages.map messageMap@{ message ->
        if (message.id != messageId) return@messageMap message
        var ordinal = 0
        val parts = message.parts.map partMap@{ part ->
            if (part !is UIMessagePart.Tool) return@partMap part
            val currentOrdinal = ordinal++
            if (currentOrdinal != toolOrdinal) return@partMap part
            if (part.toolName != "ask_user" || part.isExecuted ||
                part.approvalState !is ToolApprovalState.Pending
            ) {
                return null
            }
            matched = true
            part.copy(approvalState = ToolApprovalState.Answered(answer))
        }
        message.copy(parts = parts)
    }
    return updated.takeIf { matched }
}
