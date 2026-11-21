package com.example.snorelyzer.data.local

import com.example.snorelyzer.SleepTrackingSessionController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal class SleepDataDeletion(
    private val sessionController: SleepTrackingSessionController,
    private val metadataSink: RoomRecordingMetadataSink,
    private val localDataSource: SleepRecordingLocalDataSource,
    private val interruptionStore: RecordingInterruptionStore
) {
    suspend fun deleteAll(): Boolean {
        val sessionMutex = sessionController.sessionMutex
        if (!sessionMutex.tryLock()) return false

        try {
            withContext(Dispatchers.IO + NonCancellable) {
                // The recorder can release the session lock before its queued Room writes finish.
                metadataSink.afterPendingWrites {
                    localDataSource.deleteAllSessions()
                    interruptionStore.clear()
                    sessionController.clearRecordingStatus()
                }
            }
            return true
        } finally {
            sessionMutex.unlock()
        }
    }
}
