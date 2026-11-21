package com.example.snorelyzer.presentation.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.example.snorelyzer.data.local.RecordingEpisodeEntity
import com.example.snorelyzer.data.local.SleepNight
import com.example.snorelyzer.data.local.SleepRecordingLocalDataSource
import com.example.snorelyzer.ml.recording.readWavPeaks
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val waveformsByPath: Map<String, ClipWaveform> = emptyMap(),
    val collapsedSessionIds: Set<String> = emptySet(),
    val expandedEpisodeIds: Set<String> = emptySet()
)

sealed interface ClipWaveform {
    data class Peaks(val values: List<Float>) : ClipWaveform
    data object Unavailable : ClipWaveform
    data object MissingFile : ClipWaveform
}

data class EpisodePlaybackState(
    val episodeId: String,
    val positionMillis: Long,
    val durationMillis: Long,
    val isPlaying: Boolean = false,
    val isPlayRequested: Boolean = false,
    val hasError: Boolean = false
)

sealed interface SessionInsightsAction {
    data object OnRequestDeleteNight : SessionInsightsAction
    data object OnDismissDeleteNight : SessionInsightsAction
    data object OnConfirmDeleteNight : SessionInsightsAction
    data object OnShowDatePicker : SessionInsightsAction
    data object OnDismissDatePicker : SessionInsightsAction
    data class OnDateSelected(val date: LocalDate) : SessionInsightsAction
    data class OnTogglePlayback(val episode: RecordingEpisodeEntity) : SessionInsightsAction
    data class OnSeekPlayback(val episode: RecordingEpisodeEntity, val positionMillis: Long) : SessionInsightsAction
    data class OnWaveformVisible(val filePath: String) : SessionInsightsAction
    data object OnStopPlayback : SessionInsightsAction
    data class OnToggleSessionCollapse(val sessionId: String) : SessionInsightsAction
    data class OnToggleEpisodeSpans(val episodeId: String) : SessionInsightsAction
}

class SessionInsightsViewModel(
    private val localDataSource: SleepRecordingLocalDataSource,
    private val player: Player
) : ViewModel() {
    private val _state = MutableStateFlow(SessionInsightsState())
    val state: StateFlow<SessionInsightsState> = _state.asStateFlow()

    private val _playback = MutableStateFlow<EpisodePlaybackState?>(null)
    val playback: StateFlow<EpisodePlaybackState?> = _playback.asStateFlow()

    private var isManualDateSelection = false
    private var positionUpdates: Job? = null
    private val waveformLoads = mutableMapOf<String, Job>()

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncPlayback()
            if (!player.isPlaying) {
                positionUpdates?.cancel()
                positionUpdates = null
            } else if (positionUpdates?.isActive != true) {
                positionUpdates = viewModelScope.launch {
                    while (true) {
                        delay(PlaybackPositionIntervalMillis)
                        syncPlayback()
                    }
                }
            }
        }
    }

    init {
        player.addListener(playerListener)

        viewModelScope.launch {
            localDataSource.observeSleepNights(ZoneId.systemDefault()).collect { sleepNights ->
                val previousDate = _state.value.selectedDate
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
                if (previousDate != _state.value.selectedDate) clearPlayback()
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
                if (action.date != _state.value.selectedDate) clearPlayback()
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
            is SessionInsightsAction.OnTogglePlayback -> togglePlayback(action.episode)
            is SessionInsightsAction.OnSeekPlayback -> seekPlayback(action.episode, action.positionMillis)
            is SessionInsightsAction.OnWaveformVisible -> loadWaveform(action.filePath)
            SessionInsightsAction.OnStopPlayback -> {
                player.pause()
                syncPlayback()
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

    private fun togglePlayback(episode: RecordingEpisodeEntity) {
        val current = _playback.value
        if (current?.episodeId == episode.episodeId && current.isPlayRequested) {
            player.pause()
        } else {
            if (current?.episodeId != episode.episodeId) {
                if (!prepareClip(episode, positionMillis = 0L)) return
            } else {
                if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
            }
            player.play()
        }
        syncPlayback()
    }

    private fun seekPlayback(episode: RecordingEpisodeEntity, positionMillis: Long) {
        val current = _playback.value?.takeIf { it.episodeId == episode.episodeId }
        val duration = current?.durationMillis ?: episode.durationMillis
        if (duration <= 0L) return
        val target = positionMillis.coerceIn(0L, duration)
        if (current == null) {
            if (!prepareClip(episode, target)) return
        } else {
            // ExoPlayer can retain playWhenReady at the end of a clip.
            if (player.playbackState == Player.STATE_ENDED) player.pause()
            player.seekTo(target)
        }
        syncPlayback()
    }

    private fun prepareClip(episode: RecordingEpisodeEntity, positionMillis: Long): Boolean {
        val filePath = episode.filePath ?: return false
        player.pause()
        _playback.value = EpisodePlaybackState(episode.episodeId, positionMillis, episode.durationMillis)
        val mediaItem = MediaItem.Builder().setMediaId(episode.episodeId).setUri(filePath).build()
        player.setMediaItem(mediaItem, positionMillis)
        player.prepare()
        return true
    }

    private fun syncPlayback() {
        val current = _playback.value ?: return
        if (player.currentMediaItem?.mediaId != current.episodeId) return
        val duration = player.duration.takeIf { it > 0L } ?: current.durationMillis
        val ended = player.playbackState == Player.STATE_ENDED
        val hasError = player.playerError != null
        _playback.value = current.copy(
            positionMillis = if (ended && duration > 0L) duration else player.currentPosition,
            durationMillis = duration,
            isPlaying = player.isPlaying,
            isPlayRequested = player.playWhenReady && !ended && !hasError,
            hasError = hasError
        )
    }

    private fun clearPlayback() {
        if (_playback.value == null) return
        _playback.value = null
        positionUpdates?.cancel()
        positionUpdates = null
        player.stop()
        player.clearMediaItems()
    }

    private fun loadWaveform(filePath: String) {
        if (filePath in _state.value.waveformsByPath || filePath in waveformLoads) return
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val waveform = withContext(WaveformDispatcher) {
                    val file = File(filePath)
                    if (!file.isFile) {
                        ClipWaveform.MissingFile
                    } else {
                        val peaks = try {
                            readWavPeaks(file)
                        } catch (_: IOException) {
                            null
                        }
                        peaks?.let(ClipWaveform::Peaks) ?: ClipWaveform.Unavailable
                    }
                }
                _state.update { state ->
                    val cache = state.waveformsByPath
                    val retained = if (cache.size >= CachedWaveformCount) cache - cache.keys.first() else cache
                    state.copy(waveformsByPath = retained + (filePath to waveform))
                }
            } finally {
                waveformLoads.remove(filePath)
            }
        }
        waveformLoads[filePath] = job
        job.start()
    }

    private fun deleteNight() {
        val current = _state.value
        val night = current.nightToDelete ?: return
        if (current.isDeletingNight) return

        _state.update { it.copy(isDeletingNight = true, nightDeletionFailed = false) }
        viewModelScope.launch {
            try {
                val episodes = night.allEpisodes
                if (episodes.any { it.episode.episodeId == _playback.value?.episodeId }) clearPlayback()
                localDataSource.deleteFinishedSessions(night.sessions.map { it.session.sessionId })
                val deletedPaths = episodes.mapNotNull { it.episode.filePath }
                deletedPaths.forEach { waveformLoads[it]?.cancel() }
                _state.update { it.copy(nightToDelete = null, waveformsByPath = it.waveformsByPath - deletedPaths) }
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
        player.removeListener(playerListener)
        player.release()
    }

    private companion object {
        const val PlaybackPositionIntervalMillis = 50L
        const val CachedWaveformCount = 128
        val WaveformDispatcher = Dispatchers.IO.limitedParallelism(2)
    }
}
