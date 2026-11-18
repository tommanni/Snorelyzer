package com.example.snorelyzer

import com.example.snorelyzer.ml.AudioProcessingResult
import com.example.snorelyzer.ml.SleepAudioProcessor
import com.example.snorelyzer.ml.recording.AudioEventRecorder
import com.example.snorelyzer.ml.recording.displayLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class DetectedClassUi(
    val label: String,
    val probabilityPercent: Int
)

data class SleepTrackingSessionState(
    val latestStatus: String = "Waiting for audio...",
    val latestResults: List<DetectedClassUi> = emptyList(),
    val isRunning: Boolean = false,
    val sessionStartedAtMillis: Long? = null,
    val isReconnecting: Boolean = false,
    val interruptedAtMillis: Long? = null,
    val hasSaveFailure: Boolean = false
)

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

    private var audioProcessingPipeline: SleepAudioProcessor? = null
    private val saveStatusLock = Any()
    private var saveStatusSession: Any? = null

    fun startSession(startedAtMillis: Long) {
        stopCurrentPipeline()
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
        val windowEndMillis = chunkStartMillis + chunk.size * 1_000L / audioEventRecorder.config.sampleRate

        when (
            val result = pipeline.processChunk(
                chunk = chunk,
                chunkStartMillis = chunkStartMillis,
                forceInference = audioEventRecorder.shouldForceInference
            )
        ) {
            is AudioProcessingResult.Skipped -> {
                audioEventRecorder.onClassificationWindow(result.windowStartMillis, windowEndMillis, emptyList())
                _state.update {
                    it.copy(
                        isReconnecting = false,
                        latestStatus = "Listening for sleep events...",
                        latestResults = emptyList()
                    )
                }
            }
            is AudioProcessingResult.Classified -> {
                audioEventRecorder.onClassificationWindow(
                    result.windowStartMillis,
                    windowEndMillis,
                    result.occurringGroupResults
                )
                _state.update {
                    it.copy(
                        isReconnecting = false,
                        latestStatus = if (result.occurringGroupResults.isEmpty()) {
                            "Listening for sleep events..."
                        } else {
                            ""
                        },
                        latestResults = result.occurringGroupResults.map { groupResult ->
                            DetectedClassUi(
                                label = groupResult.group.displayLabel(),
                                probabilityPercent = (groupResult.probability * 100).toInt()
                            )
                        }
                    )
                }
            }
            is AudioProcessingResult.Failed -> {
                audioEventRecorder.onClassificationWindow(result.windowStartMillis, windowEndMillis, emptyList())
                _state.update {
                    it.copy(
                        isReconnecting = false,
                        latestStatus = "Listening for sleep events...",
                        latestResults = emptyList()
                    )
                }
            }
        }
    }

    fun onCaptureInterrupted() {
        _state.update { it.copy(isReconnecting = true, latestResults = emptyList()) }
        audioEventRecorder.onCaptureInterrupted()
        audioProcessingPipeline?.reset()
    }

    fun stopSession(save: Boolean, endedAtMillis: Long, interrupted: Boolean = false) {
        try {
            if (save) {
                audioEventRecorder.stopSession(endedAtMillis)
            } else {
                audioEventRecorder.discardSession()
            }
        } finally {
            try {
                audioEventRecorder.reset()
                stopCurrentPipeline()
            } finally {
                synchronized(saveStatusLock) {
                    if (!save) saveStatusSession = null
                    _state.value = SleepTrackingSessionState(
                        latestStatus = "Stopped",
                        interruptedAtMillis = endedAtMillis.takeIf { interrupted },
                        hasSaveFailure = save && _state.value.hasSaveFailure
                    )
                }
            }
        }
    }

    private fun stopCurrentPipeline() {
        audioProcessingPipeline?.close()
        audioProcessingPipeline = null
    }
}
