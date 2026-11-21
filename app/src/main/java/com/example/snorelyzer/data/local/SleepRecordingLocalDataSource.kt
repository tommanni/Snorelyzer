package com.example.snorelyzer.data.local

import androidx.room.withTransaction
import com.example.snorelyzer.ml.recording.AudioClipWriter
import com.example.snorelyzer.ml.recording.RecordingEpisodeMetadata
import com.example.snorelyzer.ml.recording.RecordingSessionMetadata
import java.io.IOException
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface SleepRecordingLocalDataSource {
    suspend fun upsertSession(session: RecordingSessionMetadata)
    suspend fun insertEpisode(episode: RecordingEpisodeMetadata)
    suspend fun completeSession(session: RecordingSessionMetadata)
    suspend fun deleteSession(sessionId: String)
    suspend fun deleteFinishedSessions(sessionIds: List<String>)
    suspend fun deleteAllSessions()
    fun observeSessions(): Flow<List<RecordingSessionWithEpisodes>>
    fun observeSleepNights(zoneId: ZoneId): Flow<List<SleepNight>>
    fun observeEpisodesForSession(sessionId: String): Flow<List<RecordingEpisodeWithDetails>>
}

class RoomSleepRecordingDataSource(
    private val database: SnorelyzerDatabase,
    private val audioClipWriter: AudioClipWriter
) : SleepRecordingLocalDataSource {
    private val dao = database.sleepRecordingDao

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

    override suspend fun deleteFinishedSessions(sessionIds: List<String>) {
        withContext(Dispatchers.IO + NonCancellable) {
            database.withTransaction {
                check(dao.countActiveSessions(sessionIds) == 0)
                val paths = dao.getAudioPaths(sessionIds)
                dao.deleteSessions(sessionIds)
                for (path in paths) {
                    if (!audioClipWriter.deleteClip(path)) {
                        throw IOException("Could not delete recording audio")
                    }
                }
            }
        }
    }

    override suspend fun deleteAllSessions() {
        if (!audioClipWriter.deleteAllClips()) {
            throw IOException("Could not delete all recording audio")
        }
        dao.deleteAllSessions()
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
