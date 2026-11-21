package com.example.snorelyzer.data.local

import com.example.snorelyzer.ml.recording.RecordingEpisodeMetadata
import com.example.snorelyzer.ml.recording.RecordingSessionMetadata
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomRecordingMetadataSinkTest {
    @Test
    fun episodeWriteFailureIsReportedAndSessionCanStillComplete() = runBlocking {
        val dataSource = FailingEpisodeDataSource()
        val sink = RoomRecordingMetadataSink(dataSource)
        val episodeFailed = CompletableDeferred<Unit>()
        val session = RecordingSessionMetadata(sessionId = "session-0", startedAtMillis = 0L)
        val endedSession = session.copy(endedAtMillis = 5_000L)
        sink.onEpisodeCompleted(
            RecordingEpisodeMetadata(
                episodeId = "episode-1",
                sessionId = session.sessionId,
                clipStartMillis = 0,
                clipEndMillis = 5_000L,
                groups = emptySet(),
                eventSpans = emptyList(),
                peakProbabilities = emptyMap(),
                sampleRate = 32_000,
                durationMillis = 5_000L
            )
        ) { episodeFailed.complete(Unit) }
        sink.onSessionCompleted(endedSession) { error("Unexpected session completion failure") }

        val completedSession = withTimeout(5_000L) { dataSource.completedSession.await() }
        assertTrue(episodeFailed.isCompleted)
        assertEquals(endedSession, completedSession)
    }

    private class FailingEpisodeDataSource : SleepRecordingLocalDataSource {
        val completedSession = CompletableDeferred<RecordingSessionMetadata>()

        override suspend fun upsertSession(session: RecordingSessionMetadata) = Unit

        override suspend fun insertEpisode(episode: RecordingEpisodeMetadata) {
            error("Episode insert failed")
        }

        override suspend fun completeSession(session: RecordingSessionMetadata) {
            completedSession.complete(session)
        }

        override suspend fun deleteSession(sessionId: String) = Unit

        override suspend fun deleteFinishedSessions(sessionIds: List<String>) = error("Unexpected deletion")

        override suspend fun deleteAllSessions() = Unit

        override fun observeSessions() = emptyFlow<List<RecordingSessionWithEpisodes>>()

        override fun observeSleepNights(zoneId: ZoneId) = emptyFlow<List<SleepNight>>()

        override fun observeEpisodesForSession(sessionId: String) =
            emptyFlow<List<RecordingEpisodeWithDetails>>()
    }
}
