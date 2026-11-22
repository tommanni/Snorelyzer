package com.example.snorelyzer.presentation.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.snorelyzer.data.local.AudioRetentionChange
import com.example.snorelyzer.data.local.AudioRetentionPeriod
import com.example.snorelyzer.data.local.RecordingAudioRetention
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
    val deleteFailure: SettingsDeleteFailure? = null,
    val retentionPeriod: AudioRetentionPeriod = AudioRetentionPeriod.Default,
    val showRetentionOptions: Boolean = false,
    val retentionToConfirm: AudioRetentionPeriod? = null,
    val isSavingRetention: Boolean = false,
    val retentionSaveFailed: Boolean = false
)

internal enum class SettingsDeleteFailure {
    RecordingInProgress,
    CleanupFailed
}

internal sealed interface SettingsAction {
    data object OnDeleteDataClick : SettingsAction
    data object OnDismissDeleteConfirmation : SettingsAction
    data object OnConfirmDeleteData : SettingsAction
    data object OnShowRetentionOptions : SettingsAction
    data object OnDismissRetentionOptions : SettingsAction
    data class OnSelectRetention(val period: AudioRetentionPeriod) : SettingsAction
    data object OnDismissRetentionConfirmation : SettingsAction
    data object OnConfirmRetention : SettingsAction
}

internal sealed interface SettingsEvent {
    data object AllDataDeleted : SettingsEvent
}

internal class SettingsViewModel(
    private val sleepDataDeletion: SleepDataDeletion,
    private val audioRetention: RecordingAudioRetention
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsState(retentionPeriod = audioRetention.period.value))
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    private val _events = Channel<SettingsEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun onAction(action: SettingsAction) {
        if (_state.value.isDeleting || _state.value.isSavingRetention) return

        when (action) {
            SettingsAction.OnDeleteDataClick -> _state.update { it.copy(showDeleteConfirmation = true) }
            SettingsAction.OnDismissDeleteConfirmation -> _state.update {
                it.copy(showDeleteConfirmation = false, deleteFailure = null)
            }
            SettingsAction.OnConfirmDeleteData -> deleteData()
            SettingsAction.OnShowRetentionOptions -> _state.update { it.copy(showRetentionOptions = true) }
            SettingsAction.OnDismissRetentionOptions -> _state.update { it.copy(showRetentionOptions = false) }
            is SettingsAction.OnSelectRetention -> updateRetention(action.period)
            SettingsAction.OnDismissRetentionConfirmation -> _state.update { it.copy(retentionToConfirm = null) }
            SettingsAction.OnConfirmRetention -> _state.value.retentionToConfirm?.let {
                updateRetention(it, confirmed = true)
            }
        }
    }

    private fun updateRetention(period: AudioRetentionPeriod, confirmed: Boolean = false) {
        _state.update {
            it.copy(
                showRetentionOptions = false,
                retentionToConfirm = null,
                isSavingRetention = true,
                retentionSaveFailed = false
            )
        }
        viewModelScope.launch {
            try {
                val change = audioRetention.changePeriod(period, confirmed)
                _state.update {
                    it.copy(
                        retentionPeriod = audioRetention.period.value,
                        retentionToConfirm = period.takeIf { change == AudioRetentionChange.ConfirmationRequired }
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e("SettingsViewModel", "Could not update audio retention", error)
                _state.update { it.copy(retentionSaveFailed = true, retentionPeriod = audioRetention.period.value) }
            } finally {
                _state.update { it.copy(isSavingRetention = false) }
            }
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
