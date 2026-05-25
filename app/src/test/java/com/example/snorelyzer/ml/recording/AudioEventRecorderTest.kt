package com.example.snorelyzer.ml.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioEventRecorderTest {

    @Test
    fun breathingDoesNotTriggerEpisode() {
        val recorder = AudioEventRecorder()

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
        val recorder = testRecorder()

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
        val recorder = AudioEventRecorder()

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
    fun episodeMergesGroupsAndDominantGroupUsesPeakProbability() {
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
        assertEquals(RecordedEventGroup.Gasp, episode.dominantGroup)
        assertEquals(2, episode.eventSpans.size)
    }

    @Test
    fun storesAndExtractsSingleAudioChunk() {
        val recorder = testRecorder(bufferDurationMillis = 10_000L)

        recorder.startSession(startedAtMillis = 0L)
        recorder.onAudioChunk(floatArrayOf(1f, 2f, 3f), chunkStartMillis = 0L)

        assertArrayEquals(
            floatArrayOf(1f, 2f, 3f),
            recorder.extractBufferedAudio(0L, 3L),
            0f
        )
    }

    @Test
    fun appendsMultipleAudioChunksInChronologicalOrder() {
        val recorder = testRecorder(bufferDurationMillis = 10_000L)

        recorder.startSession(startedAtMillis = 0L)
        recorder.onAudioChunk(floatArrayOf(1f, 2f), chunkStartMillis = 0L)
        recorder.onAudioChunk(floatArrayOf(3f, 4f), chunkStartMillis = 2L)

        assertArrayEquals(
            floatArrayOf(1f, 2f, 3f, 4f),
            recorder.extractBufferedAudio(0L, 4L),
            0f
        )
    }

    @Test
    fun extractsAudioAfterRollingBufferWraps() {
        val recorder = testRecorder(bufferDurationMillis = 5L)

        recorder.startSession(startedAtMillis = 0L)
        recorder.onAudioChunk(floatArrayOf(1f, 2f, 3f), chunkStartMillis = 0L)
        recorder.onAudioChunk(floatArrayOf(4f, 5f, 6f, 7f), chunkStartMillis = 3L)

        assertArrayEquals(
            floatArrayOf(3f, 4f, 5f, 6f, 7f),
            recorder.extractBufferedAudio(0L, 10L),
            0f
        )
    }

    @Test
    fun clampsExtractionToAvailableBufferedAudio() {
        val recorder = testRecorder(bufferDurationMillis = 5L)

        recorder.startSession(startedAtMillis = 0L)
        recorder.onAudioChunk(floatArrayOf(10f, 11f, 12f, 13f, 14f), chunkStartMillis = 0L)

        assertArrayEquals(
            floatArrayOf(10f, 11f, 12f, 13f, 14f),
            recorder.extractBufferedAudio(-5L, 10L),
            0f
        )
    }

    @Test
    fun oversizedChunkKeepsNewestSamples() {
        val recorder = testRecorder(bufferDurationMillis = 4L)

        recorder.startSession(startedAtMillis = 0L)
        recorder.onAudioChunk(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f), chunkStartMillis = 0L)

        assertArrayEquals(
            floatArrayOf(3f, 4f, 5f, 6f),
            recorder.extractBufferedAudio(0L, 10L),
            0f
        )
    }

    @Test
    fun onAudioChunkCopiesCallerArray() {
        val recorder = testRecorder(bufferDurationMillis = 10_000L)
        val chunk = floatArrayOf(1f, 2f, 3f)

        recorder.startSession(startedAtMillis = 0L)
        recorder.onAudioChunk(chunk, chunkStartMillis = 0L)
        chunk.fill(9f)

        assertArrayEquals(
            floatArrayOf(1f, 2f, 3f),
            recorder.extractBufferedAudio(0L, 3L),
            0f
        )
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
    fun invalidClampedBoundaryDoesNotEmitEpisodeMetadata() {
        val recorder = testRecorder()

        recorder.startSession(startedAtMillis = 0L)
        recorder.feedAudio(startMillis = 0L, durationMillis = 16_000L)
        recorder.onClassificationWindow(
            windowStartMillis = 10_000L,
            occurringGroups = listOf(groupResult(RecordedEventGroup.Snoring, 0.31f))
        )
        recorder.stopSession(endedAtMillis = 16_000L)

        assertTrue(recorder.completedEpisodeMetadata.isEmpty())
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

    private fun testRecorder(bufferDurationMillis: Long = 50_000L): AudioEventRecorder {
        return AudioEventRecorder(
            RecordedEventConfig(
                rollingBufferDurationMillis = bufferDurationMillis,
                sampleRate = 1_000
            )
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
}
