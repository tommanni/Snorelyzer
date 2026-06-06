package com.example.snorelyzer.data.local

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.snorelyzer.ml.recording.EventSpanMetadata
import com.example.snorelyzer.ml.recording.RecordedEventGroup
import com.example.snorelyzer.ml.recording.RecordingEpisodeMetadata
import com.example.snorelyzer.ml.recording.RecordingSessionMetadata
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class SleepRecordingDaoTest {
    private lateinit var database: SnorelyzerDatabase
    private lateinit var dataSource: SleepRecordingLocalDataSource

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, SnorelyzerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dataSource = RoomSleepRecordingDataSource(database.sleepRecordingDao)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun persistsSessionEpisodeGroupsAndSpans() = runBlocking {
        dataSource.upsertSession(
            RecordingSessionMetadata(
                sessionId = "session-1",
                startedAtMillis = 1_000L
            )
        )

        dataSource.insertEpisode(
            RecordingEpisodeMetadata(
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
                    ),
                    EventSpanMetadata(
                        group = RecordedEventGroup.Gasp,
                        startedAtMillis = 11_000L,
                        endedAtMillis = 13_000L,
                        peakProbability = 0.80f
                    )
                ),
                peakProbabilities = mapOf(
                    RecordedEventGroup.Snoring to 0.90f,
                    RecordedEventGroup.Gasp to 0.80f
                ),
                sampleRate = 32_000,
                durationMillis = 5_000L,
                filePath = "/recordings/episode-1.wav"
            )
        )

        dataSource.completeSession(
            RecordingSessionMetadata(
                sessionId = "session-1",
                startedAtMillis = 1_000L,
                endedAtMillis = 20_000L,
                completedEpisodeCount = 1
            )
        )

        val sessions = dataSource.observeSessions().first()
        val episodeDetails = dataSource.observeEpisodesForSession("session-1").first().single()

        assertEquals(20_000L, sessions.single().session.endedAtMillis)
        assertEquals("episode-1", sessions.single().episodes.single().episodeId)
        assertEquals(setOf(RecordedEventGroup.Snoring, RecordedEventGroup.Gasp), episodeDetails.groups.map { it.group }.toSet())
        assertEquals(2, episodeDetails.eventSpans.size)
        assertFalse(RecordingEpisodeEntity::class.java.declaredFields.any { it.name.contains("probability", ignoreCase = true) })
        assertFalse(EventSpanEntity::class.java.declaredFields.any { it.name.contains("probability", ignoreCase = true) })
    }
}
