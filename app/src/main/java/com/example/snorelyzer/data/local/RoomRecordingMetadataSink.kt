package com.example.snorelyzer.data.local

import android.util.Log
import com.example.snorelyzer.ml.recording.RecordingEpisodeMetadata
import com.example.snorelyzer.ml.recording.RecordingMetadataSink
import com.example.snorelyzer.ml.recording.RecordingSessionMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RoomRecordingMetadataSink(
    private val localDataSource: SleepRecordingLocalDataSource
) : RecordingMetadataSink {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistenceMutex = Mutex()

    override fun onSessionStarted(session: RecordingSessionMetadata) {
        persist("Failed to persist recording session start") {
            localDataSource.upsertSession(session)
        }
    }

    override fun onEpisodeCompleted(episode: RecordingEpisodeMetadata) {
        persist("Failed to persist recording episode") {
            localDataSource.insertEpisode(episode)
        }
    }

    override fun onSessionCompleted(session: RecordingSessionMetadata) {
        persist("Failed to persist recording session completion") {
            localDataSource.completeSession(session)
        }
    }

    private fun persist(errorMessage: String, block: suspend () -> Unit) {
        scope.launch {
            persistenceMutex.withLock {
                runCatching { block() }
                    .onFailure { Log.e(TAG, errorMessage, it) }
            }
        }
    }

    private companion object {
        const val TAG = "RecordingMetadataSink"
    }
}
