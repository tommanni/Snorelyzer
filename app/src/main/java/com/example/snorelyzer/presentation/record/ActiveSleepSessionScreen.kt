package com.example.snorelyzer.presentation.record

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel

@Composable
fun ActiveSleepSessionRoot(
    onStopRecordingService: () -> Unit,
    onDiscardRecordingService: () -> Unit,
    onShowRecordingDiscardedMessage: () -> Unit,
    viewModel: RecordViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                RecordEvent.RequestRecordingPermission,
                RecordEvent.StartRecordingService -> Unit
                is RecordEvent.StopRecordingService -> {
                    if (event.save) {
                        onStopRecordingService()
                    } else {
                        onDiscardRecordingService()
                        onShowRecordingDiscardedMessage()
                    }
                }
                RecordEvent.ShowRecordingDiscardedNotification -> Unit
            }
        }
    }

    ActiveSleepSessionScreen(
        state = state,
        onAction = viewModel::onAction
    )
}

@Composable
fun ActiveSleepSessionScreen(
    state: RecordState,
    onAction: (RecordAction) -> Unit
) {
    BackHandler(enabled = true) {}

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HoldToStopTrackingButton(
            onStopTracking = { onAction(RecordAction.OnStopClick) },
            resetKey = state.stopTrackingHoldResetKey,
            modifier = Modifier.fillMaxWidth()
        )
    }

    if (state.showShortSessionDialog) {
        AlertDialog(
            onDismissRequest = { onAction(RecordAction.OnKeepRecordingClick) },
            title = { Text("Recording is too short") },
            text = {
                Text("Sleep sessions shorter than 10 minutes are not saved. Keep tracking to save this session later.")
            },
            confirmButton = {
                TextButton(onClick = { onAction(RecordAction.OnKeepRecordingClick) }) {
                    Text("Keep tracking")
                }
            },
            dismissButton = {
                TextButton(onClick = { onAction(RecordAction.OnEndShortSessionNowClick) }) {
                    Text("End now")
                }
            }
        )
    }
}
