package com.example.snorelyzer.presentation

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.snorelyzer.R
import com.example.snorelyzer.ui.theme.SnorelyzerTheme

internal enum class SnackbarTone(val icon: ImageVector) {
    Success(Icons.Outlined.CheckCircle),
    Warning(Icons.Outlined.WarningAmber),
    Error(Icons.Outlined.ErrorOutline)
}

internal data class AppSnackbarVisuals(
    override val message: String,
    val tone: SnackbarTone
) : SnackbarVisuals {
    override val actionLabel: String? = null
    override val withDismissAction = false
    override val duration = SnackbarDuration.Short
}

@Composable
internal fun AppSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState, modifier) { data ->
        val visuals = data.visuals as? AppSnackbarVisuals
        if (visuals != null) {
            AppSnackbar(visuals)
        } else {
            Snackbar(data)
        }
    }
}

@Composable
private fun AppSnackbar(visuals: AppSnackbarVisuals) {
    val colors = MaterialTheme.colorScheme
    val (containerColor, contentColor) = when (visuals.tone) {
        SnackbarTone.Success -> colors.primaryContainer to colors.onPrimaryContainer
        SnackbarTone.Warning -> colors.tertiaryContainer to colors.onTertiaryContainer
        SnackbarTone.Error -> colors.errorContainer to colors.onErrorContainer
    }

    Snackbar(
        shape = RoundedCornerShape(16.dp),
        containerColor = containerColor,
        contentColor = contentColor
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(vertical = 8.dp)
        ) {
            Icon(visuals.tone.icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(visuals.message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Preview(name = "App Snackbars - Light", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "App Snackbars - Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AppSnackbarPreview() {
    SnorelyzerTheme(darkTheme = isSystemInDarkTheme()) {
        Surface {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AppSnackbar(AppSnackbarVisuals(stringResource(R.string.settings_delete_success), SnackbarTone.Success))
                AppSnackbar(AppSnackbarVisuals(stringResource(R.string.insights_export_partial, 2, 1), SnackbarTone.Warning))
                AppSnackbar(AppSnackbarVisuals(stringResource(R.string.insights_export_failed), SnackbarTone.Error))
            }
        }
    }
}
