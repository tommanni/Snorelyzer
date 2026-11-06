package com.example.snorelyzer.presentation.insights

import com.example.snorelyzer.data.local.EventSpanEntity
import com.example.snorelyzer.data.local.RecordingEpisodeEntity
import com.example.snorelyzer.data.local.RecordingEpisodeGroupEntity
import com.example.snorelyzer.data.local.RecordingEpisodeWithDetails
import com.example.snorelyzer.data.local.RecordingSessionEntity
import com.example.snorelyzer.data.local.RecordingSessionWithEpisodes
import com.example.snorelyzer.data.local.SleepNight
import com.example.snorelyzer.ml.recording.RecordedEventGroup
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAnalyticsTest {

    private val zoneId = ZoneId.of("UTC")

    @Test
    fun `quiet night with no episodes yields Quiet severity and zero snore score`() {
        val date = LocalDate.of(2026, 11, 6)
        val startMillis = 1762464000000L
        val night = SleepNight(
            date = date,
            sessions = listOf(
                RecordingSessionWithEpisodes(
                    session = RecordingSessionEntity(
                        sessionId = "s1",
                        startedAtMillis = startMillis,
                        endedAtMillis = startMillis + 28800000L // 8 hours
                    ),
                    episodes = emptyList()
                )
            )
        )

        val analytics = computeNightAnalytics(night, startMillis + 28800000L, zoneId)

        assertEquals(0, analytics.snoreScore)
        assertEquals(SnoreSeverity.Quiet, analytics.severity)
        assertEquals(0, analytics.totalEpisodes)
        assertEquals(0L, analytics.totalSnoreDurationMillis)
        assertEquals(0f, analytics.snorePercentage, 0.01f)
    }

    @Test
    fun `night with snoring and gasping calculates scores and hourly distribution`() {
        val date = LocalDate.of(2026, 11, 6)
        val startMillis = 1762464000000L // 2026-11-06T21:20:00Z approx
        val episode1 = RecordingEpisodeWithDetails(
            episode = RecordingEpisodeEntity(
                episodeId = "ep1",
                sessionId = "s1",
                clipStartMillis = startMillis + 3600000L,
                clipEndMillis = startMillis + 3600000L + 15000L,
                durationMillis = 15000L,
                sampleRate = 16000,
                filePath = "/path/ep1.wav"
            ),
            groups = listOf(
                RecordingEpisodeGroupEntity("ep1", RecordedEventGroup.Snoring),
                RecordingEpisodeGroupEntity("ep1", RecordedEventGroup.Gasp)
            ),
            eventSpans = listOf(
                EventSpanEntity(
                    id = 1L,
                    episodeId = "ep1",
                    group = RecordedEventGroup.Snoring,
                    startedAtMillis = startMillis + 3600000L,
                    endedAtMillis = startMillis + 3600000L + 10000L
                ),
                EventSpanEntity(
                    id = 2L,
                    episodeId = "ep1",
                    group = RecordedEventGroup.Gasp,
                    startedAtMillis = startMillis + 3600000L + 11000L,
                    endedAtMillis = startMillis + 3600000L + 14000L
                )
            )
        )

        val night = SleepNight(
            date = date,
            sessions = listOf(
                RecordingSessionWithEpisodes(
                    session = RecordingSessionEntity(
                        sessionId = "s1",
                        startedAtMillis = startMillis,
                        endedAtMillis = startMillis + 28800000L
                    ),
                    episodes = listOf(episode1)
                )
            )
        )

        val analytics = computeNightAnalytics(night, startMillis + 28800000L, zoneId)

        assertEquals(1, analytics.totalEpisodes)
        assertEquals(10000L, analytics.totalSnoreDurationMillis)
        assertTrue(analytics.snoreScore > 0)
        assertTrue(analytics.hourlyDistribution.isNotEmpty())
        assertNotNull(analytics.peakHourLabel)
        assertEquals(2, analytics.soundBreakdowns.size)
    }

    @Test
    fun `computeMultiNightAnalytics aggregates multiple nights properly`() {
        val date1 = LocalDate.of(2026, 11, 5)
        val date2 = LocalDate.of(2026, 11, 6)
        val startMillis1 = 1762377600000L
        val startMillis2 = 1762464000000L

        val night1 = SleepNight(
            date = date1,
            sessions = listOf(
                RecordingSessionWithEpisodes(
                    session = RecordingSessionEntity("s1", startMillis1, startMillis1 + 25200000L),
                    episodes = emptyList()
                )
            )
        )
        val night2 = SleepNight(
            date = date2,
            sessions = listOf(
                RecordingSessionWithEpisodes(
                    session = RecordingSessionEntity("s2", startMillis2, startMillis2 + 28800000L),
                    episodes = emptyList()
                )
            )
        )

        val multi = computeMultiNightAnalytics(listOf(night2, night1), zoneId)

        assertEquals(2, multi.totalNights)
        assertEquals(2, multi.nightTrends.size)
        assertEquals(0, multi.avgSnoreScore)
    }
}
