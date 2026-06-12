package com.example.snorelyzer

import com.example.snorelyzer.ml.AudioProcessingResult
import com.example.snorelyzer.ml.SleepAudioProcessor
import com.example.snorelyzer.ml.recording.AudioEventRecorder
import com.example.snorelyzer.ml.recording.displayLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DetectedClassUi(
    val label: String,
    val probabilityPercent: Int
)

data class SleepTrackingSessionState(
    val latestStatus: String = "Waiting for audio...",
    val latestResults: List<DetectedClassUi> = emptyList(),
    val isRunning: Boolean = false,
    val sessionStartedAtMillis: Long? = null
)

interface SleepTrackingSessionStateSource {
    val state: StateFlow<SleepTrackingSessionState>
}

class SleepTrackingSessionController(
    private val audioEventRecorder: AudioEventRecorder,
    private val audioProcessingPipelineFactory: () -> SleepAudioProcessor
) : SleepTrackingSessionStateSource {
    private val _state = MutableStateFlow(SleepTrackingSessionState())
    override val state = _state.asStateFlow()

    private var audioProcessingPipeline: SleepAudioProcessor? = null

    fun startSession(startedAtMillis: Long) {
        stopCurrentPipeline()
        audioEventRecorder.reset()
        audioEventRecorder.startSession(startedAtMillis)
        audioProcessingPipeline = audioProcessingPipelineFactory().also { it.reset() }
        _state.value = SleepTrackingSessionState(
            latestStatus = "Waiting for audio...",
            latestResults = emptyList(),
            isRunning = true,
            sessionStartedAtMillis = startedAtMillis
        )
    }

    fun onAudioChunk(chunk: FloatArray, chunkStartMillis: Long) {
        val pipeline = audioProcessingPipeline ?: return
        audioEventRecorder.onAudioChunk(chunk, chunkStartMillis)

        when (
            val result = pipeline.processChunk(
                chunk = chunk,
                chunkStartMillis = chunkStartMillis,
                forceInference = audioEventRecorder.shouldForceInference
            )
        ) {
            is AudioProcessingResult.Skipped -> {
                audioEventRecorder.onClassificationWindow(result.windowStartMillis, emptyList())
                _state.value = _state.value.copy(
                    latestStatus = "Listening for sleep events...",
                    latestResults = emptyList()
                )
            }
            is AudioProcessingResult.Classified -> {
                audioEventRecorder.onClassificationWindow(
                    result.windowStartMillis,
                    result.occurringGroupResults
                )
                _state.value = _state.value.copy(
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
            is AudioProcessingResult.Failed -> {
                audioEventRecorder.onClassificationWindow(result.windowStartMillis, emptyList())
                _state.value = _state.value.copy(
                    latestStatus = "Listening for sleep events...",
                    latestResults = emptyList()
                )
            }
        }
    }

    fun stopSession(save: Boolean, endedAtMillis: Long) {
        if (save) {
            audioEventRecorder.stopSession(endedAtMillis)
        } else {
            audioEventRecorder.discardSession()
        }
        audioEventRecorder.reset()
        stopCurrentPipeline()
        _state.value = SleepTrackingSessionState(
            latestStatus = "Stopped",
            latestResults = emptyList(),
            isRunning = false,
            sessionStartedAtMillis = null
        )
    }

    private fun stopCurrentPipeline() {
        audioProcessingPipeline?.close()
        audioProcessingPipeline = null
    }
}
