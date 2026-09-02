package net.weero.measix.pilot.service.runtime

import kotlinx.coroutines.flow.map

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionContext
import me.rerere.ai.core.ToolMetadataDelivery
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.TurnTerminalReasons
import net.weero.measix.pilot.data.ai.GenerationLoop
import net.weero.measix.pilot.data.ai.GenerationRequest
import net.weero.measix.pilot.data.ai.resolveMemoryOwnerId
import net.weero.measix.pilot.data.ai.mcp.McpServerCapabilityState
import net.weero.measix.pilot.data.ai.mcp.TurnMcpCapabilitySnapshot
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallPhase
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallState
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantRunStateReducer
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantRunSpecResolution
import net.weero.measix.pilot.data.ai.subassistant.buildInitialSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.buildClassifiedFailureResult
import net.weero.measix.pilot.data.ai.subassistant.buildUnavailableCallResult
import net.weero.measix.pilot.data.ai.subassistant.buildChildUserParts
import net.weero.measix.pilot.data.ai.subassistant.buildSubAssistantCallResultParts
import net.weero.measix.pilot.data.ai.subassistant.extractFinalAnswerInternal
import net.weero.measix.pilot.data.ai.subassistant.extractDeliverableArtifacts
import net.weero.measix.pilot.data.ai.subassistant.validateDeliverableArtifacts
import net.weero.measix.pilot.data.ai.subassistant.projectArtifactsForCaller
import net.weero.measix.pilot.data.ai.subassistant.computeSubAssistantPreview
import net.weero.measix.pilot.data.ai.subassistant.computeTerminalPreview
import net.weero.measix.pilot.data.ai.subassistant.cloneLineagePrefix
import net.weero.measix.pilot.data.ai.subassistant.findPreviousCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.classifySubAssistantFailure
import net.weero.measix.pilot.data.ai.subassistant.modelVisibleFailureDetail
import net.weero.measix.pilot.data.ai.subassistant.getSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.mapSubAssistantCallPhase
import net.weero.measix.pilot.data.ai.subassistant.normalizeSubAssistantCancellationReason
import net.weero.measix.pilot.data.ai.subassistant.preprocessSubAssistantTask
import net.weero.measix.pilot.data.ai.subassistant.reportSubAssistantMetadataPatch
import net.weero.measix.pilot.data.ai.subassistant.resolveLineage
import net.weero.measix.pilot.data.ai.subassistant.resolveActiveRunStopReason
import net.weero.measix.pilot.data.ai.subassistant.resolvePreWriteBlockReason
import net.weero.measix.pilot.data.ai.subassistant.resolveSubAssistantRunSpec
import net.weero.measix.pilot.data.ai.subassistant.validateReadiness
import net.weero.measix.pilot.data.ai.subassistant.ReadinessResult
import net.weero.measix.pilot.data.ai.subassistant.LineageDecision
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantDeliverableArtifact
import net.weero.measix.pilot.data.ai.tools.GenerationToolSetFactory
import net.weero.measix.pilot.data.ai.tools.buildMemoryTools
import net.weero.measix.pilot.data.ai.tools.PendingInteraction
import net.weero.measix.pilot.data.ai.tools.ToolInteractionAvailability
import net.weero.measix.pilot.data.ai.tools.ToolInteractionKind
import net.weero.measix.pilot.data.ai.tools.ToolRuntimeMetadata
import net.weero.measix.pilot.data.ai.tools.ToolSetRunMode
import net.weero.measix.pilot.data.ai.tools.local.LocalToolOption
import net.weero.measix.pilot.data.ai.tools.local.TtsToolPlaybackContext
import net.weero.measix.pilot.data.ai.tts.TtsPlaybackSource
import net.weero.measix.pilot.data.ai.attachments.AttachmentResolveResult
import net.weero.measix.pilot.data.ai.attachments.AttachmentResolver
import net.weero.measix.pilot.data.ai.transformers.TemplateTransformer
import net.weero.measix.pilot.data.ai.transformers.AttachmentProjectionTransformer
import net.weero.measix.pilot.data.ai.transformers.Base64ImageToLocalFileTransformer
import net.weero.measix.pilot.data.ai.transformers.DocumentAsPromptTransformer
import net.weero.measix.pilot.data.ai.transformers.WorkspaceReminderTransformer
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.findProvider
import net.weero.measix.pilot.data.datastore.getAssistantById
import net.weero.measix.pilot.data.files.AttachmentCloner
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.OwnedArtifact
import net.weero.measix.pilot.data.files.ToolArtifactRewriter
import net.weero.measix.pilot.data.files.requireDiscarded
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.ConversationModelContextApplicability
import net.weero.measix.pilot.data.model.toMessageNode
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.data.repository.MemoryRepository
import net.weero.measix.pilot.data.repository.WorkspaceRepository
import net.weero.measix.pilot.service.ConversationDisclosureSnapshotService
import net.weero.measix.pilot.service.SubAssistantRunGate
import net.weero.measix.pilot.service.SubAssistantRunKey
import net.weero.measix.pilot.service.TurnFinalization
import kotlinx.coroutines.Job
import kotlin.coroutines.coroutineContext
import kotlin.uuid.Uuid

private const val TAG = "DelegationCoordinator"
private const val PREVIEW_THROTTLE_MS = 100L
private const val MAX_SUB_ASSISTANT_INTERACTIONS = 16
private const val FINALIZATION_TIMEOUT_MS = 5_000L

/** Target Generation 运行结果（最终消息 + 唯一终态语义）。 */
private data class TargetGenerationResult(
    val messages: List<UIMessage>,
    val outcome: TurnOutcome,
)

/**
 * 子助手调用协调器（编排域）。
 *
 * [executeCall] 依次确认 readiness/lineage/lease，建立 Child 与附件所有权，
 * 运行 Target 生成并构建终态结果。
 * 正常终态归 [TurnFinalization]，结果形状归 SubAssistantResultProjection，并发门禁归
 * [SubAssistantRunGate]。
 */
class DelegationCoordinator(
    private val generationLoop: GenerationLoop,
    private val conversationRepo: ConversationRepository,
    private val runtimeRegistry: ConversationRuntimeRegistry,
    private val commandCoordinator: ConversationCommandCoordinator,
    private val toolSetFactory: GenerationToolSetFactory,
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val templateTransformer: TemplateTransformer,
    private val turnRequestContextFactory: TurnRequestContextFactory,
    private val artifactStore: ArtifactStore,
    private val toolArtifactRewriter: ToolArtifactRewriter,
    private val json: Json,
    private val attachmentResolver: AttachmentResolver,
    private val context: Context,
    private val turnFinalization: TurnFinalization,
    private val runGate: SubAssistantRunGate,
) {
    // Master/Target 共用管道装配。Target 管道不注入 ToolArtifactReplay
    // （run 内 artifact 重放仅 Master 侧需要），与 targetInput() 的固定清单一致。
    private val turnPipelineFactory = TurnPipelineFactory(
        templateTransformer = templateTransformer,
        workspaceReminderTransformer = WorkspaceReminderTransformer(),
        toolArtifactReplayTransformer = null,
        attachmentProjectionTransformer = AttachmentProjectionTransformer(artifactStore),
        base64ImageToLocalFileTransformer = Base64ImageToLocalFileTransformer(artifactStore),
        documentAsPromptTransformer = DocumentAsPromptTransformer(artifactStore),
    )

    /** 删除 Target 前取消并等待其所有正在执行的 Target Run 停止写回。 */
    suspend fun cancelRunsForAssistant(assistantId: Uuid) {
        runGate.cancelRunsForAssistant(assistantId)
    }

    /** 主聊天 UI 回答子助手当前 ask_user；过期或重复 interaction 会被拒绝。 */
    fun answerUserInteraction(runId: String, interactionId: String, answer: String): Boolean {
        return runGate.completeAnswer(runId, interactionId, answer)
    }

    /** preflight 产物：放行（含 lease）或拒绝（含结果 parts）。 */
    private sealed interface Preflight {
        data class Ready(
            val settings: Settings,
            val runSpec: net.weero.measix.pilot.data.ai.subassistant.SubAssistantRunSpec,
            val target: Assistant,
            val model: me.rerere.ai.provider.Model,
            val processedTask: String,
            val lineageDecision: LineageDecision,
            val runId: String,
            val runKey: SubAssistantRunKey,
            val lease: SubAssistantRunGate.LeaseHandle,
        ) : Preflight

        data class Blocked(val parts: List<UIMessagePart>) : Preflight
    }

    private suspend fun preflightCall(
        callerAssistantId: Uuid,
        masterConversationId: Uuid,
        targetAssistantId: Uuid,
        task: String,
        execContext: ToolExecutionContext,
    ): Preflight {
        val settings = settingsStore.effectiveSettings.value.settings
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

        val runKey = SubAssistantRunKey(masterConversationId, targetAssistantId)
        val callerAllowedIds = callerAssistant?.allowedSubAssistantIds ?: emptySet()
        val callerHasDelegation = callerAssistant?.localTools?.any {
            it == LocalToolOption.AssistantDelegation
        } ?: false
        val readiness = validateReadiness(
            targetAssistant = targetAssistant,
            callerAssistantId = callerAssistantId,
            callerAllowedSubAssistantIds = callerAllowedIds,
            callerHasDelegation = callerHasDelegation,
            settingsChatModel = resolvedRunSpec?.model,
            isActiveRun = runGate.isBusy(runKey),
            modelUnavailableReason = modelUnavailableReason,
        )
        if (readiness is ReadinessResult.Blocked) {
            return Preflight.Blocked(
                buildUnavailableCallResult(
                    json = json,
                    execContext = execContext,
                    targetAssistantId = targetAssistantId,
                    assistantName = targetAssistant?.name ?: targetAssistantId.toString(),
                    reason = readiness.reason,
                )
            )
        }

        val runSpec = requireNotNull(resolvedRunSpec)
        val target = runSpec.assistant
        val processedTask = preprocessSubAssistantTask(task, target)

        // lineage：previous run 元数据（当前调用之前的同 Target 调用）决定 Child 语义
        val masterMessages = runtimeRegistry.findRuntime(masterConversationId)
            ?.snapshot?.value?.currentMessages()
            ?: emptyList()
        val previousMeta = findPreviousCallMetadata(
            masterMessages = masterMessages,
            currentMessageId = execContext.messageId,
            currentToolOrdinal = execContext.toolOrdinal,
            targetAssistantId = targetAssistantId,
            json = json,
        )
        val lineageDecision = when (previousMeta) {
            null -> LineageDecision.CreateNew
            else -> {
                val childConversation = previousMeta.childConversationId?.let { childIdStr ->
                    runCatching { Uuid.parse(childIdStr) }.getOrNull()
                        ?.let { conversationRepo.getConversationById(it) }
                }
                resolveLineage(
                    previousMeta = previousMeta,
                    childConversation = childConversation,
                    expectedMasterConversationId = masterConversationId,
                    expectedTargetAssistantId = targetAssistantId,
                )
            }
        }

        // 原子获取 lease（lineage 决策后、持久化前；同 Master+Target 不可并发）
        val runId = Uuid.random().toString()
        val lease = runGate.acquireLease(
            key = runKey,
            runId = runId,
            callerAssistantId = callerAssistantId,
            parentJob = coroutineContext[Job],
        ) ?: return Preflight.Blocked(
            buildUnavailableCallResult(
                json = json,
                execContext = execContext,
                targetAssistantId = targetAssistantId,
                assistantName = target.name,
                reason = "target_busy",
                runId = runId,
            )
        )

        // 写入 request 前复验（关闭 preflight 与持久化之间的撤权竞态窗口）
        val latestBlockReason = resolvePreWriteBlockReason(
            settings = settingsStore.effectiveSettings.value.settings,
            callerAssistantId = callerAssistantId,
            targetAssistantId = targetAssistantId,
            runSpec = runSpec,
        )
        if (latestBlockReason != null) {
            lease.close()
            return Preflight.Blocked(
                buildUnavailableCallResult(
                    json = json,
                    execContext = execContext,
                    targetAssistantId = targetAssistantId,
                    assistantName = target.name,
                    reason = latestBlockReason,
                    runId = runId,
                )
            )
        }

        return Preflight.Ready(
            settings = settings,
            runSpec = runSpec,
            target = target,
            model = runSpec.model,
            processedTask = processedTask,
            lineageDecision = lineageDecision,
            runId = runId,
            runKey = runKey,
            lease = lease,
        )
    }

    /** materialize 产物：Child 定位 + 初始 metadata，或失败（含结果 parts）。 */
    private sealed interface Materialized {
        data class Ready(
            val childConversationId: Uuid,
            val childTaskNodeId: Uuid,
            val runJob: Job,
            val createdChild: Boolean,
            val createdArtifacts: List<OwnedArtifact>,
        ) : Materialized

        data class Failure(val parts: List<UIMessagePart>) : Materialized
    }

    private suspend fun materializeChild(
        preflight: Preflight.Ready,
        masterConversationId: Uuid,
        targetAssistantId: Uuid,
        execContext: ToolExecutionContext,
        attachments: List<String>,
        extras: Set<String>,
    ): Materialized {
        val target = preflight.target
        // Clone-created files remain owned here until the Child link has committed.
        val createdArtifacts = mutableListOf<OwnedArtifact>()
        try {
            return attachmentResolver.withImages(attachments) { resolved ->
                val resolvedImages = when (resolved) {
                    is AttachmentResolveResult.Failure -> return@withImages Materialized.Failure(
                        buildUnavailableCallResult(
                            json = json,
                            execContext = execContext,
                            targetAssistantId = targetAssistantId,
                            assistantName = target.name,
                            reason = resolved.reason,
                            runId = preflight.runId,
                        )
                    )
                    is AttachmentResolveResult.Success -> resolved.parts
                }
                val userParts = buildChildUserParts(preflight.processedTask, resolvedImages)

                when (preflight.lineageDecision) {
                    is LineageDecision.CreateNew,
                    is LineageDecision.CreateNewDueToError,
                    -> {
                        val (childId, taskNodeId) = createNewChild(target, masterConversationId, userParts)
                        Materialized.Ready(childId, taskNodeId, preflight.lease.job, true, createdArtifacts.toList())
                    }

                    is LineageDecision.ReuseChild -> {
                        val taskNodeId = reuseChild(target, preflight.lineageDecision.childConversationId, userParts)
                        Materialized.Ready(
                            preflight.lineageDecision.childConversationId,
                            taskNodeId,
                            preflight.lease.job,
                            false,
                            createdArtifacts.toList(),
                        )
                    }

                    is LineageDecision.CloneChild -> {
                        val (childId, taskNodeId) = cloneChild(
                            target = target,
                            masterConversationId = masterConversationId,
                            sourceChildId = preflight.lineageDecision.sourceChildConversationId,
                            throughTaskMessageId = preflight.lineageDecision.throughTaskMessageId,
                            userParts = userParts,
                            createdArtifacts = createdArtifacts,
                        )
                        Materialized.Ready(childId, taskNodeId, preflight.lease.job, true, createdArtifacts.toList())
                    }
                }
            }
        } catch (e: Exception) {
            // Child persistence failure only rolls back newly created clone resources.
            // lease 释放由 executeCall 的统一 finally 负责。
            discardCreatedArtifacts(createdArtifacts, "sub-assistant materialization rollback", e)
            if (e is CancellationException) throw e
            return Materialized.Failure(
                buildClassifiedFailureResult(
                    json = json,
                    error = e,
                    execContext = execContext,
                    targetAssistantId = targetAssistantId,
                    assistantName = target.name,
                    extras = extras,
                    runId = preflight.runId,
                )
            )
        }
    }

    private suspend fun compensateUnlinkedChild(child: Materialized.Ready) {
        var failure: Throwable? = null
        try {
            if (child.createdChild) {
                commandCoordinator.deleteOrThrow(child.childConversationId)
            } else {
                commandCoordinator.executeOrThrow(child.childConversationId, DeleteMessage(child.childTaskNodeId))
            }
        } catch (error: Throwable) {
            failure = error
        }
        child.createdArtifacts.asReversed().forEach { owned ->
            try {
                artifactStore.discardUnpublished(owned).requireDiscarded("unlinked Child rollback")
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    private suspend fun discardCreatedArtifacts(
        artifacts: List<OwnedArtifact>,
        operation: String,
        primary: Throwable,
    ) = withContext(NonCancellable) {
        artifacts.asReversed().forEach { owned ->
            try {
                artifactStore.discardUnpublished(owned).requireDiscarded(operation)
            } catch (cleanupFailure: Throwable) {
                primary.addSuppressed(cleanupFailure)
            }
        }
    }

    // ---- assistant_call 主线 ----

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
        extras: Set<String> = emptySet(),
        attachments: List<String> = emptyList(),
    ): List<UIMessagePart> {
        val preflight = preflightCall(
            callerAssistantId = callerAssistantId,
            masterConversationId = masterConversationId,
            targetAssistantId = targetAssistantId,
            task = task,
            execContext = execContext,
        )
        if (preflight is Preflight.Blocked) return preflight.parts
        val ready = preflight as Preflight.Ready
        val target = ready.target
        val model = ready.model

        var settingsWatcher: kotlinx.coroutines.Job? = null
        var runScope: CoroutineScope? = null
        var childConversationId: Uuid? = null
        var childTaskNodeId: Uuid? = null
        var childTurnId: Uuid? = null
        var childRunJob: Job? = null
        var runState: SubAssistantRunStateReducer? = null
        try {
            val materialized = materializeChild(
                preflight = ready,
                masterConversationId = masterConversationId,
                targetAssistantId = targetAssistantId,
                execContext = execContext,
                attachments = attachments,
                extras = extras,
            )
            if (materialized is Materialized.Failure) return materialized.parts
            val child = materialized as Materialized.Ready
            childConversationId = child.childConversationId
            childTaskNodeId = child.childTaskNodeId
            val runJob = child.runJob
            childRunJob = runJob

            // 调用↔Child 关系归位：本次工具执行的 durable 事实携带 child id
            // （崩溃恢复经 turn_execution(status) → child_conversation_id 定点收口）。
            // 初始 metadata（start link）
            val initialMeta = buildInitialSubAssistantCallMetadata(
                runId = ready.runId,
                targetAssistantId = targetAssistantId,
                targetNameSnapshot = target.name,
                previousRunId = when (val lineage = ready.lineageDecision) {
                    is LineageDecision.ReuseChild -> lineage.previousRunId
                    is LineageDecision.CloneChild -> lineage.sourceRunId
                    else -> null
                },
            ).copy(
                childConversationId = childConversationId.toString(),
                childTaskNodeId = childTaskNodeId.toString(),
                state = SubAssistantCallState.RUNNING,
                phase = SubAssistantCallPhase.PREPARING,
            )
            runState = SubAssistantRunStateReducer(initialMeta)
            var childLinkCommitted = false
            try {
                // 先只更新内存投影，再由 child link 的单次 checkpoint 原子提交
                // messages + tool_execution.child_conversation_id。不得拆成两个 durable 提交。
                withContext(NonCancellable) {
                    reportSubAssistantMetadataPatch(json, execContext, initialMeta, delivery = ToolMetadataDelivery.DEFERRED)
                    execContext.reportChildConversation(childConversationId.toString())
                    childLinkCommitted = true
                    artifactStore.publishAllUnpublished(child.createdArtifacts)
                }
            } catch (e: Exception) {
                if (!childLinkCommitted) {
                    withContext(NonCancellable) {
                        try {
                            compensateUnlinkedChild(child)
                        } catch (cleanupFailure: Throwable) {
                            e.addSuppressed(cleanupFailure)
                        }
                    }
                }
                if (childLinkCommitted || e is CancellationException) throw e
                return buildClassifiedFailureResult(
                    json = json,
                    error = e,
                    execContext = execContext,
                    targetAssistantId = targetAssistantId,
                    assistantName = target.name,
                    extras = extras,
                    runId = ready.runId,
                )
            }

            // Durable link 成功后才把 lease Job 安装进 Child Runtime；否则失败补偿会被
            // active runtime 自己阻断，留下未关联 Child 与克隆 artifact。
            val installedChildTurnId = Uuid.random()
            childTurnId = installedChildTurnId
            runtimeRegistry.installAndStartActiveRequest(
                conversationId = childConversationId,
                turnId = installedChildTurnId,
                worker = runJob,
            )

            // 运行中 Settings 撤权监听器：重算 caller/Target 关系，变化即取消 Child Job
            runScope = CoroutineScope(coroutineContext + runJob)
            settingsWatcher = runScope.launch {
                settingsStore.effectiveSettings.map { it.settings }.collect { latestSettings ->
                    resolveActiveRunStopReason(
                        settings = latestSettings,
                        callerAssistantId = callerAssistantId,
                        targetAssistantId = targetAssistantId,
                        runSpec = ready.runSpec,
                    )?.let { reason ->
                        runJob.cancel(reason)
                    }
                }
            }

            runJob.ensureActive()
            val genResult = withContext(runJob) {
                runTargetGeneration(
                    settings = ready.settings,
                    target = target,
                    model = model,
                    callerAssistantId = callerAssistantId,
                    runSpec = ready.runSpec,
                    childConversationId = childConversationId,
                    childTaskNodeId = childTaskNodeId,
                    childTurnId = installedChildTurnId,
                    activeWorker = runJob,
                    execContext = execContext,
                    runId = ready.runId,
                    runState = runState,
                    turnTtsContext = turnTtsContext,
                )
            }

            // StateFlow watcher 与 final 可能同时到达；提交 completed 前同步重验，
            // 防止撤权或模型失效期间到达的迟到结果被误记为成功。
            resolveActiveRunStopReason(
                settings = settingsStore.effectiveSettings.value.settings,
                callerAssistantId = callerAssistantId,
                targetAssistantId = targetAssistantId,
                runSpec = ready.runSpec,
            )?.let { reason -> throw CancellationException(reason) }

            return terminalResult(
                outcome = genResult.outcome,
                genResult = genResult,
                target = target,
                childTaskNodeId = childTaskNodeId,
                runState = runState,
                execContext = execContext,
                extras = extras,
            )
        } catch (e: CancellationException) {
            // materialize 阶段取消：无 Child 无 run，直接上抛
            val currentChild = childConversationId ?: throw e
            val currentTaskId = childTaskNodeId ?: throw e
            val currentRunState = runState ?: throw e
            // 撤权取消 message 为 target_removed/...；用户取消为 null 或 user_cancelled
            val cancelReason = normalizeSubAssistantCancellationReason(e.message)
            val masterCancelled = coroutineContext[Job]?.isActive == false
            val terminalMeta = currentRunState.updateTerminalState(
                state = SubAssistantCallState.STOPPED,
                reason = cancelReason,
            )
            try {
                val ownedTurnId = childTurnId
                if (ownedTurnId == null) {
                    turnFinalization.finalizeUnstartedSubAssistantRun(execContext, terminalMeta)
                } else {
                    turnFinalization.finalizeSubAssistantRun(
                        childConversationId = currentChild,
                        childTurnId = ownedTurnId,
                        reason = cancelReason,
                        execContext = execContext,
                        terminalMetadata = terminalMeta,
                    )
                }
            } catch (finalizationFailure: Exception) {
                if (masterCancelled) {
                    e.addSuppressed(finalizationFailure)
                    throw e
                }
                throw finalizationFailure
            }
            if (masterCancelled) throw e
            return buildSubAssistantCallResultParts(
                json = json,
                status = "stopped",
                assistantName = target.name,
                reason = cancelReason,
                messages = childRunMessages(currentChild),
                childTaskNodeId = currentTaskId,
                extras = extras,
            )
        } catch (e: Exception) {
            // materialize 阶段异常已由 materializeChild 分类；此处只剩 run 阶段失败
            val currentChild = childConversationId ?: throw e
            val currentTaskId = childTaskNodeId ?: throw e
            val currentRunState = runState ?: throw e
            Log.e(TAG, "Target generation failed", e)
            val failureReason = classifySubAssistantFailure(e)
            val terminalMeta = currentRunState.updateTerminalState(
                state = SubAssistantCallState.FAILED,
                reason = failureReason,
            )
            try {
                val ownedTurnId = childTurnId
                if (ownedTurnId == null) {
                    turnFinalization.finalizeUnstartedSubAssistantRun(execContext, terminalMeta)
                } else {
                    turnFinalization.finalizeSubAssistantRun(
                        childConversationId = currentChild,
                        childTurnId = ownedTurnId,
                        reason = failureReason,
                        execContext = execContext,
                        terminalMetadata = terminalMeta,
                    )
                }
            } catch (finalizationFailure: Exception) {
                finalizationFailure.addSuppressed(e)
                throw finalizationFailure
            }
            return buildSubAssistantCallResultParts(
                json = json,
                status = "failed",
                assistantName = target.name,
                reason = failureReason,
                detail = modelVisibleFailureDetail(failureReason, e),
                messages = childRunMessages(currentChild),
                childTaskNodeId = currentTaskId,
                extras = extras,
            )
        } finally {
            settingsWatcher?.cancel()
            runScope?.cancel()
            val ownedChildId = childConversationId
            val ownedTurnId = childTurnId
            val ownedWorker = childRunJob
            if (ownedChildId != null && ownedTurnId != null && ownedWorker != null) {
                runtimeRegistry.findRuntime(ownedChildId)?.releaseActiveRequest(
                    turnId = ownedTurnId,
                    worker = ownedWorker,
                    retainAwaitingOwner = false,
                )
            }
            ready.lease.close()
        }
    }

    /** 按 [TurnOutcome] 统一生成终态，所有失败分支共享同一结果形状。 */
    private suspend fun terminalResult(
        outcome: TurnOutcome,
        genResult: TargetGenerationResult,
        target: Assistant,
        childTaskNodeId: Uuid,
        runState: SubAssistantRunStateReducer,
        execContext: ToolExecutionContext,
        extras: Set<String>,
    ): List<UIMessagePart> = when (outcome) {
        is TurnOutcome.Incomplete -> failedTerminal(
            when (outcome.terminalReason) {
                TurnTerminalReasons.TOOL_LOOP_LIMIT -> "step_limit_reached"
                TurnTerminalReasons.INTERACTION_LIMIT -> "interaction_limit_reached"
                else -> "provider_incomplete"
            },
            target,
            genResult,
            childTaskNodeId,
            runState,
            execContext,
            extras,
        )
        is TurnOutcome.AwaitingApproval ->
            // 只有无法桥接 Pending ask_user 或达到交互上限时才会返回到这里。
            failedTerminal("approval_blocked", target, genResult, childTaskNodeId, runState, execContext, extras)
        TurnOutcome.Completed -> {
            val finalText = extractFinalAnswerInternal(genResult.messages, childTaskNodeId)
            val extracted = validateDeliverableArtifacts(
                extracted = extractDeliverableArtifacts(
                    messages = genResult.messages,
                    childTaskNodeId = childTaskNodeId,
                    filesDir = context.filesDir,
                ),
                artifactStore = artifactStore,
            )
            val artifacts = extracted.artifacts.map { it.toMetadata() }
            // Caller native/reference 投影统一交给 AttachmentProjectionTransformer
            // 按本次请求的 resolved model 决定，这里不判断 Caller 能力。
            val callerProjection = projectArtifactsForCaller(
                artifacts = extracted.artifacts,
                extras = extras,
            )
            val terminalMeta = runState.updateTerminalState(
                state = SubAssistantCallState.COMPLETED,
                preview = computeTerminalPreview(finalText).ifEmpty { null },
                hasNonTextOutput = extracted.hasNonTextOutput,
                artifacts = artifacts,
                artifactOmitted = extracted.omitted,
            )
            reportSubAssistantMetadataPatch(json, execContext, terminalMeta, delivery = ToolMetadataDelivery.DEFERRED)
            buildSubAssistantCallResultParts(
                json = json,
                status = "completed",
                assistantName = target.name,
                content = finalText,
                hasNonTextOutput = extracted.hasNonTextOutput,
                messages = genResult.messages,
                childTaskNodeId = childTaskNodeId,
                extras = extras,
                artifacts = artifacts,
                artifactsOmitted = extracted.omitted,
                extraParts = callerProjection.extraParts,
            )
        }
        is TurnOutcome.Cancelled -> error("Cancelled Target outcome must be handled by executeCall")
        is TurnOutcome.Failed -> throw outcome.error
    }

    private suspend fun failedTerminal(
        reason: String,
        target: Assistant,
        genResult: TargetGenerationResult,
        childTaskNodeId: Uuid,
        runState: SubAssistantRunStateReducer,
        execContext: ToolExecutionContext,
        extras: Set<String>,
    ): List<UIMessagePart> {
        val terminalMeta = runState.updateTerminalState(
            state = SubAssistantCallState.FAILED,
            reason = reason,
        )
        reportSubAssistantMetadataPatch(json, execContext, terminalMeta, delivery = ToolMetadataDelivery.DEFERRED)
        return buildSubAssistantCallResultParts(
            json = json,
            status = "failed",
            assistantName = target.name,
            reason = reason,
            messages = genResult.messages,
            childTaskNodeId = childTaskNodeId,
            extras = extras,
        )
    }

    // ---- Child Conversation 管理 ----

    private suspend fun createNewChild(
        target: Assistant,
        masterConversationId: Uuid,
        userParts: List<UIMessagePart>,
    ): Pair<Uuid, Uuid> {
        val childId = Uuid.random()
        // 首次创建时只写入 Target 的 preset messages
        val taskMessage = UIMessage(role = MessageRole.USER, parts = userParts)
        val conversation = Conversation(
            id = childId,
            assistantId = target.id,
            title = target.name,
            messageNodes = target.presetMessages.map { it.toMessageNode() } + taskMessage.toMessageNode(),
            parentConversationId = masterConversationId,
        )
        createOwnedChild(conversation)

        // child_task_node_id 是本次 USER 的 UIMessage.id，供 final answer / artifacts /
        // preview 按 messagesInRunRange 定位。
        Log.i(TAG, "createNewChild: child=$childId, taskMsg=${taskMessage.id}")
        return childId to taskMessage.id
    }

    private suspend fun reuseChild(
        target: Assistant,
        childConversationId: Uuid,
        userParts: List<UIMessagePart>,
    ): Uuid {
        // 追加任务消息 = AppendUserMessage 命令（delta 落库，消除整对象回写）
        val taskMessage = UIMessage(role = MessageRole.USER, parts = userParts)
        try {
            commandCoordinator.executeOrThrow(childConversationId, AppendUserMessage(taskMessage))
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                try {
                    commandCoordinator.executeOrThrow(childConversationId, DeleteMessage(taskMessage.id))
                } catch (cleanupFailure: Throwable) {
                    error.addSuppressed(cleanupFailure)
                }
            }
            throw error
        }

        Log.i(TAG, "reuseChild: child=$childConversationId, taskMsg=${taskMessage.id}")
        return taskMessage.id
    }

    private suspend fun cloneChild(
        target: Assistant,
        masterConversationId: Uuid,
        sourceChildId: Uuid,
        throughTaskMessageId: Uuid,
        userParts: List<UIMessagePart>,
        createdArtifacts: MutableList<OwnedArtifact>,
    ): Pair<Uuid, Uuid> {
        val sourceConversation = conversationRepo.getConversationSnapshotById(sourceChildId)
            ?: throw IllegalStateException("Source child conversation not found: $sourceChildId")

        // 只克隆所选 previous run 的前缀；源 Child 的后续 task 属于另一条分支。
        val newChildId = Uuid.random()
        val sourcePrefix = cloneLineagePrefix(sourceConversation.nodes, throughTaskMessageId)
            ?: throw IllegalStateException("Source child lineage endpoint is invalid: $throughTaskMessageId")
        val copiedArtifacts = linkedMapOf<String, OwnedArtifact>()
        // Child lineage clone 重建 node id、保留 message id：context entries 走唯一映射，
        // 并按统一因果谓词过滤（§14.1）。
        val clonedNodeIdMap = mutableMapOf<Uuid, Uuid>()
        val clonedNodes = sourcePrefix.map { node ->
            val newNodeId = Uuid.random()
            clonedNodeIdMap[node.id] = newNodeId
            node.copy(
                id = newNodeId,
                messages = node.messages.map { message ->
                    message.copy(
                        parts = AttachmentCloner.cloneParts(
                            message.parts,
                            artifactStore,
                            createdArtifacts = createdArtifacts,
                            toolArtifactRewriter = toolArtifactRewriter,
                            copiedArtifacts = copiedArtifacts,
                        )
                    )
                },
            )
        }
        val taskMessage = UIMessage(role = MessageRole.USER, parts = userParts)
        val snapshot = sourceConversation.copy(
            conversationId = newChildId,
            header = sourceConversation.header.copy(
                id = newChildId,
                assistantId = target.id,
                title = target.name,
                parentConversationId = masterConversationId,
                isPinned = false,
                folderId = null,
                chatSuggestions = emptyList(),
                customSystemPrompt = null,
                modeInjectionIds = emptySet(),
                workspaceCwd = null,
                newConversation = false,
            ),
            nodes = clonedNodes + taskMessage.toMessageNode(),
            activeTurn = null,
            modelContextEntries = ConversationModelContextApplicability.remapForClone(
                entries = sourceConversation.modelContextEntries,
                nodeIdMap = clonedNodeIdMap,
                messageIdMap = emptyMap(),
                clonedBranchMessages = clonedNodes.map { it.currentMessage },
            ),
        )
        createOwnedChild(snapshot)

        Log.i(TAG, "cloneChild: source=$sourceChildId, new=$newChildId, taskMsg=${taskMessage.id}")
        return newChildId to taskMessage.id
    }

    /**
     * A create call can be cancelled while returning from its non-cancellable commit boundary.
     * The random Child id is this operation's ownership token, so cancellation compensates that
     * exact aggregate before attachment ownership is released by the caller.
     */
    private suspend fun createOwnedChild(snapshot: ConversationAggregateSnapshot) {
        try {
            commandCoordinator.createSnapshot(snapshot)
        } catch (error: Throwable) {
            if (error !is ConversationCommandConflictException) {
                withContext(NonCancellable) {
                    try {
                        commandCoordinator.deleteOrThrow(snapshot.conversationId)
                    } catch (cleanupFailure: Throwable) {
                        error.addSuppressed(cleanupFailure)
                    }
                }
            }
            throw error
        }
    }

    private suspend fun createOwnedChild(conversation: Conversation) {
        try {
            commandCoordinator.create(conversation)
        } catch (error: Throwable) {
            if (error !is ConversationCommandConflictException) {
                withContext(NonCancellable) {
                    try {
                        commandCoordinator.deleteOrThrow(conversation.id)
                    } catch (cleanupFailure: Throwable) {
                        error.addSuppressed(cleanupFailure)
                    }
                }
            }
            throw error
        }
    }

    private suspend fun runTargetGeneration(
        settings: Settings,
        target: Assistant,
        model: me.rerere.ai.provider.Model,
        callerAssistantId: Uuid,
        runSpec: net.weero.measix.pilot.data.ai.subassistant.SubAssistantRunSpec,
        childConversationId: Uuid,
        childTaskNodeId: Uuid,
        childTurnId: Uuid,
        activeWorker: kotlinx.coroutines.Job,
        execContext: ToolExecutionContext,
        runId: String,
        runState: SubAssistantRunStateReducer,
        turnTtsContext: TtsToolPlaybackContext? = null,
    ): TargetGenerationResult {
        val runtime = commandCoordinator.load(childConversationId)
        val snapshot = runtime.snapshot.value

        // 复用 turn-level TtsToolPlaybackContext 的 sessionId，使整轮 turn 内的 Master 和
        // 所有 Target 的 TTS 调用归属同一条播放队列；无 turnTtsContext 时回退独立 context。
        val ttsPlaybackContext = TtsToolPlaybackContext(
            sessionId = turnTtsContext?.sessionId ?: Uuid.random().toString(),
            assistantId = target.id,
            assistantName = target.name,
            sourceType = TtsPlaybackSource.SourceType.SUB_ASSISTANT,
        )
        val mediaCapabilities = generationLoop.resolveRequestMediaCapabilities(settings, model)
        val providerSetting = model.findProvider(settings.providers) ?: error("Provider not found")
        val promptInputs = turnRequestContextFactory.capturePromptInputs(
            settings = settings,
            assistant = target,
            model = model,
            conversationSystemPrompt = null,
            conversationModeInjectionIds = target.modeInjectionIds,
        )
        val disclosureCandidate = ConversationDisclosureSnapshotService.captureCandidate(
            settings = settings,
            assistant = target,
            memoryRepository = memoryRepository,
        )
        val mcpCapabilities = toolSetFactory.prepareMcpCapabilities(target)
        targetMcpPreparationFailure(mcpCapabilities)?.let(::error)
        val regularTools = toolSetFactory.buildTools(
            assistant = target,
            conversationId = childConversationId,
            settings = settings,
            capabilityModel = model,
            workspaceCwd = snapshot.header.workspaceCwd,
            runMode = ToolSetRunMode.TARGET,
            ttsPlaybackContext = ttsPlaybackContext,
            mcpCapabilities = mcpCapabilities,
        )
        val memoryOwnerId = resolveMemoryOwnerId(target)
        val tools = buildList {
            if (memoryOwnerId != null) {
                addAll(
                    buildMemoryTools(
                        onCreation = { content -> memoryRepository.addMemory(memoryOwnerId, content) },
                        onUpdate = { id, content -> memoryRepository.updateContent(id, content, memoryOwnerId) },
                        onDelete = { id -> memoryRepository.deleteMemory(id, memoryOwnerId) },
                        isStillAllowed = {
                            resolveMemoryOwnerId(
                                settingsStore.effectiveSettings.value.settings.getAssistantById(target.id),
                            ) == memoryOwnerId
                        },
                    ),
                )
            }
            addAll(regularTools)
        }
        val credentialOwner = captureProviderCredentialOwner(
            settings = settings,
            model = model,
            selectedProvider = providerSetting,
        )
        val requestContext = turnRequestContextFactory.create(
            assistant = target,
            model = model,
            providerSetting = providerSetting,
            providerTransportLease = ProviderTransportLease {
                resolveProviderTransportOwner(
                    settingsStore.effectiveSettings.value.settings,
                    credentialOwner,
                )
            },
            mediaCapabilities = mediaCapabilities,
            promptInputs = promptInputs,
            tools = tools,
        )
        // START admission is checked after all suspending preparation. Live state may revoke the
        // run, but it cannot change the already frozen wire shape for an admitted START.
        activeWorker.ensureActive()
        resolveActiveRunStopReason(
            settings = settingsStore.effectiveSettings.value.settings,
            callerAssistantId = callerAssistantId,
            targetAssistantId = target.id,
            runSpec = runSpec,
        )?.let { reason -> throw CancellationException(reason) }
        activeWorker.ensureActive()
        // Registry owns the exact run lease Job; withContext(runJob) may expose a child scope Job.
        runtime.bindTurnRequestContext(childTurnId, activeWorker, requestContext)

        // 只作为下一轮 GenerationLoop 的输入与 preview 投影源。暂停投影刻意不经流式通道，
        // 因此它不能用于定位挂起交互；定位一律使用 TurnOutcome.AwaitingApproval.pending。
        var lastMessages = snapshot.currentMessages()
        var lastPreviewUpdate = 0L
        var outcome: TurnOutcome? = null
        var interactionCount = 0

        // One SubAssistant Run owns one immutable context and one durable Child turn across ask_user continuations.
        val started = TurnEngine.start(
            commandCoordinator = commandCoordinator,
            runtime = runtime,
            turnId = childTurnId,
            modelContextCandidate = disclosureCandidate,
            turnFinalization = turnFinalization,
        )
        val turnEngine = started.engine
        lastMessages = runtime.snapshot.value.currentMessages()
        val modelContextProjection = ConversationTransition.projectTurnModelContext(runtime.snapshot.value)
        while (true) {
            outcome = null
            generationLoop.run(
                GenerationRequest(
                    conversationId = childConversationId,
                    requestContext = requestContext,
                    messages = lastMessages,
                    inputTransformers = turnPipelineFactory.targetInput(),
                    outputTransformers = turnPipelineFactory.targetOutput(),
                    interactionAvailability = ToolInteractionAvailability.USER_INPUT_ONLY,
                    assistantMessageId = started.assistantMessageId,
                    modelContextEntries = modelContextProjection.entries,
                    durableMessageLocators = modelContextProjection.locators,
                    onMessagesObserved = turnEngine::observeMessages,
                    reportProcessingText = runtime.processingReporter(),
                    maxSteps = 256,
                    providerSessionId = childConversationId.toString(),
                    onCheckpoint = { checkpoint ->
                        lastMessages = checkpoint.messages
                        turnEngine.onCheckpoint(checkpoint)
                    },
                )
            ).let { source ->
                turnEngine.bind(source).collect { event ->
                    when (event) {
                        is TurnEvent.Streaming -> {
                            // 流式 delta 已由 bind 内 applyStreamingDelta 更新 Child Runtime 投影
                            lastMessages = event.messages

                            // 节流回写 preview 到 Master；内容未变则不 patch。
                            val now = System.currentTimeMillis()
                            if (now - lastPreviewUpdate >= PREVIEW_THROTTLE_MS) {
                                lastPreviewUpdate = now
                                val preview = computeSubAssistantPreview(event.messages, childTaskNodeId)
                                val before = runState.snapshot()
                                val meta = runState.updatePreview(preview.ifEmpty { null })
                                if (meta !== before) {
                                    reportSubAssistantMetadataPatch(json, execContext, meta, delivery = ToolMetadataDelivery.STREAMING)
                                }
                            }
                        }

                        is TurnEvent.Phase -> {
                            // 立即更新 card 状态；phase/tool 未变则不回写 Master。
                            mapSubAssistantCallPhase(event.phase)?.let { phase ->
                                val before = runState.snapshot()
                                val meta = runState.updatePhase(phase, event.toolName)
                                if (meta !== before) {
                                    reportSubAssistantMetadataPatch(json, execContext, meta, delivery = ToolMetadataDelivery.STREAMING)
                                }
                            }
                        }

                        is TurnEvent.Finished -> {
                            outcome = event.outcome
                        }
                    }
                }
            }

            when (val finished = outcome) {
                is TurnOutcome.Failed -> throw finished.error
                is TurnOutcome.Cancelled -> throw CancellationException(finished.terminalReason)
                else -> Unit
            }

            val awaiting = outcome as? TurnOutcome.AwaitingApproval ?: break
            if (interactionCount++ >= MAX_SUB_ASSISTANT_INTERACTIONS) {
                val limitOutcome = TurnOutcome.Incomplete(TurnTerminalReasons.INTERACTION_LIMIT)
                outcome = limitOutcome
                turnEngine.submitOutcome(
                    messages = runtime.snapshot.value.currentMessages(),
                    outcome = limitOutcome,
                    closeInterruptedTools = false,
                )
                break
            }
            val resumedMessages = awaitPendingAskUser(
                runtime = runtime,
                childTaskNodeId = childTaskNodeId,
                runId = runId,
                pending = awaiting.pending,
                execContext = execContext,
                runState = runState,
            )
            lastMessages = resumedMessages
        }

        return TargetGenerationResult(
            messages = lastMessages,
            outcome = outcome ?: TurnOutcome.Incomplete(TurnTerminalReasons.PROVIDER_INCOMPLETE),
        )
    }

    private suspend fun awaitPendingAskUser(
        runtime: ConversationRuntime,
        childTaskNodeId: Uuid,
        runId: String,
        pending: List<PendingInteraction>,
        execContext: ToolExecutionContext,
        runState: SubAssistantRunStateReducer,
    ): List<UIMessage> {
        // 消息一律从 Runtime 的当前投影读取；暂停投影刻意不经流式通道，
        // 因此调用方持有的任何消息快照都不能用于定位交互。
        val messages = runtime.snapshot.value.currentMessages()
        val message = requireNotNull(messages.lastOrNull()) { "user-input continuation has no assistant message" }
        // 定位直接来自 Runtime 在挂起当刻产出的 pending；不回消息里扫描，
        // 不依赖 ToolRuntimeMetadata 是否已经落盘，也不按工具名特判。
        val interaction = pending.firstOrNull { it.kind == ToolInteractionKind.USER_INPUT }
            ?: error("AwaitingApproval carried no user-input interaction for run $runId")
        val toolOrdinal = interaction.locator.toolOrdinal
        val tool = message.getTools().getOrNull(toolOrdinal)
            ?: error("pending user-input locator $toolOrdinal is outside the owning assistant message")
        val interactionId = "${runId}_${message.id}_$toolOrdinal"
        val answer = runGate.registerPendingInteraction(runId, interactionId)

        val userInteraction = net.weero.measix.pilot.data.ai.subassistant.SubAssistantUserInteraction(
            interactionId = interactionId,
            messageId = message.id.toString(),
            toolOrdinal = toolOrdinal,
            toolName = tool.toolName,
            input = tool.input,
        )
        val waitingMetadata = runState.awaitUserInteraction(
            interaction = userInteraction,
            preview = computeSubAssistantPreview(messages, childTaskNodeId).ifEmpty { null },
        )
        reportSubAssistantMetadataPatch(json, execContext, waitingMetadata, delivery = ToolMetadataDelivery.CHECKPOINT)
        val owner = requireNotNull(runtime.snapshot.value.activeTurn) {
            "ask_user wait has no active turn owner"
        }
        val handle = TurnHandle(runtime.id, owner.epoch, owner.turnId, owner.assistantMessageId)
        runtime.retainAwaitingApproval(handle)

        return try {
            val answered = answer.await()
            // 应答 = ResolveToolInteraction(Answer) 命令（与 Master HITL 同一命令路径）
            val before = runtime.snapshot.value
            commandCoordinator.executeOrThrow(
                runtime.id,
                ResolveToolInteraction(
                    messageId = message.id,
                    toolOrdinal = toolOrdinal,
                    decision = ToolUserDecision.Answer(answered),
                    handle = before.activeTurn.let { owner ->
                        requireNotNull(owner) { "user interaction continuation has no active turn owner" }
                        TurnHandle(
                            conversationId = runtime.id,
                            epoch = owner.epoch,
                            turnId = owner.turnId,
                            assistantMessageId = owner.assistantMessageId,
                        )
                    },
                )
            )
            if (runtime.snapshot.value === before) {
                error("Pending ask_user locator is no longer valid")
            }
            reportSubAssistantMetadataPatch(
                json = json,
                execContext = execContext,
                meta = runState.clearUserInteraction(),
                delivery = ToolMetadataDelivery.CHECKPOINT,
            )
            runtime.markRunning(handle)
            runtime.snapshot.value.currentMessages()
        } finally {
            runGate.unregisterPendingInteraction(runId)
        }
    }

    private fun childRunMessages(childConversationId: Uuid): List<UIMessage> {
        return runtimeRegistry.findRuntime(childConversationId)?.snapshot?.value?.currentMessages()
            ?: emptyList()
    }
}

internal fun targetMcpPreparationFailure(
    snapshot: TurnMcpCapabilitySnapshot,
): String? = snapshot.serverOutcomes
    .filter { outcome -> outcome.state != McpServerCapabilityState.READY }
    .takeIf { it.isNotEmpty() }
    ?.joinToString(
        prefix = "Target MCP capability preparation failed: ",
        separator = ", ",
    ) { outcome -> "${outcome.serverName}=${outcome.state}" }
