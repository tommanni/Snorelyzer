package com.example.snorelyzer.presentation.insights

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.snorelyzer.data.local.RecordingEpisodeWithDetails
import org.koin.androidx.compose.koinViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
            .padding(24.dp),
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
                .padding(top = 24.dp),
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
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
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
    val selectedNight = state.selectedNight
    if (selectedNight != null) {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp)
        ) {
            items(selectedNight.allEpisodes) { episodeWithDetails ->
                val isActiveEpisode = state.playingEpisodeId == episodeWithDetails.episode.episodeId
                val isPlaying = isActiveEpisode && state.isPlayerPlaying
                SleepEpisodeItem(
                    episodeDetails = episodeWithDetails,
                    isPlaying = isPlaying,
                    onTogglePlayClick = {
                        val filePath = episodeWithDetails.episode.filePath
                        if (filePath != null) {
                            onAction(
                                SessionInsightsAction.OnTogglePlayback(
                                    episodeId = episodeWithDetails.episode.episodeId,
                                    filePath = filePath
                                )
                            )
                        }
                    }
                )
            }
        }
    } else {
        InsightsPlaceholder(
            text = "No sleep sessions available.",
            modifier = modifier
        )
    }
}

@Composable
fun SleepEpisodeItem(
    episodeDetails: RecordingEpisodeWithDetails,
    isPlaying: Boolean,
    onTogglePlayClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val episode = episodeDetails.episode
    val formatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    val startTime = formatter.format(Instant.ofEpochMilli(episode.clipStartMillis))
    val durationSec = episode.durationMillis / 1000

    val labels = episodeDetails.groups.joinToString(", ") { it.group.name }

    Card(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Time: $startTime ($durationSec sec)",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (labels.isNotEmpty()) {
                    Text(
                        text = "Detected: $labels",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            
            IconButton(
                onClick = onTogglePlayClick,
                enabled = episode.filePath != null
            ) {
                val icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow
                val contentDesc = if (isPlaying) "Pause audio" else "Play audio"
                Icon(icon, contentDescription = contentDesc)
            }
        }
    }
}

internal const val SessionInsightsContentTag = "session_insights_content"
internal const val AnalyticsInsightsContentTag = "analytics_insights_content"
