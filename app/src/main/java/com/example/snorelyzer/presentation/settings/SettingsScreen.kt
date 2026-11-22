package com.example.snorelyzer.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.snorelyzer.R
import com.example.snorelyzer.data.local.AudioRetentionPeriod
import com.example.snorelyzer.presentation.AppSnackbarVisuals
import com.example.snorelyzer.presentation.SnackbarTone
import com.example.snorelyzer.ui.theme.SnorelyzerTheme
import org.koin.androidx.compose.koinViewModel

@Composable
internal fun SettingsRoot(
    snackbarHostState: SnackbarHostState,
    viewModel: SettingsViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val deletionMessage by rememberUpdatedState(stringResource(R.string.settings_delete_success))

    LaunchedEffect(viewModel.events, snackbarHostState) {
        viewModel.events.collect { event ->
            when (event) {
                SettingsEvent.AllDataDeleted -> snackbarHostState.showSnackbar(
                    AppSnackbarVisuals(deletionMessage, SnackbarTone.Success)
                )
            }
        }
    }

    SettingsScreen(state = state, onAction = viewModel::onAction)
}

@Composable
internal fun SettingsScreen(
    state: SettingsState,
    onAction: (SettingsAction) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.outlinedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_data_management),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                RetentionSetting(state = state, onAction = onAction)
                Button(
                    onClick = { onAction(SettingsAction.OnDeleteDataClick) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.showDeleteConfirmation && !state.isSavingRetention,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Text(stringResource(R.string.settings_delete_all_data))
                }
            }
        }
    }

    if (state.showDeleteConfirmation) {
        DeleteDataDialog(state = state, onAction = onAction)
    }
    state.retentionToConfirm?.let { period ->
        ConfirmRetentionDialog(period = period, onAction = onAction)
    }
}

@Composable
private fun RetentionSetting(state: SettingsState, onAction: (SettingsAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.settings_audio_retention),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = stringResource(R.string.settings_audio_retention_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box {
            OutlinedButton(
                enabled = !state.isSavingRetention && !state.isDeleting,
                onClick = { onAction(SettingsAction.OnShowRetentionOptions) }
            ) {
                Text(text = state.retentionPeriod.label(), modifier = Modifier.padding(end = 4.dp))
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(
                expanded = state.showRetentionOptions,
                onDismissRequest = { onAction(SettingsAction.OnDismissRetentionOptions) }
            ) {
                for (period in AudioRetentionPeriod.entries) {
                    DropdownMenuItem(
                        text = { Text(period.label()) },
                        trailingIcon = if (period == state.retentionPeriod) {
                            { Icon(Icons.Default.Check, contentDescription = stringResource(R.string.settings_audio_retention_selected)) }
                        } else null,
                        onClick = { onAction(SettingsAction.OnSelectRetention(period)) }
                    )
                }
            }
        }
        if (state.retentionSaveFailed) {
            Text(
                text = stringResource(R.string.settings_audio_retention_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun AudioRetentionPeriod.label(): String = stringResource(
    when (this) {
        AudioRetentionPeriod.SevenDays -> R.string.settings_audio_retention_7_days
        AudioRetentionPeriod.ThirtyDays -> R.string.settings_audio_retention_30_days
        AudioRetentionPeriod.NinetyDays -> R.string.settings_audio_retention_90_days
        AudioRetentionPeriod.Indefinitely -> R.string.settings_audio_retention_indefinite
    }
)

@Composable
private fun ConfirmRetentionDialog(period: AudioRetentionPeriod, onAction: (SettingsAction) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAction(SettingsAction.OnDismissRetentionConfirmation) },
        title = { Text(stringResource(R.string.settings_audio_retention_confirm_title)) },
        text = { Text(stringResource(R.string.settings_audio_retention_warning, period.label())) },
        dismissButton = {
            TextButton(onClick = { onAction(SettingsAction.OnDismissRetentionConfirmation) }) {
                Text(stringResource(R.string.settings_delete_cancel))
            }
        },
        confirmButton = {
            Button(onClick = { onAction(SettingsAction.OnConfirmRetention) }) {
                Text(stringResource(R.string.settings_audio_retention_confirm))
            }
        }
    )
}

@Composable
private fun DeleteDataDialog(state: SettingsState, onAction: (SettingsAction) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAction(SettingsAction.OnDismissDeleteConfirmation) },
        properties = DialogProperties(
            dismissOnBackPress = !state.isDeleting,
            dismissOnClickOutside = !state.isDeleting
        ),
        icon = {
            Icon(
                imageVector = Icons.Outlined.DeleteOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = { Text(stringResource(R.string.settings_delete_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.settings_delete_warning))
                state.deleteFailure?.let { failure ->
                    val message = when (failure) {
                        SettingsDeleteFailure.RecordingInProgress -> R.string.settings_delete_recording_active
                        SettingsDeleteFailure.CleanupFailed -> R.string.settings_delete_failed
                    }
                    Text(text = stringResource(message), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = { onAction(SettingsAction.OnDismissDeleteConfirmation) },
                enabled = !state.isDeleting
            ) {
                Text(stringResource(R.string.settings_delete_cancel))
            }
        },
        confirmButton = {
            Button(
                onClick = { onAction(SettingsAction.OnConfirmDeleteData) },
                enabled = !state.isDeleting,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                if (state.isDeleting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        strokeWidth = 2.dp
                    )
                    Text(stringResource(R.string.settings_delete_in_progress), Modifier.padding(start = 8.dp))
                } else {
                    Text(stringResource(R.string.settings_delete_all_data))
                }
            }
        }
    )
}

@Preview(name = "Settings Screen - Light")
@Composable
private fun SettingsScreenLightPreview() {
    SnorelyzerTheme(darkTheme = false) {
        Surface {
            SettingsScreen(state = SettingsState(showRetentionOptions = true), onAction = {})
        }
    }
}

@Preview(name = "Settings Screen - Dark")
@Composable
private fun SettingsScreenDarkPreview() {
    SnorelyzerTheme(darkTheme = true) {
        Surface {
            SettingsScreen(
                state = SettingsState(retentionPeriod = AudioRetentionPeriod.Indefinitely, retentionSaveFailed = true),
                onAction = {}
            )
        }
    }
}

@Preview(name = "Delete Data Confirmation - Light")
@Composable
private fun DeleteDataDialogLightPreview() {
    SnorelyzerTheme(darkTheme = false) {
        DeleteDataDialog(state = SettingsState(showDeleteConfirmation = true), onAction = {})
    }
}

@Preview(name = "Delete Data Confirmation - Dark")
@Composable
private fun DeleteDataDialogDarkPreview() {
    SnorelyzerTheme(darkTheme = true) {
        DeleteDataDialog(state = SettingsState(showDeleteConfirmation = true), onAction = {})
    }
}

@Preview(name = "Audio Retention Confirmation - Light")
@Composable
private fun RetentionDialogLightPreview() {
    SnorelyzerTheme(darkTheme = false) {
        ConfirmRetentionDialog(period = AudioRetentionPeriod.SevenDays, onAction = {})
    }
}

@Preview(name = "Audio Retention Confirmation - Dark")
@Composable
private fun RetentionDialogDarkPreview() {
    SnorelyzerTheme(darkTheme = true) {
        ConfirmRetentionDialog(period = AudioRetentionPeriod.SevenDays, onAction = {})
    }
}
