package com.example.snorelyzer.presentation.insights

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class InsightsTab(
    val label: String
) {
    Session("Session"),
    Analytics("Analytics")
}

data class InsightsState(
    val selectedTab: InsightsTab = InsightsTab.Session
)

sealed interface InsightsAction {
    data class OnTabSelected(val tab: InsightsTab) : InsightsAction
}

class InsightsViewModel : ViewModel() {
    private val _state = MutableStateFlow(InsightsState())
    val state = _state.asStateFlow()

    fun onAction(action: InsightsAction) {
        when (action) {
            is InsightsAction.OnTabSelected -> {
                _state.update { currentState ->
                    currentState.copy(selectedTab = action.tab)
                }
            }
        }
    }
}
