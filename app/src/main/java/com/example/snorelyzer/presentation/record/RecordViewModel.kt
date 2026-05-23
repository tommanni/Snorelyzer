package com.example.snorelyzer.presentation.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.snorelyzer.DetectedClassUi
import com.example.snorelyzer.SleepTrackerService
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RecordState(
    val latestStatus: String = "Waiting for audio...",
    val latestResults: List<DetectedClassUi> = emptyList(),
    val isServiceRunning: Boolean = false
)

sealed interface RecordAction {
    data object OnStartClick : RecordAction
    data object OnStopClick : RecordAction
    data class OnRecordingPermissionResult(val granted: Boolean) : RecordAction
}

sealed interface RecordEvent {
    data object RequestRecordingPermission : RecordEvent
    data object StartRecordingService : RecordEvent
    data object StopRecordingService : RecordEvent
}

class RecordViewModel : ViewModel() {
    private val _state = MutableStateFlow(RecordState())
    val state = _state.asStateFlow()

    private val _events = Channel<RecordEvent>()
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            SleepTrackerService.latestStatus.collect { latestStatus ->
                _state.update { it.copy(latestStatus = latestStatus) }
            }
        }
        viewModelScope.launch {
            SleepTrackerService.latestResults.collect { latestResults ->
                _state.update { it.copy(latestResults = latestResults) }
            }
        }
        viewModelScope.launch {
            SleepTrackerService.isServiceRunning.collect { isServiceRunning ->
                _state.update { it.copy(isServiceRunning = isServiceRunning) }
            }
        }
    }

    fun onAction(action: RecordAction) {
        when (action) {
            RecordAction.OnStartClick -> sendEvent(RecordEvent.RequestRecordingPermission)
            RecordAction.OnStopClick -> sendEvent(RecordEvent.StopRecordingService)
            is RecordAction.OnRecordingPermissionResult -> {
                if (action.granted) {
                    sendEvent(RecordEvent.StartRecordingService)
                }
            }
        }
    }

    private fun sendEvent(event: RecordEvent) {
        viewModelScope.launch {
            _events.send(event)
        }
    }
}
