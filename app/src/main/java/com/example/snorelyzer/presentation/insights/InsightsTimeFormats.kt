package com.example.snorelyzer.presentation.insights

import android.icu.text.SimpleDateFormat
import android.icu.util.TimeZone
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import java.util.Date
import java.util.Locale

@Composable
internal fun rememberInsightsTimeFormats(): InsightsTimeFormats {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    var use24HourClock by remember(context, locale) { mutableStateOf(DateFormat.is24HourFormat(context)) }
    var timeZone by remember { mutableStateOf(TimeZone.getDefault()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        use24HourClock = DateFormat.is24HourFormat(context)
        timeZone = TimeZone.getDefault()
    }
    return remember(locale, use24HourClock, timeZone) {
        InsightsTimeFormats(locale, use24HourClock, timeZone)
    }
}

internal class InsightsTimeFormats(locale: Locale, use24HourClock: Boolean, timeZone: TimeZone) {
    private val minutes = SimpleDateFormat(
        if (use24HourClock) "HH:mm" else DateFormat.getBestDateTimePattern(locale, "hm"),
        locale
    ).apply { this.timeZone = timeZone }

    private val seconds = SimpleDateFormat(
        if (use24HourClock) "HH:mm:ss" else DateFormat.getBestDateTimePattern(locale, "hms"),
        locale
    ).apply { this.timeZone = timeZone }

    fun formatMinutes(millis: Long): String = minutes.format(Date(millis))

    fun formatSeconds(millis: Long): String = seconds.format(Date(millis))
}
