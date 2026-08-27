package net.weero.measix.pilot.service

import android.content.Context
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
    return ChatError(
        title = context.getString(presentation.titleResource),
        detail = detail?.trim()?.takeIf(String::isNotEmpty)
            ?: context.getString(presentation.fallbackDetailResource),
        conversationId = conversationId,
        solution = presentation.solution,
        retention = ChatErrorRetention.UNTIL_DISMISSED,
        sourceMessageId = messageId,
    )
}
