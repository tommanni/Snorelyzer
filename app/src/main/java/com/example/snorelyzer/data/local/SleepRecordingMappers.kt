package com.example.snorelyzer.data.local

import com.example.snorelyzer.ml.recording.EventSpanMetadata
import com.example.snorelyzer.ml.recording.RecordingEpisodeMetadata
import com.example.snorelyzer.ml.recording.RecordingSessionMetadata

fun RecordingSessionMetadata.toEntity(): RecordingSessionEntity {
    return RecordingSessionEntity(
        sessionId = sessionId,
        startedAtMillis = startedAtMillis,
        endedAtMillis = endedAtMillis
    )
}

fun RecordingEpisodeMetadata.toEntity(): RecordingEpisodeEntity {
    return RecordingEpisodeEntity(
        episodeId = episodeId,
        sessionId = sessionId,
        clipStartMillis = clipStartMillis,
        clipEndMillis = clipEndMillis,
        durationMillis = durationMillis,
        sampleRate = sampleRate,
        filePath = filePath
    )
}

fun RecordingEpisodeMetadata.toGroupEntities(): List<RecordingEpisodeGroupEntity> {
    return groups.map { group ->
        RecordingEpisodeGroupEntity(
            episodeId = episodeId,
            group = group
        )
    }
}

fun RecordingEpisodeMetadata.toSpanEntities(): List<EventSpanEntity> {
    return eventSpans.map { span ->
        span.toEntity(episodeId)
    }
}

private fun EventSpanMetadata.toEntity(episodeId: String): EventSpanEntity {
    return EventSpanEntity(
        episodeId = episodeId,
        group = group,
        startedAtMillis = startedAtMillis,
        endedAtMillis = endedAtMillis
    )
}
