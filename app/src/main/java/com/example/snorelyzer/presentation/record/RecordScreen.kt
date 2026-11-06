package com.example.snorelyzer.presentation.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.snorelyzer.ui.theme.SnorelyzerTheme
import org.koin.androidx.compose.koinViewModel

@Composable
fun RecordRoot(
    onRequestRecordingPermission: ((Boolean) -> Unit) -> Unit,
    onStartRecordingService: () -> Unit,
    onStopRecordingService: () -> Unit,
    onDiscardRecordingService: () -> Unit,
    onShowRecordingDiscardedMessage: () -> Unit,
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
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(
            onClick = { onAction(RecordAction.OnStartClick) },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Start Tracking",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Preview(name = "Record Screen - Dark")
@Composable
private fun RecordScreenDarkPreview() {
    SnorelyzerTheme(darkTheme = true) {
        Surface {
            RecordScreen(
                state = RecordState(isServiceRunning = false),
                onAction = {}
            )
        }
    }
}

@Preview(name = "Record Screen - Light")
@Composable
private fun RecordScreenLightPreview() {
    SnorelyzerTheme(darkTheme = false) {
        Surface {
            RecordScreen(
                state = RecordState(isServiceRunning = false),
                onAction = {}
            )
        }
    }
}
