package com.example.snorelyzer.presentation.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.snorelyzer.DetectedClassUi
import org.koin.androidx.compose.koinViewModel

@Composable
fun RecordRoot(
    onRequestRecordingPermission: ((Boolean) -> Unit) -> Unit,
    onStartRecordingService: () -> Unit,
    onStopRecordingService: () -> Unit,
    viewModel: RecordViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                RecordEvent.RequestRecordingPermission -> {
                    onRequestRecordingPermission { granted ->
                        viewModel.onAction(RecordAction.OnRecordingPermissionResult(granted))
                    }
                }
                RecordEvent.StartRecordingService -> onStartRecordingService()
                RecordEvent.StopRecordingService -> onStopRecordingService()
            }
        }
    }

    RecordScreen(
        state = state,
        onAction = viewModel::onAction
    )
}

@Composable
fun RecordScreen(
    state: RecordState,
    onAction: (RecordAction) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Record",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )

        Text(
            text = if (state.isServiceRunning) "Tracking" else state.latestStatus,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )

        LatestResultsSection(
            latestStatus = state.latestStatus,
            latestResults = state.latestResults
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = { onAction(RecordAction.OnStartClick) },
                modifier = Modifier.weight(1f)
            ) {
                Text("Start")
            }

            Button(
                onClick = { onAction(RecordAction.OnStopClick) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Stop")
            }
        }
    }
}

@Composable
private fun LatestResultsSection(
    latestStatus: String,
    latestResults: List<DetectedClassUi>
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Latest sounds",
            style = MaterialTheme.typography.titleMedium
        )

        if (latestResults.isEmpty()) {
            Text(
                text = latestStatus,
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            latestResults.forEach { result ->
                SummaryRow(
                    label = result.label,
                    value = "${result.probabilityPercent}%"
                )
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}
