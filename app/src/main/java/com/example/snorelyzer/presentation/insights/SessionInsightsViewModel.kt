package com.example.snorelyzer.presentation.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.example.snorelyzer.SessionSaveStatus
import com.example.snorelyzer.StoppedSessionSave
import com.example.snorelyzer.data.local.AudioExportDestination
import com.example.snorelyzer.data.local.AudioExportFormat
import com.example.snorelyzer.data.local.IncompleteAudioExportException
import com.example.snorelyzer.data.local.RecordingAudioAccess
import com.example.snorelyzer.data.local.RecordingAudioExport
import com.example.snorelyzer.data.local.RecordingEpisodeEntity
import com.example.snorelyzer.data.local.SleepNight
import com.example.snorelyzer.data.local.SleepRecordingLocalDataSource
import com.example.snorelyzer.data.local.discardFailedAudioExport
import com.example.snorelyzer.ml.recording.readWavPeaks
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
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
    val expandedEpisodeIds: Set<String> = emptySet(),
    val audioExport: AudioExportState? = null,
    val hasExportableNightAudio: Boolean = false,
    val openedSessionId: String? = null
)

data class AudioExportState(
    val nightDate: LocalDate,
    val format: AudioExportFormat,
    val stage: AudioExportStage
)

enum class AudioExportStage { CheckingAudio, ChoosingDestination, Saving }

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
    data class OnSaveClip(val episodeId: String) : SessionInsightsAction
    data object OnExportNight : SessionInsightsAction
    data class OnExportDestinationResult(val destination: AudioExportDestination?) : SessionInsightsAction
    data object OnExportPickerUnavailable : SessionInsightsAction
    data object OnShowDatePicker : SessionInsightsAction
    data object OnDismissDatePicker : SessionInsightsAction
    data class OnDateSelected(val date: LocalDate) : SessionInsightsAction
    data class OnShowSavedSession(val save: StoppedSessionSave) : SessionInsightsAction
    data class OnTogglePlayback(val episode: RecordingEpisodeEntity) : SessionInsightsAction
    data class OnSeekPlayback(val episode: RecordingEpisodeEntity, val positionMillis: Long) : SessionInsightsAction
    data class OnWaveformVisible(val filePath: String) : SessionInsightsAction
    data object OnStopPlayback : SessionInsightsAction
    data class OnToggleSessionCollapse(val sessionId: String) : SessionInsightsAction
    data class OnToggleEpisodeSpans(val episodeId: String) : SessionInsightsAction
}

sealed interface SessionInsightsEvent {
    data class ChooseExportDestination(val format: AudioExportFormat, val fileName: String) : SessionInsightsEvent
    data class AudioSaved(val format: AudioExportFormat, val saved: Int, val skipped: Int) : SessionInsightsEvent
    data object ExportAudioUnavailable : SessionInsightsEvent
    data class ExportFailed(val incompleteFileMayRemain: Boolean = false) : SessionInsightsEvent
}

class SessionInsightsViewModel(
    private val localDataSource: SleepRecordingLocalDataSource,
    private val player: Player,
    private val audioAccess: RecordingAudioAccess = RecordingAudioAccess()
) : ViewModel() {
    private val _state = MutableStateFlow(SessionInsightsState())
    val state: StateFlow<SessionInsightsState> = _state.asStateFlow()

    private val _events = Channel<SessionInsightsEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val _playback = MutableStateFlow<EpisodePlaybackState?>(null)
    val playback: StateFlow<EpisodePlaybackState?> = _playback.asStateFlow()

    private var isManualDateSelection = false
    private val recordingSave = MutableStateFlow<StoppedSessionSave?>(null)
    private var sessionIdToSelect: String? = null
    private var positionUpdates: Job? = null
    private val waveformLoads = mutableMapOf<String, Job>()
    private var pendingExport: RecordingAudioExport? = null
    private var exportAvailabilityCheck: Job? = null
    private var exportLease: AutoCloseable? = null
    private var playbackLease: AutoCloseable? = null

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
            // Requery after finalization so this reader sees the committed row or a definite failure.
            recordingSave.collectLatest { save ->
                localDataSource.observeSleepNights(ZoneId.systemDefault()).collect { sleepNights ->
                    updateSleepNights(sleepNights, save)
                }
            }
        }
    }

    private fun updateSleepNights(sleepNights: List<SleepNight>, save: StoppedSessionSave?) {
        val previousState = _state.value
        val previousNight = previousState.selectedNight
        val sessionId = sessionIdToSelect
        val saveStatus = save?.status
        if (sessionId != null && sessionId != save?.sessionId) return
        val selectedNight = if (sessionId != null) {
            sleepNights.firstOrNull { night ->
                night.sessions.any { details ->
                    details.session.sessionId == sessionId &&
                        (details.session.endedAtMillis != null || saveStatus == SessionSaveStatus.Failed)
                }
            }?.also {
                isManualDateSelection = true
                sessionIdToSelect = null
            }
        } else {
            val retainedNight = if (isManualDateSelection) {
                sleepNights.firstOrNull { it.date == previousState.selectedDate }
            } else {
                null
            }
            isManualDateSelection = retainedNight != null
            retainedNight ?: sleepNights.firstOrNull()
        }

        _state.update { currentState ->
            currentState.copy(
                sleepNights = sleepNights,
                selectedDate = selectedNight?.date,
                selectedNight = selectedNight,
                selectedNightAnalytics = selectedNight?.let(::computeNightAnalytics),
                availableDates = sleepNights.mapTo(mutableSetOf()) { it.date },
                hasExportableNightAudio = selectedNight == previousNight && currentState.hasExportableNightAudio,
                isLoading = selectedNight == null && sessionIdToSelect != null && saveStatus == SessionSaveStatus.Saving
            )
        }
        if (selectedNight != previousNight) refreshNightAudioAvailability()
        val currentPlayback = _playback.value
        if (currentPlayback != null) {
            val clipStillAvailable = selectedNight?.allEpisodes?.any {
                it.episode.episodeId == currentPlayback.episodeId && it.episode.filePath != null
            } == true
            if (previousNight?.date != selectedNight?.date || !clipStillAvailable) clearPlayback()
        }
    }

    fun onAction(action: SessionInsightsAction) {
        when (action) {
            SessionInsightsAction.OnRequestDeleteNight -> {
                _state.update { current ->
                    val night = current.selectedNight
                    if (night?.endedAtMillis != null && !current.isDeletingNight &&
                        current.audioExport?.nightDate != night.date
                    ) {
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
            is SessionInsightsAction.OnSaveClip -> requestAudioExport(action.episodeId)
            SessionInsightsAction.OnExportNight -> requestAudioExport()
            is SessionInsightsAction.OnExportDestinationResult -> saveAudioExport(action.destination)
            SessionInsightsAction.OnExportPickerUnavailable -> {
                if (_state.value.audioExport?.stage == AudioExportStage.ChoosingDestination) {
                    clearAudioExport()
                    _events.trySend(SessionInsightsEvent.ExportFailed())
                }
            }
            SessionInsightsAction.OnShowDatePicker -> {
                _state.update { it.copy(isDatePickerVisible = it.availableDates.isNotEmpty()) }
            }
            SessionInsightsAction.OnDismissDatePicker -> {
                _state.update { it.copy(isDatePickerVisible = false) }
            }
            is SessionInsightsAction.OnDateSelected -> {
                if (action.date != _state.value.selectedDate) clearPlayback()
                sessionIdToSelect = null
                isManualDateSelection = true
                _state.update { currentState ->
                    val night = currentState.sleepNights.firstOrNull { it.date == action.date }
                    val analytics = night?.let { computeNightAnalytics(it) }
                    currentState.copy(
                        selectedDate = action.date,
                        selectedNight = night,
                        selectedNightAnalytics = analytics,
                        hasExportableNightAudio = night == currentState.selectedNight && currentState.hasExportableNightAudio,
                        isDatePickerVisible = false
                    )
                }
                refreshNightAudioAvailability()
            }
            is SessionInsightsAction.OnShowSavedSession -> {
                val save = action.save
                if (recordingSave.value?.sessionId != save.sessionId) {
                    sessionIdToSelect = save.sessionId
                    clearPlayback()
                    _state.update {
                        it.copy(
                            openedSessionId = save.sessionId,
                            selectedDate = null,
                            selectedNight = null,
                            selectedNightAnalytics = null,
                            hasExportableNightAudio = false,
                            isLoading = true,
                            isDatePickerVisible = false,
                            nightToDelete = null
                        )
                    }
                }
                recordingSave.value = save
            }
            is SessionInsightsAction.OnTogglePlayback -> withSelectedClip(action.episode.episodeId, ::togglePlayback)
            is SessionInsightsAction.OnSeekPlayback -> withSelectedClip(action.episode.episodeId) { night, episode ->
                seekPlayback(night, episode, action.positionMillis)
            }
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

    private fun refreshNightAudioAvailability() {
        exportAvailabilityCheck?.cancel()
        val night = _state.value.selectedNight ?: return
        if (night.endedAtMillis == null || !night.hasAudioPaths) return
        exportAvailabilityCheck = viewModelScope.launch {
            val available = RecordingAudioExport.night(night, ZoneId.systemDefault()).hasAvailableAudio()
            _state.update { if (it.selectedNight == night) it.copy(hasExportableNightAudio = available) else it }
        }
    }

    private fun requestAudioExport(episodeId: String? = null) {
        val current = _state.value
        val night = current.selectedNight ?: return
        if (current.audioExport != null || current.nightToDelete?.date == night.date ||
            current.isDeletingNight
        ) return

        val zoneId = ZoneId.systemDefault()
        val export = if (episodeId == null) {
            if (night.endedAtMillis == null) return
            RecordingAudioExport.night(night, zoneId)
        } else {
            val episode = night.allEpisodes.firstOrNull { it.episode.episodeId == episodeId }?.episode ?: return
            RecordingAudioExport.single(episode, zoneId)
        }
        exportLease = audioAccess.tryUse(night.date)
        if (exportLease == null) {
            _events.trySend(SessionInsightsEvent.ExportAudioUnavailable)
            return
        }
        pendingExport = export
        _state.update { it.copy(audioExport = AudioExportState(night.date, export.format, AudioExportStage.CheckingAudio)) }
        viewModelScope.launch {
            try {
                if (export.hasAvailableAudio()) {
                    updateAudioExportStage(AudioExportStage.ChoosingDestination)
                    _events.send(SessionInsightsEvent.ChooseExportDestination(export.format, export.fileName))
                } else {
                    clearAudioExport()
                    if (export.format == AudioExportFormat.Zip && _state.value.selectedNight == night) {
                        _state.update { it.copy(hasExportableNightAudio = false) }
                    }
                    _events.send(SessionInsightsEvent.ExportAudioUnavailable)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                clearAudioExport()
                _events.send(SessionInsightsEvent.ExportFailed())
            }
        }
    }

    private fun saveAudioExport(destination: AudioExportDestination?) {
        val export = pendingExport
        if (export == null) {
            // A picker result may be restored after the original process was killed.
            if (destination != null) viewModelScope.launch {
                val discarded = discardFailedAudioExport(destination)
                _events.send(SessionInsightsEvent.ExportFailed(incompleteFileMayRemain = !discarded))
            }
            return
        }
        if (_state.value.audioExport?.stage != AudioExportStage.ChoosingDestination) return
        if (destination == null) {
            clearAudioExport()
            return
        }

        updateAudioExportStage(AudioExportStage.Saving)
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val feedback = try {
                val saved = export.saveTo(destination)
                if (saved == 0) SessionInsightsEvent.ExportAudioUnavailable
                else SessionInsightsEvent.AudioSaved(export.format, saved, export.clipCount - saved)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                SessionInsightsEvent.ExportFailed(incompleteFileMayRemain = exception is IncompleteAudioExportException)
            } finally {
                clearAudioExport()
            }
            _events.send(feedback)
        }
    }

    private fun updateAudioExportStage(stage: AudioExportStage) {
        _state.update { it.copy(audioExport = it.audioExport?.copy(stage = stage)) }
    }

    private fun clearAudioExport() {
        pendingExport = null
        exportLease?.close()
        exportLease = null
        _state.update { it.copy(audioExport = null) }
    }

    private inline fun withSelectedClip(episodeId: String, action: (SleepNight, RecordingEpisodeEntity) -> Unit) {
        val night = _state.value.selectedNight ?: return
        val episode = night.allEpisodes.firstOrNull { it.episode.episodeId == episodeId }?.episode ?: return
        if (episode.filePath != null) action(night, episode)
    }

    private fun togglePlayback(night: SleepNight, episode: RecordingEpisodeEntity) {
        val current = _playback.value
        if (current?.episodeId == episode.episodeId && current.isPlayRequested) {
            player.pause()
        } else {
            val lease = audioAccess.tryUse(night.date) ?: return
            try {
                if (current?.episodeId != episode.episodeId) {
                    if (!prepareClip(episode, positionMillis = 0L)) return
                } else {
                    if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
                    if (player.playbackState == Player.STATE_IDLE) player.prepare()
                }
                player.play()
                releasePlaybackLease()
                playbackLease = lease
            } finally {
                if (playbackLease !== lease) lease.close()
            }
        }
        syncPlayback()
    }

    private fun seekPlayback(night: SleepNight, episode: RecordingEpisodeEntity, positionMillis: Long) {
        val current = _playback.value?.takeIf { it.episodeId == episode.episodeId }
        val duration = current?.durationMillis ?: episode.durationMillis
        if (duration <= 0L) return
        val target = positionMillis.coerceIn(0L, duration)
        val lease = audioAccess.tryUse(night.date) ?: return
        lease.use {
            if (current == null) {
                if (!prepareClip(episode, target)) return
                releasePlaybackLease()
            } else {
                // ExoPlayer can retain playWhenReady at the end of a clip.
                if (player.playbackState == Player.STATE_ENDED) player.pause()
                player.seekTo(target)
            }
            syncPlayback()
        }
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
        val isPlayRequested = player.playWhenReady && !ended && !hasError
        _playback.value = current.copy(
            positionMillis = if (ended && duration > 0L) duration else player.currentPosition,
            durationMillis = duration,
            isPlaying = player.isPlaying,
            isPlayRequested = isPlayRequested,
            hasError = hasError
        )
        if (!isPlayRequested) releasePlaybackLease()
    }

    private fun clearPlayback() {
        if (_playback.value == null) return
        _playback.value = null
        positionUpdates?.cancel()
        positionUpdates = null
        player.stop()
        player.clearMediaItems()
        releasePlaybackLease()
    }

    private fun releasePlaybackLease() {
        playbackLease?.close()
        playbackLease = null
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
        if (current.isDeletingNight || current.audioExport?.nightDate == night.date) return

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
        releasePlaybackLease()
        if (_state.value.audioExport?.stage != AudioExportStage.Saving) clearAudioExport()
    }

    private companion object {
        const val PlaybackPositionIntervalMillis = 50L
        const val CachedWaveformCount = 128
        val WaveformDispatcher = Dispatchers.IO.limitedParallelism(2)
    }
}
