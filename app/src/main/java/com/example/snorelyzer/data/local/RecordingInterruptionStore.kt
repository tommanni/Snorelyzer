package com.example.snorelyzer.data.local

import android.content.Context
import java.io.IOException

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

    fun clear() {
        if (!preferences.edit().remove(INTERRUPTED_AT).commit()) {
            throw IOException("Could not clear recording interruption status")
        }
    }

    private companion object {
        const val INTERRUPTED_AT = "interrupted_at_millis"
    }
}
