package com.example.snorelyzer.presentation.insights

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.snorelyzer.ui.theme.SnorelyzerTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun NightCalendarButton(
    selectedDate: LocalDate?,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val description = selectedDate?.let {
        "Choose night, ${it.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))}"
    } ?: "Choose night"
    FilledTonalIconButton(onClick = onClick, enabled = enabled) {
        Icon(Icons.Outlined.CalendarMonth, contentDescription = description)
    }
}

@Composable
internal fun NightDateNavigation(
    selectedDate: LocalDate,
    availableDates: Set<LocalDate>,
    onDateSelected: (LocalDate) -> Unit,
    onOpenCalendar: () -> Unit
) {
    val previousDate = remember(availableDates, selectedDate) {
        availableDates.filter { it < selectedDate }.maxOrNull()
    }
    val nextDate = remember(availableDates, selectedDate) {
        availableDates.filter { it > selectedDate }.minOrNull()
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Sleep night",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onOpenCalendar) {
                Text(
                    text = selectedDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        IconButton(
            onClick = { previousDate?.let(onDateSelected) },
            enabled = previousDate != null
        ) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Previous recorded night")
        }
        IconButton(
            onClick = { nextDate?.let(onDateSelected) },
            enabled = nextDate != null
        ) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Next recorded night")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NightDatePickerDialog(
    selectedDate: LocalDate?,
    availableDates: Set<LocalDate>,
    onDateSelected: (LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    // Material date pickers represent calendar dates at UTC midnight, independent of local time.
    val selectableDates = remember(availableDates) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                Instant.ofEpochMilli(utcTimeMillis).atOffset(ZoneOffset.UTC).toLocalDate() in availableDates

            override fun isSelectableYear(year: Int): Boolean = availableDates.any { it.year == year }
        }
    }
    val initialDate = selectedDate?.takeIf { it in availableDates } ?: availableDates.maxOrNull()
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialDate?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        yearRange = (availableDates.minOf { it.year })..(availableDates.maxOf { it.year }),
        initialDisplayMode = DisplayMode.Picker,
        selectableDates = selectableDates
    )
    val pendingDate = pickerState.selectedDateMillis?.let {
        Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).toLocalDate()
    }
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { pendingDate?.takeIf { it in availableDates }?.let(onDateSelected) },
                enabled = pendingDate in availableDates
            ) { Text("Show night") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    ) {
        DatePicker(
            state = pickerState,
            title = {
                Column(
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Choose a sleep night", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Only nights with recordings can be selected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
    }
}

@Preview(name = "Night navigation - Light")
@Preview(name = "Night navigation - Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun NightDateNavigationPreview() {
    val date = LocalDate.of(2026, 11, 14)
    SnorelyzerTheme(darkTheme = isSystemInDarkTheme()) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            NightDateNavigation(
                selectedDate = date,
                availableDates = setOf(date, date.minusDays(2)),
                onDateSelected = {},
                onOpenCalendar = {}
            )
        }
    }
}

@Preview(name = "Night calendar - Light")
@Preview(name = "Night calendar - Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun NightDatePickerPreview() {
    val date = LocalDate.of(2026, 11, 14)
    SnorelyzerTheme(darkTheme = isSystemInDarkTheme()) {
        NightDatePickerDialog(date, setOf(date, date.minusDays(2)), {}, {})
    }
}
