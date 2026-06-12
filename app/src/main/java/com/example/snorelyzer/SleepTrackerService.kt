package com.example.snorelyzer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.koin.android.ext.android.inject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

class SleepTrackerService : Service() {

    companion object {
        const val ACTION_STOP_SAVE = "com.example.snorelyzer.action.STOP_SAVE"
        const val ACTION_STOP_DISCARD = "com.example.snorelyzer.action.STOP_DISCARD"
    }

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var isRecording = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var processingJob: Job? = null
    private var shouldSaveSessionOnStop = true

    private val sessionController: SleepTrackingSessionController by inject()

    private val stepSamples = 32_000

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SAVE) {
            shouldSaveSessionOnStop = true
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP_DISCARD) {
            shouldSaveSessionOnStop = false
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundService()
        startAudioProcessing()
        return START_STICKY
    }

    private fun startForegroundService() {
        val channelId = "sleep_tracker_channel"
        val channel = NotificationChannel(channelId, "Sleep Tracker", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Sleep Tracker Active")
            .setContentText("Listening for sleep events...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // For Android 11 (API 30) and newer declare the microphone type
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            // For Android 10 (API 29) and older we start the foreground service normally
            startForeground(1, notification)
        }
    }

    private fun startAudioProcessing() {
        if (isRecording.getAndSet(true)) return

        val minBufferSize = AudioRecord.getMinBufferSize(
            32000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                32000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
                maxOf(minBufferSize, stepSamples * 4) // Ensure enough buffer
            )

            audioRecord?.startRecording()
            val sessionStartedAtMillis = System.currentTimeMillis()
            sessionController.startSession(sessionStartedAtMillis)

            processingJob = scope.launch {
                val tempBuffer = FloatArray(stepSamples)
                var capturedSamples = 0L

                while (isRecording.get() && isActive) {
                    // Read new chunk
                    var read = 0
                    var retryCount = 0
                    while (read < stepSamples && isRecording.get() && isActive) {
                        val chunk = audioRecord?.read(tempBuffer, read, stepSamples - read, AudioRecord.READ_BLOCKING) ?: 0
                        if (chunk > 0) {
                            read += chunk
                            retryCount = 0
                        } else if (chunk < 0) {
                            Log.e("SleepTracker", "Audio read error: $chunk")
                            isRecording.set(false)
                            break
                        } else {
                            retryCount++
                            if (retryCount > 10) {
                                Log.e("SleepTracker", "Audio read timed out")
                                isRecording.set(false)
                                break
                            }
                            delay(10.milliseconds)
                        }
                    }

                    if (!isRecording.get() || !isActive) break

                    val chunkStartMillis = sessionStartedAtMillis + capturedSamples / 32
                    capturedSamples += stepSamples
                    sessionController.onAudioChunk(tempBuffer, chunkStartMillis)
                }
            }
        } catch (e: SecurityException) {
            Log.e("SleepTracker", "Microphone permission denied")
            stopSelf()
        } catch (e: Exception) {
            Log.e("SleepTracker", "Audio capture failed", e)
            stopSelf()
        }
    }

    override fun onDestroy() {
        isRecording.set(false)
        runCatching { audioRecord?.stop() }

        runBlocking {
            val stoppedCleanly = withTimeoutOrNull(2_000L.milliseconds) {
                processingJob?.join()
                true
            } == true

            if (!stoppedCleanly) {
                processingJob?.cancel()
                withTimeoutOrNull(500L.milliseconds) {
                    processingJob?.join()
                }
            }
        }

        processingJob = null

        runCatching { audioRecord?.release() }
        audioRecord = null
        sessionController.stopSession(
            save = shouldSaveSessionOnStop,
            endedAtMillis = System.currentTimeMillis()
        )
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
