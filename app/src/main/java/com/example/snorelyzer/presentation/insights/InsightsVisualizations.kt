package com.example.snorelyzer.presentation.insights

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.snorelyzer.ml.recording.RecordedEventGroup
import com.example.snorelyzer.ui.theme.SnorelyzerTheme
import java.time.LocalDate
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SessionAnalyticsHeroCard(
    analytics: NightAnalytics,
    formattedDate: String,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header: Date & Sleep Night title
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Sleep Night Analytics",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = formattedDate,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.Bedtime,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            // Snore Score & Disturbance Hero section
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularScoreMeter(
                    score = analytics.snoreScore,
                    severity = analytics.severity,
                    modifier = Modifier.size(76.dp)
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Snore Score",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                        SnoreSeverityBadge(severity = analytics.severity)
                    }

                    Text(
                        text = analytics.severity.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Text(
                        text = analytics.severity.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Key Metrics 4-tile grid
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MetricTile(
                        label = "Snoring Time",
                        value = formatDuration(analytics.totalSnoreDurationMillis),
                        subtext = "${String.format(Locale.US, "%.1f", analytics.snorePercentage)}% of sleep",
                        icon = Icons.Outlined.Speed,
                        modifier = Modifier.weight(1f)
                    )
                    MetricTile(
                        label = "Sleep Duration",
                        value = formatDuration(analytics.totalSleepDurationMillis),
                        subtext = "${analytics.totalEpisodes} episodes recorded",
                        icon = Icons.Outlined.Schedule,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MetricTile(
                        label = "Disturbance Rate",
                        value = "${String.format(Locale.US, "%.1f", analytics.disturbanceRatePerHour)}/h",
                        subtext = "episodes per hour",
                        icon = Icons.Outlined.GraphicEq,
                        modifier = Modifier.weight(1f)
                    )
                    MetricTile(
                        label = "Peak Snoring",
                        value = analytics.peakHourLabel ?: "None",
                        subtext = if (analytics.longestEpisodeMillis > 0) {
                            "Longest: ${analytics.longestEpisodeMillis / 1000}s"
                        } else "No sound hits",
                        icon = Icons.AutoMirrored.Outlined.TrendingUp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Night Acoustic Timeline (Hourly Histogram)
            if (analytics.hourlyDistribution.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Acoustic Timeline",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (analytics.peakHourLabel != null) {
                            Text(
                                text = "Peak at ${analytics.peakHourLabel}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    AcousticHourlyTimeline(
                        hourlyItems = analytics.hourlyDistribution,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Sound Composition Segmented Bar & Legend
            if (analytics.soundBreakdowns.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Sound Composition",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    SoundCompositionMeter(
                        breakdowns = analytics.soundBreakdowns,
                        modifier = Modifier.fillMaxWidth()
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        analytics.soundBreakdowns.forEach { breakdown ->
                            SoundBreakdownPill(breakdown = breakdown)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CircularScoreMeter(
    score: Int,
    severity: SnoreSeverity,
    modifier: Modifier = Modifier
) {
    val progress = (score / 100f).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing),
        label = "score_progress"
    )

    val (meterColor, _) = severityColors(severity)
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 7.dp.toPx()
            // Track
            drawArc(
                color = trackColor,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
            // Progress
            drawArc(
                color = meterColor,
                startAngle = 135f,
                sweepAngle = 270f * animatedProgress,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = score.toString(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "/100",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun SnoreSeverityBadge(
    severity: SnoreSeverity,
    modifier: Modifier = Modifier
) {
    val (containerColor, contentColor) = severityColors(severity)

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = containerColor
    ) {
        Text(
            text = severity.title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    subtext: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtext,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun AcousticHourlyTimeline(
    hourlyItems: List<HourlyDistributionItem>,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(12.dp)
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        hourlyItems.forEach { item ->
            HourlyTimelineBar(item = item)
        }
    }
}

@Composable
private fun HourlyTimelineBar(
    item: HourlyDistributionItem,
    modifier: Modifier = Modifier
) {
    val animatedFraction by animateFloatAsState(
        targetValue = item.intensityFraction,
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label = "bar_height"
    )

    val (barColor, _) = item.dominantGroup?.let { groupColor(it) }
        ?: Pair(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)

    val hasHits = item.totalEventsCount > 0

    Column(
        modifier = modifier.width(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (hasHits) {
            Text(
                text = item.totalEventsCount.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
        } else {
            Spacer(modifier = Modifier.height(14.dp))
        }

        // Bar container
        Box(
            modifier = Modifier
                .width(16.dp)
                .height(64.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.BottomCenter
        ) {
            if (hasHits) {
                val barHeightDp = (58 * animatedFraction).coerceAtLeast(6f).dp
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(barHeightDp)
                        .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 4.dp, bottomEnd = 4.dp))
                        .background(barColor)
                )
            }
        }

        Text(
            text = item.hourLabel,
            style = MaterialTheme.typography.labelSmall,
            color = if (hasHits) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
            fontWeight = if (hasHits) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1
        )
    }
}

@Composable
fun SoundCompositionMeter(
    breakdowns: List<SoundGroupBreakdown>,
    modifier: Modifier = Modifier
) {
    if (breakdowns.isEmpty()) return

    Row(
        modifier = modifier
            .height(14.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        breakdowns.forEach { breakdown ->
            val weight = breakdown.percentageOfEvents.coerceAtLeast(1f)
            val (containerColor, _) = groupColor(breakdown.group)
            Box(
                modifier = Modifier
                    .weight(weight)
                    .fillMaxHeight()
                    .background(containerColor)
            )
        }
    }
}

@Composable
private fun SoundBreakdownPill(
    breakdown: SoundGroupBreakdown,
    modifier: Modifier = Modifier
) {
    val (containerColor, contentColor) = groupColor(breakdown.group)
    val pct = String.format(Locale.US, "%.0f", breakdown.percentageOfEvents)
    val duration = formatDuration(breakdown.durationMillis)

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = containerColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "${groupEmoji(breakdown.group)} ${breakdown.group.name}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = contentColor
            )
            Text(
                text = "$duration ($pct%)",
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.85f)
            )
        }
    }
}

@Composable
fun MultiNightTrendCard(
    analytics: MultiNightAnalytics,
    selectedDate: LocalDate?,
    onSelectDate: (LocalDate) -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Weekly Trends",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Snore Severity History",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                SnoreSeverityBadge(severity = analytics.overallSeverity)
            }

            // Trend Column Bar Chart
            if (analytics.nightTrends.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.Bottom
                ) {
                    analytics.nightTrends.forEach { trendItem ->
                        val isSelected = trendItem.date == selectedDate
                        TrendBarColumn(
                            item = trendItem,
                            isSelected = isSelected,
                            onClick = { onSelectDate(trendItem.date) }
                        )
                    }
                }
            }

            // Summary metrics row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OverviewMetricItem(
                    label = "Avg. Score",
                    value = "${analytics.avgSnoreScore}/100"
                )
                OverviewMetricItem(
                    label = "Avg. Snore Time",
                    value = formatDuration(analytics.avgSnoreDurationMillis)
                )
                OverviewMetricItem(
                    label = "Avg. Sleep Time",
                    value = formatDuration(analytics.avgSleepDurationMillis)
                )
            }
        }
    }
}

@Composable
private fun TrendBarColumn(
    item: NightTrendItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val fraction = (item.snoreScore / 100f).coerceIn(0.08f, 1f)
    val animatedHeight by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label = "trend_bar_height"
    )

    val (barColor, _) = severityColors(item.severity)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = item.snoreScore.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )

        Box(
            modifier = Modifier
                .width(18.dp)
                .height(72.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (isSelected) {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    }
                ),
            contentAlignment = Alignment.BottomCenter
        ) {
            val columnHeight = (68 * animatedHeight).dp
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(columnHeight)
                    .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 4.dp, bottomEnd = 4.dp))
                    .background(if (isSelected) MaterialTheme.colorScheme.primary else barColor)
            )
        }

        Text(
            text = item.dayLabel,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun SleepHealthInsightsCard(
    analytics: MultiNightAnalytics,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.HealthAndSafety,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Column {
                    Text(
                        text = "Sleep Health & Hygiene",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Actionable acoustic sleep guidance",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            val gaspCount = analytics.soundTotals[RecordedEventGroup.Gasp] ?: 0
            if (gaspCount > 0) {
                InsightTipRow(
                    icon = "😮",
                    title = "Breathing Interruption Indicator",
                    description = "$gaspCount gasping sounds detected across recorded sessions. Occasional gasping can be related to sleep position or airway resistance."
                )
            }

            InsightTipRow(
                icon = "🛌",
                title = "Positional Sleeping",
                description = "Sleeping on your side often reduces snoring frequency and intensity by up to 50% compared to sleeping on your back."
            )

            InsightTipRow(
                icon = "💧",
                title = "Bedroom Air Quality",
                description = "Dry bedroom air can irritate throat and nasal passages. Maintaining 40-50% humidity can significantly reduce nocturnal coughing and snoring."
            )
        }
    }
}

@Composable
private fun InsightTipRow(
    icon: String,
    title: String,
    description: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = icon,
                style = MaterialTheme.typography.titleMedium
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun OverviewMetricItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun severityColors(severity: SnoreSeverity): Pair<Color, Color> {
    return when (severity) {
        SnoreSeverity.Quiet -> Pair(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer
        )
        SnoreSeverity.Mild -> Pair(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
        SnoreSeverity.Moderate -> Pair(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer
        )
        SnoreSeverity.Severe -> Pair(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

@Composable
fun groupColor(group: RecordedEventGroup): Pair<Color, Color> {
    return when (group) {
        RecordedEventGroup.Snoring -> Pair(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
        RecordedEventGroup.Gasp -> Pair(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer
        )
        RecordedEventGroup.Cough -> Pair(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer
        )
        RecordedEventGroup.SleepTalking -> Pair(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

fun groupEmoji(group: RecordedEventGroup): String {
    return when (group) {
        RecordedEventGroup.Snoring -> "💤"
        RecordedEventGroup.Gasp -> "😮"
        RecordedEventGroup.Cough -> "😷"
        RecordedEventGroup.SleepTalking -> "🗣️"
    }
}

fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

@Preview(name = "Session Analytics Hero Card - Dark")
@Composable
private fun SessionAnalyticsHeroCardDarkPreview() {
    SnorelyzerTheme(darkTheme = true) {
        Surface {
            SessionAnalyticsHeroCard(
                analytics = samplePreviewAnalytics,
                formattedDate = "Friday, Nov 6, 2026",
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

@Preview(name = "Session Analytics Hero Card - Light")
@Composable
private fun SessionAnalyticsHeroCardLightPreview() {
    SnorelyzerTheme(darkTheme = false) {
        Surface {
            SessionAnalyticsHeroCard(
                analytics = samplePreviewAnalytics,
                formattedDate = "Friday, Nov 6, 2026",
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

private val samplePreviewAnalytics: NightAnalytics by lazy {
    NightAnalytics(
        totalSleepDurationMillis = 7 * 3600 * 1000L + 25 * 60 * 1000L,
        totalSnoreDurationMillis = 28 * 60 * 1000L,
        snorePercentage = 6.3f,
        snoreScore = 24,
        severity = SnoreSeverity.Mild,
        totalEpisodes = 8,
        disturbanceRatePerHour = 1.1f,
        longestEpisodeMillis = 45000L,
        peakHourLabel = "3am",
        soundBreakdowns = listOf(
            SoundGroupBreakdown(RecordedEventGroup.Snoring, 6, 28 * 60 * 1000L, 75f),
            SoundGroupBreakdown(RecordedEventGroup.Gasp, 1, 15000L, 12.5f),
            SoundGroupBreakdown(RecordedEventGroup.SleepTalking, 1, 12000L, 12.5f)
        ),
        hourlyDistribution = listOf(
            HourlyDistributionItem("11pm", 0L, 0, 0L, emptyMap(), null, 0f),
            HourlyDistributionItem("12am", 0L, 1, 12000L, mapOf(RecordedEventGroup.Snoring to 1), RecordedEventGroup.Snoring, 0.25f),
            HourlyDistributionItem("1am", 0L, 0, 0L, emptyMap(), null, 0f),
            HourlyDistributionItem("2am", 0L, 2, 34000L, mapOf(RecordedEventGroup.Snoring to 2), RecordedEventGroup.Snoring, 0.5f),
            HourlyDistributionItem("3am", 0L, 4, 75000L, mapOf(RecordedEventGroup.Snoring to 3, RecordedEventGroup.Gasp to 1), RecordedEventGroup.Snoring, 1.0f),
            HourlyDistributionItem("4am", 0L, 1, 20000L, mapOf(RecordedEventGroup.Snoring to 1), RecordedEventGroup.Snoring, 0.25f),
            HourlyDistributionItem("5am", 0L, 0, 0L, emptyMap(), null, 0f),
            HourlyDistributionItem("6am", 0L, 0, 0L, emptyMap(), null, 0f)
        )
    )
}
