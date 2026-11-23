package com.example.snorelyzer.presentation.insights

import androidx.annotation.StringRes
import com.example.snorelyzer.R
import com.example.snorelyzer.data.local.SleepNight
import com.example.snorelyzer.ml.recording.RecordedEventGroup
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

enum class SnoreSeverity(@field:StringRes val titleRes: Int) {
    Quiet(R.string.insights_sound_severity_quiet),
    Mild(R.string.insights_sound_severity_low),
    Moderate(R.string.insights_sound_severity_moderate),
    Severe(R.string.insights_sound_severity_high)
}

data class SoundGroupBreakdown(
    val group: RecordedEventGroup,
    val count: Int,
    val percentageOfEvents: Float
)

data class HourlyDistributionItem(
    val hourStartMillis: Long,
    val totalEventsCount: Int,
    val dominantGroup: RecordedEventGroup?,
    val intensityFraction: Float
)

data class NightAnalytics(
    val totalSleepDurationMillis: Long,
    val totalSnoreDurationMillis: Long,
    val snorePercentage: Float,
    val snoreScore: Int,
    val severity: SnoreSeverity,
    val totalEpisodes: Int,
    val disturbanceRatePerHour: Float,
    val busiestHourStartMillis: Long?,
    val soundBreakdowns: List<SoundGroupBreakdown>,
    val hourlyDistribution: List<HourlyDistributionItem>
)

data class NightTrendItem(
    val date: LocalDate,
    val dayLabel: String,
    val dateLabel: String,
    val snoreScore: Int,
    val snoreDurationMillis: Long,
    val totalSleepDurationMillis: Long,
    val episodeCount: Int,
    val severity: SnoreSeverity
)

data class MultiNightAnalytics(
    val totalNights: Int,
    val avgSleepDurationMillis: Long,
    val avgSnoreDurationMillis: Long,
    val avgSnoreScore: Int,
    val overallSeverity: SnoreSeverity,
    val soundTotals: Map<RecordedEventGroup, Int>,
    val nightTrends: List<NightTrendItem>
)

fun computeNightAnalytics(
    night: SleepNight,
    nowMillis: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault()
): NightAnalytics {
    val totalSleepDuration = night.totalRecordingDurationMillis(nowMillis).coerceAtLeast(1L)
    val allEpisodes = night.allEpisodes
    val totalEpisodes = allEpisodes.size

    var snoreDurationMillis = 0L
    val groupCounts = mutableMapOf<RecordedEventGroup, Int>()
    var gaspCount = 0

    allEpisodes.forEach { episodeDetails ->
        val episode = episodeDetails.episode
        val spans = episodeDetails.eventSpans

        if (spans.isNotEmpty()) {
            spans.forEach { span ->
                val spanDuration = (span.endedAtMillis - span.startedAtMillis).coerceAtLeast(0L)
                groupCounts[span.group] = (groupCounts[span.group] ?: 0) + 1
                if (span.group == RecordedEventGroup.Snoring) {
                    snoreDurationMillis += spanDuration
                }
                if (span.group == RecordedEventGroup.Gasp) {
                    gaspCount++
                }
            }
        } else {
            episodeDetails.groups.forEach { groupEntity ->
                val grp = groupEntity.group
                groupCounts[grp] = (groupCounts[grp] ?: 0) + 1
                if (grp == RecordedEventGroup.Snoring) {
                    snoreDurationMillis += episode.durationMillis
                }
                if (grp == RecordedEventGroup.Gasp) {
                    gaspCount++
                }
            }
        }
    }

    val snoreRatio = (snoreDurationMillis.toFloat() / totalSleepDuration).coerceIn(0f, 1f)
    val snorePercentage = snoreRatio * 100f

    val durationHours = (totalSleepDuration / 3600000.0).coerceAtLeast(0.5)
    val disturbanceRatePerHour = (totalEpisodes / durationHours).toFloat()
    val gaspsPerHour = (gaspCount / durationHours).toFloat()

    val percentScore = (snorePercentage * 1.0f).coerceAtMost(50f)
    val gaspPenalty = (gaspsPerHour * 5.0f).coerceAtMost(30f)
    val rateScore = (disturbanceRatePerHour * 2.5f).coerceAtMost(20f)
    val snoreScore = (percentScore + gaspPenalty + rateScore).roundToInt().coerceIn(0, 100)

    val severity = when {
        snoreScore <= 15 -> SnoreSeverity.Quiet
        snoreScore <= 35 -> SnoreSeverity.Mild
        snoreScore <= 60 -> SnoreSeverity.Moderate
        else -> SnoreSeverity.Severe
    }

    val totalGroupHits = groupCounts.values.sum().coerceAtLeast(1)
    val soundBreakdowns = RecordedEventGroup.entries.mapNotNull { group ->
        val count = groupCounts[group] ?: 0
        if (count > 0) {
            SoundGroupBreakdown(
                group = group,
                count = count,
                percentageOfEvents = (count.toFloat() / totalGroupHits) * 100f
            )
        } else null
    }

    // Compute hourly timeline distribution
    val hourlyItems = mutableListOf<HourlyDistributionItem>()
    val startMillis = night.startedAtMillis
    val endMillis = night.endedAtMillis ?: (startMillis + totalSleepDuration)

    val zonedDateTimeStart = Instant.ofEpochMilli(startMillis).atZone(zoneId)
    var cursor = zonedDateTimeStart.withMinute(0).withSecond(0).withNano(0)

    var maxHourCount = 0
    while (cursor.toInstant().toEpochMilli() <= endMillis) {
        val bucketStart = cursor.toInstant().toEpochMilli()
        val bucketEnd = cursor.plusHours(1).toInstant().toEpochMilli()

        val episodesInHour = allEpisodes.filter { ep ->
            ep.episode.clipStartMillis in bucketStart until bucketEnd
        }

        val hourGroupCounts = mutableMapOf<RecordedEventGroup, Int>()

        episodesInHour.forEach { ep ->
            ep.groups.forEach { g ->
                hourGroupCounts[g.group] = (hourGroupCounts[g.group] ?: 0) + 1
            }
        }

        val totalHourEvents = episodesInHour.size
        if (totalHourEvents > maxHourCount) {
            maxHourCount = totalHourEvents
        }

        val dominant = hourGroupCounts.maxByOrNull { it.value }?.key

        hourlyItems.add(
            HourlyDistributionItem(
                hourStartMillis = bucketStart,
                totalEventsCount = totalHourEvents,
                dominantGroup = dominant,
                intensityFraction = 0f // Normalized below
            )
        )
        cursor = cursor.plusHours(1)
    }

    val normalizedHourly = hourlyItems.map { item ->
        val fraction = if (maxHourCount > 0) {
            (item.totalEventsCount.toFloat() / maxHourCount).coerceIn(0f, 1f)
        } else 0f
        item.copy(intensityFraction = fraction)
    }

    val busiestHourStartMillis = normalizedHourly
        .maxByOrNull { it.totalEventsCount }
        ?.takeIf { it.totalEventsCount > 0 }
        ?.hourStartMillis

    return NightAnalytics(
        totalSleepDurationMillis = totalSleepDuration,
        totalSnoreDurationMillis = snoreDurationMillis,
        snorePercentage = snorePercentage,
        snoreScore = snoreScore,
        severity = severity,
        totalEpisodes = totalEpisodes,
        disturbanceRatePerHour = disturbanceRatePerHour,
        busiestHourStartMillis = busiestHourStartMillis,
        soundBreakdowns = soundBreakdowns,
        hourlyDistribution = normalizedHourly
    )
}

fun computeMultiNightAnalytics(
    nights: List<SleepNight>,
    zoneId: ZoneId = ZoneId.systemDefault()
): MultiNightAnalytics {
    if (nights.isEmpty()) {
        return MultiNightAnalytics(
            totalNights = 0,
            avgSleepDurationMillis = 0L,
            avgSnoreDurationMillis = 0L,
            avgSnoreScore = 0,
            overallSeverity = SnoreSeverity.Quiet,
            soundTotals = emptyMap(),
            nightTrends = emptyList()
        )
    }

    val nowMillis = System.currentTimeMillis()
    val nightAnalyticsList = nights.map { night ->
        val analytics = computeNightAnalytics(night, nowMillis, zoneId)
        val dayOfWeek = night.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        val dateFormatter = DateTimeFormatter.ofPattern("M/d", Locale.getDefault())
        NightTrendItem(
            date = night.date,
            dayLabel = dayOfWeek,
            dateLabel = night.date.format(dateFormatter),
            snoreScore = analytics.snoreScore,
            snoreDurationMillis = analytics.totalSnoreDurationMillis,
            totalSleepDurationMillis = analytics.totalSleepDurationMillis,
            episodeCount = analytics.totalEpisodes,
            severity = analytics.severity
        )
    }

    val totalNights = nights.size
    val avgSleepDuration = nightAnalyticsList.map { it.totalSleepDurationMillis }.average().toLong()
    val avgSnoreDuration = nightAnalyticsList.map { it.snoreDurationMillis }.average().toLong()
    val avgScore = nightAnalyticsList.map { it.snoreScore }.average().roundToInt()

    val overallSeverity = when {
        avgScore <= 15 -> SnoreSeverity.Quiet
        avgScore <= 35 -> SnoreSeverity.Mild
        avgScore <= 60 -> SnoreSeverity.Moderate
        else -> SnoreSeverity.Severe
    }

    val soundTotals = mutableMapOf<RecordedEventGroup, Int>()
    nights.forEach { night ->
        night.allEpisodes.forEach { ep ->
            ep.groups.forEach { g ->
                soundTotals[g.group] = (soundTotals[g.group] ?: 0) + 1
            }
        }
    }

    return MultiNightAnalytics(
        totalNights = totalNights,
        avgSleepDurationMillis = avgSleepDuration,
        avgSnoreDurationMillis = avgSnoreDuration,
        avgSnoreScore = avgScore,
        overallSeverity = overallSeverity,
        soundTotals = soundTotals,
        // Order oldest to newest for trend graphs (left to right)
        nightTrends = nightAnalyticsList.take(7).reversed()
    )
}
