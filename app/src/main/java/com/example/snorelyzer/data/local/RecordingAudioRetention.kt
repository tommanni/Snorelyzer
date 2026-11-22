package com.example.snorelyzer.data.local

import android.content.Context
import java.io.IOException
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class AudioRetentionPeriod(val days: Long?) {
    SevenDays(7),
    ThirtyDays(30),
    NinetyDays(90),
    Indefinitely(null);

    fun cutoffAt(nowMillis: Long): Long? = days?.let { nowMillis - Duration.ofDays(it).toMillis() }

    companion object {
        val Default = ThirtyDays
    }
}

internal enum class AudioRetentionChange { Applied, ConfirmationRequired }
internal enum class AudioCleanupResult { Complete, Retry }

internal class AudioRetentionPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("audio_retention", Context.MODE_PRIVATE)
    private val _period = MutableStateFlow(
        AudioRetentionPeriod.entries.firstOrNull { it.name == preferences.getString(PeriodKey, null) }
            ?: AudioRetentionPeriod.Default
    )
    val period = _period.asStateFlow()

    fun setPeriod(period: AudioRetentionPeriod) {
        if (!preferences.edit().putString(PeriodKey, period.name).commit()) {
            throw IOException("Could not save audio retention preference")
        }
        _period.value = period
    }

    private companion object {
        const val PeriodKey = "period"
    }
}

internal class RecordingAudioRetention(
    private val preferences: AudioRetentionPreferences,
    private val recordingData: RecordingAudioRetentionDataSource,
    private val recordingMaintenance: RecordingMaintenance,
    private val audioAccess: RecordingAudioAccess,
    private val enqueueCleanup: () -> Unit
) {
    private val policyMutex = Mutex()
    val period = preferences.period

    suspend fun changePeriod(
        newPeriod: AudioRetentionPeriod,
        confirmed: Boolean = false,
        nowMillis: Long = System.currentTimeMillis()
    ): AudioRetentionChange = withContext(Dispatchers.IO) {
        policyMutex.withLock {
            val current = period.value
            if (newPeriod == current) return@withLock AudioRetentionChange.Applied

            val newCutoff = newPeriod.cutoffAt(nowMillis)
            val previousCutoff = current.cutoffAt(nowMillis)
            val isShorter = newCutoff != null && (previousCutoff == null || newCutoff > previousCutoff)
            if (isShorter && !confirmed && recordingData.expirableNights(newCutoff, previousCutoff)
                    .any(ExpirableNightAudio::hasFilesOnDisk)
            ) {
                return@withLock AudioRetentionChange.ConfirmationRequired
            }

            preferences.setPeriod(newPeriod)
            if (isShorter) enqueueCleanup()
            AudioRetentionChange.Applied
        }
    }

    suspend fun cleanUp(nowMillis: Long = System.currentTimeMillis()): AudioCleanupResult = withContext(Dispatchers.IO) {
        policyMutex.withLock {
            val cutoff = period.value.cutoffAt(nowMillis) ?: return@withLock AudioCleanupResult.Complete

            recordingMaintenance.tryRunWhenIdle {
                var result = AudioCleanupResult.Complete
                for (night in recordingData.expirableNights(cutoff)) {
                    val lease = audioAccess.tryExpire(night.date)
                    if (lease == null || !lease.use { recordingData.expireNightAudio(night) }) {
                        result = AudioCleanupResult.Retry
                    }
                }
                result
            } ?: AudioCleanupResult.Retry
        }
    }
}
