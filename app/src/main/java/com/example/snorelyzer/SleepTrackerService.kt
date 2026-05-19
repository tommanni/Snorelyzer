package com.example.snorelyzer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import com.example.snorelyzer.ml.AudioGate
import com.example.snorelyzer.ml.AudioProcessor
import com.example.snorelyzer.ml.SleepClassifier
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.android.ext.android.inject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.milliseconds

data class DetectedClassUi(
    val label: String,
    val probabilityPercent: Int
)

class SleepTrackerService : Service() {

    companion object {
        private val _latestStatus = MutableStateFlow("Waiting for audio...")
        val latestStatus = _latestStatus.asStateFlow()

        private val _latestResults = MutableStateFlow<List<DetectedClassUi>>(emptyList())
        val latestResults = _latestResults.asStateFlow()

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning = _isServiceRunning.asStateFlow()

        private val summaryAccumulator = NightSummaryAccumulator()
        private val _nightSummary = MutableStateFlow(NightSummaryUi())
        val nightSummary = _nightSummary.asStateFlow()
    }
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var isRecording = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null

    private val audioProcessor: AudioProcessor by inject()
    private val audioGate: AudioGate by inject()
    private val classifier: SleepClassifier by inject()

    // 320,000 samples needed for full context (10 seconds @ 32kHz)
    // We step by 32,000 samples per inference (1 second)
    private val totalSamples = 320000
    private val stepSamples = 32000
    private val audioBuffer = FloatArray(totalSamples)

    override fun onCreate() {
        super.onCreate()
        _isServiceRunning.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
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

        val stopIntent = Intent(this, SleepTrackerService::class.java).apply { action = "STOP" }
        val pendingStop = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Sleep Tracker Active")
            .setContentText("Listening for sleep events...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", pendingStop)
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

        summaryAccumulator.startSession(System.currentTimeMillis())
        _nightSummary.value = summaryAccumulator.snapshot(System.currentTimeMillis())

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
            audioGate.reset()

            scope.launch {
                val tempBuffer = FloatArray(stepSamples)

                // Pre-fill initial buffer
                var initialRead = 0
                while (initialRead < totalSamples && isRecording.get()) {
                    val toRead = minOf(tempBuffer.size, totalSamples - initialRead)
                    val read = audioRecord?.read(tempBuffer, 0, toRead, AudioRecord.READ_BLOCKING) ?: 0
                    if (read > 0) {
                        System.arraycopy(tempBuffer, 0, audioBuffer, initialRead, read)
                        initialRead += read
                    }
                }

                while (isRecording.get()) {
                    // Read new chunk
                    var read = 0
                    var retryCount = 0
                    while (read < stepSamples && isRecording.get()) {
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

                    if (!isRecording.get()) break

                    // Shift buffer left by stepSamples
                    System.arraycopy(audioBuffer, stepSamples, audioBuffer, 0, totalSamples - stepSamples)
                    // Copy new data to right
                    System.arraycopy(tempBuffer, 0, audioBuffer, totalSamples - stepSamples, stepSamples)

                    val gateDecision = audioGate.analyze(tempBuffer)
                    summaryAccumulator.recordGateDecision(gateDecision)
                    _nightSummary.value = summaryAccumulator.snapshot(System.currentTimeMillis())
                    Log.d(
                        "AudioGate",
                        "state=${gateDecision.state} infer=${gateDecision.shouldInfer} " +
                            "trigger=${gateDecision.triggerType} " +
                            "noiseFloorDb=${"%.1f".format(gateDecision.noiseFloorDb)} " +
                            "rmsDb=${"%.1f".format(gateDecision.rmsDb)} " +
                            "relativeDb=${"%.1f".format(gateDecision.relativeDb)} " +
                            "maxFrameRelativeDb=${"%.1f".format(gateDecision.maxFrameRelativeDb)} " +
                            "onsetDb=${"%.1f".format(gateDecision.onsetDb)} " +
                            "zcr=${"%.3f".format(gateDecision.zcr)} " +
                            "zcrDelta=${"%.3f".format(gateDecision.zcrDelta)} " +
                            "crest=${"%.2f".format(gateDecision.crestFactor)} " +
                            "crestDelta=${"%.2f".format(gateDecision.crestDelta)} " +
                            "activityScore=${gateDecision.activityScore} " +
                            "stable=${gateDecision.isStableBackground} " +
                            "reasons=${gateDecision.reasons.joinToString("|")}"
                    )

                    if (!gateDecision.shouldInfer) {
                        // AudioProcessor caches incremental mel frames, so skipped seconds invalidate that cache.
                        audioProcessor.reset()
                        continue
                    }

                    // Clone for processing to avoid mutation during inference
                    val processBuffer = audioBuffer.clone()

                    val audioMin = processBuffer.min()
                    val audioMax = processBuffer.max()
                    val audioRms = sqrt(processBuffer.map { it * it }.average())
                    Log.d("SleepTracker", "Audio stats: min=$audioMin max=$audioMax rms=$audioRms")

                    processAndClassify(processBuffer)
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

    private fun processAndClassify(buffer: FloatArray) {
        val start = System.currentTimeMillis()
        try {
            // 1. DSP
            val t0 = System.nanoTime()
            val melTensor = audioProcessor.process(buffer)
            val t1 = System.nanoTime()

            val min = melTensor.min()
            val max = melTensor.max()
            val mean = melTensor.average()
            Log.d("Debug", "Mel stats: min=$min max=$max mean=$mean")

            // 2. Inference
            val topResults = classifier.classify(melTensor, topK = 3)
            val t2 = System.nanoTime()
            Log.d(
                "Timing",
                "Preprocessing: ${(t1 - t0) / 1_000_000}ms, Inference: ${(t2 - t1) / 1_000_000}ms"
            )

            // 3. Log results
            if (topResults.isNotEmpty()) {
                summaryAccumulator.recordClassificationResults(topResults.take(3))
                _nightSummary.value = summaryAccumulator.snapshot(System.currentTimeMillis())

                val top1 = topResults[0]
                val top2 = if (topResults.size > 1) topResults[1] else null

                val logMsg = buildString {
                    append("Inference ${System.currentTimeMillis() - start}ms | ")
                    append("Top: ${top1.label} [#${top1.index}] (${"%.3f".format(top1.probability)})")
                    if (top2 != null) {
                        append(" | 2nd: ${top2.label} [#${top2.index}] (${"%.3f".format(top2.probability)})")
                    }
                }
                Log.d("SleepTracker", logMsg)
                _latestStatus.value = ""
                _latestResults.value = topResults.take(3).map { result ->
                    DetectedClassUi(
                        label = result.label,
                        probabilityPercent = (result.probability * 100).toInt()
                    )
                }
            }

        } catch (e: Exception) {
            Log.e("SleepTracker", "Processing failed", e)
        }
    }

    override fun onDestroy() {
        _isServiceRunning.value = false
        _latestStatus.value = "Stopped"
        _latestResults.value = emptyList()
        summaryAccumulator.stopSession(System.currentTimeMillis())
        _nightSummary.value = summaryAccumulator.snapshot(System.currentTimeMillis())
        isRecording.set(false)
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        audioProcessor.reset()
        audioGate.reset()
        scope.cancel()
        classifier.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
