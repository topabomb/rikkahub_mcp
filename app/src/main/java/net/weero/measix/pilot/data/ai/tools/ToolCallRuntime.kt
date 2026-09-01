package net.weero.measix.pilot.data.ai.tools

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolArgumentsException
import me.rerere.ai.core.ToolAttachmentResolution
import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.core.ToolExecutionContext
import me.rerere.ai.core.ToolExecutionFailure
import me.rerere.ai.core.ToolInteractionRequirement
import me.rerere.ai.core.ToolMetadataDelivery
import me.rerere.ai.core.ToolOutputPolicy
import me.rerere.ai.core.ToolResourceLease
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import net.weero.measix.pilot.data.ai.ToolResultEvent
import net.weero.measix.pilot.data.ai.ToolResultEventStatus
import kotlin.uuid.Uuid

/** Tool Runtime 的稳定 Logcat tag。 */
private const val TAG = "ToolCallRuntime"

/** Which user interactions the current run can actually pause for. */
enum class ToolInteractionAvailability {
    /** Master session: approval and user input can both suspend for the user. */
    FULL,

    /** Target run：只允许 typed UserInput 暂停；typed Approval 自动拒绝。 */
    USER_INPUT_ONLY,

    /** 完全无人值守：任何交互都不能暂停运行。 */
    NONE,
}

/** Stable interaction taxonomy shared by the gate, presentation and the decision command. */
enum class ToolInteractionKind {
    NONE,
    APPROVAL,
    USER_INPUT,
}

/** A Provider tool call together with its position inside the owning Assistant message. */
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
    val definition: Tool,
    val arguments: JsonObject,
    val interaction: ToolInteractionRequirement,
    val approvedByUser: Boolean,
    /** 同一 Provider tool-call 批次共享其首个 Tool ordinal，作为稳定且无需第二计数器的批次身份。 */
    val resultBatchOrdinal: Int,
)

internal data class ToolBatchPreparation(
    /** Updated Tool parts (Pending state, rejections with replay results) keyed by ordinal. */
    val replacements: Map<Int, UIMessagePart.Tool>,
    /** Rejections that already carry a Provider replay result and need a FAILED fact. */
    val immediateResults: List<ToolResultEvent>,
    /** Non-empty means the whole batch waits; no executable call runs before all are decided. */
    val pendingInteractions: Set<ToolInteractionKind>,
    /** Remaining unresolved calls in message ordinal order. */
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
    /** Runtime-owned metadata merged into the Tool part before the result checkpoint. */
    val runtimeMetadata: JsonObject,
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
    /** 构造确定性的 step 工具索引；请求前拒绝空名和重复名。 */
    fun buildIndex(tools: List<Tool>): Map<String, Tool> {
        val map = LinkedHashMap<String, Tool>(tools.size)
        for (tool in tools) {
            require(tool.name.isNotBlank()) { "Tool name must not be blank" }
            require(tool.name !in map) { "Duplicate tool name: ${tool.name}" }
            map[tool.name] = tool
        }
        return map.toMap()
    }

    fun prepareBatch(
        messageId: Uuid,
        calls: List<LocatedToolCall>,
        toolIndex: Map<String, Tool>,
        availability: ToolInteractionAvailability,
    ): ToolBatchPreparation {
        val replacements = LinkedHashMap<Int, UIMessagePart.Tool>()
        val immediateResults = mutableListOf<ToolResultEvent>()
        val pendingInteractions = linkedSetOf<ToolInteractionKind>()
        val resolvedCalls = mutableListOf<ResolvedToolCall>()
        val currentBatchOrdinal = calls.minOfOrNull(LocatedToolCall::ordinal) ?: 0

        for (call in calls) {
            val tool = call.tool
            val definition = toolIndex[tool.toolName]
            val capturedKind = ToolRuntimeMetadata.interactionKindOf(tool.metadata)
            val resultBatchOrdinal = ToolRuntimeMetadata.resultBatchOrdinalOf(tool.metadata)
                ?: currentBatchOrdinal

            fun terminalMetadata(
                interaction: ToolInteractionKind,
                terminalStatus: String,
            ): JsonObject = ToolRuntimeMetadata.applyTo(
                tool.metadata,
                ToolRuntimeMetadata.forResult(
                    interaction = interaction,
                    outputPolicy = definition?.outputPolicy?.name
                        ?: ToolRuntimeMetadata.outputPolicyOf(tool.metadata)
                        ?: ToolOutputPolicy.PRESERVE.name,
                    terminalStatus = terminalStatus,
                    resultBatchOrdinal = resultBatchOrdinal,
                ),
            )

            fun reject(output: List<UIMessagePart>, wasPending: Boolean) {
                replacements[call.ordinal] = tool.copy(
                    output = output,
                    approvalState = if (wasPending) ToolApprovalState.Auto else tool.approvalState,
                    metadata = terminalMetadata(
                        interaction = ToolRuntimeMetadata.interactionKindOf(tool.metadata)
                            ?: ToolInteractionKind.NONE,
                        terminalStatus = "failed",
                    ),
                )
                immediateResults += ToolResultEvent(
                    messageId = messageId,
                    toolOrdinal = call.ordinal,
                    status = ToolResultEventStatus.FAILED,
                )
            }

            fun resolveDenied() {
                val reason = (tool.approvalState as ToolApprovalState.Denied).reason
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
                        metadata = terminalMetadata(ToolInteractionKind.APPROVAL, "denied"),
                    ),
                )
            }

            fun resolveAnswered() {
                val answer = (tool.approvalState as ToolApprovalState.Answered).answer
                resolvedCalls += ResolvedToolCall.Answered(
                    ordinal = call.ordinal,
                    result = tool.copy(
                        output = listOf(UIMessagePart.Text(answer)),
                        metadata = terminalMetadata(ToolInteractionKind.USER_INPUT, "answered"),
                    ),
                )
            }

            if (ToolRuntimeMetadata.isInvalid(tool.metadata)) {
                reject(
                    rejectionEnvelope(ToolGateRejection.INTERACTION_STATE_INVALID, tool.toolName),
                    wasPending = tool.approvalState is ToolApprovalState.Pending,
                )
                continue
            }

            // 已提交的拒绝/回答是可回放事实。metadata 足以证明类型时，不再依赖当前工具定义；
            // 老消息缺 metadata 时才继续用当前 definition 与参数严格重建。
            when (tool.approvalState) {
                is ToolApprovalState.Denied -> if (capturedKind != null) {
                    if (capturedKind == ToolInteractionKind.APPROVAL) resolveDenied()
                    else reject(
                        rejectionEnvelope(ToolGateRejection.INTERACTION_STATE_INVALID, tool.toolName),
                        wasPending = false,
                    )
                    continue
                }

                is ToolApprovalState.Answered -> if (capturedKind != null) {
                    if (capturedKind == ToolInteractionKind.USER_INPUT) resolveAnswered()
                    else reject(
                        rejectionEnvelope(ToolGateRejection.INTERACTION_STATE_INVALID, tool.toolName),
                        wasPending = false,
                    )
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
                    wasPending = tool.approvalState is ToolApprovalState.Pending,
                )
                continue
            }

            val args = try {
                definition.parseArguments(tool.input, json)
            } catch (rejection: ToolArgumentsException) {
                reject(rejection.output, wasPending = tool.approvalState is ToolApprovalState.Pending)
                continue
            }

            val requirement = definition.interactionRequirement(args)
            val pendingKind = requirement.pendingKindOrNull()
            val currentKind = pendingKind ?: ToolInteractionKind.NONE

            when (tool.approvalState) {
                is ToolApprovalState.Denied -> {
                    if (currentKind != ToolInteractionKind.APPROVAL) {
                        reject(
                            rejectionEnvelope(ToolGateRejection.INTERACTION_STATE_INVALID, tool.toolName),
                            wasPending = false,
                        )
                    } else {
                        resolveDenied()
                    }
                }

                is ToolApprovalState.Answered -> {
                    if (currentKind != ToolInteractionKind.USER_INPUT) {
                        reject(
                            rejectionEnvelope(ToolGateRejection.INTERACTION_STATE_INVALID, tool.toolName),
                            wasPending = false,
                        )
                    } else {
                        resolveAnswered()
                    }
                }

                ToolApprovalState.Pending -> {
                    if (currentKind == ToolInteractionKind.NONE ||
                        capturedKind != null && capturedKind != currentKind
                    ) {
                        reject(
                            rejectionEnvelope(ToolGateRejection.INTERACTION_STATE_INVALID, tool.toolName),
                            wasPending = true,
                        )
                    } else {
                        pendingInteractions += currentKind
                    }
                }

                ToolApprovalState.Auto -> when {
                    capturedKind != null && capturedKind != currentKind -> reject(
                        rejectionEnvelope(ToolGateRejection.INTERACTION_STATE_INVALID, tool.toolName),
                        wasPending = false,
                    )

                    requirement == ToolInteractionRequirement.None -> resolvedCalls += executable(
                        messageId, call, tool, definition, args, requirement,
                        approvedByUser = false,
                        resultBatchOrdinal = resultBatchOrdinal,
                    )

                    availability.allows(requirement) -> {
                        val kind = requireNotNull(pendingKind)
                        pendingInteractions += kind
                        replacements[call.ordinal] = tool.copy(
                            approvalState = ToolApprovalState.Pending,
                            metadata = ToolRuntimeMetadata.withInteraction(
                                metadata = tool.metadata,
                                interaction = kind,
                                outputPolicy = definition.outputPolicy.name,
                                resultBatchOrdinal = resultBatchOrdinal,
                            ),
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

                ToolApprovalState.Approved -> {
                    if (currentKind != ToolInteractionKind.APPROVAL ||
                        capturedKind != null && capturedKind != ToolInteractionKind.APPROVAL
                    ) {
                        reject(
                            rejectionEnvelope(ToolGateRejection.INTERACTION_STATE_INVALID, tool.toolName),
                            wasPending = false,
                        )
                    } else {
                        resolvedCalls += executable(
                            messageId, call, tool, definition, args, requirement,
                            approvedByUser = true,
                            resultBatchOrdinal = resultBatchOrdinal,
                        )
                    }
                }

            }
        }

        // 首个 STARTED checkpoint 必须一次固化整批所有可执行调用的批次身份；否则进程在批内中断后，
        // 剩余调用会用新的最小 ordinal 重新分批，破坏 recent-batch 保护与归档确定性。
        resolvedCalls.filterIsInstance<ResolvedToolCall.Executable>().forEach { resolved ->
            val prepared = resolved.call
            val preparedSource = prepared.source.copy(
                metadata = ToolRuntimeMetadata.withInteraction(
                    metadata = prepared.source.metadata,
                    interaction = prepared.interaction.toKind(),
                    outputPolicy = prepared.definition.outputPolicy.name,
                    resultBatchOrdinal = prepared.resultBatchOrdinal,
                ),
            )
            if (preparedSource != prepared.source) {
                replacements.putIfAbsent(resolved.ordinal, preparedSource)
            }
        }

        return ToolBatchPreparation(
            replacements = replacements,
            immediateResults = immediateResults,
            pendingInteractions = pendingInteractions,
            resolvedCalls = resolvedCalls,
        )
    }

    private fun executable(
        messageId: Uuid,
        call: LocatedToolCall,
        tool: UIMessagePart.Tool,
        definition: Tool,
        args: JsonObject,
        requirement: ToolInteractionRequirement,
        approvedByUser: Boolean,
        resultBatchOrdinal: Int,
    ): ResolvedToolCall.Executable {
        val executionId = "${messageId}_${call.ordinal}".replace(Regex("[^A-Za-z0-9_-]"), "_")
        return ResolvedToolCall.Executable(
            ordinal = call.ordinal,
            call = PreparedToolCall(
                locator = ToolCallLocator(messageId, call.ordinal),
                executionId = executionId,
                source = tool,
                definition = definition,
                arguments = args,
                interaction = requirement,
                approvedByUser = approvedByUser,
                resultBatchOrdinal = resultBatchOrdinal,
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
            messageId = call.locator.messageId,
            toolOrdinal = call.locator.toolOrdinal,
            toolCallId = call.source.toolCallId,
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
                call.definition.executeWithContext(context, call.arguments),
                emptyStatus = EmptyToolResultStatus.COMPLETED,
            )
            ToolCallOutcome(
                output = output,
                executionFailed = false,
                runtimeMetadata = runtimeMetadata(
                    call = call,
                    terminalStatus = "completed",
                    outputPolicy = if (registeredArtifact) {
                        ToolOutputPolicy.PRESERVE
                    } else {
                        resolveSuccessfulOutputPolicy(call, output)
                    },
                ),
            )
        } catch (failure: ToolExecutionFailure) {
            ToolCallOutcome(
                output = ensureProviderReplayResult(
                    failure.output,
                    emptyStatus = EmptyToolResultStatus.FAILED,
                ),
                executionFailed = true,
                runtimeMetadata = runtimeMetadata(
                    call,
                    "failed",
                    outputPolicy = artifactSafeOutputPolicy(call, registeredArtifact),
                ),
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
                runtimeMetadata = runtimeMetadata(
                    call,
                    "failed",
                    outputPolicy = artifactSafeOutputPolicy(call, registeredArtifact),
                ),
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
                runtimeMetadata = runtimeMetadata(
                    call,
                    "failed",
                    outputPolicy = artifactSafeOutputPolicy(call, registeredArtifact),
                ),
            )
        }
    }

    private fun runtimeMetadata(
        call: PreparedToolCall,
        terminalStatus: String?,
        outputPolicy: ToolOutputPolicy = call.definition.outputPolicy,
    ): JsonObject =
        ToolRuntimeMetadata.forResult(
            interaction = call.interaction.toKind(),
            outputPolicy = outputPolicy.name,
            terminalStatus = terminalStatus,
            resultBatchOrdinal = call.resultBatchOrdinal,
        )

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

private fun ToolInteractionAvailability.allows(requirement: ToolInteractionRequirement): Boolean = when {
    requirement == ToolInteractionRequirement.None -> true
    this == ToolInteractionAvailability.FULL -> true
    requirement == ToolInteractionRequirement.UserInput &&
        this == ToolInteractionAvailability.USER_INPUT_ONLY -> true
    else -> false
}

private fun ToolInteractionRequirement.pendingKindOrNull(): ToolInteractionKind? = when (this) {
    ToolInteractionRequirement.Approval -> ToolInteractionKind.APPROVAL
    ToolInteractionRequirement.UserInput -> ToolInteractionKind.USER_INPUT
    ToolInteractionRequirement.None -> null
}

private fun ToolInteractionRequirement.toKind(): ToolInteractionKind =
    pendingKindOrNull() ?: ToolInteractionKind.NONE

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

/**
 * Codec for the Runtime-reserved `tool_runtime` metadata namespace. Only the ToolCallRuntime,
 * the conversation reducer path and presentation projectors may read or write it; tool-owned
 * metadata patches containing the reserved key are rejected before merging.
 */
@Serializable
data class ToolOutputArchiveRef(
    val relativePath: String,
    val mimeType: String,
)

/** Stable archive descriptor embedded in tool_runtime metadata; the only model-visible handle is `ref`. */
@Serializable
data class ToolOutputArchive(
    val ref: Long,
    val artifact: ToolOutputArchiveRef,
    val characters: Long,
    val lines: Int,
)

@Serializable
internal data class ToolRuntimeMetadata(
    val version: Int = 1,
    val interaction: String? = null,
    val outputPolicy: String? = null,
    val terminalStatus: String? = null,
    val resultBatchOrdinal: Int? = null,
    val archive: ToolOutputArchive? = null,
) {
    companion object {
        /** Tool part metadata 中由 Runtime 独占的保留键。 */
        const val METADATA_KEY = "tool_runtime"
        /** 持久化的授权审批交互枚举值。 */
        const val INTERACTION_APPROVAL = "approval"
        /** 持久化的用户输入交互枚举值。 */
        const val INTERACTION_USER_INPUT = "user_input"

        // v1 metadata 是运行时判定依据；未知字段必须失败关闭，避免新旧协议被静默混用。
        private val codec = Json { encodeDefaults = false }

        fun interactionKindOf(metadata: JsonObject?): ToolInteractionKind? =
            read(metadata)?.interaction?.let {
                when (it) {
                    INTERACTION_APPROVAL -> ToolInteractionKind.APPROVAL
                    INTERACTION_USER_INPUT -> ToolInteractionKind.USER_INPUT
                    else -> ToolInteractionKind.NONE
                }
            }

        fun withInteraction(
            metadata: JsonObject?,
            interaction: ToolInteractionKind,
            outputPolicy: String,
            resultBatchOrdinal: Int? = null,
        ): JsonObject = merge(
            metadata,
            ToolRuntimeMetadata(
                interaction = interaction.encode(),
                outputPolicy = outputPolicy,
                resultBatchOrdinal = resultBatchOrdinal,
            ),
        )

        fun completed(interaction: ToolInteractionKind, outputPolicy: String): JsonObject =
            forResult(interaction, outputPolicy, terminalStatus = "completed")

        fun forResult(
            interaction: ToolInteractionKind,
            outputPolicy: String,
            terminalStatus: String?,
            resultBatchOrdinal: Int? = null,
        ): JsonObject = codec.encodeToJsonElement(
            ToolRuntimeMetadata.serializer(),
            ToolRuntimeMetadata(
                interaction = interaction.encode(),
                outputPolicy = outputPolicy,
                terminalStatus = terminalStatus,
                resultBatchOrdinal = resultBatchOrdinal,
            ),
        ).jsonObject()

        /** Places a Runtime-owned metadata object under the reserved key of a Tool part. */
        fun applyTo(metadata: JsonObject?, runtimeMetadata: JsonObject): JsonObject =
            JsonObject((metadata ?: JsonObject(emptyMap())) + (METADATA_KEY to runtimeMetadata))

        fun requireToolOwnedPatch(patch: JsonObject): JsonObject {
            require(METADATA_KEY !in patch) { "Tool metadata patch contains reserved key: $METADATA_KEY" }
            return patch
        }

        /** Attaches the archive handle to an existing tool part metadata; staging is single-shot. */
        fun withArchive(metadata: JsonObject?, archive: ToolOutputArchive): JsonObject = merge(
            metadata,
            ToolRuntimeMetadata(archive = archive),
        )

        fun archiveOf(metadata: JsonObject?): ToolOutputArchive? =
            read(metadata)?.archive?.takeIf { it.ref > 0 && it.artifact.relativePath.isNotBlank() }

        fun terminalStatusOf(metadata: JsonObject?): String? = read(metadata)?.terminalStatus

        fun outputPolicyOf(metadata: JsonObject?): String? = read(metadata)?.outputPolicy

        fun resultBatchOrdinalOf(metadata: JsonObject?): Int? =
            read(metadata)?.resultBatchOrdinal?.takeIf { it >= 0 }

        /** key 存在但不符合当前 v1 schema 时必须 fail-closed，不能伪装成旧消息缺 metadata。 */
        fun isInvalid(metadata: JsonObject?): Boolean =
            metadata?.containsKey(METADATA_KEY) == true && read(metadata) == null

        private fun read(metadata: JsonObject?): ToolRuntimeMetadata? =
            (metadata?.get(METADATA_KEY) as? JsonObject)?.let(::readFromObject)

        private fun readFromObject(value: JsonObject): ToolRuntimeMetadata? = runCatching {
            codec.decodeFromJsonElement(ToolRuntimeMetadata.serializer(), value)
        }.getOrNull()?.takeIf { runtime ->
            runtime.version == 1 &&
                runtime.interaction in setOf(null, INTERACTION_APPROVAL, INTERACTION_USER_INPUT, "none") &&
                runtime.outputPolicy?.let { policy ->
                    ToolOutputPolicy.entries.any { it.name == policy }
                } != false &&
                runtime.terminalStatus in setOf(null, "completed", "failed", "denied", "answered") &&
                runtime.resultBatchOrdinal?.let { it >= 0 } != false &&
                runtime.archive?.let { archive ->
                    val relativePath = archive.artifact.relativePath.replace('\\', '/')
                    archive.ref > 0 &&
                        relativePath == archive.artifact.relativePath &&
                        relativePath.startsWith("tool_outputs/") &&
                        relativePath.split('/').none { it.isBlank() || it == "." || it == ".." } &&
                        archive.artifact.mimeType == "text/plain" &&
                        archive.characters >= 0 &&
                        archive.lines >= 0
                } != false
        }

        private fun merge(metadata: JsonObject?, value: ToolRuntimeMetadata): JsonObject {
            val existing = read(metadata)
            val merged = ToolRuntimeMetadata(
                version = 1,
                interaction = value.interaction ?: existing?.interaction,
                outputPolicy = value.outputPolicy ?: existing?.outputPolicy,
                terminalStatus = value.terminalStatus ?: existing?.terminalStatus,
                resultBatchOrdinal = value.resultBatchOrdinal ?: existing?.resultBatchOrdinal,
                archive = value.archive ?: existing?.archive,
            )
            val encoded = codec.encodeToJsonElement(ToolRuntimeMetadata.serializer(), merged).jsonObject()
            return JsonObject((metadata ?: JsonObject(emptyMap())) + (METADATA_KEY to encoded))
        }

        private fun ToolInteractionKind.encode(): String = when (this) {
            ToolInteractionKind.APPROVAL -> INTERACTION_APPROVAL
            ToolInteractionKind.USER_INPUT -> INTERACTION_USER_INPUT
            ToolInteractionKind.NONE -> "none"
        }
    }
}

private fun JsonElement.jsonObject(): JsonObject = this as JsonObject
