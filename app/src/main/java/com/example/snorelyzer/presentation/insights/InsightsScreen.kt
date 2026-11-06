package com.example.snorelyzer.presentation.insights

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.snorelyzer.data.local.EventSpanEntity
import com.example.snorelyzer.data.local.RecordingEpisodeWithDetails
import com.example.snorelyzer.data.local.RecordingSessionWithEpisodes
import com.example.snorelyzer.data.local.SleepNight
import com.example.snorelyzer.ml.recording.RecordedEventGroup
import org.koin.androidx.compose.koinViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun InsightsRoot(
    viewModel: InsightsViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    BackHandler(enabled = state.selectedTab == InsightsTab.Analytics) {
        viewModel.onAction(InsightsAction.OnTabSelected(InsightsTab.Session))
    }

    InsightsScreen(
        state = state,
        onAction = viewModel::onAction
    )
}

@Composable
fun InsightsScreen(
    state: InsightsState,
    onAction: (InsightsAction) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        PrimaryScrollableTabRow(
            selectedTabIndex = state.selectedTab.ordinal,
            modifier = Modifier.fillMaxWidth(),
            edgePadding = 0.dp,
            divider = {}
        ) {
            InsightsTab.entries.forEach { tab ->
                InsightsTabItem(
                    text = tab.label,
                    selected = state.selectedTab == tab,
                    onClick = { onAction(InsightsAction.OnTabSelected(tab)) }
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            when (state.selectedTab) {
                InsightsTab.Session -> SessionInsightsRoot(
                    modifier = Modifier.testTag(SessionInsightsContentTag)
                )
                InsightsTab.Analytics -> InsightsPlaceholder(
                    text = "Analytics insights content will appear here.",
                    modifier = Modifier.testTag(AnalyticsInsightsContentTag)
                )
            }
        }
    }
}

@Composable
private fun InsightsTabItem(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = modifier
            .height(48.dp)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
                interactionSource = interactionSource,
                indication = null
            )
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = contentColor,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun InsightsPlaceholder(
    text: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Outlined.Bedtime,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.outlineVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun SessionInsightsRoot(
    modifier: Modifier = Modifier,
    viewModel: SessionInsightsViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        onDispose {
            viewModel.onAction(SessionInsightsAction.OnStopPlayback)
        }
    }

    SessionInsightsScreen(
        state = state,
        onAction = viewModel::onAction,
        modifier = modifier
    )
}

@Composable
fun SessionInsightsScreen(
    state: SessionInsightsState,
    onAction: (SessionInsightsAction) -> Unit,
    modifier: Modifier = Modifier
) {
    if (state.isLoading) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        return
    }

    if (state.sleepNights.isEmpty()) {
        InsightsPlaceholder(
            text = "No sleep sessions available.",
            modifier = modifier
        )
        return
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Date selector strip at the top
        DateSelectorStrip(
            availableDates = state.availableDates,
            selectedDate = state.selectedDate,
            onDateSelected = { onAction(SessionInsightsAction.OnDateSelected(it)) }
        )

        val selectedNight = state.selectedNight
        if (selectedNight != null) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item(key = "night_overview_${selectedNight.date}") {
                    SleepNightOverviewCard(night = selectedNight)
                }

                item(key = "sessions_header_${selectedNight.date}") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Recording Sessions",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${selectedNight.sessions.size} ${if (selectedNight.sessions.size == 1) "session" else "sessions"}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                itemsIndexed(
                    items = selectedNight.sessions,
                    key = { _, sessionWithEpisodes -> sessionWithEpisodes.session.sessionId }
                ) { index, sessionWithEpisodes ->
                    val isCollapsed = state.collapsedSessionIds.contains(sessionWithEpisodes.session.sessionId)
                    RecordingSessionCard(
                        sessionIndex = index + 1,
                        sessionWithEpisodes = sessionWithEpisodes,
                        isCollapsed = isCollapsed,
                        playingEpisodeId = state.playingEpisodeId,
                        isPlayerPlaying = state.isPlayerPlaying,
                        expandedEpisodeIds = state.expandedEpisodeIds,
                        onToggleCollapse = {
                            onAction(SessionInsightsAction.OnToggleSessionCollapse(sessionWithEpisodes.session.sessionId))
                        },
                        onTogglePlayEpisode = { episodeId, filePath ->
                            onAction(SessionInsightsAction.OnTogglePlayback(episodeId = episodeId, filePath = filePath))
                        },
                        onToggleEpisodeSpans = { episodeId ->
                            onAction(SessionInsightsAction.OnToggleEpisodeSpans(episodeId))
                        }
                    )
                }
            }
        } else {
            InsightsPlaceholder(
                text = "No sleep sessions available.",
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun DateSelectorStrip(
    availableDates: Set<LocalDate>,
    selectedDate: LocalDate?,
    onDateSelected: (LocalDate) -> Unit,
    modifier: Modifier = Modifier
) {
    val sortedDates = remember(availableDates) {
        availableDates.sortedDescending()
    }

    if (sortedDates.isEmpty()) return

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(sortedDates, key = { it.toString() }) { date ->
            val isSelected = date == selectedDate
            val dayOfWeek = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            val dayOfMonth = date.dayOfMonth.toString()
            val month = date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())

            Surface(
                onClick = { onDateSelected(date) },
                shape = RoundedCornerShape(16.dp),
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                },
                tonalElevation = if (isSelected) 4.dp else 1.dp,
                modifier = Modifier
                    .height(68.dp)
                    .width(60.dp)
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = dayOfWeek,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                    Text(
                        text = dayOfMonth,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                    Text(
                        text = month,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SleepNightOverviewCard(
    night: SleepNight,
    modifier: Modifier = Modifier
) {
    val nowMillis = remember { System.currentTimeMillis() }
    val totalDurationMillis = night.totalRecordingDurationMillis(nowMillis)
    val totalEpisodes = night.episodeCount

    val groupCounts = remember(night) {
        val counts = mutableMapOf<RecordedEventGroup, Int>()
        night.sessions.forEach { session ->
            session.episodes.forEach { episode ->
                episode.groups.forEach { groupEntity ->
                    counts[groupEntity.group] = (counts[groupEntity.group] ?: 0) + 1
                }
            }
        }
        counts
    }

    val dateFormatter = remember { DateTimeFormatter.ofPattern("EEEE, MMM d, yyyy") }
    val formattedDate = remember(night.date) { night.date.format(dateFormatter) }

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
                        text = "Sleep Night",
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

            // Stats grid row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OverviewStatItem(
                    label = "Total Time",
                    value = formatDuration(totalDurationMillis)
                )
                OverviewStatItem(
                    label = "Sessions",
                    value = night.sessions.size.toString()
                )
                OverviewStatItem(
                    label = "Episodes",
                    value = totalEpisodes.toString()
                )
            }

            if (groupCounts.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Detected Sounds Summary",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RecordedEventGroup.entries.forEach { group ->
                            val count = groupCounts[group] ?: 0
                            if (count > 0) {
                                GroupBadge(group = group, count = count)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewStatItem(
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
private fun RecordingSessionCard(
    sessionIndex: Int,
    sessionWithEpisodes: RecordingSessionWithEpisodes,
    isCollapsed: Boolean,
    playingEpisodeId: String?,
    isPlayerPlaying: Boolean,
    expandedEpisodeIds: Set<String>,
    onToggleCollapse: () -> Unit,
    onTogglePlayEpisode: (String, String) -> Unit,
    onToggleEpisodeSpans: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val session = sessionWithEpisodes.session
    val episodes = sessionWithEpisodes.episodes
    val isOngoing = session.endedAtMillis == null
    val sessionDuration = (session.endedAtMillis ?: System.currentTimeMillis()) - session.startedAtMillis

    val startTimeStr = formatTimestamp(session.startedAtMillis, "hh:mm a")
    val endTimeStr = if (session.endedAtMillis != null) formatTimestamp(session.endedAtMillis, "hh:mm a") else "Ongoing"

    OutlinedCard(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Session Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleCollapse),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Session $sessionIndex",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isOngoing) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            }
                        ) {
                            Text(
                                text = if (isOngoing) "Active" else "Completed",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isOngoing) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "$startTimeStr – $endTimeStr  •  ${formatDuration(sessionDuration)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Text(
                        text = "${episodes.size} ${if (episodes.size == 1) "episode" else "episodes"} recorded",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                IconButton(onClick = onToggleCollapse) {
                    Icon(
                        imageVector = if (isCollapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                        contentDescription = if (isCollapsed) "Expand session" else "Collapse session",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            AnimatedVisibility(visible = !isCollapsed) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                    if (episodes.isEmpty()) {
                        Text(
                            text = "No sound events detected in this session.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        episodes.forEach { episodeDetails ->
                            val isActiveEpisode = playingEpisodeId == episodeDetails.episode.episodeId
                            val isPlaying = isActiveEpisode && isPlayerPlaying
                            val isSpansExpanded = expandedEpisodeIds.contains(episodeDetails.episode.episodeId)

                            SleepEpisodeItem(
                                episodeDetails = episodeDetails,
                                isPlaying = isPlaying,
                                onTogglePlayClick = {
                                    episodeDetails.episode.filePath?.let { filePath ->
                                        onTogglePlayEpisode(episodeDetails.episode.episodeId, filePath)
                                    }
                                },
                                isSpansExpanded = isSpansExpanded,
                                onToggleSpansClick = {
                                    onToggleEpisodeSpans(episodeDetails.episode.episodeId)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SleepEpisodeItem(
    episodeDetails: RecordingEpisodeWithDetails,
    isPlaying: Boolean,
    onTogglePlayClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSpansExpanded: Boolean = false,
    onToggleSpansClick: () -> Unit = {}
) {
    val episode = episodeDetails.episode
    val startTime = formatTimestamp(episode.clipStartMillis, "hh:mm:ss a")
    val durationSec = (episode.durationMillis / 1000.0).let { String.format(Locale.US, "%.1f", it) }
    val hasAudioFile = episode.filePath != null
    val eventSpans = episodeDetails.eventSpans

    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isPlaying) {
                MaterialTheme.colorScheme.surfaceContainerHighest
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header: time, duration, and audio control
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = startTime,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Text(
                                text = "${durationSec}s",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    if (hasAudioFile) {
                        Text(
                            text = if (isPlaying) "Playing audio recording..." else "Audio recording ready",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        )
                    } else {
                        Text(
                            text = "No audio clip stored",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isPlaying) {
                        ModernAudioEqualizer(isPlaying = true)
                    }

                    FilledTonalIconButton(
                        onClick = onTogglePlayClick,
                        enabled = hasAudioFile,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = if (isPlaying) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            },
                            contentColor = if (isPlaying) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            }
                        ),
                        modifier = Modifier.size(36.dp)
                    ) {
                        val icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow
                        val contentDesc = if (isPlaying) "Pause audio" else "Play audio"
                        Icon(icon, contentDescription = contentDesc, modifier = Modifier.size(18.dp))
                    }
                }
            }

            // Detected group chips
            if (episodeDetails.groups.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    episodeDetails.groups.forEach { groupEntity ->
                        GroupBadge(group = groupEntity.group)
                    }
                }
            }

            // Event Spans hierarchy (Level 4 in Room schema)
            if (eventSpans.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onToggleSpansClick),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.GraphicEq,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Event Spans (${eventSpans.size})",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        imageVector = if (isSpansExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isSpansExpanded) "Hide event spans" else "Show event spans",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }

                AnimatedVisibility(visible = isSpansExpanded) {
                    Column(
                        modifier = Modifier.padding(top = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        eventSpans.forEach { span ->
                            EventSpanItem(span = span)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventSpanItem(
    span: EventSpanEntity,
    modifier: Modifier = Modifier
) {
    val spanDurationSec = ((span.endedAtMillis - span.startedAtMillis) / 1000.0).let {
        String.format(Locale.US, "%.1f", it)
    }
    val startTimeStr = formatTimestamp(span.startedAtMillis, "hh:mm:ss")
    val endTimeStr = formatTimestamp(span.endedAtMillis, "hh:mm:ss")
    val (containerColor, contentColor) = groupColor(span.group)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f, fill = false),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = containerColor,
                modifier = Modifier.size(8.dp)
            ) {}
            Text(
                text = "${groupEmoji(span.group)} ${span.group.name}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = "$startTimeStr – $endTimeStr (${spanDurationSec}s)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun GroupBadge(
    group: RecordedEventGroup,
    modifier: Modifier = Modifier,
    count: Int? = null
) {
    val (containerColor, contentColor) = groupColor(group)
    val text = if (count != null) {
        "${groupEmoji(group)} $count ${group.name}"
    } else {
        "${groupEmoji(group)} ${group.name}"
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = containerColor
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun ModernAudioEqualizer(
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "equalizer")
    val bar1Height by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(380),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar1"
    )
    val bar2Height by transition.animateFloat(
        initialValue = 0.75f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(320),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar2"
    )
    val bar3Height by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(420),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar3"
    )
    val bar4Height by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(350),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar4"
    )

    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.outlineVariant

    Row(
        modifier = modifier.height(20.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val bars = if (isPlaying) {
            listOf(bar1Height, bar2Height, bar3Height, bar4Height)
        } else {
            listOf(0.4f, 0.6f, 0.3f, 0.5f)
        }
        bars.forEach { fraction ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height((20 * fraction).dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(if (isPlaying) activeColor else inactiveColor)
            )
        }
    }
}

@Composable
private fun groupColor(group: RecordedEventGroup): Pair<Color, Color> {
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

private fun groupEmoji(group: RecordedEventGroup): String {
    return when (group) {
        RecordedEventGroup.Snoring -> "💤"
        RecordedEventGroup.Gasp -> "😮"
        RecordedEventGroup.Cough -> "😷"
        RecordedEventGroup.SleepTalking -> "🗣️"
    }
}

private fun formatTimestamp(millis: Long, pattern: String): String {
    return DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis))
}

private fun formatDuration(millis: Long): String {
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

internal const val SessionInsightsContentTag = "session_insights_content"
internal const val AnalyticsInsightsContentTag = "analytics_insights_content"

@androidx.compose.ui.tooling.preview.Preview(name = "Session Insights Screen - Dark")
@Composable
private fun SessionInsightsScreenDarkPreview() {
    com.example.snorelyzer.ui.theme.SnorelyzerTheme(darkTheme = true) {
        Surface {
            SessionInsightsScreen(
                state = previewSampleState,
                onAction = {}
            )
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(name = "Session Insights Screen - Light")
@Composable
private fun SessionInsightsScreenLightPreview() {
    com.example.snorelyzer.ui.theme.SnorelyzerTheme(darkTheme = false) {
        Surface {
            SessionInsightsScreen(
                state = previewSampleState,
                onAction = {}
            )
        }
    }
}

private val previewSampleState: SessionInsightsState by lazy {
    val date = LocalDate.of(2026, 11, 6)
    val sessionStart = 1762464000000L
    val sessionEnd = sessionStart + 7 * 3600 * 1000L + 25 * 60 * 1000L
    val episode1 = RecordingEpisodeWithDetails(
        episode = com.example.snorelyzer.data.local.RecordingEpisodeEntity(
            episodeId = "ep-1",
            sessionId = "sess-1",
            clipStartMillis = sessionStart + 3600 * 1000L,
            clipEndMillis = sessionStart + 3600 * 1000L + 12000L,
            durationMillis = 12000L,
            sampleRate = 16000,
            filePath = "/fake/path/audio1.wav"
        ),
        groups = listOf(
            com.example.snorelyzer.data.local.RecordingEpisodeGroupEntity("ep-1", RecordedEventGroup.Snoring),
            com.example.snorelyzer.data.local.RecordingEpisodeGroupEntity("ep-1", RecordedEventGroup.Gasp)
        ),
        eventSpans = listOf(
            EventSpanEntity(
                id = 1L,
                episodeId = "ep-1",
                group = RecordedEventGroup.Snoring,
                startedAtMillis = sessionStart + 3600 * 1000L + 1000L,
                endedAtMillis = sessionStart + 3600 * 1000L + 5000L
            ),
            EventSpanEntity(
                id = 2L,
                episodeId = "ep-1",
                group = RecordedEventGroup.Gasp,
                startedAtMillis = sessionStart + 3600 * 1000L + 7000L,
                endedAtMillis = sessionStart + 3600 * 1000L + 10000L
            )
        )
    )
    val session = RecordingSessionWithEpisodes(
        session = com.example.snorelyzer.data.local.RecordingSessionEntity(
            sessionId = "sess-1",
            startedAtMillis = sessionStart,
            endedAtMillis = sessionEnd
        ),
        episodes = listOf(episode1)
    )
    val night = SleepNight(
        date = date,
        sessions = listOf(session)
    )
    SessionInsightsState(
        sleepNights = listOf(night),
        selectedDate = date,
        selectedNight = night,
        availableDates = setOf(date, date.minusDays(1), date.minusDays(2)),
        isLoading = false,
        playingEpisodeId = "ep-1",
        isPlayerPlaying = true,
        expandedEpisodeIds = setOf("ep-1")
    )
}
