package com.example.snorelyzer.presentation.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.example.snorelyzer.data.local.SleepNight
import com.example.snorelyzer.data.local.SleepRecordingLocalDataSource
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SessionInsightsState(
    val sleepNights: List<SleepNight> = emptyList(),
    val selectedDate: LocalDate? = null,
    val selectedNight: SleepNight? = null,
    val selectedNightAnalytics: NightAnalytics? = null,
    val availableDates: Set<LocalDate> = emptySet(),
    val isLoading: Boolean = true,
    val isDatePickerVisible: Boolean = false,
    val nightToDelete: SleepNight? = null,
    val isDeletingNight: Boolean = false,
    val nightDeletionFailed: Boolean = false,
    val playingEpisodeId: String? = null,
    val isPlayerPlaying: Boolean = false,
    val collapsedSessionIds: Set<String> = emptySet(),
    val expandedEpisodeIds: Set<String> = emptySet()
)

sealed interface SessionInsightsAction {
    data object OnRequestDeleteNight : SessionInsightsAction
    data object OnDismissDeleteNight : SessionInsightsAction
    data object OnConfirmDeleteNight : SessionInsightsAction
    data object OnShowDatePicker : SessionInsightsAction
    data object OnDismissDatePicker : SessionInsightsAction
    data class OnDateSelected(val date: LocalDate) : SessionInsightsAction
    data class OnTogglePlayback(val episodeId: String, val filePath: String) : SessionInsightsAction
    data object OnStopPlayback : SessionInsightsAction
    data class OnToggleSessionCollapse(val sessionId: String) : SessionInsightsAction
    data class OnToggleEpisodeSpans(val episodeId: String) : SessionInsightsAction
}

class SessionInsightsViewModel(
    private val localDataSource: SleepRecordingLocalDataSource,
    private val player: Player
) : ViewModel() {
    private val _state = MutableStateFlow(SessionInsightsState())
    val state = _state.asStateFlow()

    private var isManualDateSelection = false

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update { it.copy(isPlayerPlaying = isPlaying) }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    _state.update { it.copy(isPlayerPlaying = false, playingEpisodeId = null) }
                }
            }
        })
        
        viewModelScope.launch {
            localDataSource.observeSleepNights(ZoneId.systemDefault()).collect { sleepNights ->
                _state.update { currentState ->
                    val selectedDate = if (isManualDateSelection &&
                        currentState.selectedDate != null &&
                        sleepNights.any { it.date == currentState.selectedDate }
                    ) {
                        currentState.selectedDate
                    } else {
                        isManualDateSelection = false
                        sleepNights.firstOrNull()?.date
                    }

                    val selectedNight = sleepNights.firstOrNull { it.date == selectedDate }
                    val selectedNightAnalytics = selectedNight?.let { computeNightAnalytics(it) }

                    currentState.copy(
                        sleepNights = sleepNights,
                        selectedDate = selectedDate,
                        selectedNight = selectedNight,
                        selectedNightAnalytics = selectedNightAnalytics,
                        availableDates = sleepNights.mapTo(mutableSetOf()) { it.date },
                        isLoading = false
                    )
                }
            }
        }
    }

    fun onAction(action: SessionInsightsAction) {
        when (action) {
            SessionInsightsAction.OnRequestDeleteNight -> {
                _state.update { current ->
                    val night = current.selectedNight
                    if (night?.endedAtMillis != null && !current.isDeletingNight) {
                        current.copy(nightToDelete = night, nightDeletionFailed = false)
                    } else {
                        current
                    }
                }
            }
            SessionInsightsAction.OnDismissDeleteNight -> {
                _state.update {
                    if (it.isDeletingNight) it else it.copy(nightToDelete = null)
                }
            }
            SessionInsightsAction.OnConfirmDeleteNight -> deleteNight()
            SessionInsightsAction.OnShowDatePicker -> {
                _state.update { it.copy(isDatePickerVisible = it.availableDates.isNotEmpty()) }
            }
            SessionInsightsAction.OnDismissDatePicker -> {
                _state.update { it.copy(isDatePickerVisible = false) }
            }
            is SessionInsightsAction.OnDateSelected -> {
                isManualDateSelection = true
                _state.update { currentState ->
                    val night = currentState.sleepNights.firstOrNull { it.date == action.date }
                    val analytics = night?.let { computeNightAnalytics(it) }
                    currentState.copy(
                        selectedDate = action.date,
                        selectedNight = night,
                        selectedNightAnalytics = analytics,
                        isDatePickerVisible = false
                    )
                }
            }
            is SessionInsightsAction.OnTogglePlayback -> {
                val currentState = _state.value
                if (currentState.playingEpisodeId == action.episodeId) {
                    if (player.isPlaying) {
                        player.pause()
                    } else {
                        player.play()
                    }
                } else {
                    player.setMediaItem(MediaItem.fromUri(action.filePath))
                    player.prepare()
                    player.play()
                    
                    _state.update { currentState ->
                        currentState.copy(playingEpisodeId = action.episodeId)
                    }
                }
            }
            SessionInsightsAction.OnStopPlayback -> {
                player.pause()
            }
            is SessionInsightsAction.OnToggleSessionCollapse -> {
                _state.update { currentState ->
                    val updatedCollapsed = if (currentState.collapsedSessionIds.contains(action.sessionId)) {
                        currentState.collapsedSessionIds - action.sessionId
                    } else {
                        currentState.collapsedSessionIds + action.sessionId
                    }
                    currentState.copy(collapsedSessionIds = updatedCollapsed)
                }
            }
            is SessionInsightsAction.OnToggleEpisodeSpans -> {
                _state.update { currentState ->
                    val updatedExpanded = if (currentState.expandedEpisodeIds.contains(action.episodeId)) {
                        currentState.expandedEpisodeIds - action.episodeId
                    } else {
                        currentState.expandedEpisodeIds + action.episodeId
                    }
                    currentState.copy(expandedEpisodeIds = updatedExpanded)
                }
            }
        }
    }
    
    private fun deleteNight() {
        val current = _state.value
        val night = current.nightToDelete ?: return
        if (current.isDeletingNight) return

        _state.update { it.copy(isDeletingNight = true, nightDeletionFailed = false) }
        viewModelScope.launch {
            try {
                if (night.allEpisodes.any { it.episode.episodeId == _state.value.playingEpisodeId }) {
                    player.stop()
                    player.clearMediaItems()
                    _state.update { it.copy(playingEpisodeId = null, isPlayerPlaying = false) }
                }
                localDataSource.deleteFinishedSessions(night.sessions.map { it.session.sessionId })
                _state.update { it.copy(nightToDelete = null) }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _state.update { it.copy(nightDeletionFailed = true) }
            } finally {
                _state.update { it.copy(isDeletingNight = false) }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        player.release()
    }
}
