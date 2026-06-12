package com.example.snorelyzer.data.local

import com.example.snorelyzer.ml.recording.RecordingEpisodeMetadata
import com.example.snorelyzer.ml.recording.RecordingSessionMetadata
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface SleepRecordingLocalDataSource {
    suspend fun upsertSession(session: RecordingSessionMetadata)
    suspend fun insertEpisode(episode: RecordingEpisodeMetadata)
    suspend fun completeSession(session: RecordingSessionMetadata)
    suspend fun deleteSession(sessionId: String)
    fun observeSessions(): Flow<List<RecordingSessionWithEpisodes>>
    fun observeSleepNights(zoneId: ZoneId): Flow<List<SleepNight>>
    fun observeEpisodesForSession(sessionId: String): Flow<List<RecordingEpisodeWithDetails>>
}

class RoomSleepRecordingDataSource(
    private val dao: SleepRecordingDao
) : SleepRecordingLocalDataSource {
    override suspend fun upsertSession(session: RecordingSessionMetadata) {
        dao.upsertSession(session.toEntity())
    }

    override suspend fun insertEpisode(episode: RecordingEpisodeMetadata) {
        dao.upsertEpisodeWithDetails(
            episode = episode.toEntity(),
            groups = episode.toGroupEntities(),
            spans = episode.toSpanEntities()
        )
    }

    override suspend fun completeSession(session: RecordingSessionMetadata) {
        dao.upsertSession(session.toEntity())
    }

    override suspend fun deleteSession(sessionId: String) {
        dao.deleteSession(sessionId)
    }

    override fun observeSessions(): Flow<List<RecordingSessionWithEpisodes>> {
        return dao.observeSessions()
    }

    override fun observeSleepNights(zoneId: ZoneId): Flow<List<SleepNight>> {
        return observeSessions().map { sessions ->
            sessions.toSleepNights(zoneId)
        }
    }

    override fun observeEpisodesForSession(sessionId: String): Flow<List<RecordingEpisodeWithDetails>> {
        return dao.observeEpisodesForSession(sessionId)
    }
}
