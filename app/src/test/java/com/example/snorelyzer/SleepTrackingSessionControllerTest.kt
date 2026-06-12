package com.example.snorelyzer

import com.example.snorelyzer.ml.AudioGateDecision
import com.example.snorelyzer.ml.AudioGateState
import com.example.snorelyzer.ml.AudioProcessingResult
import com.example.snorelyzer.ml.SleepAudioProcessor
import com.example.snorelyzer.ml.recording.AudioClipWriteRequest
import com.example.snorelyzer.ml.recording.AudioClipWriteResult
import com.example.snorelyzer.ml.recording.AudioClipWriter
import com.example.snorelyzer.ml.recording.AudioEventRecorder
import com.example.snorelyzer.ml.recording.RecordedEventGroup
import com.example.snorelyzer.ml.recording.RecordedEventGroupResult
import com.example.snorelyzer.ml.recording.RecordingEpisodeMetadata
import com.example.snorelyzer.ml.recording.RecordingMetadataSink
import com.example.snorelyzer.ml.recording.RecordingSessionMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTrackingSessionControllerTest {
    private val chunk = FloatArray(32_000)

    @Test
    fun startSessionResetsRecorderAndPublishesRunningState() {
        val pipeline = FakeSleepAudioProcessor()
        val sink = FakeRecordingMetadataSink()
        val controller = controller(pipeline = pipeline, sink = sink)

        controller.startSession(startedAtMillis = 1_000L)

        assertEquals(1, pipeline.resetCalls)
        assertTrue(controller.state.value.isRunning)
        assertEquals(1_000L, controller.state.value.sessionStartedAtMillis)
        assertEquals("Waiting for audio...", controller.state.value.latestStatus)
        assertEquals("session-1000", sink.startedSessions.single().sessionId)
    }

    @Test
    fun skippedChunkClearsLatestResultsAndKeepsListeningStatus() {
        val pipeline = FakeSleepAudioProcessor(
            results = ArrayDeque(
                listOf(
                    AudioProcessingResult.Skipped(
                        windowStartMillis = 0L,
                        gateDecision = gateDecision(shouldInfer = false)
                    )
                )
            )
        )
        val controller = controller(pipeline = pipeline)

        controller.startSession(startedAtMillis = 0L)
        controller.onAudioChunk(chunk, chunkStartMillis = 0L)

        assertEquals("Listening for sleep events...", controller.state.value.latestStatus)
        assertTrue(controller.state.value.latestResults.isEmpty())
        assertFalse(pipeline.forceInferenceValues.single())
    }

    @Test
    fun classifiedChunkPublishesDetectedResults() {
        val pipeline = FakeSleepAudioProcessor(
            results = ArrayDeque(
                listOf(
                    AudioProcessingResult.Classified(
                        windowStartMillis = 0L,
                        gateDecision = gateDecision(shouldInfer = true),
                        groupResults = listOf(snoringResult),
                        occurringGroupResults = listOf(snoringResult)
                    )
                )
            )
        )
        val controller = controller(pipeline = pipeline)

        controller.startSession(startedAtMillis = 0L)
        controller.onAudioChunk(chunk, chunkStartMillis = 0L)

        assertEquals("", controller.state.value.latestStatus)
        assertEquals(
            listOf(DetectedClassUi(label = "Snoring", probabilityPercent = 31)),
            controller.state.value.latestResults
        )
    }

    @Test
    fun stopWithSaveCompletesSessionAndClosesPipeline() {
        val pipeline = FakeSleepAudioProcessor()
        val sink = FakeRecordingMetadataSink()
        val controller = controller(pipeline = pipeline, sink = sink)

        controller.startSession(startedAtMillis = 0L)
        controller.stopSession(save = true, endedAtMillis = 60_000L)

        assertEquals(60_000L, sink.completedSessions.single().endedAtMillis)
        assertEquals(1, pipeline.closeCalls)
        assertFalse(controller.state.value.isRunning)
        assertEquals("Stopped", controller.state.value.latestStatus)
    }

    @Test
    fun stopWithDiscardDiscardsSessionAndClosesPipeline() {
        val pipeline = FakeSleepAudioProcessor()
        val sink = FakeRecordingMetadataSink()
        val controller = controller(pipeline = pipeline, sink = sink)

        controller.startSession(startedAtMillis = 5_000L)
        controller.stopSession(save = false, endedAtMillis = 60_000L)

        assertEquals(listOf("session-5000"), sink.discardedSessionIds)
        assertTrue(sink.completedSessions.isEmpty())
        assertEquals(1, pipeline.closeCalls)
        assertFalse(controller.state.value.isRunning)
    }

    private fun controller(
        pipeline: FakeSleepAudioProcessor = FakeSleepAudioProcessor(),
        sink: FakeRecordingMetadataSink = FakeRecordingMetadataSink()
    ): SleepTrackingSessionController {
        return SleepTrackingSessionController(
            audioEventRecorder = AudioEventRecorder(
                clipWriter = FakeAudioClipWriter(),
                metadataSink = sink
            ),
            audioProcessingPipelineFactory = { pipeline }
        )
    }

    private val snoringResult = RecordedEventGroupResult(
        group = RecordedEventGroup.Snoring,
        probability = 0.31f,
        sourceLabel = "Snoring",
        threshold = 0.15f
    )

    private fun gateDecision(shouldInfer: Boolean): AudioGateDecision {
        return AudioGateDecision(
            shouldInfer = shouldInfer,
            state = if (shouldInfer) AudioGateState.Candidate else AudioGateState.Background,
            triggerType = if (shouldInfer) "activity" else "stableBackground",
            noiseFloorDb = -60.0f,
            rmsDb = -60.0f,
            relativeDb = 0.0f,
            maxFrameRelativeDb = 0.0f,
            onsetDb = 0.0f,
            zcr = 0.0f,
            zcrDelta = 0.0f,
            crestFactor = 0.0f,
            crestDelta = 0.0f,
            activityScore = 0,
            isStableBackground = !shouldInfer,
            reasons = emptyList()
        )
    }

    private class FakeSleepAudioProcessor(
        private val results: ArrayDeque<AudioProcessingResult> = ArrayDeque()
    ) : SleepAudioProcessor {
        val forceInferenceValues = mutableListOf<Boolean>()
        var resetCalls = 0
        var closeCalls = 0

        override fun reset() {
            resetCalls++
        }

        override fun processChunk(
            chunk: FloatArray,
            chunkStartMillis: Long,
            forceInference: Boolean
        ): AudioProcessingResult {
            forceInferenceValues += forceInference
            return results.removeFirstOrNull() ?: AudioProcessingResult.Skipped(
                windowStartMillis = chunkStartMillis,
                gateDecision = defaultGateDecision(shouldInfer = false)
            )
        }

        override fun close() {
            closeCalls++
        }
    }

    private class FakeRecordingMetadataSink : RecordingMetadataSink {
        val startedSessions = mutableListOf<RecordingSessionMetadata>()
        val completedSessions = mutableListOf<RecordingSessionMetadata>()
        val discardedSessionIds = mutableListOf<String>()

        override fun onSessionStarted(session: RecordingSessionMetadata) {
            startedSessions += session
        }

        override fun onEpisodeCompleted(episode: RecordingEpisodeMetadata) = Unit

        override fun onSessionCompleted(session: RecordingSessionMetadata) {
            completedSessions += session
        }

        override fun onSessionDiscarded(sessionId: String) {
            discardedSessionIds += sessionId
        }
    }

    private class FakeAudioClipWriter : AudioClipWriter {
        override fun writeClip(request: AudioClipWriteRequest): AudioClipWriteResult {
            return AudioClipWriteResult(
                filePath = "fake/${request.episodeId}.wav",
                sampleRate = request.sampleRate,
                durationMillis = request.clipEndMillis - request.clipStartMillis
            )
        }

        override fun deleteClip(filePath: String): Boolean = true
    }

    private companion object {
        fun defaultGateDecision(shouldInfer: Boolean): AudioGateDecision {
            return AudioGateDecision(
                shouldInfer = shouldInfer,
                state = if (shouldInfer) AudioGateState.Candidate else AudioGateState.Background,
                triggerType = if (shouldInfer) "activity" else "stableBackground",
                noiseFloorDb = -60.0f,
                rmsDb = -60.0f,
                relativeDb = 0.0f,
                maxFrameRelativeDb = 0.0f,
                onsetDb = 0.0f,
                zcr = 0.0f,
                zcrDelta = 0.0f,
                crestFactor = 0.0f,
                crestDelta = 0.0f,
                activityScore = 0,
                isStableBackground = !shouldInfer,
                reasons = emptyList()
            )
        }
    }
}
