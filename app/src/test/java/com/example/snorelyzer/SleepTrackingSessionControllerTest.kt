package com.example.snorelyzer

import com.example.snorelyzer.ml.AudioGateDecision
import com.example.snorelyzer.ml.AudioGateState
import com.example.snorelyzer.ml.AudioProcessingResult
import com.example.snorelyzer.ml.SleepAudioProcessor
import com.example.snorelyzer.ml.recording.AudioClipWriteRequest
import com.example.snorelyzer.ml.recording.AudioClipWriteResult
import com.example.snorelyzer.ml.recording.AudioClipWriter
import com.example.snorelyzer.ml.recording.AudioEventRecorder
import com.example.snorelyzer.ml.recording.OpusAudioClipWriter
import com.example.snorelyzer.ml.recording.RecordedEventGroup
import com.example.snorelyzer.ml.recording.RecordedEventGroupResult
import com.example.snorelyzer.ml.recording.RecordingEpisodeMetadata
import com.example.snorelyzer.ml.recording.RecordingMetadataSink
import com.example.snorelyzer.ml.recording.RecordingSessionMetadata
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SleepTrackingSessionControllerTest {
    @get:Rule
    val clips = TemporaryFolder()

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

    @Test
    fun reconnectClosesTheEpisodeAndResumesInTheSameSessionAfterTheGap() {
        val pipeline = FakeSleepAudioProcessor(
            results = ArrayDeque(listOf(1_000L, 10_000L).map { start ->
                AudioProcessingResult.Classified(
                    windowStartMillis = start,
                    gateDecision = gateDecision(shouldInfer = true),
                    groupResults = listOf(snoringResult),
                    occurringGroupResults = listOf(snoringResult)
                )
            })
        )
        val sink = FakeRecordingMetadataSink()
        val controller = controller(pipeline, sink)
        controller.startSession(startedAtMillis = 1_000L)
        controller.onAudioChunk(chunk, chunkStartMillis = 1_000L)

        controller.onCaptureInterrupted()

        assertTrue(controller.state.value.isRunning)
        assertTrue(controller.state.value.isReconnecting)
        assertTrue(controller.state.value.latestResults.isEmpty())
        assertEquals(2_000L, sink.completedEpisodes.single().clipEndMillis)
        assertEquals(2, pipeline.resetCalls)

        controller.onAudioChunk(chunk, chunkStartMillis = 10_000L)

        assertFalse(controller.state.value.isReconnecting)
        assertEquals(1_000L, controller.state.value.sessionStartedAtMillis)
        assertEquals(listOf(false, false), pipeline.forceInferenceValues)
        controller.stopSession(save = true, endedAtMillis = 11_000L)
        val session = sink.completedSessions.single()
        assertEquals(sink.startedSessions.single().sessionId, session.sessionId)
        assertEquals(2, session.completedEpisodeCount)
        assertEquals(listOf(1_000L, 10_000L), sink.completedEpisodes.map { it.clipStartMillis })
        assertTrue(sink.completedEpisodes.all { it.sessionId == session.sessionId })
    }

    @Test
    fun exhaustedRecoveryPublishesEndTimeAndSavesThePartialSession() {
        val sink = FakeRecordingMetadataSink()
        val controller = controller(sink = sink)
        controller.startSession(startedAtMillis = 1_000L)
        controller.onAudioChunk(chunk, chunkStartMillis = 1_000L)
        controller.onCaptureInterrupted()

        controller.stopSession(save = true, endedAtMillis = 2_000L, interrupted = true)

        assertFalse(controller.state.value.isRunning)
        assertFalse(controller.state.value.isReconnecting)
        assertEquals(2_000L, controller.state.value.interruptedAtMillis)
        assertEquals(2_000L, sink.completedSessions.single().endedAtMillis)
    }

    @Test
    fun negativeWindowKeepsInferenceForcedUntilQuietTimeout() {
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
        for (second in 1..22) {
            controller.onAudioChunk(chunk, chunkStartMillis = second * 1_000L)
        }

        assertFalse(pipeline.forceInferenceValues.first())
        assertTrue(pipeline.forceInferenceValues.subList(1, 21).all { it })
        assertFalse(pipeline.forceInferenceValues[21])
        controller.stopSession(save = false, endedAtMillis = 23_000L)
    }

    @Test
    fun explicitStopCanNavigateWhileTheLastClipIsStillBeingSaved() {
        val result = AudioProcessingResult.Classified(
            windowStartMillis = 1_000L,
            gateDecision = gateDecision(shouldInfer = true),
            groupResults = listOf(snoringResult),
            occurringGroupResults = listOf(snoringResult)
        )
        val pipeline = FakeSleepAudioProcessor(results = ArrayDeque(listOf(result)))
        var stateDuringClipWrite: SleepTrackingSessionState? = null
        lateinit var controller: SleepTrackingSessionController
        controller = controller(pipeline = pipeline, writer = FakeAudioClipWriter {
            stateDuringClipWrite = controller.state.value
        })
        controller.startSession(startedAtMillis = 1_000L)
        controller.onAudioChunk(chunk, chunkStartMillis = 1_000L)

        controller.stopSession(save = true, endedAtMillis = 2_000L, explicitlyRequestedSave = true)

        val state = checkNotNull(stateDuringClipWrite)
        assertFalse(state.isRunning)
        assertEquals(StoppedSessionSave("session-1000", SessionSaveStatus.Saving), state.explicitlyStoppedSession)
    }

    @Test
    fun explicitStopSavesTheOpenEpisodeAsWavAfterEarlierOpusClips() {
        val event = AudioProcessingResult.Classified(
            windowStartMillis = 1_000L,
            gateDecision = gateDecision(shouldInfer = true),
            groupResults = listOf(snoringResult),
            occurringGroupResults = listOf(snoringResult)
        )
        val results = ArrayDeque<AudioProcessingResult>(listOf(event))
        val sink = FakeRecordingMetadataSink()
        val writer = OpusAudioClipWriter(clips.root, encode = { file, _ -> file.writeText("encoded audio") })
        val controller = controller(FakeSleepAudioProcessor(results), sink, writer)
        controller.startSession(startedAtMillis = 1_000L)

        for (second in 1..21) {
            controller.onAudioChunk(chunk, chunkStartMillis = second * 1_000L)
        }
        assertEquals("opus", File(checkNotNull(sink.completedEpisodes.single().filePath)).extension)

        results.addLast(event.copy(windowStartMillis = 22_000L))
        controller.onAudioChunk(chunk, chunkStartMillis = 22_000L)
        controller.stopSession(save = true, endedAtMillis = 23_000L, explicitlyRequestedSave = true)

        assertEquals(2, sink.completedSessions.single().completedEpisodeCount)
        val finalEpisode = sink.completedEpisodes.last()
        assertEquals(setOf(RecordedEventGroup.Snoring), finalEpisode.groups)
        val finalClip = File(checkNotNull(finalEpisode.filePath))
        assertEquals("wav", finalClip.extension)
        assertEquals("RIFF", finalClip.inputStream().use { it.readNBytes(4).decodeToString() })
    }

    @Test
    fun explicitStopPublishesSessionIdBeforeTheSaveFinishes() {
        val sink = FakeRecordingMetadataSink()
        val controller = controller(sink = sink)
        controller.startSession(startedAtMillis = 1_000L)
        controller.stopSession(save = true, endedAtMillis = 2_000L, explicitlyRequestedSave = true)

        assertFalse(controller.state.value.isRunning)
        assertEquals(StoppedSessionSave("session-1000", SessionSaveStatus.Saving), controller.state.value.explicitlyStoppedSession)

        sink.sessionCompletionResults.single()(true)

        assertEquals(StoppedSessionSave("session-1000", SessionSaveStatus.Saved), controller.state.value.explicitlyStoppedSession)
    }

    @Test
    fun lateMetadataFailureWarnsAfterStopAndDoesNotAffectNextSession() {
        val sink = FakeRecordingMetadataSink()
        val controller = controller(sink = sink)
        controller.startSession(startedAtMillis = 1_000L)
        controller.stopSession(save = true, endedAtMillis = 2_000L, explicitlyRequestedSave = true)

        val reportResult = sink.sessionCompletionResults.single()
        assertFalse(controller.state.value.hasSaveFailure)
        reportResult(false)
        assertTrue(controller.state.value.hasSaveFailure)
        assertFalse(controller.state.value.isRunning)
        assertEquals(SessionSaveStatus.Failed, controller.state.value.explicitlyStoppedSession?.status)

        controller.startSession(startedAtMillis = 3_000L)
        reportResult(false)
        assertFalse(controller.state.value.hasSaveFailure)
        assertNull(controller.state.value.explicitlyStoppedSession)
    }

    private fun controller(
        pipeline: FakeSleepAudioProcessor = FakeSleepAudioProcessor(),
        sink: FakeRecordingMetadataSink = FakeRecordingMetadataSink(),
        writer: AudioClipWriter = FakeAudioClipWriter()
    ): SleepTrackingSessionController {
        return SleepTrackingSessionController(
            audioEventRecorder = AudioEventRecorder(
                clipWriter = writer,
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
        val completedEpisodes = mutableListOf<RecordingEpisodeMetadata>()
        val completedSessions = mutableListOf<RecordingSessionMetadata>()
        val discardedSessionIds = mutableListOf<String>()
        val sessionCompletionResults = mutableListOf<(Boolean) -> Unit>()

        override fun onSessionStarted(session: RecordingSessionMetadata, onFailure: () -> Unit) {
            startedSessions += session
        }

        override fun onEpisodeCompleted(episode: RecordingEpisodeMetadata, onFailure: () -> Unit) {
            completedEpisodes += episode
        }

        override fun onSessionCompleted(session: RecordingSessionMetadata, onResult: (Boolean) -> Unit) {
            completedSessions += session
            sessionCompletionResults += onResult
        }

        override fun onSessionDiscarded(sessionId: String) {
            discardedSessionIds += sessionId
        }
    }

    private class FakeAudioClipWriter(private val onWrite: () -> Unit = {}) : AudioClipWriter {
        override fun writeClip(request: AudioClipWriteRequest): AudioClipWriteResult {
            onWrite()
            return AudioClipWriteResult(
                filePath = "fake/${request.episodeId}.wav",
                sampleRate = request.sampleRate,
                durationMillis = request.clipEndMillis - request.clipStartMillis
            )
        }

        override fun deleteClip(filePath: String): Boolean = true
        override fun deleteAllClips(): Boolean = true
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
