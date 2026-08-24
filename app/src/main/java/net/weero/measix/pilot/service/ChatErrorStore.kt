package net.weero.measix.pilot.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.uuid.Uuid

data class ChatError(
    val id: Uuid = Uuid.random(),
    val title: String? = null,
    val error: Throwable,
    val conversationId: Uuid? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val solution: ChatErrorSolution? = null,
)

enum class ChatErrorSolution {
    CheckTitleModelSettings,
}

class ChatErrorStore {
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    fun add(
        error: Throwable,
        conversationId: Uuid? = null,
        title: String? = null,
        solution: ChatErrorSolution? = null,
    ) {
        if (error is CancellationException) return
        _errors.update { current ->
            current + ChatError(
                title = title,
                error = error,
                conversationId = conversationId,
                solution = solution,
            )
        }
    }

    fun add(error: ChatError) {
        if (error.error is CancellationException) return
        _errors.update { it + error }
    }

    fun dismiss(id: Uuid) {
        _errors.update { list -> list.filterNot { it.id == id } }
    }

    fun clear() { _errors.value = emptyList() }
}
