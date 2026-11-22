package com.example.snorelyzer.data.local

import com.example.snorelyzer.SleepTrackingSessionController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal class SleepDataDeletion(
    private val sessionController: SleepTrackingSessionController,
    private val recordingMaintenance: RecordingMaintenance,
    private val localDataSource: SleepRecordingLocalDataSource,
    private val interruptionStore: RecordingInterruptionStore
) {
    suspend fun deleteAll(): Boolean = withContext(Dispatchers.IO + NonCancellable) {
        recordingMaintenance.tryRunWhenIdle {
            localDataSource.deleteAllSessions()
            interruptionStore.clear()
            sessionController.clearRecordingStatus()
        } != null
    }
}
