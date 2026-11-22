package com.example.snorelyzer.data.local

import androidx.room.withTransaction
import com.example.snorelyzer.ml.recording.AudioClipWriter
import com.example.snorelyzer.ml.recording.RecordingEpisodeMetadata
import com.example.snorelyzer.ml.recording.RecordingSessionMetadata
import java.io.File
import java.io.IOException
import java.time.LocalDate
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
) : SleepRecordingLocalDataSource, RecordingAudioRetentionDataSource {
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

    override suspend fun expirableNights(cutoffMillis: Long, previousCutoffMillis: Long?): List<ExpirableNightAudio> {
        val clipsBySession = audioClipWriter.storedClips().groupBy { it.sessionId }
        val savedSessions = dao.sessionsForAudioRetention()
        val savedSessionIds = savedSessions.mapTo(mutableSetOf()) { it.sessionId }
        // A process can stop after writing a clip and before its session or episode reaches Room.
        val zoneId = ZoneId.systemDefault()
        val untrackedSessions = clipsBySession.filterKeys { it !in savedSessionIds }.map { (sessionId, clips) ->
            val start = clips.first().sessionStartMillis
            SessionForAudioRetention(sessionId, sleepNightDateFor(start, zoneId).toEpochDay(), start)
        }
        return (savedSessions + untrackedSessions).groupBy { it.nightEpochDay }.mapNotNull { (epochDay, sessions) ->
            val lastEnd = sessions.maxOf { session ->
                session.endedAtMillis ?: maxOf(
                    session.lastClipEndMillis ?: session.startedAtMillis,
                    clipsBySession[session.sessionId]?.maxOfOrNull { it.clipEndMillis } ?: session.startedAtMillis
                )
            }
            if (lastEnd > cutoffMillis || (previousCutoffMillis != null && lastEnd <= previousCutoffMillis)) {
                return@mapNotNull null
            }

            val storedPaths = if (sessions.any { it.hasAudioPaths }) dao.getNightAudioPaths(epochDay) else emptyList()
            val diskPaths = sessions.flatMap { session ->
                clipsBySession[session.sessionId].orEmpty().map { it.filePath }
            }
            val paths = (storedPaths + diskPaths).toSet()
            if (paths.isEmpty()) null else ExpirableNightAudio(LocalDate.ofEpochDay(epochDay), paths)
        }.sortedBy { it.date }
    }

    override suspend fun expireNightAudio(night: ExpirableNightAudio): Boolean = withContext(Dispatchers.IO + NonCancellable) {
        database.withTransaction {
            var allDeleted = true
            for (path in night.paths) {
                if (audioClipWriter.deleteClip(path)) {
                    dao.markAudioExpired(night.date.toEpochDay(), path)
                } else {
                    allDeleted = false
                }
            }
            allDeleted
        }
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

interface RecordingAudioRetentionDataSource {
    suspend fun expirableNights(cutoffMillis: Long, previousCutoffMillis: Long? = null): List<ExpirableNightAudio>
    suspend fun expireNightAudio(night: ExpirableNightAudio): Boolean
}

data class ExpirableNightAudio(val date: LocalDate, val paths: Set<String>) {
    fun hasFilesOnDisk(): Boolean = paths.any { File(it).isFile }
}

data class SessionForAudioRetention(
    val sessionId: String,
    val nightEpochDay: Long,
    val startedAtMillis: Long,
    val endedAtMillis: Long? = null,
    val lastClipEndMillis: Long? = null,
    val hasAudioPaths: Boolean = false
)
