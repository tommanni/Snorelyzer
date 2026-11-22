package com.example.snorelyzer.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.ZoneId

@Database(
    entities = [
        RecordingSessionEntity::class,
        RecordingEpisodeEntity::class,
        RecordingEpisodeGroupEntity::class,
        EventSpanEntity::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(RecordedEventGroupConverter::class)
abstract class SnorelyzerDatabase : RoomDatabase() {
    abstract val sleepRecordingDao: SleepRecordingDao
}

internal object RecordingRetentionMigration : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE recording_sessions ADD COLUMN nightEpochDay INTEGER")
        db.execSQL("ALTER TABLE recording_episodes ADD COLUMN audioExpired INTEGER NOT NULL DEFAULT 0")

        val zone = ZoneId.systemDefault()
        val nightDates = db.query("SELECT sessionId, startedAtMillis FROM recording_sessions").use { rows ->
            buildList {
                while (rows.moveToNext()) {
                    add(rows.getString(0) to sleepNightDateFor(rows.getLong(1), zone).toEpochDay())
                }
            }
        }
        db.compileStatement("UPDATE recording_sessions SET nightEpochDay = ? WHERE sessionId = ?").use { update ->
            for ((sessionId, nightDate) in nightDates) {
                update.bindLong(1, nightDate)
                update.bindString(2, sessionId)
                update.executeUpdateDelete()
            }
        }
    }
}
