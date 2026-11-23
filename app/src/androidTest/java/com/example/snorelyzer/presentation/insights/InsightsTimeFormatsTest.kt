package com.example.snorelyzer.presentation.insights

import android.icu.util.TimeZone
import java.time.Instant
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class InsightsTimeFormatsTest {
    private val timeZone = TimeZone.getTimeZone("Europe/Helsinki")
    private val midnightLocal = Instant.parse("2026-11-23T22:00:00Z").toEpochMilli()
    private val eveningLocal = Instant.parse("2026-11-23T19:05:37Z").toEpochMilli()

    @Test
    fun finnish24HourClockUsesPaddedHoursAndOptionalSeconds() {
        val formats = InsightsTimeFormats(Locale.forLanguageTag("fi-FI"), true, timeZone)

        assertEquals("00:00", formats.formatMinutes(midnightLocal))
        assertEquals("21:05", formats.formatMinutes(eveningLocal))
        assertEquals("21:05:37", formats.formatSeconds(eveningLocal))
    }

    @Test
    fun twelveHourClockShowsDayPeriodForMinutesAndSeconds() {
        val formats = InsightsTimeFormats(Locale.US, false, timeZone)

        assertEquals("12:00 AM", formats.formatMinutes(midnightLocal).withSpaces())
        assertEquals("9:05 PM", formats.formatMinutes(eveningLocal).withSpaces())
        assertEquals("9:05:37 PM", formats.formatSeconds(eveningLocal).withSpaces())
    }

    private fun String.withSpaces(): String = replace('\u202f', ' ').replace('\u00a0', ' ')
}
