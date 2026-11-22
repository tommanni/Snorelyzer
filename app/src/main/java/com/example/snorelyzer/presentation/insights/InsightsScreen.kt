package com.example.snorelyzer.presentation.insights

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.LayoutBoundsHolder
import androidx.compose.ui.layout.layoutBounds
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.snorelyzer.R
import com.example.snorelyzer.data.local.AudioExportFormat
import com.example.snorelyzer.data.local.DocumentAudioExportDestination
import com.example.snorelyzer.data.local.EventSpanEntity
import com.example.snorelyzer.data.local.RecordingEpisodeWithDetails
import com.example.snorelyzer.data.local.RecordingSessionWithEpisodes
import com.example.snorelyzer.data.local.SleepNight
import com.example.snorelyzer.ml.recording.RecordedEventGroup
import com.example.snorelyzer.presentation.AppSnackbarVisuals
import com.example.snorelyzer.presentation.SnackbarTone
import org.koin.androidx.compose.koinViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun InsightsRoot(
    snackbarHostState: SnackbarHostState,
    viewModel: InsightsViewModel = koinViewModel(),
    sessionViewModel: SessionInsightsViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val sessionState by sessionViewModel.state.collectAsStateWithLifecycle()
    val playback = sessionViewModel.playback.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val resources = LocalResources.current
    val onExportResult: (Uri?) -> Unit = { uri ->
        val destination = uri?.let { DocumentAudioExportDestination(context.applicationContext.contentResolver, it) }
        sessionViewModel.onAction(SessionInsightsAction.OnExportDestinationResult(destination))
    }
    val saveWav = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav"), onExportResult)
    val saveZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip"), onExportResult)
    LaunchedEffect(sessionViewModel.events, resources, snackbarHostState) {
        sessionViewModel.events.collect { event ->
            val snackbar = when (event) {
                is SessionInsightsEvent.ChooseExportDestination -> {
                    val launcher = when (event.format) {
                        AudioExportFormat.Wav -> saveWav
                        AudioExportFormat.Zip -> saveZip
                    }
                    try {
                        launcher.launch(event.fileName)
                    } catch (_: ActivityNotFoundException) {
                        sessionViewModel.onAction(SessionInsightsAction.OnExportPickerUnavailable)
                    } catch (_: SecurityException) {
                        sessionViewModel.onAction(SessionInsightsAction.OnExportPickerUnavailable)
                    }
                    null
                }
                is SessionInsightsEvent.AudioSaved -> {
                    val message = when {
                        event.format == AudioExportFormat.Wav -> resources.getString(R.string.insights_clip_saved)
                        event.skipped > 0 -> resources.getString(R.string.insights_export_partial, event.saved, event.skipped)
                        else -> resources.getQuantityString(R.plurals.insights_clips_exported, event.saved, event.saved)
                    }
                    val tone = if (event.skipped > 0) SnackbarTone.Warning else SnackbarTone.Success
                    AppSnackbarVisuals(message, tone)
                }
                SessionInsightsEvent.ExportAudioUnavailable -> AppSnackbarVisuals(
                    resources.getString(R.string.insights_export_audio_unavailable),
                    SnackbarTone.Warning
                )
                is SessionInsightsEvent.ExportFailed -> AppSnackbarVisuals(
                    resources.getString(
                        if (event.incompleteFileMayRemain) R.string.insights_export_incomplete_file
                        else R.string.insights_export_failed
                    ),
                    SnackbarTone.Error
                )
            }
            if (snackbar != null) launch { snackbarHostState.showSnackbar(snackbar) }
        }
    }

    DisposableEffect(state.selectedTab, sessionViewModel) {
        onDispose { sessionViewModel.onAction(SessionInsightsAction.OnStopPlayback) }
    }

    BackHandler(enabled = state.selectedTab == InsightsTab.Analytics) {
        viewModel.onAction(InsightsAction.OnTabSelected(InsightsTab.Session))
    }

    InsightsScreen(
        state = state,
        onAction = viewModel::onAction,
        sessionState = sessionState,
        onSessionAction = sessionViewModel::onAction,
        playback = remember(playback) { { playback.value } }
    )
}

@Composable
fun InsightsScreen(
    state: InsightsState,
    onAction: (InsightsAction) -> Unit,
    sessionState: SessionInsightsState,
    onSessionAction: (SessionInsightsAction) -> Unit,
    playback: () -> EpisodePlaybackState? = { null }
) {
    if (state.selectedTab == InsightsTab.Session && sessionState.isDatePickerVisible &&
        sessionState.availableDates.isNotEmpty()
    ) {
        NightDatePickerDialog(
            selectedDate = sessionState.selectedDate,
            availableDates = sessionState.availableDates,
            onDateSelected = { onSessionAction(SessionInsightsAction.OnDateSelected(it)) },
            onDismiss = { onSessionAction(SessionInsightsAction.OnDismissDatePicker) }
        )
    }

    val nightToDelete = sessionState.nightToDelete
    if (state.selectedTab == InsightsTab.Session && nightToDelete != null) {
        val date = nightToDelete.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))
        val count = nightToDelete.sessions.size
        val recordings = if (count == 1) "1 session and its audio" else "$count sessions and their audio"
        AlertDialog(
            onDismissRequest = { onSessionAction(SessionInsightsAction.OnDismissDeleteNight) },
            title = { Text("Delete night?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Permanently delete $recordings for the sleep night of $date?")
                    if (sessionState.nightDeletionFailed) {
                        Text(
                            "Couldn’t finish deleting this night. Try again.",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !sessionState.isDeletingNight,
                    onClick = { onSessionAction(SessionInsightsAction.OnConfirmDeleteNight) }
                ) {
                    Text(
                        if (sessionState.isDeletingNight) "Deleting…" else stringResource(R.string.insights_delete_night),
                        color = if (sessionState.isDeletingNight) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !sessionState.isDeletingNight,
                    onClick = { onSessionAction(SessionInsightsAction.OnDismissDeleteNight) }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrimaryScrollableTabRow(
                selectedTabIndex = state.selectedTab.ordinal,
                modifier = Modifier.weight(1f),
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
            if (state.selectedTab == InsightsTab.Session) {
                NightCalendarButton(
                    selectedDate = sessionState.selectedDate,
                    enabled = !sessionState.isLoading && sessionState.availableDates.isNotEmpty(),
                    onClick = { onSessionAction(SessionInsightsAction.OnShowDatePicker) }
                )
                val night = sessionState.selectedNight
                if (night?.endedAtMillis != null) {
                    var menuExpanded by remember(sessionState.selectedDate) { mutableStateOf(false) }
                    Box {
                        IconButton(
                            enabled = !sessionState.isDeletingNight,
                            onClick = { menuExpanded = true }
                        ) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.insights_night_options)
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            offset = DpOffset(0.dp, 4.dp),
                            shape = RoundedCornerShape(16.dp),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.insights_export_night)) },
                                leadingIcon = { Icon(Icons.Outlined.FolderZip, contentDescription = null) },
                                enabled = sessionState.audioExport == null && sessionState.hasExportableNightAudio,
                                onClick = {
                                    menuExpanded = false
                                    onSessionAction(SessionInsightsAction.OnExportNight)
                                }
                            )
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.insights_delete_night)) },
                                leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                                colors = MenuDefaults.itemColors(
                                    textColor = MaterialTheme.colorScheme.error,
                                    leadingIconColor = MaterialTheme.colorScheme.error
                                ),
                                enabled = sessionState.audioExport?.nightDate != night.date,
                                onClick = {
                                    menuExpanded = false
                                    onSessionAction(SessionInsightsAction.OnRequestDeleteNight)
                                }
                            )
                        }
                    }
                }
            }
        }

        if (state.selectedTab == InsightsTab.Session &&
            sessionState.audioExport?.stage == AudioExportStage.Saving &&
            sessionState.audioExport.format == AudioExportFormat.Zip
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.insights_export_in_progress),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            when (state.selectedTab) {
                InsightsTab.Session -> SessionInsightsScreen(
                    state = sessionState,
                    onAction = onSessionAction,
                    modifier = Modifier.testTag(SessionInsightsContentTag),
                    playback = playback
                )
                InsightsTab.Analytics -> AnalyticsInsightsRoot(
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
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.Bedtime,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Text(
                    text = text,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun SessionInsightsScreen(
    state: SessionInsightsState,
    onAction: (SessionInsightsAction) -> Unit,
    modifier: Modifier = Modifier,
    playback: () -> EpisodePlaybackState? = { null }
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
            text = "Session insights content will appear here.",
            modifier = modifier
        )
        return
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val selectedNight = state.selectedNight
        if (selectedNight != null) {
            val waveformViewport = remember { LayoutBoundsHolder() }
            LazyColumn(
                modifier = Modifier.fillMaxSize().layoutBounds(waveformViewport),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item(key = "night_overview_${selectedNight.date}") {
                    val analytics = state.selectedNightAnalytics
                        ?: remember(selectedNight) { computeNightAnalytics(selectedNight) }
                    val dateFormatter = remember { DateTimeFormatter.ofPattern("EEEE, MMM d, yyyy") }
                    val formattedDate = remember(selectedNight.date) { selectedNight.date.format(dateFormatter) }
                    SessionAnalyticsHeroCard(
                        analytics = analytics,
                        formattedDate = formattedDate,
                        dateNavigation = {
                            NightDateNavigation(
                                selectedDate = selectedNight.date,
                                availableDates = state.availableDates,
                                onDateSelected = { onAction(SessionInsightsAction.OnDateSelected(it)) },
                                onOpenCalendar = { onAction(SessionInsightsAction.OnShowDatePicker) }
                            )
                        }
                    )
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
                        canSaveClips = state.audioExport == null && !state.isDeletingNight,
                        expandedEpisodeIds = state.expandedEpisodeIds,
                        waveformsByPath = state.waveformsByPath,
                        waveformViewport = waveformViewport,
                        playback = playback,
                        onAction = onAction
                    )
                }
            }
        } else {
            InsightsPlaceholder(
                text = "Session insights content will appear here.",
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun RecordingSessionCard(
    sessionIndex: Int,
    sessionWithEpisodes: RecordingSessionWithEpisodes,
    isCollapsed: Boolean,
    canSaveClips: Boolean,
    expandedEpisodeIds: Set<String>,
    waveformsByPath: Map<String, ClipWaveform>,
    waveformViewport: LayoutBoundsHolder,
    playback: () -> EpisodePlaybackState?,
    onAction: (SessionInsightsAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val session = sessionWithEpisodes.session
    val episodes = sessionWithEpisodes.episodes
    val isOngoing = session.endedAtMillis == null
    val sessionDuration = (session.endedAtMillis ?: System.currentTimeMillis()) - session.startedAtMillis

    val startTimeStr = formatTimestamp(session.startedAtMillis, "hh:mm a")
    val endTimeStr = if (session.endedAtMillis != null) formatTimestamp(session.endedAtMillis, "hh:mm a") else "Ongoing"
    val onToggleCollapse = { onAction(SessionInsightsAction.OnToggleSessionCollapse(session.sessionId)) }

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
                            val episode = episodeDetails.episode
                            key(episode.episodeId) {
                                SleepEpisodeItem(
                                    episodeDetails = episodeDetails,
                                    playback = playback,
                                    waveform = episode.filePath?.let(waveformsByPath::get),
                                    waveformViewport = waveformViewport,
                                    isSpansExpanded = episode.episodeId in expandedEpisodeIds,
                                    canSaveClip = canSaveClips,
                                    onAction = onAction
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SleepEpisodeItem(
    episodeDetails: RecordingEpisodeWithDetails,
    playback: () -> EpisodePlaybackState?,
    waveform: ClipWaveform?,
    waveformViewport: LayoutBoundsHolder,
    isSpansExpanded: Boolean,
    canSaveClip: Boolean,
    onAction: (SessionInsightsAction) -> Unit
) {
    val episode = episodeDetails.episode
    val clipPlayback by remember(episode.episodeId, playback) {
        derivedStateOf { playback()?.takeIf { it.episodeId == episode.episodeId } }
    }
    val isPlaying = clipPlayback?.isPlaying == true
    val isPlayRequested = clipPlayback?.isPlayRequested == true
    val startTime = remember(episode.clipStartMillis) { formatTimestamp(episode.clipStartMillis, "hh:mm:ss a") }
    val durationMillis = clipPlayback?.durationMillis ?: episode.durationMillis
    val isAudioMissing = waveform == ClipWaveform.MissingFile
    val hasAudioFile = episode.filePath != null && !isAudioMissing
    val showWaveform = hasAudioFile && durationMillis > 0L
    val eventSpans = episodeDetails.eventSpans

    Card(
        modifier = Modifier
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
                        if (!showWaveform) {
                            val durationSec = String.format(Locale.US, "%.1f", episode.durationMillis / 1000.0)
                            Surface(
                                shape = RoundedCornerShape(8.dp),
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
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (isPlaying) {
                        ModernAudioEqualizer(isPlaying = true)
                    }

                    if (hasAudioFile) {
                        IconButton(
                            enabled = canSaveClip,
                            onClick = { onAction(SessionInsightsAction.OnSaveClip(episode.episodeId)) }
                        ) {
                            Icon(Icons.Outlined.Download, contentDescription = stringResource(R.string.insights_save_clip))
                        }
                    }
                    AnimatedVisibility(visible = !episode.audioExpired) {
                        FilledTonalIconButton(
                            onClick = { onAction(SessionInsightsAction.OnTogglePlayback(episode)) },
                            enabled = hasAudioFile,
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = if (isPlayRequested) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.primaryContainer
                                },
                                contentColor = if (isPlayRequested) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                }
                            )
                        ) {
                            val icon = if (isPlayRequested) Icons.Default.Pause else Icons.Default.PlayArrow
                            val contentDesc = if (isPlayRequested) "Pause audio" else "Play audio"
                            Icon(icon, contentDescription = contentDesc, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }

            if (showWaveform) {
                EpisodeWaveform(
                    peaks = (waveform as? ClipWaveform.Peaks)?.values,
                    durationMillis = durationMillis,
                    positionMillis = clipPlayback?.positionMillis ?: 0L,
                    onSeek = { onAction(SessionInsightsAction.OnSeekPlayback(episode, it)) },
                    modifier = if (waveform == null) {
                        Modifier.onVisibilityChanged(
                            minDurationMs = 100L,
                            minFractionVisible = 0.5f,
                            viewportBounds = waveformViewport
                        ) { isVisible ->
                            if (isVisible) onAction(SessionInsightsAction.OnWaveformVisible(episode.filePath))
                        }
                    } else Modifier
                )
            }

            if (episode.audioExpired || isAudioMissing || clipPlayback?.hasError == true) {
                Text(
                    text = stringResource(
                        when {
                            episode.audioExpired -> R.string.insights_audio_expired
                            isAudioMissing -> R.string.insights_audio_missing
                            else -> R.string.insights_audio_playback_failed
                        }
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
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
                        .clickable { onAction(SessionInsightsAction.OnToggleEpisodeSpans(episode.episodeId)) },
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

private fun formatTimestamp(millis: Long, pattern: String): String {
    return DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis))
}

@Composable
fun AnalyticsInsightsRoot(
    modifier: Modifier = Modifier,
    viewModel: AnalyticsInsightsViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AnalyticsInsightsScreen(
        state = state,
        onAction = viewModel::onAction,
        modifier = modifier
    )
}

@Composable
fun AnalyticsInsightsScreen(
    state: AnalyticsInsightsState,
    onAction: (AnalyticsInsightsAction) -> Unit,
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
            text = "Analytics insights content will appear here.",
            modifier = modifier
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "multi_night_trends") {
            MultiNightTrendCard(
                analytics = state.multiNightAnalytics,
                selectedDate = state.selectedTrendDate,
                onSelectDate = { onAction(AnalyticsInsightsAction.OnSelectTrendNight(it)) }
            )
        }
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
                onAction = {},
                playback = { previewPlayback }
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
                onAction = {},
                playback = { previewPlayback }
            )
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(name = "Analytics Insights Screen - Dark")
@Composable
private fun AnalyticsInsightsScreenDarkPreview() {
    com.example.snorelyzer.ui.theme.SnorelyzerTheme(darkTheme = true) {
        Surface {
            AnalyticsInsightsScreen(
                state = previewAnalyticsSampleState,
                onAction = {}
            )
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(name = "Analytics Insights Screen - Light")
@Composable
private fun AnalyticsInsightsScreenLightPreview() {
    com.example.snorelyzer.ui.theme.SnorelyzerTheme(darkTheme = false) {
        Surface {
            AnalyticsInsightsScreen(
                state = previewAnalyticsSampleState,
                onAction = {}
            )
        }
    }
}

private val previewAnalyticsSampleState: AnalyticsInsightsState by lazy {
    val date = LocalDate.of(2026, 11, 6)
    val night = previewSampleState.selectedNight!!
    val multi = computeMultiNightAnalytics(listOf(night))
    AnalyticsInsightsState(
        isLoading = false,
        sleepNights = listOf(night),
        multiNightAnalytics = multi,
        selectedTrendDate = date
    )
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
    val analytics = computeNightAnalytics(night)
    SessionInsightsState(
        sleepNights = listOf(night),
        selectedDate = date,
        selectedNight = night,
        selectedNightAnalytics = analytics,
        availableDates = setOf(date, date.minusDays(1), date.minusDays(2)),
        isLoading = false,
        waveformsByPath = mapOf("/fake/path/audio1.wav" to ClipWaveform.Peaks(previewWaveformPeaks)),
        expandedEpisodeIds = setOf("ep-1")
    )
}

private val previewPlayback = EpisodePlaybackState(
    episodeId = "ep-1",
    positionMillis = 5_000L,
    durationMillis = 12_000L,
    isPlaying = true,
    isPlayRequested = true
)

@androidx.compose.ui.tooling.preview.Preview(name = "Insights - Light")
@androidx.compose.ui.tooling.preview.Preview(
    name = "Insights - Dark",
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun InsightsScreenPreview() {
    com.example.snorelyzer.ui.theme.SnorelyzerTheme(darkTheme = isSystemInDarkTheme()) {
        Surface {
            InsightsScreen(
                state = InsightsState(),
                onAction = {},
                sessionState = previewSampleState,
                onSessionAction = {},
                playback = { previewPlayback }
            )
        }
    }
}
