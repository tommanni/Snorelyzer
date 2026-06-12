package com.example.snorelyzer.ml.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioEventRecorderTest {

    @Test
    fun breathingDoesNotTriggerEpisode() {
        val recorder = testRecorder()

        recorder.startSession(startedAtMillis = 0L)
        recorder.onClassificationWindow(
            windowStartMillis = 1_000L,
            occurringGroups = emptyList()
        )
        recorder.stopSession(endedAtMillis = 30_000L)

        assertTrue(recorder.completedEpisodeMetadata.isEmpty())
    }

    @Test
    fun episodeStartsOnFirstAboveThresholdTrackedClass() {
        val writer = FakeAudioClipWriter()
        val recorder = testRecorder(writer = writer)

        recorder.startSession(startedAtMillis = 1_000L)
        recorder.feedAudio(durationMillis = 20_000L)
        recorder.onClassificationWindow(
            windowStartMillis = 5_000L,
            occurringGroups = listOf(groupResult(RecordedEventGroup.Snoring, 0.31f))
        )
        assertTrue(recorder.shouldForceInference)

        recorder.stopSession(endedAtMillis = 10_000L)

        val episode = recorder.completedEpisodeMetadata.single()
        assertEquals(12_000L, episode.clipStartMillis)
        assertEquals(17_000L, episode.clipEndMillis)
        assertEquals(setOf(RecordedEventGroup.Snoring), episode.groups)
        assertEquals("fake/session-1000-episode-1.wav", episode.filePath)
        assertEquals(1_000, episode.sampleRate)
        assertEquals(5_000L, episode.durationMillis)
        assertEquals(1, writer.requests.size)
        assertEquals(5_000, writer.requests.single().samples.size)
    }

    @Test
    fun eventSpanClosesWhenGroupDropsBelowThresholdUsingPostPad() {
        val recorder = testRecorder()

        recorder.startSession(startedAtMillis = 0L)
        recorder.feedAudio(durationMillis = 20_000L)
        recorder.onClassificationWindow(
            windowStartMillis = 10_000L,
            occurringGroups = listOf(groupResult(RecordedEventGroup.Cough, 0.30f))
        )
        recorder.onClassificationWindow(
            windowStartMillis = 11_000L,
            occurringGroups = emptyList()
        )
        assertFalse(recorder.shouldForceInference)
        assertTrue(recorder.completedEpisodeMetadata.isEmpty())

        recorder.stopSession(endedAtMillis = 12_000L)

        val span = recorder.completedEpisodeMetadata.single().eventSpans.single()
        assertEquals(RecordedEventGroup.Cough, span.group)
        assertEquals(10_000L, span.startedAtMillis)
        assertEquals(14_000L, span.endedAtMillis)
    }

    @Test
    fun belowThresholdGroupResultDoesNotStartEpisode() {
        val recorder = testRecorder()

        recorder.startSession(startedAtMillis = 0L)
        recorder.onClassificationWindow(
            windowStartMillis = 10_000L,
            occurringGroups = listOf(groupResult(RecordedEventGroup.Cough, 0.10f))
        )
        recorder.stopSession(endedAtMillis = 12_000L)

        assertTrue(recorder.completedEpisodeMetadata.isEmpty())
    }

    @Test
    fun episodeClosesAfterTwentySecondsWithoutTrackedHits() {
        val recorder = testRecorder()

        recorder.startSession(startedAtMillis = 0L)
        recorder.feedAudio(durationMillis = 40_000L)
        recorder.onClassificationWindow(
            windowStartMillis = 10_000L,
            occurringGroups = listOf(groupResult(RecordedEventGroup.Snoring, 0.31f))
        )
        recorder.onClassificationWindow(
            windowStartMillis = 29_000L,
            occurringGroups = emptyList()
        )
        assertFalse(recorder.shouldForceInference)
        assertTrue(recorder.completedEpisodeMetadata.isEmpty())

        recorder.onClassificationWindow(
            windowStartMillis = 30_000L,
            occurringGroups = emptyList()
        )

        val episode = recorder.completedEpisodeMetadata.single()
        assertEquals(17_000L, episode.clipStartMillis)
        assertEquals(22_000L, episode.clipEndMillis)
    }

    @Test
    fun newHitDuringPendingTimeoutMergesIntoSameEpisode() {
        val recorder = testRecorder()

        recorder.startSession(startedAtMillis = 0L)
        recorder.feedAudio(durationMillis = 30_000L)
        recorder.onClassificationWindow(
            windowStartMillis = 10_000L,
            occurringGroups = listOf(groupResult(RecordedEventGroup.Snoring, 0.31f))
        )
        recorder.onClassificationWindow(
            windowStartMillis = 11_000L,
            occurringGroups = emptyList()
        )
        assertFalse(recorder.shouldForceInference)

        recorder.onClassificationWindow(
            windowStartMillis = 20_000L,
            occurringGroups = listOf(groupResult(RecordedEventGroup.Gasp, 0.40f))
        )
        assertTrue(recorder.shouldForceInference)
        recorder.stopSession(endedAtMillis = 25_000L)

        val episode = recorder.completedEpisodeMetadata.single()
        assertEquals(
            setOf(RecordedEventGroup.Snoring, RecordedEventGroup.Gasp),
            episode.groups
        )
        assertEquals(17_000L, episode.clipStartMillis)
        assertEquals(23_000L, episode.clipEndMillis)
    }

    @Test
    fun episodeMergesGroupsWithoutPersistedDominantGroup() {
        val recorder = testRecorder()

        recorder.startSession(startedAtMillis = 0L)
        recorder.feedAudio(durationMillis = 20_000L)
        recorder.onClassificationWindow(
            windowStartMillis = 10_000L,
            occurringGroups = listOf(
                groupResult(RecordedEventGroup.Snoring, 0.35f),
                groupResult(RecordedEventGroup.Gasp, 0.80f)
            )
        )
        recorder.stopSession(endedAtMillis = 15_000L)

        val episode = recorder.completedEpisodeMetadata.single()
        assertEquals(
            setOf(RecordedEventGroup.Snoring, RecordedEventGroup.Gasp),
            episode.groups
        )
        assertEquals(2, episode.eventSpans.size)
    }

    @Test
    fun singlePositiveWindowUsesFiveSecondMinimumClipDuration() {
        val recorder = testRecorder()

        recorder.startSession(startedAtMillis = 0L)
        recorder.feedAudio(durationMillis = 30_000L)
        recorder.onClassificationWindow(
            windowStartMillis = 10_000L,
            occurringGroups = listOf(groupResult(RecordedEventGroup.Snoring, 0.31f))
        )
        recorder.stopSession(endedAtMillis = 30_000L)

        val episode = recorder.completedEpisodeMetadata.single()
        assertEquals(17_000L, episode.clipStartMillis)
        assertEquals(22_000L, episode.clipEndMillis)
    }

    @Test
    fun clipBoundaryClampsToAvailableBufferedAudio() {
        val recorder = testRecorder()

        recorder.startSession(startedAtMillis = 0L)
        recorder.feedAudio(startMillis = 0L, durationMillis = 19_000L)
        recorder.onClassificationWindow(
            windowStartMillis = 10_000L,
            occurringGroups = listOf(groupResult(RecordedEventGroup.Snoring, 0.31f))
        )
        recorder.stopSession(endedAtMillis = 19_000L)

        val episode = recorder.completedEpisodeMetadata.single()
        assertEquals(17_000L, episode.clipStartMillis)
        assertEquals(19_000L, episode.clipEndMillis)
    }

    @Test
    fun clipBoundaryClampsToMaxClipDuration() {
        val writer = FakeAudioClipWriter()
        val recorder = testRecorder(
            maxClipDurationMillis = 8_000L,
            writer = writer
        )

        recorder.startSession(startedAtMillis = 0L)
        recorder.feedAudio(durationMillis = 50_000L)
        recorder.onClassificationWindow(
            windowStartMillis = 10_000L,
            occurringGroups = listOf(groupResult(RecordedEventGroup.Snoring, 0.31f))
        )
        recorder.onClassificationWindow(
            windowStartMillis = 30_000L,
            occurringGroups = listOf(groupResult(RecordedEventGroup.Snoring, 0.40f))
        )
        recorder.stopSession(endedAtMillis = 50_000L)

        val episode = recorder.completedEpisodeMetadata.single()
        assertEquals(17_000L, episode.clipStartMillis)
        assertEquals(25_000L, episode.clipEndMillis)
        assertEquals(8_000L, episode.durationMillis)
        assertEquals(1, writer.requests.size)
        assertEquals(8_000, writer.requests.single().samples.size)
    }

    @Test
    fun invalidClampedBoundaryDoesNotEmitEpisodeMetadata() {
        val writer = FakeAudioClipWriter()
        val recorder = testRecorder(writer = writer)

        recorder.startSession(startedAtMillis = 0L)
        recorder.feedAudio(startMillis = 0L, durationMillis = 16_000L)
        recorder.onClassificationWindow(
            windowStartMillis = 10_000L,
            occurringGroups = listOf(groupResult(RecordedEventGroup.Snoring, 0.31f))
        )
        recorder.stopSession(endedAtMillis = 16_000L)

        assertTrue(recorder.completedEpisodeMetadata.isEmpty())
        assertTrue(writer.requests.isEmpty())
    }

    @Test
    fun failedClipWriteDoesNotEmitEpisodeMetadata() {
        val writer = FakeAudioClipWriter(shouldThrow = true)
        val sink = FakeRecordingMetadataSink()
        val recorder = testRecorder(writer = writer, metadataSink = sink)

        recorder.startSession(startedAtMillis = 0L)
        recorder.feedAudio(durationMillis = 30_000L)
        recorder.onClassificationWindow(
            windowStartMillis = 10_000L,
            occurringGroups = listOf(groupResult(RecordedEventGroup.Snoring, 0.31f))
        )
        recorder.stopSession(endedAtMillis = 30_000L)

        assertTrue(recorder.completedEpisodeMetadata.isEmpty())
        assertEquals(1, writer.requests.size)
        assertTrue(sink.completedEpisodes.isEmpty())
    }

    @Test
    fun metadataSinkReceivesSessionAndCompletedEpisodeEvents() {
        val sink = FakeRecordingMetadataSink()
        val recorder = testRecorder(metadataSink = sink)

        recorder.startSession(startedAtMillis = 0L)
        recorder.feedAudio(durationMillis = 30_000L)
        recorder.onClassificationWindow(
            windowStartMillis = 10_000L,
            occurringGroups = listOf(groupResult(RecordedEventGroup.Snoring, 0.31f))
        )
        recorder.stopSession(endedAtMillis = 30_000L)

        assertEquals("session-0", sink.startedSessions.single().sessionId)
        assertEquals("session-0-episode-1", sink.completedEpisodes.single().episodeId)
        assertEquals(30_000L, sink.completedSessions.single().endedAtMillis)
    }

    @Test
    fun discardSessionDeletesCompletedClipsAndDoesNotCompleteSession() {
        val writer = FakeAudioClipWriter()
        val sink = FakeRecordingMetadataSink()
        val recorder = testRecorder(writer = writer, metadataSink = sink)

        recorder.startSession(startedAtMillis = 0L)
        recorder.feedAudio(durationMillis = 40_000L)
        recorder.onClassificationWindow(
            windowStartMillis = 10_000L,
            occurringGroups = listOf(groupResult(RecordedEventGroup.Snoring, 0.31f))
        )
        recorder.onClassificationWindow(
            windowStartMillis = 30_000L,
            occurringGroups = emptyList()
        )

        recorder.discardSession()

        assertTrue(recorder.completedEpisodeMetadata.isEmpty())
        assertEquals(listOf("fake/session-0-episode-1.wav"), writer.deletedClips)
        assertTrue(sink.completedSessions.isEmpty())
        assertEquals(listOf("session-0"), sink.discardedSessions)
    }

    private fun groupResult(
        group: RecordedEventGroup,
        probability: Float
    ): RecordedEventGroupResult {
        return RecordedEventGroupResult(
            group = group,
            probability = probability,
            sourceLabel = group.name,
            threshold = 0.25f
        )
    }

    private fun testRecorder(
        bufferDurationMillis: Long = 50_000L,
        maxClipDurationMillis: Long = 5 * 60_000L,
        writer: FakeAudioClipWriter = FakeAudioClipWriter(),
        metadataSink: RecordingMetadataSink = NoOpRecordingMetadataSink
    ): AudioEventRecorder {
        return AudioEventRecorder(
            config = RecordedEventConfig(
                rollingBufferDurationMillis = bufferDurationMillis,
                maxClipDurationMillis = maxClipDurationMillis,
                sampleRate = 1_000
            ),
            clipWriter = writer,
            metadataSink = metadataSink
        )
    }

    private fun AudioEventRecorder.feedAudio(
        startMillis: Long = 0L,
        durationMillis: Long
    ) {
        val chunkDurationMillis = 1_000L
        var elapsedMillis = 0L
        while (elapsedMillis < durationMillis) {
            val currentChunkDuration = minOf(chunkDurationMillis, durationMillis - elapsedMillis)
            onAudioChunk(
                chunk = FloatArray(currentChunkDuration.toInt()),
                chunkStartMillis = startMillis + elapsedMillis
            )
            elapsedMillis += currentChunkDuration
        }
    }

    private class FakeAudioClipWriter(
        private val shouldThrow: Boolean = false
    ) : AudioClipWriter {
        val requests = mutableListOf<AudioClipWriteRequest>()
        val deletedClips = mutableListOf<String>()

        override fun writeClip(request: AudioClipWriteRequest): AudioClipWriteResult {
            requests += request
            if (shouldThrow) error("Write failed")
            return AudioClipWriteResult(
                filePath = "fake/${request.episodeId}.wav",
                sampleRate = request.sampleRate,
                durationMillis = request.samples.size * 1_000L / request.sampleRate
            )
        }

        override fun deleteClip(filePath: String): Boolean {
            deletedClips += filePath
            return true
        }
    }

    private class FakeRecordingMetadataSink : RecordingMetadataSink {
        val startedSessions = mutableListOf<RecordingSessionMetadata>()
        val completedEpisodes = mutableListOf<RecordingEpisodeMetadata>()
        val completedSessions = mutableListOf<RecordingSessionMetadata>()
        val discardedSessions = mutableListOf<String>()

        override fun onSessionStarted(session: RecordingSessionMetadata) {
            startedSessions += session
        }

        override fun onEpisodeCompleted(episode: RecordingEpisodeMetadata) {
            completedEpisodes += episode
        }

        override fun onSessionCompleted(session: RecordingSessionMetadata) {
            completedSessions += session
        }

        override fun onSessionDiscarded(sessionId: String) {
            discardedSessions += sessionId
        }
    }
}
