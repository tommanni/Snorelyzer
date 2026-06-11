package com.example.snorelyzer.presentation.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.format.DateTimeFormatter
import org.koin.androidx.compose.koinViewModel

@Composable
fun InsightsRoot(
    viewModel: SessionInsightsViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    InsightsScreen(
        state = state,
        onAction = viewModel::onAction
    )
}

@Composable
fun InsightsScreen(
    state: SessionInsightsState,
    onAction: (SessionInsightsAction) -> Unit
) {
    val selectedDateText = state.selectedDate?.format(DateTimeFormatter.ISO_LOCAL_DATE)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Insights",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Text(
            text = when {
                state.isLoading -> "Loading sleep nights..."
                state.sleepNights.isEmpty() -> "Sleep clips and metadata visualizations will appear here."
                state.selectedNight != null -> "Selected night: $selectedDateText"
                selectedDateText != null -> "No recording for $selectedDateText"
                else -> "Select a day to inspect sleep metadata."
            },
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
    }
}
