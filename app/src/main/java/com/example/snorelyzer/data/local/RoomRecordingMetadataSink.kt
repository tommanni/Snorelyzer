package com.example.snorelyzer.data.local

import android.util.Log
import com.example.snorelyzer.ml.recording.RecordingEpisodeMetadata
import com.example.snorelyzer.ml.recording.RecordingMetadataSink
import com.example.snorelyzer.ml.recording.RecordingSessionMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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

    override fun onSessionStarted(session: RecordingSessionMetadata, onFailure: () -> Unit) {
        persist("Failed to save start of ${session.sessionId}", { if (!it) onFailure() }) {
            localDataSource.upsertSession(session)
        }
    }

    override fun onEpisodeCompleted(episode: RecordingEpisodeMetadata, onFailure: () -> Unit) {
        persist("Failed to save metadata for ${episode.episodeId}", { if (!it) onFailure() }) {
            localDataSource.insertEpisode(episode)
        }
    }

    override fun onSessionCompleted(session: RecordingSessionMetadata, onResult: (Boolean) -> Unit) {
        persist("Failed to save completion of ${session.sessionId}", onResult) {
            localDataSource.completeSession(session)
        }
    }

    override fun onSessionDiscarded(sessionId: String) {
        persist("Failed to discard $sessionId", onResult = {}) {
            localDataSource.deleteSession(sessionId)
        }
    }

    internal suspend fun <T> afterPendingWrites(block: suspend () -> T): T =
        persistenceMutex.withLock { block() }

    private fun persist(
        errorMessage: String,
        onResult: (Boolean) -> Unit,
        block: suspend () -> Unit
    ) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            persistenceMutex.withLock {
                val saved = try {
                    block()
                    true
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    runCatching { Log.e(TAG, errorMessage, error) }
                    false
                }
                onResult(saved)
            }
        }
    }

    private companion object {
        const val TAG = "RecordingMetadataSink"
    }
}
