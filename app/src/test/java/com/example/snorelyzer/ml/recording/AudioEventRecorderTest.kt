package com.example.snorelyzer.ml.recording

import java.util.concurrent.Executor
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
            windowEndMillis = 11_000L,
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
            windowEndMillis = 15_000L,
            occurringGroups = listOf(groupResult(RecordedEventGroup.Snoring, 0.31f))
        )
        assertTrue(recorder.shouldForceInference)

        recorder.stopSession(endedAtMillis = 20_000L)

        val episode = recorder.completedEpisodeMetadata.single()
        assertEquals(4_000L, episode.clipStartMillis)
        assertEquals(16_000L, episode.clipEndMillis)
        assertEquals(setOf(RecordedEventGroup.Snoring), episode.groups)
        assertEquals("fake/session-1000-episode-1.wav", episode.filePath)
        assertEquals(1_000, episode.sampleRate)
        assertEquals(12_000L, episode.durationMillis)
        assertEquals(1, writer.requests.size)
        assertEquals(12_000, writer.requests.single().samples.size)
    }

    @Test
    fun eventSpanClosesAtLastPositiveTimeWhileClipKeepsPostPad() {
        val recorder = testRecorder()

        recorder.startSession(startedAtMillis = 0L)
        recorder.feedAudio(durationMillis = 30_000L)
        recorder.onClassificationWindow(
            windowStartMillis = 10_000L,
            windowEndMillis = 20_000L,
            occurringGroups = listOf(groupResult(RecordedEventGroup.Cough, 0.30f))
        )
        recorder.onClassificationWindow(
            windowStartMillis = 11_000L,
            windowEndMillis = 21_000L,
            occurringGroups = emptyList()
        )
        assertTrue(recorder.shouldForceInference)
        assertTrue(recorder.completedEpisodeMetadata.isEmpty())

        recorder.stopSession(endedAtMillis = 30_000L)

        val episode = recorder.completedEpisodeMetadata.single()
        val span = episode.eventSpans.single()
        assertEquals(RecordedEventGroup.Cough, span.group)
        assertEquals(10_000L, span.startedAtMillis)
        assertEquals(20_000L, span.endedAtMillis)
        assertEquals(21_000L, episode.clipEndMillis)
    }

    @Test
    fun belowThresholdGroupResultDoesNotStartEpisode() {
        val recorder = testRecorder()

        recorder.startSession(startedAtMillis = 0L)
        recorder.onClassificationWindow(
            windowStartMillis = 10_000L,
            windowEndMillis = 20_000L,
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
            windowEndMillis = 20_000L,
            occurringGroups = listOf(groupResult(RecordedEventGroup.Snoring, 0.31f))
        )
        recorder.onClassificationWindow(
            windowStartMillis = 29_000L,
            windowEndMillis = 39_000L,
            occurringGroups = emptyList()
        )
        assertTrue(recorder.shouldForceInference)
        assertTrue(recorder.completedEpisodeMetadata.isEmpty())

        recorder.onClassificationWindow(
            windowStartMillis = 30_000L,
            windowEndMillis = 40_000L,
            occurringGroups = emptyList()
        )

        assertFalse(recorder.shouldForceInference)
        val episode = recorder.completedEpisodeMetadata.single()
        assertEquals(9_000L, episode.clipStartMillis)
        assertEquals(21_000L, episode.clipEndMillis)
    }

    @Test
    fun newHitDuringPendingTimeoutMergesIntoSameEpisode() {
        val recorder = testRecorder()

        recorder.startSession(startedAtMillis = 0L)
        recorder.feedAudio(durationMillis = 30_000L)
        recorder.onClassificationWindow(
            windowStartMillis = 10_000L,
            windowEndMillis = 20_000L,
            occurringGroups = listOf(groupResult(RecordedEventGroup.Snoring, 0.31f))
        )
        recorder.onClassificationWindow(
            windowStartMillis = 11_000L,
            windowEndMillis = 21_000L,
            occurringGroups = emptyList()
        )
        assertTrue(recorder.shouldForceInference)

        recorder.onClassificationWindow(
            windowStartMillis = 20_000L,
            windowEndMillis = 30_000L,
            occurringGroups = listOf(groupResult(RecordedEventGroup.Gasp, 0.40f))
        )
        assertTrue(recorder.shouldForceInference)
        recorder.stopSession(endedAtMillis = 25_000L)

        val episode = recorder.completedEpisodeMetadata.single()
        assertEquals(
            setOf(RecordedEventGroup.Snoring, RecordedEventGroup.Gasp),
            episode.groups
        )
        assertEquals(9_000L, episode.clipStartMillis)
        assertEquals(30_000L, episode.clipEndMillis)
    }

    @Test
    fun episodeMergesGroupsWithoutPersistedDominantGroup() {
        val recorder = testRecorder()

        recorder.startSession(startedAtMillis = 0L)
        recorder.feedAudio(durationMillis = 20_000L)
        recorder.onClassificationWindow(
            windowStartMillis = 10_000L,
            windowEndMillis = 20_000L,
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
    fun singlePositiveWindowIncludesEntireWindowAndPadding() {
        val recorder = testRecorder()

        recorder.startSession(startedAtMillis = 0L)
        recorder.feedAudio(durationMillis = 30_000L)
        recorder.onClassificationWindow(
            windowStartMillis = 10_000L,
            windowEndMillis = 20_000L,
            occurringGroups = listOf(groupResult(RecordedEventGroup.Snoring, 0.31f))
        )
        recorder.stopSession(endedAtMillis = 30_000L)

        val episode = recorder.completedEpisodeMetadata.single()
        assertEquals(9_000L, episode.clipStartMillis)
        assertEquals(21_000L, episode.clipEndMillis)
    }

    @Test
    fun clipBoundaryClampsToAvailableBufferedAudio() {
        val recorder = testRecorder()

        recorder.startSession(startedAtMillis = 0L)
        recorder.feedAudio(startMillis = 0L, durationMillis = 19_000L)
        recorder.onClassificationWindow(
            windowStartMillis = 10_000L,
            windowEndMillis = 20_000L,
            occurringGroups = listOf(groupResult(RecordedEventGroup.Snoring, 0.31f))
        )
        recorder.stopSession(endedAtMillis = 19_000L)

        val episode = recorder.completedEpisodeMetadata.single()
        assertEquals(9_000L, episode.clipStartMillis)
        assertEquals(19_000L, episode.clipEndMillis)
    }

    @Test
    fun continuousTwelveMinuteEpisodeSavesEverySampleInConsecutiveClips() {
        val writer = FakeAudioClipWriter()
        val recorder = testRecorder(
            bufferDurationMillis = 330_000L,
            writer = writer
        )

        recorder.startSession(startedAtMillis = 0L)
        for (second in 0 until 720) {
            val start = second * 1_000L
            val end = start + 1_000L
            val samples = FloatArray(1_000) { index -> (start + index).toFloat() }
            recorder.onAudioChunk(samples, chunkStartMillis = start)
            recorder.onClassificationWindow(
                windowStartMillis = (end - 10_000L).coerceAtLeast(0),
                windowEndMillis = end,
                occurringGroups = listOf(groupResult(RecordedEventGroup.Snoring, 0.31f))
            )
            assertTrue(recorder.shouldForceInference)
        }
        assertEquals(2, writer.requests.size)
        recorder.stopSession(endedAtMillis = 720_000L)

        val boundaries = listOf(0L to 300_000L, 300_000L to 600_000L, 600_000L to 720_000L)
        assertEquals(boundaries, writer.requests.map { it.clipStartMillis to it.clipEndMillis })
        assertEquals(
            boundaries,
            recorder.completedEpisodeMetadata.map { it.clipStartMillis to it.clipEndMillis }
        )
        assertEquals(3, recorder.currentSession?.completedEpisodeCount)
        assertEquals(3, recorder.completedEpisodeMetadata.map { it.episodeId }.toSet().size)
        recorder.completedEpisodeMetadata.zip(writer.requests).forEach { (episode, request) ->
            val span = episode.eventSpans.single()
            assertEquals(request.clipStartMillis, span.startedAtMillis)
            assertEquals(request.clipEndMillis, span.endedAtMillis)
            assertEquals(episode.durationMillis.toInt(), request.samples.size)
            assertTrue(request.samples.indices.all { index ->
                request.samples[index] == (request.clipStartMillis + index).toFloat()
            })
        }
    }

    @Test
    fun failedSegmentDoesNotStopTheNextSegmentFromSaving() {
        val writer = FakeAudioClipWriter(failOnWrite = 1)
        val recorder = testRecorder(maxClipDurationMillis = 10_000L, writer = writer)
        var failures = 0
        recorder.startSession(startedAtMillis = 0L) { failures++ }

        for (second in 0 until 15) {
            val start = second * 1_000L
            recorder.onAudioChunk(FloatArray(1_000), chunkStartMillis = start)
            recorder.onClassificationWindow(
                windowStartMillis = start,
                windowEndMillis = start + 1_000L,
                occurringGroups = listOf(groupResult(RecordedEventGroup.Snoring, 0.31f))
            )
        }
        recorder.stopSession(endedAtMillis = 15_000L)

        assertEquals(1, failures)
        assertEquals(2, writer.requests.size)
        val episode = recorder.completedEpisodeMetadata.single()
        assertEquals(10_000L, episode.clipStartMillis)
        assertEquals(15_000L, episode.clipEndMillis)
    }

    @Test
    fun invalidClampedBoundaryDoesNotEmitEpisodeMetadata() {
        val writer = FakeAudioClipWriter()
        val recorder = testRecorder(writer = writer)

        recorder.startSession(startedAtMillis = 0L)
        recorder.feedAudio(startMillis = 0L, durationMillis = 7_000L)
        recorder.onClassificationWindow(
            windowStartMillis = 10_000L,
            windowEndMillis = 20_000L,
            occurringGroups = listOf(groupResult(RecordedEventGroup.Snoring, 0.31f))
        )
        recorder.stopSession(endedAtMillis = 7_000L)

        assertTrue(recorder.completedEpisodeMetadata.isEmpty())
        assertTrue(writer.requests.isEmpty())
    }

    @Test
    fun failedClipWriteDoesNotEmitEpisodeMetadata() {
        val writer = FakeAudioClipWriter(failOnWrite = 1)
        val sink = FakeRecordingMetadataSink()
        val recorder = testRecorder(writer = writer, metadataSink = sink)
        var failures = 0

        recorder.startSession(startedAtMillis = 0L) { failures++ }
        recorder.feedAudio(durationMillis = 30_000L)
        recorder.onClassificationWindow(
            windowStartMillis = 10_000L,
            windowEndMillis = 20_000L,
            occurringGroups = listOf(groupResult(RecordedEventGroup.Snoring, 0.31f))
        )
        recorder.stopSession(endedAtMillis = 30_000L)

        assertTrue(recorder.completedEpisodeMetadata.isEmpty())
        assertEquals(1, writer.requests.size)
        assertTrue(sink.completedEpisodes.isEmpty())
        assertEquals(1, failures)
    }

    @Test
    fun metadataSinkReceivesSessionAndCompletedEpisodeEvents() {
        val sink = FakeRecordingMetadataSink()
        val recorder = testRecorder(metadataSink = sink)

        recorder.startSession(startedAtMillis = 0L)
        recorder.feedAudio(durationMillis = 30_000L)
        recorder.onClassificationWindow(
            windowStartMillis = 10_000L,
            windowEndMillis = 20_000L,
            occurringGroups = listOf(groupResult(RecordedEventGroup.Snoring, 0.31f))
        )
        recorder.stopSession(endedAtMillis = 30_000L)

        assertEquals("session-0", sink.startedSessions.single().sessionId)
        assertEquals("session-0-episode-1", sink.completedEpisodes.single().episodeId)
        assertEquals(30_000L, sink.completedSessions.single().endedAtMillis)
    }

    @Test
    fun captureContinuesWhileAClipIsQueuedForSaving() {
        val writer = FakeAudioClipWriter()
        val sink = FakeRecordingMetadataSink()
        val saves = ArrayDeque<Runnable>()
        val recorder = testRecorder(writer = writer, metadataSink = sink, clipWriteExecutor = Executor(saves::addLast))

        recorder.startSession(startedAtMillis = 0)
        recorder.feedAudio(durationMillis = 40_000)
        recorder.onClassificationWindow(10_000, 20_000, listOf(groupResult(RecordedEventGroup.Snoring, 0.31f)))
        recorder.onClassificationWindow(30_000, 40_000, emptyList())
        recorder.onAudioChunk(FloatArray(1_000) { 0.5f }, 40_000)

        assertEquals(0.5f, recorder.extractBufferedAudio(40_000, 41_000)!!.last())
        assertTrue(writer.requests.isEmpty())
        assertTrue(sink.completedEpisodes.isEmpty())

        saves.removeFirst().run()
        recorder.stopSession(endedAtMillis = 41_000)

        assertEquals("session-0-episode-1", sink.completedEpisodes.single().episodeId)
        assertEquals(1, sink.completedSessions.single().completedEpisodeCount)
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
            windowEndMillis = 20_000L,
            occurringGroups = listOf(groupResult(RecordedEventGroup.Snoring, 0.31f))
        )
        recorder.onClassificationWindow(
            windowStartMillis = 30_000L,
            windowEndMillis = 40_000L,
            occurringGroups = emptyList()
        )

        recorder.discardSession()

        assertTrue(recorder.completedEpisodeMetadata.isEmpty())
        assertEquals(listOf("fake/session-0-episode-1.wav"), writer.deletedClips)
        assertTrue(sink.completedSessions.isEmpty())
        assertEquals(listOf("session-0"), sink.discardedSessions)
    }

    @Test
    fun singleFrameClipGetsSymmetricContextEvenWithLouderAudioOutside() {
        val writer = FakeAudioClipWriter()
        val recorder = testRecorder(writer = writer)
        recorder.startSession(startedAtMillis = 0L)
        val audio = FloatArray(40_000)
        audio[27_519] = 0.8f
        audio[28_000] = 0.005f
        audio[29_000] = 0.01f
        audio[30_000] = 0.005f
        audio[30_520] = 0.8f
        recorder.onAudioChunk(audio, chunkStartMillis = 0L)
        recorder.onClassificationWindow(
            windowStartMillis = 29_000L,
            windowEndMillis = 29_040L,
            occurringGroups = listOf(groupResult(RecordedEventGroup.Snoring, 0.31f))
        )
        recorder.stopSession(endedAtMillis = 40_000L)

        val request = writer.requests.single()
        assertEquals(27_520L, request.clipStartMillis)
        assertEquals(30_520L, request.clipEndMillis)
        assertTrue(request.samples.contentEquals(audio.copyOfRange(27_520, 30_520)))
        val span = recorder.completedEpisodeMetadata.single().eventSpans.single()
        assertEquals(29_000L, span.startedAtMillis)
        assertEquals(29_040L, span.endedAtMillis)
    }

    @Test
    fun clipsKeepTheQuietGapBetweenDetectedEvents() {
        val writer = FakeAudioClipWriter()
        val recorder = testRecorder(writer = writer)
        recorder.startSession(startedAtMillis = 0L)
        val audio = FloatArray(21_000) { if (it % 2 == 0) 0.0001f else -0.0001f }
        for (index in (9_000 until 9_040) + (11_960 until 12_000)) {
            audio[index] = if (index % 2 == 0) 0.3f else -0.3f
        }
        recorder.onAudioChunk(audio, chunkStartMillis = 0L)
        recorder.onClassificationWindow(
            windowStartMillis = 9_000L,
            windowEndMillis = 9_040L,
            occurringGroups = listOf(groupResult(RecordedEventGroup.Cough, 0.8f))
        )
        recorder.onClassificationWindow(9_040L, 11_960L, emptyList())
        recorder.onClassificationWindow(
            windowStartMillis = 11_960L,
            windowEndMillis = 12_000L,
            occurringGroups = listOf(groupResult(RecordedEventGroup.Cough, 0.8f))
        )
        recorder.stopSession(endedAtMillis = 21_000L)

        val episode = recorder.completedEpisodeMetadata.single()
        assertEquals(8_000L, episode.clipStartMillis)
        assertEquals(13_000L, episode.clipEndMillis)
        assertEquals(5_000L, episode.durationMillis)
        assertTrue(writer.requests.single().samples.contentEquals(audio.copyOfRange(8_000, 13_000)))
        assertEquals(
            listOf(9_000L to 9_040L, 11_960L to 12_000L),
            episode.eventSpans.map { it.startedAtMillis to it.endedAtMillis }
        )
    }

    @Test
    fun startupWindowsUseActualEndTimeAndWaitForFullQuietTimeout() {
        val recorder = testRecorder()
        recorder.startSession(startedAtMillis = 0L)
        recorder.feedAudio(durationMillis = 30_000L)
        recorder.onClassificationWindow(
            windowStartMillis = 0L,
            windowEndMillis = 1_000L,
            occurringGroups = listOf(groupResult(RecordedEventGroup.Snoring, 0.31f))
        )
        recorder.onClassificationWindow(0L, 2_000L, emptyList())
        assertTrue(recorder.shouldForceInference)
        recorder.onClassificationWindow(10_000L, 20_000L, emptyList())
        assertTrue(recorder.shouldForceInference)
        recorder.onClassificationWindow(11_000L, 21_000L, emptyList())

        assertFalse(recorder.shouldForceInference)
        val episode = recorder.completedEpisodeMetadata.single()
        assertEquals(0L, episode.clipStartMillis)
        assertEquals(3_000L, episode.clipEndMillis)
    }

    @Test
    fun minimumDurationAddsContextBeforeAnEventAtStop() {
        val recorder = testRecorder()
        recorder.startSession(startedAtMillis = 0L)
        recorder.feedAudio(durationMillis = 10_000L)
        recorder.onClassificationWindow(
            windowStartMillis = 9_960L,
            windowEndMillis = 10_000L,
            occurringGroups = listOf(groupResult(RecordedEventGroup.Cough, 0.8f))
        )
        recorder.stopSession(endedAtMillis = 10_000L)

        val episode = recorder.completedEpisodeMetadata.single()
        assertEquals(7_000L, episode.clipStartMillis)
        assertEquals(10_000L, episode.clipEndMillis)
        assertEquals(9_960L, episode.eventSpans.single().startedAtMillis)
    }

    @Test
    fun shortContinuationKeepsTheSplitEvenWhenLessThanThreeSecondsRemain() {
        val writer = FakeAudioClipWriter()
        val recorder = testRecorder(maxClipDurationMillis = 10_000L, writer = writer)
        recorder.startSession(startedAtMillis = 0L)
        recorder.feedAudio(durationMillis = 11_000L)
        recorder.onClassificationWindow(
            windowStartMillis = 0L,
            windowEndMillis = 11_000L,
            occurringGroups = listOf(groupResult(RecordedEventGroup.Snoring, 0.4f))
        )
        recorder.stopSession(endedAtMillis = 11_000L)

        assertEquals(
            listOf(0L to 10_000L, 10_000L to 11_000L),
            writer.requests.map { it.clipStartMillis to it.clipEndMillis }
        )
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
        metadataSink: RecordingMetadataSink = NoOpRecordingMetadataSink,
        clipWriteExecutor: Executor = Executor(Runnable::run)
    ): AudioEventRecorder {
        return AudioEventRecorder(
            config = RecordedEventConfig(
                rollingBufferDurationMillis = bufferDurationMillis,
                maxClipDurationMillis = maxClipDurationMillis,
                sampleRate = 1_000
            ),
            clipWriter = writer,
            metadataSink = metadataSink,
            clipWriteExecutor = clipWriteExecutor
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
        private val failOnWrite: Int? = null
    ) : AudioClipWriter {
        val requests = mutableListOf<AudioClipWriteRequest>()
        val deletedClips = mutableListOf<String>()

        override fun writeClip(request: AudioClipWriteRequest): AudioClipWriteResult {
            requests += request
            if (requests.size == failOnWrite) error("Write failed")
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

        override fun deleteAllClips(): Boolean = true
    }

    private class FakeRecordingMetadataSink : RecordingMetadataSink {
        val startedSessions = mutableListOf<RecordingSessionMetadata>()
        val completedEpisodes = mutableListOf<RecordingEpisodeMetadata>()
        val completedSessions = mutableListOf<RecordingSessionMetadata>()
        val discardedSessions = mutableListOf<String>()

        override fun onSessionStarted(session: RecordingSessionMetadata, onFailure: () -> Unit) {
            startedSessions += session
        }

        override fun onEpisodeCompleted(episode: RecordingEpisodeMetadata, onFailure: () -> Unit) {
            completedEpisodes += episode
        }

        override fun onSessionCompleted(session: RecordingSessionMetadata, onResult: (Boolean) -> Unit) {
            completedSessions += session
            onResult(true)
        }

        override fun onSessionDiscarded(sessionId: String) {
            discardedSessions += sessionId
        }
    }
}
