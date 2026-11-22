package com.example.snorelyzer.data.local

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.snorelyzer.ml.recording.EventSpanMetadata
import com.example.snorelyzer.ml.recording.RecordedEventGroup
import com.example.snorelyzer.ml.recording.RecordingEpisodeMetadata
import com.example.snorelyzer.ml.recording.RecordingSessionMetadata
import com.example.snorelyzer.ml.recording.WavAudioClipWriter
import java.io.File
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SleepRecordingDaoTest {
    private lateinit var database: SnorelyzerDatabase
    private lateinit var dataSource: RoomSleepRecordingDataSource
    private lateinit var recordings: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, SnorelyzerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        recordings = File(context.cacheDir, "retention-${System.nanoTime()}").apply { mkdirs() }
        dataSource = RoomSleepRecordingDataSource(database, WavAudioClipWriter(recordings))
    }

    @After
    fun tearDown() {
        database.close()
        recordings.deleteRecursively()
    }

    @Test
    fun expiresTheWholeNightFromItsLatestEndAndPreservesRecordingDetails() = runBlocking {
        val clips = (1..2).map { index ->
            val sessionStart = index * 10_000L
            val sessionId = "session-$sessionStart"
            val clipEnd = sessionStart + 5_000L
            val file = File(recordings, "$sessionId.wav").apply { writeBytes(byteArrayOf(1)) }
            dataSource.upsertSession(
                RecordingSessionMetadata(
                    sessionId, sessionStart, endedAtMillis = 16_000L.takeIf { index == 1 },
                    recordingZoneId = ZoneOffset.UTC
                )
            )
            dataSource.insertEpisode(
                RecordingEpisodeMetadata(
                    episodeId = "episode-$index",
                    sessionId = sessionId,
                    clipStartMillis = sessionStart,
                    clipEndMillis = clipEnd,
                    groups = setOf(RecordedEventGroup.Snoring),
                    eventSpans = listOf(EventSpanMetadata(RecordedEventGroup.Snoring, sessionStart, clipEnd, 0.9f)),
                    peakProbabilities = emptyMap(),
                    sampleRate = 32_000,
                    durationMillis = 5_000L,
                    filePath = file.absolutePath
                )
            )
            file
        }
        val untracked = File(recordings, "session-20000_session-20000-episode-2_25000_27000.wav")
            .apply { writeBytes(byteArrayOf(2)) }
        val before = dataSource.observeSleepNights(ZoneOffset.UTC).first().single()

        assertTrue(dataSource.expirableNights(cutoffMillis = 26_999L).isEmpty())
        val night = dataSource.expirableNights(cutoffMillis = 27_000L).single()
        assertEquals(before.date, night.date)
        assertTrue(dataSource.expireNightAudio(night))
        assertTrue(clips.none(File::exists))
        assertFalse(untracked.exists())
        assertTrue(dataSource.expirableNights(cutoffMillis = 27_000L).isEmpty())

        val after = dataSource.observeSleepNights(ZoneOffset.UTC).first().single()
        assertEquals(before.sessions.map { it.session }, after.sessions.map { it.session })
        before.allEpisodes.zip(after.allEpisodes).forEach { (original, retained) ->
            assertNull(retained.episode.filePath)
            assertTrue(retained.episode.audioExpired)
            assertEquals(original.episode.copy(filePath = null, audioExpired = true), retained.episode)
            assertEquals(original.groups, retained.groups)
            assertEquals(original.eventSpans, retained.eventSpans)
        }
    }

    @Test
    fun migrationAssignsLegacySessionsToANightAndKeepsTheirAudioAndEvents() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "${recordings.name}.db"
        val databaseFile = context.getDatabasePath(databaseName)
        databaseFile.parentFile?.mkdirs()
        val startedAt = 10_000L

        try {
            SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { oldDatabase ->
                oldDatabase.execSQL("CREATE TABLE recording_sessions (sessionId TEXT NOT NULL PRIMARY KEY, startedAtMillis INTEGER NOT NULL, endedAtMillis INTEGER)")
                oldDatabase.execSQL("""
                    CREATE TABLE recording_episodes (
                        episodeId TEXT NOT NULL PRIMARY KEY, sessionId TEXT NOT NULL, clipStartMillis INTEGER NOT NULL,
                        clipEndMillis INTEGER NOT NULL, durationMillis INTEGER NOT NULL, sampleRate INTEGER NOT NULL,
                        filePath TEXT, FOREIGN KEY(sessionId) REFERENCES recording_sessions(sessionId) ON DELETE CASCADE
                    )
                """.trimIndent())
                oldDatabase.execSQL("""
                    CREATE TABLE recording_episode_groups (
                        episodeId TEXT NOT NULL, `group` TEXT NOT NULL, PRIMARY KEY(episodeId, `group`),
                        FOREIGN KEY(episodeId) REFERENCES recording_episodes(episodeId) ON DELETE CASCADE
                    )
                """.trimIndent())
                oldDatabase.execSQL("""
                    CREATE TABLE event_spans (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, episodeId TEXT NOT NULL, `group` TEXT NOT NULL,
                        startedAtMillis INTEGER NOT NULL, endedAtMillis INTEGER NOT NULL,
                        FOREIGN KEY(episodeId) REFERENCES recording_episodes(episodeId) ON DELETE CASCADE
                    )
                """.trimIndent())
                oldDatabase.execSQL("CREATE INDEX index_recording_episodes_sessionId ON recording_episodes(sessionId)")
                oldDatabase.execSQL("CREATE INDEX index_recording_episode_groups_episodeId ON recording_episode_groups(episodeId)")
                oldDatabase.execSQL("CREATE INDEX index_event_spans_episodeId ON event_spans(episodeId)")
                oldDatabase.execSQL("INSERT INTO recording_sessions VALUES ('legacy', ?, 30000)", arrayOf(startedAt))
                oldDatabase.execSQL("INSERT INTO recording_episodes VALUES ('clip', 'legacy', 12000, 15000, 3000, 32000, '/recordings/clip.wav')")
                oldDatabase.execSQL("INSERT INTO recording_episode_groups VALUES ('clip', 'Snoring')")
                oldDatabase.execSQL("INSERT INTO event_spans VALUES (1, 'clip', 'Snoring', 12000, 15000)")
                oldDatabase.version = 1
            }

            val migrated = Room.databaseBuilder(context, SnorelyzerDatabase::class.java, databaseName)
                .addMigrations(RecordingRetentionMigration)
                .build()
            try {
                val source = RoomSleepRecordingDataSource(migrated, WavAudioClipWriter(recordings))
                val night = source.observeSleepNights(ZoneId.of("Pacific/Kiritimati")).first().single()
                val episode = night.allEpisodes.single()

                assertEquals(sleepNightDateFor(startedAt, ZoneId.systemDefault()), night.date)
                assertEquals(night.date.toEpochDay(), night.sessions.single().session.nightEpochDay)
                assertFalse(episode.episode.audioExpired)
                assertEquals("/recordings/clip.wav", episode.episode.filePath)
                assertEquals(RecordedEventGroup.Snoring, episode.groups.single().group)
                assertEquals(15_000L, episode.eventSpans.single().endedAtMillis)
            } finally {
                migrated.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
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
        assertEquals("episode-1", sessions.single().episodes.single().episode.episodeId)
        assertEquals(setOf(RecordedEventGroup.Snoring, RecordedEventGroup.Gasp), episodeDetails.groups.map { it.group }.toSet())
        assertEquals(2, episodeDetails.eventSpans.size)
        assertFalse(RecordingEpisodeEntity::class.java.declaredFields.any { it.name.contains("probability", ignoreCase = true) })
        assertFalse(EventSpanEntity::class.java.declaredFields.any { it.name.contains("probability", ignoreCase = true) })
    }

    @Test
    fun deletingSessionCascadesEpisodeGroupsAndSpans() = runBlocking {
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
                groups = setOf(RecordedEventGroup.Snoring),
                eventSpans = listOf(
                    EventSpanMetadata(
                        group = RecordedEventGroup.Snoring,
                        startedAtMillis = 10_000L,
                        endedAtMillis = 12_000L,
                        peakProbability = 0.90f
                    )
                ),
                peakProbabilities = mapOf(RecordedEventGroup.Snoring to 0.90f),
                sampleRate = 32_000,
                durationMillis = 5_000L,
                filePath = "/recordings/episode-1.wav"
            )
        )

        dataSource.deleteSession("session-1")

        assertEquals(emptyList<RecordingSessionWithEpisodes>(), dataSource.observeSessions().first())
        assertEquals(emptyList<RecordingEpisodeWithDetails>(), dataSource.observeEpisodesForSession("session-1").first())
    }
}
