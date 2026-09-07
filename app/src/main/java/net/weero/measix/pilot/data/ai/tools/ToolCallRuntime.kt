package net.weero.measix.pilot.data.ai.tools

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.ToolArgumentsException
import me.rerere.ai.core.ToolAttachmentResolution
import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.core.ToolExecutionContext
import me.rerere.ai.core.ToolExecutionFailure
import me.rerere.ai.core.ToolInteractionRequirement
import me.rerere.ai.core.ToolMetadataDelivery
import me.rerere.ai.core.ToolOutputPolicy
import me.rerere.ai.core.ToolResourceLease
import me.rerere.ai.ui.ToolInteractionState
import me.rerere.ai.ui.ToolResultStatus
import me.rerere.ai.ui.ToolRuntimeState
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.ToolResultFact
import kotlin.uuid.Uuid

/** Tool Runtime 的稳定 Logcat tag。 */
private const val TAG = "ToolCallRuntime"

/** Which user interactions the current run can actually pause for. */
enum class TurnInteractionCapability {
    /** Master session: approval and user input can both suspend for the user. */
    FULL,

    /** Target run：只允许 typed UserInput 暂停；typed Approval 自动拒绝。 */
    USER_INPUT_ONLY,

    /** 完全无人值守：任何交互都不能暂停运行。 */
    NONE,
}

/**
 * 一个已经挂起的用户交互及其稳定 locator。
 *
 * 由 [ToolCallRuntime.prepareBatch] 在判定挂起的那一刻产出：那时手里同时有 locator 与交互状态，
 * 不需要任何下游消费者回消息里扫描重建。[interaction] 只会是 [ToolInteractionState.AwaitingApproval]
 * 或 [ToolInteractionState.AwaitingInput]。
 */
@Serializable
data class PendingToolInteraction(
    val locator: ToolCallLocator,
    val interaction: ToolInteractionState,
) {
    val requiresApproval: Boolean get() = interaction is ToolInteractionState.AwaitingApproval
    val requiresUserInput: Boolean get() = interaction is ToolInteractionState.AwaitingInput
}

/** A Provider tool call together with its transient position inside the owning Assistant message. */
internal data class LocatedToolCall(
    val ordinal: Int,
    val tool: UIMessagePart.Tool,
)

internal enum class ToolGateRejection {
    APPROVAL_UNAVAILABLE,
    INPUT_UNAVAILABLE,
    INTERACTION_STATE_INVALID,
}

/** One call inside a prepared batch: either resolved without execution or ready to execute. */
internal sealed interface ResolvedToolCall {
    val ordinal: Int

    /** The user denied; the result is projected without any execution fact. */
    data class Denied(
        override val ordinal: Int,
        val result: UIMessagePart.Tool,
    ) : ResolvedToolCall

    /** The user answered a user-input call; the answer text is the replay result. */
    data class Answered(
        override val ordinal: Int,
        val result: UIMessagePart.Tool,
    ) : ResolvedToolCall

    /** Validated and gated; execution may commit side effects after STARTED. */
    data class Executable(
        override val ordinal: Int,
        val call: PreparedToolCall,
    ) : ResolvedToolCall
}

/**
 * A tool call whose arguments were parsed and validated exactly once. Approval and execution
 * share this same immutable JsonObject; recovery re-prepares from durable input at most once.
 */
internal data class PreparedToolCall(
    val locator: ToolCallLocator,
    val executionId: String,
    val source: UIMessagePart.Tool,
    val definition: ToolExecutionBinding,
    val arguments: JsonObject,
    val interaction: ToolInteractionRequirement,
    val approvedByUser: Boolean,
)

internal data class ToolBatchPreparation(
    /** Updated Tool parts (pending state, rejections with replay results) keyed by stable localCallId. */
    val replacements: Map<Uuid, UIMessagePart.Tool>,
    /** Rejections that already carry a Provider replay result and need a FAILED fact. */
    val immediateResults: List<ToolResultFact>,
    /** 已挂起的交互，按消息内顺序；非空表示整批等待，所有调用决策完成前不执行任何可执行调用。 */
    val pending: List<PendingToolInteraction>,
    /** Remaining unresolved calls in message order. */
    val resolvedCalls: List<ResolvedToolCall>,
)

/**
 * Capabilities the generation owner hands to one execution. The Runtime assembles them into a
 * [ToolExecutionContext] but never obtains Conversation, Room or presentation write access.
 */
internal data class ToolExecutionHooks(
    val resolveAttachments: suspend (List<String>) -> ToolAttachmentResolution,
    val reportMetadata: suspend (JsonObject, ToolMetadataDelivery) -> Unit,
    val reportChildConversation: suspend (String) -> Unit,
    val registerUnpublishedResource: (ToolResourceLease) -> Unit,
)

internal data class ToolCallOutcome(
    val output: List<UIMessagePart>,
    val executionFailed: Boolean,
    /** Typed terminal fact merged into the Tool part before the result checkpoint. */
    val resultStatus: ToolResultStatus,
    /** Typed output policy resolved once at completion; drives rolling-compaction eligibility. */
    val outputPolicy: ToolOutputPolicy,
)

/** Unexpected failure of a Runtime-owned capability handed to a Tool; never a Tool result. */
internal class ToolRuntimeInfrastructureException(cause: Throwable) : RuntimeException(cause)

/**
 * The single interpretation boundary for one Provider tool-call batch: definition lookup,
 * argument parsing/validation, interaction gating, execution wrapping and generic result
 * normalization. Durable commits stay with the generation/checkpoint owner.
 */
internal class ToolCallRuntime(
    private val json: Json,
) {
    fun prepareBatch(
        messageId: Uuid,
        calls: List<LocatedToolCall>,
        toolIndex: Map<String, ToolExecutionBinding>,
        availability: TurnInteractionCapability,
    ): ToolBatchPreparation {
        val replacements = LinkedHashMap<Uuid, UIMessagePart.Tool>()
        val immediateResults = mutableListOf<ToolResultFact>()
        val pending = mutableListOf<PendingToolInteraction>()
        val resolvedCalls = mutableListOf<ResolvedToolCall>()

        for (call in calls) {
            val tool = call.tool
            val definition = toolIndex[tool.toolName]
            val locator = ToolCallLocator(messageId, tool.stepId, tool.localCallId)
            val capturedRequirement = tool.interactionState.capturedRequirement()

            fun reject(output: List<UIMessagePart>, wasPending: Boolean) {
                replacements[tool.localCallId] = tool.copy(
                    output = output,
                    interactionState = if (wasPending) ToolInteractionState.NotRequired else tool.interactionState,
                    resultStatus = ToolResultStatus.FAILED,
                    runtimeState = ToolRuntimeState(definition?.outputPolicy ?: ToolOutputPolicy.PRESERVE),
                )
                immediateResults += ToolResultFact(locator = locator, status = ToolResultStatus.FAILED)
            }

            fun resolveDenied() {
                val reason = (tool.interactionState as ToolInteractionState.Denied).reason
                resolvedCalls += ResolvedToolCall.Denied(
                    ordinal = call.ordinal,
                    result = tool.copy(
                        output = listOf(
                            UIMessagePart.Text(
                                json.encodeToString(
                                    buildJsonObject {
                                        put(
                                            "error",
                                            JsonPrimitive(
                                                "Tool execution denied by user. Reason: " +
                                                    reason.ifBlank { "No reason provided" },
                                            ),
                                        )
                                    },
                                ),
                            ),
                        ),
                        resultStatus = ToolResultStatus.DENIED,
                        runtimeState = ToolRuntimeState(definition?.outputPolicy ?: tool.runtimeState.outputPolicy),
                    ),
                )
            }

            fun resolveAnswered() {
                val answer = (tool.interactionState as ToolInteractionState.Answered).answer
                resolvedCalls += ResolvedToolCall.Answered(
                    ordinal = call.ordinal,
                    result = tool.copy(
                        output = listOf(UIMessagePart.Text(answer)),
                        resultStatus = ToolResultStatus.ANSWERED,
                        runtimeState = ToolRuntimeState(definition?.outputPolicy ?: tool.runtimeState.outputPolicy),
                    ),
                )
            }

            // 已提交的拒绝/回答是可回放事实。typed interactionState 足以证明类型时，
            // 不再依赖当前工具定义；只有状态与类型不符时才失败关闭。
            when (tool.interactionState) {
                is ToolInteractionState.Denied -> if (capturedRequirement != null) {
                    if (capturedRequirement == ToolInteractionRequirement.Approval) resolveDenied()
                    else reject(rejectionEnvelope(ToolGateRejection.INTERACTION_STATE_INVALID, tool.toolName), wasPending = false)
                    continue
                }

                is ToolInteractionState.Answered -> if (capturedRequirement != null) {
                    if (capturedRequirement == ToolInteractionRequirement.UserInput) resolveAnswered()
                    else reject(rejectionEnvelope(ToolGateRejection.INTERACTION_STATE_INVALID, tool.toolName), wasPending = false)
                    continue
                }

                else -> Unit
            }

            if (definition == null) {
                reject(
                    output = listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("error", "tool_not_available")
                                put("type", "error")
                                put("status", "failed")
                                put("reason", "tool_not_available")
                                put("tool", tool.toolName)
                                put("message", "This tool is not available in the current run. Do not retry unchanged.")
                            }.toString(),
                        ),
                    ),
                    wasPending = tool.isPending,
                )
                continue
            }

            val args = try {
                definition.parseArguments(tool.input, json)
            } catch (rejection: ToolArgumentsException) {
                reject(rejection.output, wasPending = tool.isPending)
                continue
            }

            val requirement = definition.interactionRequirement(args)
            val pendingRequirement = requirement.takeIf { it != ToolInteractionRequirement.None }
            val currentRequirement = pendingRequirement ?: ToolInteractionRequirement.None

            when (tool.interactionState) {
                ToolInteractionState.AwaitingApproval,
                ToolInteractionState.AwaitingInput,
                -> {
                    if (pendingRequirement == null ||
                        capturedRequirement != null && capturedRequirement != pendingRequirement
                    ) {
                        reject(rejectionEnvelope(ToolGateRejection.INTERACTION_STATE_INVALID, tool.toolName), wasPending = true)
                    } else {
                        pending += PendingToolInteraction(locator = locator, interaction = tool.interactionState)
                    }
                }

                ToolInteractionState.NotRequired -> when {
                    capturedRequirement != null && capturedRequirement != currentRequirement -> reject(
                        rejectionEnvelope(ToolGateRejection.INTERACTION_STATE_INVALID, tool.toolName),
                        wasPending = false,
                    )

                    requirement == ToolInteractionRequirement.None -> resolvedCalls += executable(
                        messageId, call, tool, definition, args, requirement,
                        approvedByUser = false,
                    )

                    availability.allows(requirement) -> {
                        val awaiting = when (requirement) {
                            ToolInteractionRequirement.Approval -> ToolInteractionState.AwaitingApproval
                            ToolInteractionRequirement.UserInput -> ToolInteractionState.AwaitingInput
                            else -> error("requirement already handled above")
                        }
                        pending += PendingToolInteraction(locator = locator, interaction = awaiting)
                        replacements[tool.localCallId] = tool.copy(
                            interactionState = awaiting,
                            runtimeState = ToolRuntimeState(definition.outputPolicy),
                        )
                    }

                    else -> reject(
                        rejectionEnvelope(
                            if (requirement == ToolInteractionRequirement.Approval) {
                                ToolGateRejection.APPROVAL_UNAVAILABLE
                            } else {
                                ToolGateRejection.INPUT_UNAVAILABLE
                            },
                            tool.toolName,
                        ),
                        wasPending = false,
                    )
                }

                ToolInteractionState.Approved -> {
                    if (currentRequirement != ToolInteractionRequirement.Approval ||
                        capturedRequirement != null && capturedRequirement != ToolInteractionRequirement.Approval
                    ) {
                        reject(rejectionEnvelope(ToolGateRejection.INTERACTION_STATE_INVALID, tool.toolName), wasPending = false)
                    } else {
                        resolvedCalls += executable(
                            messageId, call, tool, definition, args, requirement,
                            approvedByUser = true,
                        )
                    }
                }

                is ToolInteractionState.Denied, is ToolInteractionState.Answered -> Unit
            }
        }

        // 首个 STARTED checkpoint 必须一次固化整批所有可执行调用的输出策略；否则进程在批内中断后，
        // 剩余调用会用不同的策略重新分批，破坏 recent-batch 保护与归档确定性。
        resolvedCalls.filterIsInstance<ResolvedToolCall.Executable>().forEach { resolved ->
            val prepared = resolved.call
            val preparedSource = prepared.source.copy(
                runtimeState = ToolRuntimeState(prepared.definition.outputPolicy),
            )
            if (preparedSource != prepared.source) {
                replacements.putIfAbsent(preparedSource.localCallId, preparedSource)
            }
        }

        return ToolBatchPreparation(
            replacements = replacements,
            immediateResults = immediateResults,
            pending = pending,
            resolvedCalls = resolvedCalls,
        )
    }

    private fun executable(
        messageId: Uuid,
        call: LocatedToolCall,
        tool: UIMessagePart.Tool,
        definition: ToolExecutionBinding,
        args: JsonObject,
        requirement: ToolInteractionRequirement,
        approvedByUser: Boolean,
    ): ResolvedToolCall.Executable {
        val executionId = "tool:${tool.localCallId}"
        return ResolvedToolCall.Executable(
            ordinal = call.ordinal,
            call = PreparedToolCall(
                locator = ToolCallLocator(messageId, tool.stepId, tool.localCallId),
                executionId = executionId,
                source = tool,
                definition = definition,
                arguments = args,
                interaction = requirement,
                approvedByUser = approvedByUser,
            ),
        )
    }

    /**
     * 执行一个已完成解析与 gate 的调用：只从窄 hooks 构造上下文，并统一规范化通用结果。
     * Tool 领域失败保留原输出，Runtime 基础设施失败和取消始终向外传播。
     */
    suspend fun execute(
        call: PreparedToolCall,
        hooks: ToolExecutionHooks,
    ): ToolCallOutcome {
        var registeredArtifact = false
        val context = ToolExecutionContext(
            locator = call.locator,
            providerCallId = call.source.providerCallId,
            reportMetadata = { patch, delivery ->
                runtimeCapability { hooks.reportMetadata(patch, delivery) }
            },
            resolveAttachments = { paths ->
                runtimeCapability { hooks.resolveAttachments(paths) }
            },
            reportChildConversation = { childConversationId ->
                runtimeCapability { hooks.reportChildConversation(childConversationId) }
            },
            registerUnpublishedResource = { resource ->
                try {
                    hooks.registerUnpublishedResource(resource)
                    registeredArtifact = true
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    throw ToolRuntimeInfrastructureException(error)
                }
            },
            approvedByUser = call.approvedByUser,
        )
        return try {
            val output = ensureProviderReplayResult(
                call.definition.execute(context, call.arguments),
                emptyStatus = EmptyToolResultStatus.COMPLETED,
            )
            ToolCallOutcome(
                output = output,
                executionFailed = false,
                resultStatus = ToolResultStatus.COMPLETED,
                outputPolicy = if (registeredArtifact) {
                    ToolOutputPolicy.PRESERVE
                } else {
                    resolveSuccessfulOutputPolicy(call, output)
                },
            )
        } catch (failure: ToolExecutionFailure) {
            ToolCallOutcome(
                output = ensureProviderReplayResult(
                    failure.output,
                    emptyStatus = EmptyToolResultStatus.FAILED,
                ),
                executionFailed = true,
                resultStatus = ToolResultStatus.FAILED,
                outputPolicy = artifactSafeOutputPolicy(call, registeredArtifact),
            )
        } catch (timeout: TimeoutCancellationException) {
            // 外层 Turn/collector 超时属于取消，只有工具内部子超时且父协程仍 active 才归一化为工具失败。
            currentCoroutineContext().ensureActive()
            Log.w(TAG, "Tool ${call.source.toolName} timed out: ${timeout.message}")
            ToolCallOutcome(
                output = listOf(
                    UIMessagePart.Text(
                        "{\"status\":\"failed\",\"reason\":\"tool_timeout\"}",
                    ),
                ),
                executionFailed = true,
                resultStatus = ToolResultStatus.FAILED,
                outputPolicy = artifactSafeOutputPolicy(call, registeredArtifact),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (infrastructure: ToolRuntimeInfrastructureException) {
            throw infrastructure
        } catch (error: Exception) {
            Log.w(TAG, "Tool ${call.source.toolName} failed: ${error.message}", error)
            ToolCallOutcome(
                output = listOf(
                    UIMessagePart.Text(
                        // 完整异常只进 Logcat；Provider replay 只保留稳定、短小且不泄漏路径的 reason。
                        "{\"status\":\"failed\",\"reason\":\"tool_failed\"}",
                    ),
                ),
                executionFailed = true,
                resultStatus = ToolResultStatus.FAILED,
                outputPolicy = artifactSafeOutputPolicy(call, registeredArtifact),
            )
        }
    }

    /** 任何已登记产物都要求完整保留结果；成功或失败不能把交付引用变成可归档文本。 */
    private fun artifactSafeOutputPolicy(
        call: PreparedToolCall,
        registeredArtifact: Boolean,
    ): ToolOutputPolicy = if (registeredArtifact) ToolOutputPolicy.PRESERVE else call.definition.outputPolicy

    /** 结果策略失败不能改写已经成功取得的工具事实；非取消异常只退回静态 fail-closed 策略。 */
    private fun resolveSuccessfulOutputPolicy(
        call: PreparedToolCall,
        output: List<UIMessagePart>,
    ): ToolOutputPolicy = try {
        call.definition.successfulOutputPolicy(output)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.w(TAG, "Tool ${call.source.toolName} output policy failed; preserving result", error)
        ToolOutputPolicy.PRESERVE
    }

    private suspend fun <T> runtimeCapability(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        throw ToolRuntimeInfrastructureException(error)
    }
}

internal enum class EmptyToolResultStatus {
    COMPLETED,
    FAILED,
}

/** Empty tool output is valid, but every Provider needs a non-empty serializable result envelope. */
internal fun ensureProviderReplayResult(
    output: List<UIMessagePart>,
    emptyStatus: EmptyToolResultStatus,
): List<UIMessagePart> = output.ifEmpty {
    val fallback = when (emptyStatus) {
        EmptyToolResultStatus.COMPLETED -> "{\"status\":\"completed\",\"result\":null}"
        EmptyToolResultStatus.FAILED -> "{\"status\":\"failed\",\"reason\":\"tool_failed_without_output\"}"
    }
    listOf(UIMessagePart.Text(fallback))
}

private fun TurnInteractionCapability.allows(requirement: ToolInteractionRequirement): Boolean = when {
    requirement == ToolInteractionRequirement.None -> true
    this == TurnInteractionCapability.FULL -> true
    requirement == ToolInteractionRequirement.UserInput &&
        this == TurnInteractionCapability.USER_INPUT_ONLY -> true
    else -> false
}

/**
 * The interaction kind already recorded on a Tool part, derived from its typed [ToolInteractionState].
 * `NotRequired` records no interaction and yields null, matching the "no captured interaction" case.
 */
private fun ToolInteractionState.capturedRequirement(): ToolInteractionRequirement? = when (this) {
    ToolInteractionState.AwaitingApproval,
    ToolInteractionState.Approved,
    is ToolInteractionState.Denied,
    -> ToolInteractionRequirement.Approval

    ToolInteractionState.AwaitingInput,
    is ToolInteractionState.Answered,
    -> ToolInteractionRequirement.UserInput

    ToolInteractionState.NotRequired -> null
}

private fun rejectionEnvelope(rejection: ToolGateRejection, toolName: String): List<UIMessagePart> {
    val (reason, message) = when (rejection) {
        ToolGateRejection.APPROVAL_UNAVAILABLE -> "approval_unavailable" to
            "Approval is required but unavailable in this run. Do not retry unchanged."
        ToolGateRejection.INPUT_UNAVAILABLE -> "input_unavailable" to
            "User input is required but unavailable in this run. Do not retry unchanged."
        ToolGateRejection.INTERACTION_STATE_INVALID -> "interaction_state_invalid" to
            "The stored user-interaction state does not match this tool. Do not retry unchanged."
    }
    return listOf(
        UIMessagePart.Text(
            buildJsonObject {
                put("error", "tool_not_permitted")
                put("type", "error")
                put("status", "failed")
                put("reason", reason)
                put("tool", toolName)
                put("message", message)
            }.toString(),
        ),
    )
}
