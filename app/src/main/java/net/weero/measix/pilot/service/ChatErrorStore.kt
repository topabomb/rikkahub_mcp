package net.weero.measix.pilot.service

import net.weero.measix.pilot.utils.logDiagnosticFailure

import android.content.Context
import java.text.DateFormat
import java.time.Instant
import java.util.Date
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import me.rerere.ai.ui.MessageTerminalStatus
import me.rerere.ai.ui.TurnTerminalReasons
import me.rerere.ai.util.ProviderFailureKind
import net.weero.measix.pilot.R
import net.weero.measix.pilot.utils.userVisibleDiagnostic
import net.weero.measix.pilot.utils.redactDiagnosticSecrets
import net.weero.measix.pilot.data.enterprise.EnterpriseRuntimeProblemException
import net.weero.measix.pilot.data.enterprise.PlatformBudgetCapability
import net.weero.measix.pilot.data.enterprise.PlatformBudgetPeriod
import net.weero.measix.pilot.data.enterprise.PlatformProblem
import net.weero.measix.pilot.data.enterprise.PlatformUsageMeter
import kotlin.uuid.Uuid

data class ChatError(
    val id: Uuid = Uuid.random(),
    val title: String? = null,
    val detail: String,
    val conversationId: Uuid? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val solution: ChatErrorSolution? = null,
    val retention: ChatErrorRetention = ChatErrorRetention.TRANSIENT,
    val sourceMessageId: Uuid? = null,
    val summary: String? = null,
)

enum class ChatErrorSolution {
    CheckTitleModelSettings,
    CheckProviderSettings,
    ViewEnterpriseUsage,
    RetryConversationReads,
    ViewEnterpriseSpace,
}

enum class ChatErrorRetention {
    TRANSIENT,
    UNTIL_DISMISSED,
}

class ChatErrorStore {
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors = _errors.asStateFlow()

    fun errorsFor(conversationId: Uuid): Flow<List<ChatError>> = errors.map { current ->
        current.filter { it.conversationId == null || it.conversationId == conversationId }
    }

    fun add(
        error: Throwable,
        conversationId: Uuid? = null,
        title: String? = null,
        solution: ChatErrorSolution? = null,
        retention: ChatErrorRetention = ChatErrorRetention.TRANSIENT,
        context: Context? = null,
    ) {
        if (error is CancellationException) return
        logDiagnosticFailure("ChatErrorStore", "Chat operation failed", error)
        val preparation = (error as? EnterprisePreparationException)?.takeIf { context != null }
        add(
            if (preparation != null) enterprisePreparationError(requireNotNull(context), preparation, conversationId) else ChatError(
                title = title,
                detail = error.userVisibleDiagnostic(),
                conversationId = conversationId,
                solution = solution,
                retention = retention,
            )
        )
    }

    fun add(error: ChatError) {
        _errors.update { current ->
            if (error.solution == ChatErrorSolution.RetryConversationReads && current.any {
                it.conversationId == error.conversationId && it.solution == error.solution && it.detail == error.detail
            }) return@update current
            val withoutDuplicate = error.sourceMessageId?.let { sourceMessageId ->
                current.filterNot {
                    it.conversationId == error.conversationId && it.sourceMessageId == sourceMessageId
                }
            } ?: current
            withoutDuplicate + error
        }
    }

    fun dismiss(id: Uuid) {
        _errors.update { list -> list.filterNot { it.id == id } }
    }

    fun clear(conversationId: Uuid) {
        _errors.update { list ->
            list.filterNot { it.conversationId == null || it.conversationId == conversationId }
        }
    }
}

/** Presentation follows the failed operation's boundary, never the current model or selected space. */
internal fun enterprisePreparationError(context: Context, error: EnterprisePreparationException, conversationId: Uuid? = null): ChatError {
    val cause = error.cause
    val resource = when {
        cause is net.weero.measix.pilot.data.enterprise.EnterpriseConfigurationException &&
            cause.reason == "platform_runtime_synchronization_required" -> R.string.enterprise_prepare_sync
        cause is net.weero.measix.pilot.data.enterprise.PlatformHttpException ->
            if (cause.status == 401 || cause.status == 403) R.string.enterprise_prepare_session else R.string.enterprise_prepare_rejected
        cause is java.io.IOException -> R.string.enterprise_prepare_connection
        else -> R.string.enterprise_prepare_unavailable
    }
    return ChatError(
        title = context.getString(if (resource == R.string.enterprise_prepare_connection)
            R.string.enterprise_prepare_connection_title else R.string.enterprise_prepare_title),
        summary = context.getString(resource),
        detail = error.userVisibleDiagnostic(),
        conversationId = conversationId,
        solution = ChatErrorSolution.ViewEnterpriseSpace,
        retention = ChatErrorRetention.UNTIL_DISMISSED,
    )
}

data class TerminalMessagePresentation(
    val titleResource: Int,
    val statusResource: Int,
    val fallbackDetailResource: Int,
    val solution: ChatErrorSolution? = null,
)

fun terminalMessagePresentation(
    status: MessageTerminalStatus,
    reason: String?,
): TerminalMessagePresentation = when (reason) {
    net.weero.measix.pilot.data.enterprise.EnterpriseRuntimeProblemCodes.BUDGET_EXHAUSTED -> terminalPresentation(
        R.string.enterprise_budget_exhausted,
        R.string.enterprise_budget_exhausted_detail,
        ChatErrorSolution.ViewEnterpriseUsage,
    )

    net.weero.measix.pilot.data.enterprise.EnterpriseRuntimeProblemCodes.BUDGET_SERVICE_UNAVAILABLE -> terminalPresentation(
        R.string.enterprise_budget_service_unavailable,
        R.string.enterprise_budget_service_unavailable_detail,
        ChatErrorSolution.ViewEnterpriseUsage,
    )

    net.weero.measix.pilot.data.enterprise.EnterpriseRuntimeProblemCodes.USAGE_RECONCILIATION_REQUIRED -> terminalPresentation(
        R.string.enterprise_usage_reconciliation_required,
        R.string.enterprise_usage_reconciliation_required_detail,
        ChatErrorSolution.ViewEnterpriseUsage,
    )

    net.weero.measix.pilot.data.enterprise.EnterpriseRuntimeProblemCodes.USAGE_METER_UNAVAILABLE -> terminalPresentation(
        R.string.enterprise_usage_meter_unavailable,
        R.string.enterprise_usage_meter_unavailable_detail,
        ChatErrorSolution.ViewEnterpriseUsage,
    )

    net.weero.measix.pilot.data.enterprise.EnterpriseRuntimeProblemCodes.IDENTITY_DELETED -> terminalPresentation(
        R.string.enterprise_identity_deleted,
        R.string.enterprise_identity_deleted_detail,
    )

    ProviderFailureKind.RATE_LIMITED.reason -> terminalPresentation(
        R.string.error_title_rate_limited,
        R.string.chat_error_detail_unavailable,
    )

    ProviderFailureKind.QUOTA_EXHAUSTED.reason -> terminalPresentation(
        R.string.error_title_quota_exhausted,
        R.string.chat_error_detail_unavailable,
        ChatErrorSolution.CheckProviderSettings,
    )

    ProviderFailureKind.AUTH_FAILED.reason -> terminalPresentation(
        R.string.error_title_auth_failed,
        R.string.chat_error_detail_unavailable,
        ChatErrorSolution.CheckProviderSettings,
    )

    ProviderFailureKind.PERMISSION_DENIED.reason -> terminalPresentation(
        R.string.error_title_permission_denied,
        R.string.chat_error_detail_unavailable,
        ChatErrorSolution.CheckProviderSettings,
    )

    ProviderFailureKind.CONTENT_BLOCKED.reason -> terminalPresentation(
        R.string.error_title_content_blocked,
        R.string.chat_error_detail_unavailable,
    )

    ProviderFailureKind.INVALID_REQUEST.reason -> terminalPresentation(
        R.string.error_title_invalid_request,
        R.string.chat_error_detail_unavailable,
    )

    ProviderFailureKind.PROVIDER_UNAVAILABLE.reason -> terminalPresentation(
        R.string.error_title_provider_unavailable,
        R.string.chat_error_detail_unavailable,
    )

    ProviderFailureKind.PROVIDER_ERROR.reason,
    TurnTerminalReasons.PROVIDER_FAILED,
    -> terminalPresentation(
        R.string.error_title_provider_error,
        R.string.chat_error_detail_unavailable,
    )

    ProviderFailureKind.RUNTIME_ERROR.reason -> terminalPresentation(
        R.string.error_title_runtime_error,
        R.string.chat_error_detail_unavailable,
    )

    TurnTerminalReasons.PROVIDER_INCOMPLETE -> terminalPresentation(
        R.string.error_title_response_incomplete,
        R.string.chat_error_detail_response_incomplete,
    )

    TurnTerminalReasons.TOOL_LOOP_LIMIT -> terminalPresentation(
        R.string.error_title_tool_loop_limit,
        R.string.chat_error_detail_tool_loop_limit,
    )

    TurnTerminalReasons.INTERACTION_LIMIT -> terminalPresentation(
        R.string.error_title_interaction_limit,
        R.string.chat_error_detail_interaction_limit,
    )

    TurnTerminalReasons.USER_STOP -> TerminalMessagePresentation(
        R.string.chat_message_terminal_cancelled,
        R.string.chat_message_terminal_user_stopped,
        R.string.chat_message_terminal_user_stopped,
    )

    TurnTerminalReasons.SUPERSEDED_BY_NEW_TURN -> TerminalMessagePresentation(
        R.string.chat_message_terminal_cancelled,
        R.string.chat_message_terminal_superseded,
        R.string.chat_message_terminal_superseded,
    )

    TurnTerminalReasons.PROCESS_RESTARTED -> TerminalMessagePresentation(
        R.string.chat_message_terminal_interrupted,
        R.string.chat_message_terminal_process_restarted,
        R.string.chat_message_terminal_process_restarted,
    )

    else -> when (status) {
        MessageTerminalStatus.CANCELLED -> TerminalMessagePresentation(
            R.string.chat_message_terminal_cancelled,
            R.string.chat_message_terminal_cancelled,
            R.string.chat_message_terminal_cancelled,
        )

        MessageTerminalStatus.FAILED -> TerminalMessagePresentation(
            R.string.error_title_generation,
            R.string.chat_message_terminal_failed,
            R.string.chat_error_detail_unavailable,
        )

        MessageTerminalStatus.INCOMPLETE -> TerminalMessagePresentation(
            R.string.error_title_response_incomplete,
            R.string.chat_message_terminal_incomplete,
            R.string.chat_error_detail_response_incomplete,
        )

        MessageTerminalStatus.INTERRUPTED -> TerminalMessagePresentation(
            R.string.chat_message_terminal_interrupted,
            R.string.chat_message_terminal_interrupted,
            R.string.chat_message_terminal_interrupted,
        )
    }
}

private fun terminalPresentation(
    titleResource: Int,
    fallbackDetailResource: Int,
    solution: ChatErrorSolution? = null,
) = TerminalMessagePresentation(
    titleResource = titleResource,
    statusResource = titleResource,
    fallbackDetailResource = fallbackDetailResource,
    solution = solution,
)

fun terminalChatError(
    context: Context,
    conversationId: Uuid?,
    messageId: Uuid?,
    status: MessageTerminalStatus,
    reason: String?,
    detail: String?,
    modelId: me.rerere.common.configuration.ConfigurationReference? = null,
): ChatError? {
    if (status != MessageTerminalStatus.FAILED && status != MessageTerminalStatus.INCOMPLETE) {
        return null
    }
    val presentation = terminalMessagePresentation(status, reason)
    val structured = EnterpriseRuntimeProblemException.parseTerminalDetail(detail)
    val providerConfigurationFailure = presentation.solution == ChatErrorSolution.CheckProviderSettings
    val managedModel = modelId is me.rerere.common.configuration.ConfigurationReference.Enterprise
    val diagnostic = structured?.let(::enterpriseRuntimeDiagnostic)
        ?: detail?.trim()?.takeIf(String::isNotEmpty)
        ?: context.getString(presentation.fallbackDetailResource)
    return ChatError(
        title = context.getString(presentation.titleResource),
        detail = if (structured != null) diagnostic else redactDiagnosticSecrets(diagnostic),
        summary = when {
            providerConfigurationFailure && managedModel -> context.getString(R.string.chat_enterprise_model_service_configuration)
            structured != null -> redactDiagnosticSecrets(enterpriseRuntimeDetail(context, structured, presentation.fallbackDetailResource))
            presentation.fallbackDetailResource != R.string.chat_error_detail_unavailable -> context.getString(presentation.fallbackDetailResource)
            else -> null
        },
        conversationId = conversationId,
        // A historical message's model owns this action; current space or model selection does not.
        solution = if (providerConfigurationFailure && modelId !is me.rerere.common.configuration.ConfigurationReference.User) null
            else presentation.solution,
        retention = ChatErrorRetention.UNTIL_DISMISSED,
        sourceMessageId = messageId,
    )
}

private fun enterpriseRuntimeDetail(context: Context, problem: PlatformProblem, fallback: Int): String {
    if (problem.code != net.weero.measix.pilot.data.enterprise.EnterpriseRuntimeProblemCodes.BUDGET_EXHAUSTED) {
        return context.getString(fallback)
    }
    val budget = problem.budget ?: return context.getString(fallback)
    return buildList {
        problem.detail?.trim()?.takeIf(String::isNotEmpty)?.let(::add)
        add(context.getString(capabilityResource(budget.capability)))
        budget.resourceId?.let { add(context.getString(R.string.enterprise_runtime_resource_id, it)) }
        budget.blockingLimits.forEach { limit ->
            val afterUsed = (limit.limit - limit.used).coerceAtLeast(0)
            val remaining = (afterUsed - limit.reserved).coerceAtLeast(0)
            add(context.getString(
                R.string.enterprise_budget_limit,
                context.getString(periodResource(limit.period)),
                context.getString(meterResource(limit.meter)),
                limit.used.toString(),
                limit.reserved.toString(),
                limit.limit.toString(),
                remaining.toString(),
            ))
            limit.resetAt?.let { resetAt ->
                val formatted = runCatching {
                    DateFormat.getDateTimeInstance().format(Date.from(Instant.parse(resetAt)))
                }.getOrDefault(resetAt)
                add(context.getString(R.string.enterprise_budget_reset_at, formatted))
            }
        }
        if (problem.forwarded == false) add(context.getString(R.string.enterprise_budget_request_not_forwarded))
        problem.requestId?.let { add(context.getString(R.string.enterprise_runtime_request_id, it)) }
    }.joinToString("\n")
}

private val runtimeDiagnosticJson = Json(net.weero.measix.pilot.data.enterprise.PlatformWireCodec.json) { prettyPrint = true }

private fun enterpriseRuntimeDiagnostic(problem: PlatformProblem): String {
    // Redact decoded strings so escaped newlines cannot make a header consume JSON syntax or other details.
    fun redact(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { redact(it.value) })
        is JsonArray -> JsonArray(value.map(::redact))
        is JsonPrimitive -> if (value.isString) JsonPrimitive(redactDiagnosticSecrets(value.content)) else value
    }
    return runtimeDiagnosticJson.encodeToString(redact(runtimeDiagnosticJson.encodeToJsonElement(problem)))
}

private fun capabilityResource(value: PlatformBudgetCapability): Int = when (value) {
    PlatformBudgetCapability.MODEL -> R.string.enterprise_budget_capability_model
    PlatformBudgetCapability.TTS -> R.string.enterprise_budget_capability_tts
    PlatformBudgetCapability.ASR -> R.string.enterprise_budget_capability_asr
    PlatformBudgetCapability.MCP -> R.string.enterprise_budget_capability_mcp
    PlatformBudgetCapability.IMAGE_GENERATION -> R.string.enterprise_budget_capability_image_generation
}

private fun periodResource(value: PlatformBudgetPeriod): Int = when (value) {
    PlatformBudgetPeriod.DAY -> R.string.enterprise_budget_period_day
    PlatformBudgetPeriod.WEEK -> R.string.enterprise_budget_period_week
    PlatformBudgetPeriod.MONTH -> R.string.enterprise_budget_period_month
    PlatformBudgetPeriod.LIFETIME -> R.string.enterprise_budget_period_lifetime
}

private fun meterResource(value: PlatformUsageMeter): Int = when (value) {
    PlatformUsageMeter.REQUESTS -> R.string.enterprise_budget_meter_requests
    PlatformUsageMeter.REQUESTED_IMAGES -> R.string.enterprise_budget_meter_requested_images
    PlatformUsageMeter.INPUT_TOKENS -> R.string.enterprise_budget_meter_input_tokens
    PlatformUsageMeter.OUTPUT_TOKENS -> R.string.enterprise_budget_meter_output_tokens
    PlatformUsageMeter.CACHED_TOKENS -> R.string.enterprise_budget_meter_cached_tokens
    PlatformUsageMeter.TOTAL_TOKENS -> R.string.enterprise_budget_meter_total_tokens
    PlatformUsageMeter.CHARACTERS -> R.string.enterprise_budget_meter_characters
    PlatformUsageMeter.AUDIO_SECONDS -> R.string.enterprise_budget_meter_audio_seconds
}
