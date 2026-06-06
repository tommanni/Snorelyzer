package com.example.snorelyzer.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        RecordingSessionEntity::class,
        RecordingEpisodeEntity::class,
        RecordingEpisodeGroupEntity::class,
        EventSpanEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(RecordedEventGroupConverter::class)
abstract class SnorelyzerDatabase : RoomDatabase() {
    abstract val sleepRecordingDao: SleepRecordingDao
}
