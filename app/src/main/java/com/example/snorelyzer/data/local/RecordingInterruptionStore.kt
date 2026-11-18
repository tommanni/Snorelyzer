package com.example.snorelyzer.data.local

import android.content.Context

internal class RecordingInterruptionStore(context: Context) {
    private val preferences = context.getSharedPreferences("recording_status", Context.MODE_PRIVATE)

    var interruptedAtMillis: Long?
        get() = preferences.getLong(INTERRUPTED_AT, 0L).takeIf { it != 0L }
        set(value) {
            val edit = preferences.edit()
            if (value == null) {
                edit.remove(INTERRUPTED_AT)
            } else {
                edit.putLong(INTERRUPTED_AT, value)
            }
            edit.apply()
        }

    private companion object {
        const val INTERRUPTED_AT = "interrupted_at_millis"
    }
}
