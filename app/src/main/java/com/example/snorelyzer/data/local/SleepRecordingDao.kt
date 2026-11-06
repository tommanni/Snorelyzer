package com.example.snorelyzer.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SleepRecordingDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSession(session: RecordingSessionEntity): Long

    @Update
    suspend fun updateSession(session: RecordingSessionEntity)

    @Transaction
    suspend fun upsertSession(session: RecordingSessionEntity) {
        val id = insertSession(session)
        if (id == -1L) {
            updateSession(session)
        }
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEpisode(episode: RecordingEpisodeEntity): Long

    @Update
    suspend fun updateEpisode(episode: RecordingEpisodeEntity)

    @Transaction
    suspend fun upsertEpisode(episode: RecordingEpisodeEntity) {
        val id = insertEpisode(episode)
        if (id == -1L) {
            updateEpisode(episode)
        }
    }

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

    @Query("DELETE FROM recording_sessions")
    suspend fun deleteAllSessions()

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
