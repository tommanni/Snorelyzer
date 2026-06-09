package com.example.snorelyzer.data.local

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class SleepNight(
    val date: LocalDate,
    val sessions: List<RecordingSessionWithEpisodes>
) {
    init {
        require(sessions.isNotEmpty()) {
            "SleepNight must contain at least one recording session"
        }
    }

    val startedAtMillis: Long
        get() = sessions.minOf { it.session.startedAtMillis }

    val endedAtMillis: Long?
        get() {
            if (sessions.any { it.session.endedAtMillis == null }) return null
            return sessions.maxOf { checkNotNull(it.session.endedAtMillis) }
        }

    val episodeCount: Int
        get() = sessions.sumOf { it.episodes.size }

    fun totalRecordingDurationMillis(nowMillis: Long): Long {
        return sessions.sumOf { sessionWithEpisodes ->
            val session = sessionWithEpisodes.session
            val sessionEndedAtMillis = session.endedAtMillis ?: nowMillis
            sessionEndedAtMillis - session.startedAtMillis
        }
    }
}

fun sleepNightDateFor(startedAtMillis: Long, zoneId: ZoneId): LocalDate {
    return Instant.ofEpochMilli(startedAtMillis)
        .atZone(zoneId)
        .minusHours(12)
        .toLocalDate()
}

fun List<RecordingSessionWithEpisodes>.toSleepNights(zoneId: ZoneId): List<SleepNight> {
    return groupBy { sleepNightDateFor(it.session.startedAtMillis, zoneId) }
        .map { (date, sessions) ->
            SleepNight(
                date = date,
                sessions = sessions.sortedBy { it.session.startedAtMillis }
            )
        }
        .sortedByDescending { it.date }
}
