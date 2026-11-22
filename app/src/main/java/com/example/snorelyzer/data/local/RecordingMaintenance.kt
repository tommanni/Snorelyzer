package com.example.snorelyzer.data.local

import com.example.snorelyzer.SleepTrackingSessionController

internal class RecordingMaintenance(
    private val sessionController: SleepTrackingSessionController,
    private val metadataSink: RoomRecordingMetadataSink
) {
    suspend fun <T : Any> tryRunWhenIdle(action: suspend () -> T): T? {
        val sessionMutex = sessionController.sessionMutex
        if (!sessionMutex.tryLock()) return null
        try {
            // Final Room writes can still be pending after capture releases the session lock.
            return metadataSink.afterPendingWrites(action)
        } finally {
            sessionMutex.unlock()
        }
    }
}
