package net.weero.measix.pilot.ui.pages.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.weero.measix.pilot.data.db.fts.MessageSearchResult
import net.weero.measix.pilot.data.db.fts.MessageSearchSort
import net.weero.measix.pilot.service.ConversationQueryService
import net.weero.measix.pilot.service.SearchIndexMaintenanceService
import net.weero.measix.pilot.ui.hooks.readStringPreference
import net.weero.measix.pilot.ui.hooks.writeStringPreference

private const val SORT_ORDER_PREF_KEY = "search_page_sort_order"

enum class SearchFailure {
    QUERY,
    REBUILD,
}

private data class SearchRequest(
    val query: String,
    val sort: MessageSearchSort,
    val debounce: Boolean,
    val sequence: Long,
)

class SearchVM(
    private val context: Application,
    private val conversationQueryService: ConversationQueryService,
    private val searchIndexMaintenanceService: SearchIndexMaintenanceService,
) : ViewModel() {
    var searchQuery by mutableStateOf("")
        private set
    var sortOrder by mutableStateOf(
        runCatching {
            MessageSearchSort.valueOf(
                context.readStringPreference(SORT_ORDER_PREF_KEY, MessageSearchSort.RELEVANCE.name)!!
            )
        }.getOrDefault(MessageSearchSort.RELEVANCE)
    )
        private set
    private var requestSequence = 0L
    private val searchRequests = MutableStateFlow(
        SearchRequest(query = "", sort = sortOrder, debounce = false, sequence = requestSequence),
    )
    var results by mutableStateOf<List<MessageSearchResult>>(emptyList())
        private set
    var isLoading by mutableStateOf(false)
        private set
    var isRebuilding by mutableStateOf(false)
        private set
    var rebuildProgress by mutableStateOf(0 to 0)
        private set
    var failure by mutableStateOf<SearchFailure?>(null)
        private set

    init {
        viewModelScope.launch {
            searchRequests.collectLatest { request ->
                if (request.debounce) delay(300L)
                performSearch(request.query, request.sort)
            }
        }
    }

    fun onQueryChange(query: String) {
        searchQuery = query
        requestSearch(debounce = true)
    }

    fun onSortChange(sort: MessageSearchSort) {
        if (sortOrder == sort) return
        sortOrder = sort
        context.writeStringPreference(SORT_ORDER_PREF_KEY, sort.name)
        requestSearch(debounce = false)
    }

    fun search() {
        requestSearch(debounce = false)
    }

    fun rebuildIndex() {
        viewModelScope.launch {
            isRebuilding = true
            rebuildProgress = 0 to 0
            failure = null
            try {
                searchIndexMaintenanceService.rebuild { current, total ->
                    rebuildProgress = current to total
                }
                requestSearch(debounce = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failure = SearchFailure.REBUILD
            } finally {
                isRebuilding = false
            }
        }
    }

    private fun requestSearch(debounce: Boolean) {
        searchRequests.value = SearchRequest(
            query = searchQuery,
            sort = sortOrder,
            debounce = debounce,
            sequence = ++requestSequence,
        )
    }

    private suspend fun performSearch(query: String, sort: MessageSearchSort) {
        if (query.isBlank()) {
            results = emptyList()
            failure = null
            return
        }
        isLoading = true
        failure = null
        try {
            results = conversationQueryService.searchMessages(query, sort)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failure = SearchFailure.QUERY
        } finally {
            isLoading = false
        }
    }
}
