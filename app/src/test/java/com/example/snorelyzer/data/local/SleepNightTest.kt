package com.example.snorelyzer.data.local

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertNull
import org.junit.Test

class SleepNightTest {
    private val zoneId: ZoneId = ZoneId.of("Europe/Helsinki")

    @Test
    fun sleepNightDateUsesNoonBoundary() {
        assertEquals(
            LocalDate.of(2026, 6, 8),
            sleepNightDateFor(millisAt(2026, 6, 9, 11, 59), zoneId)
        )
        assertEquals(
            LocalDate.of(2026, 6, 9),
            sleepNightDateFor(millisAt(2026, 6, 9, 12, 0), zoneId)
        )
        assertEquals(
            LocalDate.of(2026, 6, 9),
            sleepNightDateFor(millisAt(2026, 6, 9, 23, 0), zoneId)
        )
        assertEquals(
            LocalDate.of(2026, 6, 9),
            sleepNightDateFor(millisAt(2026, 6, 10, 9, 0), zoneId)
        )
    }

    @Test
    fun groupsRestartedRecordingsInSameNoonWindowIntoOneSleepNight() {
        val firstSession = session(
            id = "session-1",
            startedAtMillis = millisAt(2026, 6, 9, 22, 30),
            endedAtMillis = millisAt(2026, 6, 10, 7, 0)
        )
        val secondSession = session(
            id = "session-2",
            startedAtMillis = millisAt(2026, 6, 10, 7, 30),
            endedAtMillis = millisAt(2026, 6, 10, 8, 15)
        )

        val nights = listOf(secondSession, firstSession).toSleepNights(zoneId)

        assertEquals(1, nights.size)
        assertEquals(LocalDate.of(2026, 6, 9), nights.single().date)
        assertEquals(listOf("session-1", "session-2"), nights.single().sessions.map { it.session.sessionId })
    }

    @Test
    fun sessionsAcrossNoonBoundaryBecomeSeparateSleepNightsSortedDescending() {
        val mondayNight = session(
            id = "monday-night",
            startedAtMillis = millisAt(2026, 6, 10, 9, 0),
            endedAtMillis = millisAt(2026, 6, 10, 10, 0)
        )
        val tuesdayNight = session(
            id = "tuesday-night",
            startedAtMillis = millisAt(2026, 6, 10, 12, 0),
            endedAtMillis = millisAt(2026, 6, 10, 13, 0)
        )

        val nights = listOf(mondayNight, tuesdayNight).toSleepNights(zoneId)

        assertEquals(listOf(LocalDate.of(2026, 6, 10), LocalDate.of(2026, 6, 9)), nights.map { it.date })
    }

    @Test
    fun computedPropertiesSummarizeSessions() {
        val firstSession = session(
            id = "session-1",
            startedAtMillis = 1_000L,
            endedAtMillis = 3_000L,
            episodes = listOf(episode("episode-1", "session-1"))
        )
        val secondSession = session(
            id = "session-2",
            startedAtMillis = 5_000L,
            endedAtMillis = 8_000L,
            episodes = listOf(
                episode("episode-2", "session-2"),
                episode("episode-3", "session-2")
            )
        )

        val sleepNight = SleepNight(
            date = LocalDate.of(2026, 6, 9),
            sessions = listOf(secondSession, firstSession)
        )

        assertEquals(1_000L, sleepNight.startedAtMillis)
        assertEquals(8_000L, sleepNight.endedAtMillis)
        assertEquals(3, sleepNight.episodeCount)
        assertEquals(5_000L, sleepNight.totalRecordingDurationMillis(nowMillis = 10_000L))
    }

    @Test
    fun activeSessionMakesSleepNightActiveAndUsesNowForDuration() {
        val completedSession = session(
            id = "session-1",
            startedAtMillis = 1_000L,
            endedAtMillis = 3_000L
        )
        val activeSession = session(
            id = "session-2",
            startedAtMillis = 5_000L,
            endedAtMillis = null
        )

        val sleepNight = SleepNight(
            date = LocalDate.of(2026, 6, 9),
            sessions = listOf(completedSession, activeSession)
        )

        assertNull(sleepNight.endedAtMillis)
        assertEquals(7_000L, sleepNight.totalRecordingDurationMillis(nowMillis = 10_000L))
    }

    @Test
    fun sleepNightRequiresSessions() {
        try {
            SleepNight(
                date = LocalDate.of(2026, 6, 9),
                sessions = emptyList()
            )
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun millisAt(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int
    ): Long {
        return LocalDateTime.of(year, month, day, hour, minute)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
    }

    private fun session(
        id: String,
        startedAtMillis: Long,
        endedAtMillis: Long?,
        episodes: List<RecordingEpisodeEntity> = emptyList()
    ): RecordingSessionWithEpisodes {
        return RecordingSessionWithEpisodes(
            session = RecordingSessionEntity(
                sessionId = id,
                startedAtMillis = startedAtMillis,
                endedAtMillis = endedAtMillis
            ),
            episodes = episodes
        )
    }

    private fun episode(
        id: String,
        sessionId: String
    ): RecordingEpisodeEntity {
        return RecordingEpisodeEntity(
            episodeId = id,
            sessionId = sessionId,
            clipStartMillis = 0L,
            clipEndMillis = 1_000L,
            durationMillis = 1_000L,
            sampleRate = 32_000,
            filePath = null
        )
    }
}
