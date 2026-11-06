package com.example.snorelyzer.presentation.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.snorelyzer.data.local.SleepNight
import com.example.snorelyzer.data.local.SleepRecordingLocalDataSource
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AnalyticsInsightsState(
    val isLoading: Boolean = true,
    val sleepNights: List<SleepNight> = emptyList(),
    val multiNightAnalytics: MultiNightAnalytics = MultiNightAnalytics(
        totalNights = 0,
        avgSleepDurationMillis = 0L,
        avgSnoreDurationMillis = 0L,
        avgSnoreScore = 0,
        overallSeverity = SnoreSeverity.Quiet,
        soundTotals = emptyMap(),
        nightTrends = emptyList()
    ),
    val selectedTrendDate: LocalDate? = null
)

sealed interface AnalyticsInsightsAction {
    data class OnSelectTrendNight(val date: LocalDate) : AnalyticsInsightsAction
}

class AnalyticsInsightsViewModel(
    private val localDataSource: SleepRecordingLocalDataSource
) : ViewModel() {
    private val _state = MutableStateFlow(AnalyticsInsightsState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            localDataSource.observeSleepNights(ZoneId.systemDefault()).collect { sleepNights ->
                val analytics = computeMultiNightAnalytics(sleepNights, ZoneId.systemDefault())
                _state.update { currentState ->
                    currentState.copy(
                        isLoading = false,
                        sleepNights = sleepNights,
                        multiNightAnalytics = analytics,
                        selectedTrendDate = currentState.selectedTrendDate
                            ?: analytics.nightTrends.lastOrNull()?.date
                    )
                }
            }
        }
    }

    fun onAction(action: AnalyticsInsightsAction) {
        when (action) {
            is AnalyticsInsightsAction.OnSelectTrendNight -> {
                _state.update { it.copy(selectedTrendDate = action.date) }
            }
        }
    }
}
