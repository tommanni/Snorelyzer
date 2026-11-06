package com.example.snorelyzer.presentation.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.example.snorelyzer.data.local.SleepNight
import com.example.snorelyzer.data.local.SleepRecordingLocalDataSource
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SessionInsightsState(
    val sleepNights: List<SleepNight> = emptyList(),
    val selectedDate: LocalDate? = null,
    val selectedNight: SleepNight? = null,
    val availableDates: Set<LocalDate> = emptySet(),
    val isLoading: Boolean = true,
    val playingEpisodeId: String? = null,
    val isPlayerPlaying: Boolean = false,
    val collapsedSessionIds: Set<String> = emptySet(),
    val expandedEpisodeIds: Set<String> = emptySet()
)

sealed interface SessionInsightsAction {
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

    fun onAction(action: SessionInsightsAction) {
        when (action) {
            is SessionInsightsAction.OnDateSelected -> {
                isManualDateSelection = true
                _state.update { currentState ->
                    currentState.copy(
                        selectedDate = action.date,
                        selectedNight = currentState.sleepNights.firstOrNull { it.date == action.date }
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
    
    override fun onCleared() {
        super.onCleared()
        player.release()
    }
}
