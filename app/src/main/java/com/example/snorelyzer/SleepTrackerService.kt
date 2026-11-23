package com.example.snorelyzer

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.snorelyzer.data.local.RecordingInterruptionStore
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class SleepTrackerService : Service() {
    companion object {
        const val ACTION_STOP_SAVE = "com.example.snorelyzer.action.STOP_SAVE"
        const val ACTION_STOP_DISCARD = "com.example.snorelyzer.action.STOP_DISCARD"

        private const val TAG = "SleepTracker"
        private const val CHANNEL_ID = "sleep_tracker_channel"
        private const val NOTIFICATION_ID = 1
        private const val RECOVERY_WAKE_LOCK_TIMEOUT_MILLIS = 6 * 60_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var processingJob: Job? = null
    private var destroyed = false
    private val stopAction = AtomicReference<String?>(null)

    private val sessionController: SleepTrackingSessionController by inject()
    private val interruptionStore: RecordingInterruptionStore by inject()
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP_SAVE || action == ACTION_STOP_DISCARD) {
            stopAction.compareAndSet(null, action)
            processingJob?.cancel() ?: stopSelf()
            return START_NOT_STICKY
        }

        if (stopAction.get() != null) return START_NOT_STICKY
        if (processingJob != null) return START_STICKY

        try {
            enterForeground()
        } catch (error: SecurityException) {
            Log.e(TAG, "Cannot start microphone foreground service", error)
            stopSelf()
            return START_NOT_STICKY
        }

        processingJob = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    sessionController.sessionMutex.withLock { trackSession() }
                }
            } finally {
                if (!destroyed) stopSelf()
            }
        }
        return START_STICKY
    }

    private fun enterForeground() {
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.sleep_tracker_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val notification = notification(R.string.record_waiting_for_microphone)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notification(@StringRes status: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.sleep_tracker_active))
            .setContentText(getString(status))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()

    private suspend fun updateNotification(@StringRes status: Int) {
        withContext(Dispatchers.Main.immediate) {
            notificationManager.notify(NOTIFICATION_ID, notification(status))
        }
    }

    private suspend fun trackSession() {
        val startedAtMillis = System.currentTimeMillis()
        val startedAtElapsedMillis = SystemClock.elapsedRealtime()
        fun nowMillis() = startedAtMillis + (SystemClock.elapsedRealtime() - startedAtElapsedMillis)

        val recoveryWakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Snorelyzer:MicrophoneRecovery")
        var lastCapturedAtMillis: Long? = null
        var reconnecting = false
        var failed = false
        try {
            recoveryWakeLock.acquire(RECOVERY_WAKE_LOCK_TIMEOUT_MILLIS)
            interruptionStore.interruptedAtMillis = null
            sessionController.startSession(startedAtMillis)
            MicrophoneCapture(openInput = ::openMicrophone, nowMillis = ::nowMillis).run(
                onInterrupted = { error ->
                    Log.w(TAG, error.message, error.cause)
                    if (!reconnecting) {
                        if (!recoveryWakeLock.isHeld) {
                            recoveryWakeLock.acquire(RECOVERY_WAKE_LOCK_TIMEOUT_MILLIS)
                        }
                        reconnecting = true
                        sessionController.onCaptureInterrupted()
                        updateNotification(R.string.record_reconnecting_microphone)
                    }
                },
                onChunk = { chunk, chunkStartMillis ->
                    val isFirstChunk = lastCapturedAtMillis == null
                    sessionController.onAudioChunk(chunk, chunkStartMillis)
                    lastCapturedAtMillis = chunkStartMillis + MicrophoneCapture.CHUNK_DURATION_MILLIS
                    if (isFirstChunk || reconnecting) {
                        reconnecting = false
                        if (recoveryWakeLock.isHeld) recoveryWakeLock.release()
                        updateNotification(R.string.record_listening_for_sleep_events)
                    }
                }
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            failed = true
            Log.e(TAG, "Sleep recording was interrupted", error)
        } finally {
            try {
                val action = stopAction.get()
                val interrupted = failed && action == null
                val endedAtMillis = if (reconnecting || failed) {
                    lastCapturedAtMillis ?: nowMillis()
                } else {
                    nowMillis()
                }
                try {
                    if (!recoveryWakeLock.isHeld) recoveryWakeLock.acquire(RECOVERY_WAKE_LOCK_TIMEOUT_MILLIS)
                    sessionController.stopSession(
                        save = action != ACTION_STOP_DISCARD && (!interrupted || lastCapturedAtMillis != null),
                        endedAtMillis = endedAtMillis,
                        interrupted = interrupted,
                        explicitlyRequestedSave = action == ACTION_STOP_SAVE
                    )
                } finally {
                    if (interrupted) interruptionStore.interruptedAtMillis = endedAtMillis
                }
            } catch (error: Exception) {
                Log.e(TAG, "Could not finalize sleep recording", error)
            } finally {
                if (recoveryWakeLock.isHeld) recoveryWakeLock.release()
            }
        }
    }

    private fun openMicrophone(): MicrophoneInput {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("Microphone permission denied")
        }

        val sampleRate = MicrophoneCapture.SAMPLE_RATE
        val channel = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_FLOAT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channel, encoding)
        require(minBufferSize != AudioRecord.ERROR_BAD_VALUE) { "Unsupported microphone format" }
        if (minBufferSize <= 0) throw MicrophoneUnavailableException("Microphone input is unavailable")

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channel,
            encoding,
            maxOf(minBufferSize, MicrophoneCapture.SAMPLES_PER_CHUNK * Float.SIZE_BYTES)
        )

        val input = object : MicrophoneInput {
            override fun read(buffer: FloatArray, offset: Int, length: Int): Int {
                val count = try {
                    recorder.read(buffer, offset, length, AudioRecord.READ_NON_BLOCKING)
                } catch (error: IllegalStateException) {
                    throw MicrophoneUnavailableException("Microphone read failed", error)
                }
                require(count != AudioRecord.ERROR_BAD_VALUE) { "Invalid microphone read parameters" }
                return count
            }

            override fun close() {
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
            }
        }

        try {
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
        } catch (error: IllegalStateException) {
            input.close()
            throw MicrophoneUnavailableException("Could not start microphone capture", error)
        } catch (error: Exception) {
            input.close()
            throw error
        }
        return input
    }

    override fun onDestroy() {
        destroyed = true
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
