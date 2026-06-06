package com.example.snorelyzer.data.local

import androidx.room.TypeConverter
import com.example.snorelyzer.ml.recording.RecordedEventGroup

class RecordedEventGroupConverter {
    @TypeConverter
    fun fromRecordedEventGroup(group: RecordedEventGroup): String = group.name

    @TypeConverter
    fun toRecordedEventGroup(value: String): RecordedEventGroup {
        return RecordedEventGroup.valueOf(value)
    }
}
