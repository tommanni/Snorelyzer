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

data class InsightsState(
    val sleepNights: List<SleepNight> = emptyList(),
    val selectedDate: LocalDate? = null,
    val selectedNight: SleepNight? = null,
    val availableDates: Set<LocalDate> = emptySet(),
    val isLoading: Boolean = true
)

sealed interface InsightsAction {
    data class OnDateSelected(val date: LocalDate) : InsightsAction
}

class InsightsViewModel(
    private val localDataSource: SleepRecordingLocalDataSource
) : ViewModel() {
    private val _state = MutableStateFlow(InsightsState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            localDataSource.observeSleepNights(ZoneId.systemDefault()).collect { sleepNights ->
                _state.update { currentState ->
                    val selectedDate = currentState.selectedDate ?: sleepNights.firstOrNull()?.date

                    currentState.copy(
                        sleepNights = sleepNights,
                        selectedDate = selectedDate,
                        selectedNight = sleepNights.firstOrNull { it.date == selectedDate },
                        availableDates = sleepNights.mapTo(mutableSetOf()) { it.date },
                        isLoading = false
                    )
                }
            }
        }
    }

    fun onAction(action: InsightsAction) {
        when (action) {
            is InsightsAction.OnDateSelected -> {
                _state.update { currentState ->
                    currentState.copy(
                        selectedDate = action.date,
                        selectedNight = currentState.sleepNights.firstOrNull { it.date == action.date }
                    )
                }
            }
        }
    }
}
