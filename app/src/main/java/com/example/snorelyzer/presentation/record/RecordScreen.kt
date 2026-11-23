package com.example.snorelyzer.presentation.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.PhonelinkLock
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.snorelyzer.R
import com.example.snorelyzer.ui.theme.SnorelyzerTheme
import java.text.DateFormat
import java.util.Date
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
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(80.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.Bedtime,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
            Text(
                text = stringResource(R.string.record_bedtime_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }

        state.interruptedAtMillis?.let { interruptedAtMillis ->
            val interruptedAt = remember(interruptedAtMillis) {
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(interruptedAtMillis))
            }
            RecordingWarning(stringResource(R.string.record_interrupted_at, interruptedAt))
        }

        if (state.hasSaveFailure) {
            RecordingWarning(stringResource(R.string.record_save_failed))
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.record_setup_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                RecordingSetupTip(
                    icon = Icons.Outlined.PhoneAndroid,
                    text = stringResource(R.string.record_setup_placement)
                )
                RecordingSetupTip(
                    icon = Icons.Outlined.Power,
                    text = stringResource(R.string.record_setup_power)
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = { onAction(RecordAction.OnStartClick) },
                enabled = !state.isSavingSession,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
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
                        text = stringResource(R.string.record_start_tracking),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
internal fun RecordingWarning(text: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun RecordingSetupTip(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}

@Preview(name = "Record Screen - Dark")
@Composable
private fun RecordScreenDarkPreview() {
    SnorelyzerTheme(darkTheme = true) {
        Surface {
            RecordScreen(
                state = RecordState(hasSaveFailure = true),
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
                state = RecordState(
                    interruptedAtMillis = System.currentTimeMillis(),
                    hasSaveFailure = true
                ),
                onAction = {}
            )
        }
    }
}
