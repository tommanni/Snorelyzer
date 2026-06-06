package com.example.snorelyzer.data.local

import com.example.snorelyzer.ml.recording.EventSpanMetadata
import com.example.snorelyzer.ml.recording.RecordedEventGroup
import com.example.snorelyzer.ml.recording.RecordingEpisodeMetadata
import com.example.snorelyzer.ml.recording.RecordingSessionMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SleepRecordingMappersTest {
    @Test
    fun sessionMetadataMapsToEntity() {
        val entity = RecordingSessionMetadata(
            sessionId = "session-1",
            startedAtMillis = 1_000L,
            endedAtMillis = 2_000L,
            completedEpisodeCount = 3
        ).toEntity()

        assertEquals("session-1", entity.sessionId)
        assertEquals(1_000L, entity.startedAtMillis)
        assertEquals(2_000L, entity.endedAtMillis)
    }

    @Test
    fun episodeMetadataMapsWithoutProbabilities() {
        val episode = RecordingEpisodeMetadata(
            episodeId = "episode-1",
            sessionId = "session-1",
            clipStartMillis = 10_000L,
            clipEndMillis = 15_000L,
            groups = setOf(RecordedEventGroup.Snoring, RecordedEventGroup.Gasp),
            eventSpans = listOf(
                EventSpanMetadata(
                    group = RecordedEventGroup.Snoring,
                    startedAtMillis = 10_000L,
                    endedAtMillis = 12_000L,
                    peakProbability = 0.90f
                )
            ),
            peakProbabilities = mapOf(RecordedEventGroup.Snoring to 0.90f),
            sampleRate = 32_000,
            durationMillis = 5_000L,
            filePath = "/recordings/episode-1.wav"
        )

        val entity = episode.toEntity()
        val groups = episode.toGroupEntities()
        val spans = episode.toSpanEntities()

        assertEquals("episode-1", entity.episodeId)
        assertEquals("/recordings/episode-1.wav", entity.filePath)
        assertEquals(setOf(RecordedEventGroup.Snoring, RecordedEventGroup.Gasp), groups.map { it.group }.toSet())
        assertEquals(RecordedEventGroup.Snoring, spans.single().group)
        assertFalse(RecordingEpisodeEntity::class.java.declaredFields.any { it.name.contains("probability", ignoreCase = true) })
        assertFalse(EventSpanEntity::class.java.declaredFields.any { it.name.contains("probability", ignoreCase = true) })
    }
}
