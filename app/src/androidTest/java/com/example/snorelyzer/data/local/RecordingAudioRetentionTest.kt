package com.example.snorelyzer.data.local

import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.snorelyzer.SleepTrackingSessionController
import com.example.snorelyzer.ml.recording.AudioEventRecorder
import com.example.snorelyzer.ml.recording.RecordingEpisodeMetadata
import com.example.snorelyzer.ml.recording.RecordingSessionMetadata
import com.example.snorelyzer.ml.recording.WavAudioClipWriter
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingAudioRetentionTest {
    @Test
    fun shorteningRequiresConfirmationAndCleanupWaitsUntilTheNightIsFree() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val clips = Files.createTempDirectory(context.cacheDir.toPath(), "audio-retention-").toFile()
        val preferencesName = clips.name
        val testContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) =
                context.getSharedPreferences(preferencesName, mode)
        }

        try {
            val database = Room.inMemoryDatabaseBuilder(context, SnorelyzerDatabase::class.java).build()
            try {
                val writer = WavAudioClipWriter(clips)
                val dataSource = RoomSleepRecordingDataSource(database, writer)
                val sink = RoomRecordingMetadataSink(dataSource)
                val controller = SleepTrackingSessionController(
                    audioEventRecorder = AudioEventRecorder(clipWriter = writer, metadataSink = sink),
                    audioProcessingPipelineFactory = { error("Unexpected recording") }
                )
                val audioAccess = RecordingAudioAccess()
                var scheduledCleanups = 0
                val retention = RecordingAudioRetention(
                    AudioRetentionPreferences(testContext), dataSource, RecordingMaintenance(controller, sink), audioAccess
                ) { scheduledCleanups++ }
                val now = Instant.parse("2026-11-22T07:00:00Z").toEpochMilli()
                val end = now - Duration.ofDays(10).toMillis()
                val start = end - 5_000L
                val sessionId = "session-$start"
                val clip = File(clips, "clip.wav").apply { writeBytes(byteArrayOf(1)) }
                val orphan = File(clips, "${sessionId}_${sessionId}-episode-2_${start}_$end.opus")
                    .apply { writeBytes(byteArrayOf(2)) }
                dataSource.upsertSession(
                    RecordingSessionMetadata(sessionId, start, end, recordingZoneId = ZoneOffset.UTC)
                )
                dataSource.insertEpisode(
                    RecordingEpisodeMetadata(
                        "clip", sessionId, start, end, emptySet(), emptyList(), emptyMap(),
                        sampleRate = 32_000, durationMillis = 5_000L, filePath = clip.absolutePath
                    )
                )
                val night = dataSource.observeSleepNights(ZoneOffset.UTC).first().single()

                assertEquals(AudioRetentionPeriod.ThirtyDays, retention.period.value)
                assertEquals(AudioCleanupResult.Complete, retention.cleanUp(now))
                assertTrue(clip.exists())
                assertTrue(orphan.exists())
                assertEquals(
                    AudioRetentionChange.ConfirmationRequired,
                    retention.changePeriod(AudioRetentionPeriod.SevenDays, nowMillis = now)
                )
                assertEquals(AudioRetentionPeriod.ThirtyDays, retention.period.value)
                assertEquals(0, scheduledCleanups)

                assertEquals(
                    AudioRetentionChange.Applied,
                    retention.changePeriod(AudioRetentionPeriod.SevenDays, confirmed = true, nowMillis = now)
                )
                assertEquals(AudioRetentionPeriod.SevenDays, AudioRetentionPreferences(testContext).period.value)
                assertEquals(1, scheduledCleanups)
                checkNotNull(audioAccess.tryUse(night.date)).use {
                    assertEquals(AudioCleanupResult.Retry, retention.cleanUp(now))
                    assertTrue(clip.exists())
                }
                assertEquals(AudioCleanupResult.Complete, retention.cleanUp(now))
                assertFalse(clip.exists())
                assertFalse(orphan.exists())
                assertTrue(dataSource.observeSleepNights(ZoneOffset.UTC).first().single().allEpisodes.single().episode.audioExpired)
            } finally {
                database.close()
            }
        } finally {
            context.deleteSharedPreferences(preferencesName)
            clips.deleteRecursively()
        }
    }
}
