package com.example.snorelyzer.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.snorelyzer.ml.recording.RecordedEventGroup

@Entity(tableName = "recording_sessions")
data class RecordingSessionEntity(
    @PrimaryKey val sessionId: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long?
)

@Entity(
    tableName = "recording_episodes",
    foreignKeys = [
        ForeignKey(
            entity = RecordingSessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class RecordingEpisodeEntity(
    @PrimaryKey val episodeId: String,
    val sessionId: String,
    val clipStartMillis: Long,
    val clipEndMillis: Long,
    val durationMillis: Long,
    val sampleRate: Int,
    val filePath: String?
)

@Entity(
    tableName = "recording_episode_groups",
    primaryKeys = ["episodeId", "group"],
    foreignKeys = [
        ForeignKey(
            entity = RecordingEpisodeEntity::class,
            parentColumns = ["episodeId"],
            childColumns = ["episodeId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("episodeId")]
)
data class RecordingEpisodeGroupEntity(
    val episodeId: String,
    val group: RecordedEventGroup
)

@Entity(
    tableName = "event_spans",
    foreignKeys = [
        ForeignKey(
            entity = RecordingEpisodeEntity::class,
            parentColumns = ["episodeId"],
            childColumns = ["episodeId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("episodeId")]
)
data class EventSpanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val episodeId: String,
    val group: RecordedEventGroup,
    val startedAtMillis: Long,
    val endedAtMillis: Long
)
