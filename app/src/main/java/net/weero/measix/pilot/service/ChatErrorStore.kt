package net.weero.measix.pilot.service

import android.content.Context
import java.text.DateFormat
import java.time.Instant
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import me.rerere.ai.ui.MessageTerminalStatus
import me.rerere.ai.ui.TurnTerminalReasons
import me.rerere.ai.util.ProviderFailureKind
import me.rerere.ai.util.classifyProviderFailure
import net.weero.measix.pilot.R
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
)

enum class ChatErrorSolution {
    CheckTitleModelSettings,
    CheckProviderSettings,
    ViewEnterpriseUsage,
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
    ) {
        if (error is CancellationException) return
        add(
            ChatError(
                title = title,
                detail = classifyProviderFailure(error).detail,
                conversationId = conversationId,
                solution = solution,
                retention = retention,
            )
        )
    }

    fun add(error: ChatError) {
        _errors.update { current ->
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
    conversationId: Uuid,
    messageId: Uuid?,
    status: MessageTerminalStatus,
    reason: String?,
    detail: String?,
): ChatError? {
    if (status != MessageTerminalStatus.FAILED && status != MessageTerminalStatus.INCOMPLETE) {
        return null
    }
    val presentation = terminalMessagePresentation(status, reason)
    val structured = EnterpriseRuntimeProblemException.parseTerminalDetail(detail)
    return ChatError(
        title = context.getString(presentation.titleResource),
        detail = structured?.let { enterpriseRuntimeDetail(context, it, presentation.fallbackDetailResource) }
            ?: detail?.trim()?.takeIf(String::isNotEmpty)
            ?: context.getString(presentation.fallbackDetailResource),
        conversationId = conversationId,
        solution = presentation.solution,
        retention = ChatErrorRetention.UNTIL_DISMISSED,
        sourceMessageId = messageId,
    )
}

private fun enterpriseRuntimeDetail(context: Context, problem: PlatformProblem, fallback: Int): String {
    if (problem.code != net.weero.measix.pilot.data.enterprise.EnterpriseRuntimeProblemCodes.BUDGET_EXHAUSTED) {
        return problem.detail?.trim()?.takeIf(String::isNotEmpty) ?: context.getString(fallback)
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
