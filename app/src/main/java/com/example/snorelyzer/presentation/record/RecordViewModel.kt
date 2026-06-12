package com.example.snorelyzer.presentation.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.snorelyzer.DetectedClassUi
import com.example.snorelyzer.SleepTrackingSessionStateSource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RecordState(
    val latestStatus: String = "Waiting for audio...",
    val latestResults: List<DetectedClassUi> = emptyList(),
    val isServiceRunning: Boolean = false,
    val sessionStartedAtMillis: Long? = null,
    val showShortSessionDialog: Boolean = false,
    val stopTrackingHoldResetKey: Int = 0
)

sealed interface RecordAction {
    data object OnStartClick : RecordAction
    data object OnStopClick : RecordAction
    data object OnKeepRecordingClick : RecordAction
    data object OnEndShortSessionNowClick : RecordAction
    data class OnRecordingPermissionResult(val granted: Boolean) : RecordAction
}

sealed interface RecordEvent {
    data object RequestRecordingPermission : RecordEvent
    data object StartRecordingService : RecordEvent
    data class StopRecordingService(val save: Boolean) : RecordEvent
    data object ShowRecordingDiscardedNotification : RecordEvent
}

class RecordViewModel(
    sessionStateSource: SleepTrackingSessionStateSource,
    private val nowMillis: () -> Long = System::currentTimeMillis
) : ViewModel() {
    private val _state = MutableStateFlow(RecordState())
    val state = _state.asStateFlow()

    private val _events = Channel<RecordEvent>()
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            sessionStateSource.state.collect { sessionState ->
                _state.update {
                    it.copy(
                        latestStatus = sessionState.latestStatus,
                        latestResults = sessionState.latestResults,
                        isServiceRunning = sessionState.isRunning,
                        sessionStartedAtMillis = sessionState.sessionStartedAtMillis
                    )
                }
            }
        }
    }

    fun onAction(action: RecordAction) {
        when (action) {
            RecordAction.OnStartClick -> sendEvent(RecordEvent.RequestRecordingPermission)
            RecordAction.OnStopClick -> handleStopClick()
            RecordAction.OnKeepRecordingClick -> {
                _state.update {
                    it.copy(
                        showShortSessionDialog = false,
                        stopTrackingHoldResetKey = it.stopTrackingHoldResetKey + 1
                    )
                }
            }
            RecordAction.OnEndShortSessionNowClick -> {
                _state.update { it.copy(showShortSessionDialog = false) }
                sendEvent(RecordEvent.StopRecordingService(save = false))
                sendEvent(RecordEvent.ShowRecordingDiscardedNotification)
            }
            is RecordAction.OnRecordingPermissionResult -> {
                if (action.granted) {
                    sendEvent(RecordEvent.StartRecordingService)
                }
            }
        }
    }

    private fun handleStopClick() {
        val sessionStartedAtMillis = state.value.sessionStartedAtMillis
        if (
            sessionStartedAtMillis != null &&
            nowMillis() - sessionStartedAtMillis < MinimumRecordingDurationMillis
        ) {
            _state.update { it.copy(showShortSessionDialog = true) }
            return
        }

        sendEvent(RecordEvent.StopRecordingService(save = true))
    }

    private fun sendEvent(event: RecordEvent) {
        viewModelScope.launch {
            _events.send(event)
        }
    }

    private companion object {
        const val MinimumRecordingDurationMillis = 10 * 60 * 1_000L
    }
}
