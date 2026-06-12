package com.example.snorelyzer.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface SleepRecordingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: RecordingSessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEpisode(episode: RecordingEpisodeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroups(groups: List<RecordingEpisodeGroupEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEventSpans(spans: List<EventSpanEntity>)

    @Query("DELETE FROM recording_episode_groups WHERE episodeId = :episodeId")
    suspend fun deleteGroupsForEpisode(episodeId: String)

    @Query("DELETE FROM event_spans WHERE episodeId = :episodeId")
    suspend fun deleteSpansForEpisode(episodeId: String)

    @Query("DELETE FROM recording_sessions WHERE sessionId = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Transaction
    suspend fun upsertEpisodeWithDetails(
        episode: RecordingEpisodeEntity,
        groups: List<RecordingEpisodeGroupEntity>,
        spans: List<EventSpanEntity>
    ) {
        upsertEpisode(episode)
        deleteGroupsForEpisode(episode.episodeId)
        deleteSpansForEpisode(episode.episodeId)
        if (groups.isNotEmpty()) {
            insertGroups(groups)
        }
        if (spans.isNotEmpty()) {
            insertEventSpans(spans)
        }
    }

    @Transaction
    @Query("SELECT * FROM recording_sessions ORDER BY startedAtMillis DESC")
    fun observeSessions(): Flow<List<RecordingSessionWithEpisodes>>

    @Transaction
    @Query("SELECT * FROM recording_episodes WHERE sessionId = :sessionId ORDER BY clipStartMillis ASC")
    fun observeEpisodesForSession(sessionId: String): Flow<List<RecordingEpisodeWithDetails>>
}
