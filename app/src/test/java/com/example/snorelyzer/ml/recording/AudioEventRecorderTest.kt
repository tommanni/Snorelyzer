package com.example.snorelyzer.ml.recording

import org.junit.Assert.assertEquals
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
        val recorder = AudioEventRecorder()

        recorder.startSession(startedAtMillis = 1_000L)
        recorder.onClassificationWindow(
            windowStartMillis = 5_000L,
            occurringGroups = listOf(groupResult(RecordedEventGroup.Snoring, 0.31f))
        )
        recorder.stopSession(endedAtMillis = 10_000L)

        val episode = recorder.completedEpisodeMetadata.single()
        assertEquals(3_000L, episode.clipStartMillis)
        assertEquals(9_000L, episode.clipEndMillis)
        assertEquals(setOf(RecordedEventGroup.Snoring), episode.groups)
    }

    @Test
    fun eventSpanClosesWhenGroupDropsBelowThresholdUsingPostPad() {
        val recorder = AudioEventRecorder()

        recorder.startSession(startedAtMillis = 0L)
        recorder.onClassificationWindow(
            windowStartMillis = 10_000L,
            occurringGroups = listOf(groupResult(RecordedEventGroup.Cough, 0.30f))
        )
        recorder.onClassificationWindow(
            windowStartMillis = 11_000L,
            occurringGroups = emptyList()
        )
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
        val recorder = AudioEventRecorder()

        recorder.startSession(startedAtMillis = 0L)
        recorder.onClassificationWindow(
            windowStartMillis = 10_000L,
            occurringGroups = listOf(groupResult(RecordedEventGroup.Snoring, 0.31f))
        )
        recorder.onClassificationWindow(
            windowStartMillis = 29_000L,
            occurringGroups = emptyList()
        )
        assertTrue(recorder.completedEpisodeMetadata.isEmpty())

        recorder.onClassificationWindow(
            windowStartMillis = 30_000L,
            occurringGroups = emptyList()
        )

        val episode = recorder.completedEpisodeMetadata.single()
        assertEquals(8_000L, episode.clipStartMillis)
        assertEquals(14_000L, episode.clipEndMillis)
    }

    @Test
    fun episodeMergesGroupsAndDominantGroupUsesPeakProbability() {
        val recorder = AudioEventRecorder()

        recorder.startSession(startedAtMillis = 0L)
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
}
