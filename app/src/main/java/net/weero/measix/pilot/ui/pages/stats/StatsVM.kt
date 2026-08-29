package net.weero.measix.pilot.ui.pages.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.weero.measix.pilot.service.StatsQueryService
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

data class AppStats(
    val isLoading: Boolean = true,
    val totalConversations: Int = 0,
    val totalMessages: Int = 0,
    val totalInputTokens: Long = 0L,
    val totalOutputTokens: Long = 0L,
    val totalCacheReadInputTokens: Long = 0L,
    val coreNonExactMessages: Int = 0,
    val cacheReadNonExactMessages: Int = 0,
    val conversationsPerDay: Map<LocalDate, Int> = emptyMap(),
    val launchCount: Int = 0,
)

class StatsVM(
    private val statsQueryService: StatsQueryService,
) : ViewModel() {

    private val _stats = MutableStateFlow(AppStats())
    val stats = _stats.asStateFlow()

    init {
        viewModelScope.launch { loadStats() }
    }

    private suspend fun loadStats() {
        delay(50)

        val startDate = LocalDate.now()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
            .minusWeeks(52)
        val snapshot = statsQueryService.load(startDate)

        _stats.value = AppStats(
            isLoading = false,
            totalConversations = snapshot.totalConversations,
            totalMessages = snapshot.totalMessages,
            totalInputTokens = snapshot.totalInputTokens,
            totalOutputTokens = snapshot.totalOutputTokens,
            totalCacheReadInputTokens = snapshot.totalCacheReadInputTokens,
            coreNonExactMessages = snapshot.coreNonExactMessages,
            cacheReadNonExactMessages = snapshot.cacheReadNonExactMessages,
            conversationsPerDay = snapshot.conversationsPerDay,
            launchCount = snapshot.launchCount,
        )
    }
}
