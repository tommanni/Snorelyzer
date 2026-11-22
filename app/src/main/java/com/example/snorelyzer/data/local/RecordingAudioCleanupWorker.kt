package com.example.snorelyzer.data.local

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import org.koin.core.context.GlobalContext

class RecordingAudioCleanupWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val retention = GlobalContext.get().get<RecordingAudioRetention>()
        when (retention.cleanUp()) {
            AudioCleanupResult.Complete -> Result.success()
            AudioCleanupResult.Retry -> Result.retry()
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.e("RecordingAudioCleanup", "Could not clean up expired recording audio", error)
        Result.retry()
    }
}

internal object RecordingAudioCleanupSchedule {
    fun daily(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "daily-recording-audio-cleanup",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RecordingAudioCleanupWorker>(1, TimeUnit.DAYS).build()
        )
    }

    fun now(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "immediate-recording-audio-cleanup",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<RecordingAudioCleanupWorker>().build()
        )
    }
}
