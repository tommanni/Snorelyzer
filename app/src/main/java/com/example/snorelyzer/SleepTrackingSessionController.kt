package com.example.snorelyzer

import com.example.snorelyzer.ml.AudioProcessingResult
import com.example.snorelyzer.ml.SleepAudioProcessor
import com.example.snorelyzer.ml.recording.AudioEventRecorder
import com.example.snorelyzer.ml.recording.EventFrameTimeline
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex

data class SleepTrackingSessionState(
    val isRunning: Boolean = false,
    val sessionStartedAtMillis: Long? = null,
    val isReconnecting: Boolean = false,
    val interruptedAtMillis: Long? = null,
    val hasSaveFailure: Boolean = false,
    val explicitlyStoppedSession: StoppedSessionSave? = null
)

data class StoppedSessionSave(
    val sessionId: String,
    val status: SessionSaveStatus
)

enum class SessionSaveStatus { Saving, Saved, Failed }

interface SleepTrackingSessionStateSource {
    val state: StateFlow<SleepTrackingSessionState>
}

class SleepTrackingSessionController(
    private val audioEventRecorder: AudioEventRecorder,
    private val audioProcessingPipelineFactory: () -> SleepAudioProcessor,
    initialInterruptedAtMillis: Long? = null
) : SleepTrackingSessionStateSource {
    private val _state = MutableStateFlow(
        SleepTrackingSessionState(interruptedAtMillis = initialInterruptedAtMillis)
    )
    override val state = _state.asStateFlow()

    internal val sessionMutex = Mutex()
    private var audioProcessingPipeline: SleepAudioProcessor? = null
    private val frameTimeline = EventFrameTimeline(audioEventRecorder::onClassificationWindow)
    private val saveStatusLock = Any()
    private var saveStatusSession: Any? = null

    fun startSession(startedAtMillis: Long) {
        stopCurrentPipeline()
        frameTimeline.reset()
        audioProcessingPipeline = audioProcessingPipelineFactory().also { it.reset() }
        val saveStatus = Any()
        synchronized(saveStatusLock) {
            saveStatusSession = saveStatus
            _state.value = SleepTrackingSessionState(
                isRunning = true,
                sessionStartedAtMillis = startedAtMillis
            )
        }
        audioEventRecorder.startSession(startedAtMillis) {
            synchronized(saveStatusLock) {
                if (saveStatusSession === saveStatus) {
                    _state.update { it.copy(hasSaveFailure = true) }
                }
            }
        }
    }

    fun onAudioChunk(chunk: FloatArray, chunkStartMillis: Long) {
        val pipeline = audioProcessingPipeline ?: return
        audioEventRecorder.onAudioChunk(chunk, chunkStartMillis)

        val result = pipeline.processChunk(
            chunk = chunk,
            chunkStartMillis = chunkStartMillis,
            forceInference = audioEventRecorder.shouldForceInference || frameTimeline.hasPendingHits
        )
        when (result) {
            is AudioProcessingResult.Skipped, is AudioProcessingResult.Failed ->
                frameTimeline.onUnclassified(result.windowStartMillis)
            is AudioProcessingResult.Classified -> {
                frameTimeline.onClassified(
                    result.windowStartMillis,
                    result.occurringGroupsByFrame
                )
            }
        }
        if (_state.value.isReconnecting) {
            _state.update { it.copy(isReconnecting = false) }
        }
    }

    fun onCaptureInterrupted() {
        _state.update { it.copy(isReconnecting = true) }
        frameTimeline.flush()
        frameTimeline.reset()
        audioEventRecorder.onCaptureInterrupted()
        audioProcessingPipeline?.reset()
    }

    fun stopSession(
        save: Boolean,
        endedAtMillis: Long,
        interrupted: Boolean = false,
        explicitlyRequestedSave: Boolean = false
    ) {
        val isManualSave = explicitlyRequestedSave && !interrupted
        val sessionId = audioEventRecorder.currentSession?.sessionId
            ?.takeIf { save && isManualSave }
        val saveToken = synchronized(saveStatusLock) {
            if (sessionId != null) {
                _state.value = SleepTrackingSessionState(
                    hasSaveFailure = _state.value.hasSaveFailure,
                    explicitlyStoppedSession = StoppedSessionSave(sessionId, SessionSaveStatus.Saving)
                )
            }
            saveStatusSession
        }
        var status = SessionSaveStatus.Saving

        fun reportResult(saved: Boolean) {
            if (sessionId == null) return
            synchronized(saveStatusLock) {
                status = if (saved) SessionSaveStatus.Saved else SessionSaveStatus.Failed
                if (saveStatusSession === saveToken && _state.value.explicitlyStoppedSession?.sessionId == sessionId) {
                    _state.update {
                        it.copy(
                            explicitlyStoppedSession = StoppedSessionSave(sessionId, status),
                            hasSaveFailure = it.hasSaveFailure || !saved
                        )
                    }
                }
            }
        }

        try {
            if (save) {
                frameTimeline.flush(endedAtMillis)
                audioEventRecorder.stopSession(
                    endedAtMillis = endedAtMillis,
                    saveFinalClipUncompressed = isManualSave,
                    onResult = ::reportResult
                )
            } else {
                audioEventRecorder.discardSession()
            }
        } catch (error: Exception) {
            reportResult(false)
            throw error
        } finally {
            try {
                frameTimeline.reset()
                audioEventRecorder.reset()
                stopCurrentPipeline()
            } finally {
                synchronized(saveStatusLock) {
                    if (!save) saveStatusSession = null
                    _state.value = SleepTrackingSessionState(
                        interruptedAtMillis = endedAtMillis.takeIf { interrupted },
                        hasSaveFailure = save && (_state.value.hasSaveFailure || status == SessionSaveStatus.Failed),
                        explicitlyStoppedSession = sessionId?.let { StoppedSessionSave(it, status) }
                    )
                }
            }
        }
    }

    internal fun clearRecordingStatus() {
        synchronized(saveStatusLock) {
            saveStatusSession = null
            _state.value = SleepTrackingSessionState()
        }
    }

    private fun stopCurrentPipeline() {
        audioProcessingPipeline?.close()
        audioProcessingPipeline = null
    }
}
