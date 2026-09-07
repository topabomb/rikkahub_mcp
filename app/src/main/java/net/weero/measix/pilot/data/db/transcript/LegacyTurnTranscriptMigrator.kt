package net.weero.measix.pilot.data.db.transcript

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.UsageCompleteness
import me.rerere.ai.core.ToolOutputPolicy
import me.rerere.ai.ui.StepModelResult
import me.rerere.ai.ui.StepUsage
import me.rerere.ai.ui.StepOutcome
import me.rerere.ai.ui.ToolInteractionState
import me.rerere.ai.ui.ToolOutputArchive
import me.rerere.ai.ui.ToolResultStatus
import me.rerere.ai.ui.ToolRuntimeState
import me.rerere.ai.ui.UIMessagePart
import java.security.MessageDigest
import kotlin.uuid.Uuid

/**
 * The single legacy-transcript converter. It is the only place that may read the retired
 * `toolCallId` / `approvalState` / `metadata.tool_runtime` shape; the V3 runtime never decodes it.
 * It is invoked by exactly one upgrade chain — Room `Migration_10_11` and backup restore share it —
 * so a payload is converted once and every later read is V3.
 *
 * The converter is pure and deterministic: legacy identity is derived with RFC 4122 UUID v5 over a
 * fixed namespace so a re-run (idempotent replay after a crash) yields byte-identical ids. New
 * runtime Steps/Calls use random UUIDs and never this formula.
 */
object LegacyTurnTranscriptMigrator {

    /** Contract namespace for deterministic legacy identity. Must match the plan verbatim. */
    const val UUID5_NAMESPACE = "6b1c0e2a-7f3d-5a14-9c8e-2d4f1a0b7e65"

    private const val TOOL_METADATA_RESERVED_KEY = "tool_runtime"
    private const val INTERACTION_USER_INPUT = "user_input"

    /**
     * Convert one `message_node.messages` JSON array to the V3 transcript. [turnStatusByAssistantMessageId]
     * maps an Assistant message id to its *pre-rewrite* `turn_execution.status` string; an absent entry
     * means a historical message with no turn row (treated as already completed). Idempotent: a node
     * already carrying `step` parts is returned unchanged.
     */
    fun convertNode(
        messagesJson: String,
        turnStatusByAssistantMessageId: Map<Uuid, String>,
        json: Json,
    ): String {
        val messages = json.decodeFromString<List<JsonObject>>(messagesJson)
        val converted = messages.map { message -> convertMessage(json, message, turnStatusByAssistantMessageId) }
        val result = json.encodeToString(converted)
        V3TranscriptValidator.validateNode(result, json, turnStatusByAssistantMessageId.filterValues {
            TurnTerminality.of(it, hasTurnRow = true).isNonTerminal
        }.keys.mapTo(mutableSetOf()) { it.toString() })
        return result
    }

    private fun convertMessage(
        json: Json,
        message: JsonObject,
        turnStatusByAssistantMessageId: Map<Uuid, String>,
    ): JsonObject {
        val role = message["role"]?.jsonPrimitive?.contentOrNullSafe() ?: return message
        if (!role.equals("assistant", ignoreCase = true)) return message
        val parts = requireNotNull(message["parts"]?.arrayOrNullSafe()) { "Assistant parts must be an array" }
        val messageId = Uuid.parse(
            requireNotNull(message["id"]?.jsonPrimitive?.contentOrNullSafe()) {
                "Assistant message is missing id during transcript migration"
            },
        )
        val turnStatus = turnStatusByAssistantMessageId[messageId]
        val terminality = TurnTerminality.of(turnStatus, hasTurnRow = turnStatusByAssistantMessageId.containsKey(messageId))

        if (parts.any { it.jsonObject["type"]?.jsonPrimitive?.contentOrNullSafe() == "step" }) {
            return message
        }
        // Ordinals belong to positions: identical calls still have distinct identities.
        val outParts = mutableListOf<JsonElement>()
        var stepOrdinal = 0
        var toolOrdinal = 0
        var lastToolHadResult = false
        var lastBatchOrdinal: Int? = null
        outParts += json.encodeToJsonElement(UIMessagePart.serializer(), stepPart(messageId, 0))
        for (raw in parts) {
            val obj = raw.jsonObject
            when (obj["type"]?.jsonPrimitive?.contentOrNullSafe()) {
                "reasoning", "text", "image", "video", "audio", "document" -> {
                    // Content after a consumed result is a later model response, including its final answer.
                    if (lastToolHadResult) {
                        stepOrdinal++
                        outParts += json.encodeToJsonElement(UIMessagePart.serializer(), stepPart(messageId, stepOrdinal))
                        lastToolHadResult = false
                        lastBatchOrdinal = null
                    }
                    outParts += raw
                }
                "tool" -> {
                    val batchOrdinal = resultBatchOrdinalOf(obj)
                    val hasResult = obj["output"]?.arrayOrNullSafe()?.isNotEmpty() == true ||
                        obj["approvalState"]?.objectOrNullSafe()?.get("type")?.jsonPrimitive?.content in setOf("denied", "answered")
                    val splitByBatch = batchOrdinal != null && lastBatchOrdinal != null && batchOrdinal != lastBatchOrdinal
                    val pendingAfterBatch = lastToolHadResult && !hasResult && (batchOrdinal == null || batchOrdinal != lastBatchOrdinal)
                    if (splitByBatch || pendingAfterBatch) {
                        stepOrdinal++
                        outParts += json.encodeToJsonElement(UIMessagePart.serializer(), stepPart(messageId, stepOrdinal))
                        lastBatchOrdinal = null
                    }
                    val converted = convertTool(json, obj, messageId, toolOrdinal++, stepOrdinal, terminality)
                    outParts += json.encodeToJsonElement(UIMessagePart.serializer(), converted)
                    lastToolHadResult = hasResult
                    if (batchOrdinal != null) lastBatchOrdinal = batchOrdinal
                }
                else -> outParts += raw
            }
        }
        val closed = closeMigratedSteps(json, outParts, terminality)
        return JsonObject(message.toMutableMap().apply { put("parts", JsonArray(closed)) })
    }

    private fun stepPart(messageId: Uuid, ordinal: Int): UIMessagePart.Step = UIMessagePart.Step(
        stepId = uuid5("$messageId/step/$ordinal"),
        ordinal = ordinal,
        startedAt = kotlin.time.Instant.fromEpochMilliseconds(0),
        modelResult = null,
        outcome = null,
        finishedAt = null,
    )

    private fun resultBatchOrdinalOf(legacy: JsonObject): Int? =
        legacy["metadata"]?.objectOrNullSafe()?.get(TOOL_METADATA_RESERVED_KEY)?.objectOrNullSafe()
            ?.get("resultBatchOrdinal")?.jsonPrimitive?.intOrNull

    private fun convertTool(
        json: Json,
        legacy: JsonObject,
        messageId: Uuid,
        toolOrdinal: Int,
        stepOrdinal: Int,
        terminality: TurnTerminality,
    ): UIMessagePart.Tool {
        val toolCallId = legacy["toolCallId"]?.jsonPrimitive?.contentOrNullSafe().orEmpty()
        val toolName = legacy["toolName"]?.jsonPrimitive?.contentOrNullSafe()
            ?: error("legacy tool part is missing toolName")
        val input = legacy["input"]?.jsonPrimitive?.contentOrNullSafe().orEmpty()
        val output = legacy["output"]?.arrayOrNullSafe()?.let {
            json.decodeFromJsonElement(ListSerializer(UIMessagePart.serializer()), it)
        }.orEmpty()
        val approval = legacy["approvalState"]?.objectOrNullSafe()
        val approvalType = approval?.get("type")?.jsonPrimitive?.contentOrNullSafe() ?: "auto"
        val metadata = legacy["metadata"]?.objectOrNullSafe()
        val runtime = metadata?.get(TOOL_METADATA_RESERVED_KEY)?.objectOrNullSafe()
        require(metadata?.containsKey(TOOL_METADATA_RESERVED_KEY) != true || runtime != null) {
            "legacy tool_runtime must be an object"
        }
        requireValidToolRuntime(json, runtime)
        val hasResult = output.isNotEmpty()

        val interaction = when (approvalType) {
            "auto" -> ToolInteractionState.NotRequired
            "approved" -> ToolInteractionState.Approved
            "denied" -> ToolInteractionState.Denied(approval?.get("reason")?.jsonPrimitive?.contentOrNullSafe().orEmpty())
            "answered" -> ToolInteractionState.Answered(approval?.get("answer")?.jsonPrimitive?.contentOrNullSafe().orEmpty())
            "pending" -> when (runtime?.get("interaction")?.jsonPrimitive?.contentOrNullSafe()) {
                INTERACTION_USER_INPUT -> ToolInteractionState.AwaitingInput
                else -> ToolInteractionState.AwaitingApproval
            }

            else -> error("unknown legacy approvalState: $approvalType")
        }

        val terminalStatusToken = runtime?.get("terminalStatus")?.jsonPrimitive?.contentOrNullSafe()
        val resultStatus = resolveResultStatus(interaction, hasResult, terminalStatusToken, terminality)
        // The interaction records the original gate; resultStatus closes interrupted calls.
        // requireValidToolRuntime 已保证 present 时合法；缺省走 ARCHIVABLE_TEXT。
        val outputPolicy = runtime?.get("outputPolicy")?.jsonPrimitive?.contentOrNullSafe()
            ?.let { ToolOutputPolicy.valueOf(it) }
            ?: ToolOutputPolicy.ARCHIVABLE_TEXT
        val archive = runtime?.get("archive")?.objectOrNullSafe()?.let {
            json.decodeFromJsonElement(ToolOutputArchive.serializer(), it)
        }

        val cleanedMetadata = legacy["metadata"]?.objectOrNullSafe()?.let { meta ->
            JsonObject(meta.filterKeys { it != TOOL_METADATA_RESERVED_KEY })
                .let { upgradeSubAssistantInteraction(it) }
                .takeIf { it.isNotEmpty() }
        }

        return UIMessagePart.Tool(
            localCallId = uuid5("$messageId/tool/$toolOrdinal"),
            stepId = uuid5("$messageId/step/$stepOrdinal"),
            providerCallId = toolCallId,
            toolName = toolName,
            input = input,
            output = output,
            interactionState = interaction,
            resultStatus = resultStatus,
            runtimeState = ToolRuntimeState(outputPolicy = outputPolicy, archive = archive),
            metadata = cleanedMetadata,
        )
    }

    /**
     * 现行 `isInvalid` 语义：`tool_runtime` 保留键存在但不符合 v1 schema 时必须 fail-closed，
     * 绝不把损坏的运行时事实降级成默认值——否则迁移会伪装出可回放的假象。缺键（旧消息无
     * `tool_runtime`）合法，走各字段默认。
     */
    private fun requireValidToolRuntime(json: Json, runtime: JsonObject?) {
        if (runtime == null) return
        require(runtime.keys.all { it in setOf("version", "interaction", "outputPolicy", "terminalStatus", "resultBatchOrdinal", "archive") }) {
            "legacy tool_runtime contains unknown fields"
        }
        runtime["version"]?.let { require(it is JsonPrimitive && !it.isString && it.intOrNull == 1) { "Invalid runtime version" } }
        runtime["resultBatchOrdinal"]?.takeUnless { it is JsonNull }?.let {
            require(it is JsonPrimitive && !it.isString && it.intOrNull != null) { "Invalid resultBatchOrdinal" }
        }
        listOf("interaction", "outputPolicy", "terminalStatus").forEach { key ->
            runtime[key]?.takeUnless { it is JsonNull }?.let { require(it is JsonPrimitive && it.isString) { "Invalid runtime $key" } }
        }
        val version = runtime["version"]
        if (version != null && version !is JsonNull && version.jsonPrimitive.intOrNull != 1) {
            error("legacy tool_runtime has unsupported version: $version")
        }
        val interaction = runtime["interaction"]?.jsonPrimitive?.contentOrNullSafe()
        if (interaction != null && interaction !in setOf("approval", "user_input", "none")) {
            error("legacy tool_runtime has invalid interaction: $interaction")
        }
        val outputPolicy = runtime["outputPolicy"]?.jsonPrimitive?.contentOrNullSafe()
        if (outputPolicy != null && ToolOutputPolicy.entries.none { it.name == outputPolicy }) {
            error("legacy tool_runtime has invalid outputPolicy: $outputPolicy")
        }
        val terminalStatus = runtime["terminalStatus"]?.jsonPrimitive?.contentOrNullSafe()
        if (terminalStatus != null && terminalStatus !in setOf("completed", "failed", "denied", "answered")) {
            error("legacy tool_runtime has invalid terminalStatus: $terminalStatus")
        }
        val resultBatchOrdinal = runtime["resultBatchOrdinal"]?.jsonPrimitive?.intOrNull
        if (resultBatchOrdinal != null && resultBatchOrdinal < 0) {
            error("legacy tool_runtime has negative resultBatchOrdinal: $resultBatchOrdinal")
        }
        val archiveElement = runtime["archive"]
        if (archiveElement != null && archiveElement !is JsonNull) {
            val archive = runCatching { json.decodeFromJsonElement(ToolOutputArchive.serializer(), archiveElement) }
                .getOrNull() ?: error("legacy tool_runtime archive is malformed")
            val relativePath = archive.artifact.relativePath.replace('\\', '/')
            val wellFormed = archive.ref > 0 &&
                relativePath == archive.artifact.relativePath &&
                relativePath.startsWith("tool_outputs/") &&
                relativePath.split('/').none { it.isBlank() || it == "." || it == ".." } &&
                archive.artifact.mimeType == "text/plain" &&
                archive.characters >= 0 &&
                archive.lines >= 0
            if (!wellFormed) error("legacy tool_runtime archive is invalid: $archive")
        }
    }

    private fun resolveResultStatus(
        interaction: ToolInteractionState,
        hasResult: Boolean,
        terminalStatusToken: String?,
        terminality: TurnTerminality,
    ): ToolResultStatus? {
        if (interaction is ToolInteractionState.Denied) return ToolResultStatus.DENIED
        if (interaction is ToolInteractionState.Answered) return ToolResultStatus.ANSWERED
        val fromTerminalToken = when (terminalStatusToken) {
            "completed" -> ToolResultStatus.COMPLETED
            "failed" -> ToolResultStatus.FAILED
            "denied" -> ToolResultStatus.DENIED
            "answered" -> ToolResultStatus.ANSWERED
            else -> null
        }
        if (terminality.isNonTerminal) {
            // RUNNING / AWAITING_USER: keep open/pending exactly as-is; recovery closes them at startup.
            return fromTerminalToken ?: if (hasResult) ToolResultStatus.COMPLETED else null
        }
        // Terminal or historical: never leave an open/pending call.
        return when {
            interaction is ToolInteractionState.AwaitingApproval ||
                interaction is ToolInteractionState.AwaitingInput -> ToolResultStatus.INTERRUPTED

            fromTerminalToken != null -> fromTerminalToken
            hasResult -> ToolResultStatus.COMPLETED
            else -> ToolResultStatus.INTERRUPTED
        }
    }

    /** Rewrite `sub_assistant_call.user_interaction` from `tool_ordinal` to `local_call_id`, bump schema to 2. */
    private fun upgradeSubAssistantInteraction(metadata: JsonObject): JsonObject {
        val call = metadata["sub_assistant_call"]?.objectOrNullSafe() ?: return metadata
        val ui = call["user_interaction"]?.objectOrNullSafe() ?: return metadata.copyWith(
            "sub_assistant_call",
            JsonObject(call + ("schema_version" to JsonPrimitive(2))),
        )
        val childMessageId = Uuid.parse(requireNotNull(ui["message_id"]?.jsonPrimitive?.contentOrNullSafe()))
        val childOrdinal = requireNotNull(ui["tool_ordinal"]?.jsonPrimitive?.intOrNull)
        require(childOrdinal >= 0) { "Invalid child interaction tool ordinal" }
        val localCallId = uuid5("$childMessageId/tool/$childOrdinal")
        val newUi = JsonObject(ui.filterKeys { it != "tool_ordinal" } + ("local_call_id" to JsonPrimitive(localCallId.toString())))
        return metadata.copyWith(
            "sub_assistant_call",
            JsonObject(call + ("user_interaction" to newUi) + ("schema_version" to JsonPrimitive(2))),
        )
    }

    /**
     * 迁移后的 transcript 必须满足边界不变量：任何其后还有 Step 的 Step 一律 `Continue`；尾部
     * Step 仅在终态（或历史）turn 落定为对应 [TurnTerminality.stepOutcome]，非终态留给
     * `TurnRecovery`。已带 outcome 的 Step 不重开，保证幂等。
     */
    private fun closeMigratedSteps(
        json: Json,
        parts: List<JsonElement>,
        terminality: TurnTerminality,
    ): List<JsonElement> {
        val stepIndices = parts.indices.filter {
            parts[it].jsonObject["type"]?.jsonPrimitive?.contentOrNullSafe() == "step"
        }.toSet()
        if (stepIndices.isEmpty()) return parts
        val trailingIndex = stepIndices.max()
        return parts.mapIndexed { index, part ->
            if (index !in stepIndices) return@mapIndexed part
            val stepObj = part.jsonObject
            val existing = stepObj["outcome"]
            if (existing != null && existing !is JsonNull) return@mapIndexed part
            val segment = parts.subList(index + 1, stepIndices.filter { it > index }.minOrNull() ?: parts.size)
            val tools = segment.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "tool" }
                .map { json.decodeFromJsonElement(UIMessagePart.Tool.serializer(), it) }
            val hasInterruptedTool = tools.any { it.resultStatus == ToolResultStatus.INTERRUPTED }
            val outcome = when {
                index != trailingIndex -> StepOutcome.Continue
                terminality.isNonTerminal -> null
                terminality == TurnTerminality.HISTORICAL && hasInterruptedTool -> StepOutcome.Interrupted
                else -> terminality.stepOutcome(hasVisibleContentAfterStep(parts, index))
            }
            val result = if (outcome != null || tools.isNotEmpty()) StepModelResult(
                finishReason = null, usage = StepUsage(), providerRequestCount = 0,
                timeToFirstOutputMillis = null, requestDurationMillis = null,
                usageCompleteness = UsageCompleteness.LEGACY, providerMetadata = null,
            ) else null
            JsonObject(stepObj + mapOf(
                "outcome" to (outcome?.let { json.encodeToJsonElement(StepOutcome.serializer(), it) } ?: JsonNull),
                "modelResult" to (result?.let { json.encodeToJsonElement(StepModelResult.serializer(), it) } ?: JsonNull),
            ))
        }
    }

    private fun hasVisibleContentAfterStep(parts: List<JsonElement>, stepIndex: Int): Boolean =
        parts.drop(stepIndex + 1).any {
            val part = it.jsonObject
            when (part["type"]?.jsonPrimitive?.contentOrNullSafe()) {
                "text" -> !part["text"]?.jsonPrimitive?.contentOrNullSafe().isNullOrBlank()
                "image", "video", "audio", "document" -> true
                else -> false
            }
        }

    private fun JsonObject.copyWith(key: String, value: JsonElement): JsonObject = JsonObject(toMutableMap().apply { put(key, value) })

    private fun JsonPrimitive.contentOrNullSafe(): String? {
        if (this is JsonNull) return null
        require(isString) { "Legacy string field has a non-string value" }
        return content
    }

    /**
     * `?.jsonObject` only guards an *absent* key; a legacy transcript may carry an explicit
     * `"metadata": null` / `"user_interaction": null` (kotlinx `explicitNulls`), which is a
     * [JsonNull] and would throw "JsonNull is not a JsonObject". Only explicit null is treated
     * as absent; a different value type is corruption and must never erase durable content.
     */
    private fun JsonElement.objectOrNullSafe(): JsonObject? {
        if (this is JsonNull) return null
        require(this is JsonObject) { "Legacy object field has a non-object value" }
        return this
    }

    /** Same guard for array-valued legacy fields (`output`, `parts`): a `JsonNull` is treated as absent. */
    private fun JsonElement.arrayOrNullSafe(): JsonArray? {
        if (this is JsonNull) return null
        require(this is JsonArray) { "Legacy array field has a non-array value" }
        return this
    }

    // ---- RFC 4122 UUID v5 (SHA-1) ----

    private fun uuid5(name: String): Uuid {
        val ns = Uuid.parse(UUID5_NAMESPACE)
        val nsBytes = uuidToBytes(ns)
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(nsBytes)
        digest.update(name.toByteArray(Charsets.UTF_8))
        val hash = digest.digest()
        val bytes = hash.copyOfRange(0, 16)
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x50).toByte() // version 5
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()  // RFC 4122 variant
        return uuidFromBytes(bytes)
    }

    private fun uuidToBytes(uuid: Uuid): ByteArray {
        val hex = uuid.toString().replace("-", "")
        return ByteArray(16) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun uuidFromBytes(bytes: ByteArray): Uuid {
        val hex = bytes.joinToString("") { "%02x".format(it) }
        val dashed = buildString {
            append(hex, 0, 8); append('-')
            append(hex, 8, 12); append('-')
            append(hex, 12, 16); append('-')
            append(hex, 16, 20); append('-')
            append(hex, 20, 32)
        }
        return Uuid.parse(dashed)
    }

    /** The turn's terminal classification at migration time, derived from the pre-rewrite status string. */
    private enum class TurnTerminality {
        RUNNING,
        AWAITING_USER,
        COMPLETED,
        CANCELLED,
        FAILED,
        INCOMPLETE,
        INTERRUPTED,
        HISTORICAL,
        ;

        val isNonTerminal: Boolean get() = this == RUNNING || this == AWAITING_USER

        fun stepOutcome(hasVisibleText: Boolean): StepOutcome = when (this) {
            RUNNING, AWAITING_USER -> error("non-terminal turn must not close its Step")
            COMPLETED -> StepOutcome.Final
            CANCELLED -> StepOutcome.Cancelled
            FAILED -> StepOutcome.Failed
            INCOMPLETE -> StepOutcome.Incomplete
            INTERRUPTED -> StepOutcome.Interrupted
            HISTORICAL -> if (hasVisibleText) StepOutcome.Final else StepOutcome.Interrupted
        }

        companion object {
            fun of(status: String?, hasTurnRow: Boolean): TurnTerminality = when {
                !hasTurnRow -> HISTORICAL
                else -> when (status) {
                    "RUNNING" -> RUNNING
                    "AWAITING_APPROVAL", "AWAITING_USER" -> AWAITING_USER
                    "COMPLETED" -> COMPLETED
                    "CANCELLED" -> CANCELLED
                    "FAILED" -> FAILED
                    "INCOMPLETE" -> INCOMPLETE
                    "CREATED", "INTERRUPTED" -> INTERRUPTED
                    else -> error("Unknown legacy turn status: $status")
                }
            }
        }
    }
}
