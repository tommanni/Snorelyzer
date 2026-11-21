package com.example.snorelyzer.presentation.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.snorelyzer.data.local.SleepDataDeletion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class SettingsState(
    val showDeleteConfirmation: Boolean = false,
    val isDeleting: Boolean = false,
    val deleteFailure: SettingsDeleteFailure? = null
)

internal enum class SettingsDeleteFailure {
    RecordingInProgress,
    CleanupFailed
}

internal sealed interface SettingsAction {
    data object OnDeleteDataClick : SettingsAction
    data object OnDismissDeleteConfirmation : SettingsAction
    data object OnConfirmDeleteData : SettingsAction
}

internal sealed interface SettingsEvent {
    data object AllDataDeleted : SettingsEvent
}

internal class SettingsViewModel(
    private val sleepDataDeletion: SleepDataDeletion
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    private val _events = Channel<SettingsEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun onAction(action: SettingsAction) {
        if (_state.value.isDeleting) return

        when (action) {
            SettingsAction.OnDeleteDataClick -> _state.update { it.copy(showDeleteConfirmation = true) }
            SettingsAction.OnDismissDeleteConfirmation -> _state.update { SettingsState() }
            SettingsAction.OnConfirmDeleteData -> deleteData()
        }
    }

    private fun deleteData() {
        if (!_state.value.showDeleteConfirmation) return
        _state.update { it.copy(isDeleting = true, deleteFailure = null) }

        viewModelScope.launch {
            val failure = try {
                if (sleepDataDeletion.deleteAll()) null else SettingsDeleteFailure.RecordingInProgress
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e("SettingsViewModel", "Could not delete sleep data", error)
                SettingsDeleteFailure.CleanupFailed
            }

            _state.update {
                it.copy(showDeleteConfirmation = failure != null, isDeleting = false, deleteFailure = failure)
            }
            if (failure == null) _events.send(SettingsEvent.AllDataDeleted)
        }
    }
}
