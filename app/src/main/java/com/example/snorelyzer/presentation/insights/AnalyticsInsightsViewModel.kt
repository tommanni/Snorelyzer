package com.example.snorelyzer.presentation.insights

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AnalyticsInsightsState(
    val isLoading: Boolean = false
)

sealed interface AnalyticsInsightsAction

class AnalyticsInsightsViewModel : ViewModel() {
    private val _state = MutableStateFlow(AnalyticsInsightsState())
    val state = _state.asStateFlow()

    fun onAction(action: AnalyticsInsightsAction) = Unit
}
