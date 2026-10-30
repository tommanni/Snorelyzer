package com.example.snorelyzer.data.local

import androidx.room.Embedded
import androidx.room.Relation

data class RecordingSessionWithEpisodes(
    @Embedded val session: RecordingSessionEntity,
    @Relation(
        entity = RecordingEpisodeEntity::class,
        parentColumn = "sessionId",
        entityColumn = "sessionId"
    )
    val episodes: List<RecordingEpisodeWithDetails>
)

data class RecordingEpisodeWithDetails(
    @Embedded val episode: RecordingEpisodeEntity,
    @Relation(
        parentColumn = "episodeId",
        entityColumn = "episodeId"
    )
    val groups: List<RecordingEpisodeGroupEntity>,
    @Relation(
        parentColumn = "episodeId",
        entityColumn = "episodeId"
    )
    val eventSpans: List<EventSpanEntity>
)
