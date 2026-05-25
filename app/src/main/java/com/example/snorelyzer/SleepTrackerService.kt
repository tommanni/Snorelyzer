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
import com.example.snorelyzer.ml.recording.AudioEventRecorder
import com.example.snorelyzer.ml.recording.RecordedEventCatalog
import com.example.snorelyzer.ml.recording.displayLabel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.android.ext.android.inject
import java.util.concurrent.atomic.AtomicBoolean
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
    }
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var isRecording = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var processingJob: Job? = null

    private val audioProcessor: AudioProcessor by inject()
    private val audioGate: AudioGate by inject()
    private val classifier: SleepClassifier by inject()
    private val audioEventRecorder: AudioEventRecorder by inject()

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
            val sessionStartedAtMillis = System.currentTimeMillis()
            audioEventRecorder.startSession(sessionStartedAtMillis)

            processingJob = scope.launch {
                val tempBuffer = FloatArray(stepSamples)
                val mlInputBuffer = FloatArray(totalSamples)
                val paddedMLBuffer = FloatArray(totalSamples)
                var validSamples = 0
                var writeIndex = 0
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
                    audioEventRecorder.onAudioChunk(tempBuffer, chunkStartMillis)
                    capturedSamples += stepSamples

                    System.arraycopy(tempBuffer, 0, audioBuffer, writeIndex, stepSamples)
                    writeIndex = (writeIndex + stepSamples) % totalSamples
                    validSamples = minOf(totalSamples, validSamples + stepSamples)
                    val mlWindowEndMillis = sessionStartedAtMillis + capturedSamples / 32
                    val mlWindowStartMillis = mlWindowEndMillis - (validSamples / 32)

                    val gateDecision = audioGate.analyze(tempBuffer)
                    Log.d(
                        "AudioGate",
                        "state=${gateDecision.state} infer=${gateDecision.shouldInfer} " +
                            "recorderForce=${audioEventRecorder.shouldForceInference} " +
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

                    val shouldInfer = gateDecision.shouldInfer || audioEventRecorder.shouldForceInference
                    if (!shouldInfer) {
                        // AudioProcessor caches incremental mel frames, so skipped seconds invalidate that cache.
                        audioProcessor.reset()
                        audioEventRecorder.onClassificationWindow(mlWindowStartMillis, emptyList())
                        _latestStatus.value = "Listening for sleep events..."
                        _latestResults.value = emptyList()
                        continue
                    }

                    val finalMLInput = if (validSamples >= totalSamples) {
                        System.arraycopy(audioBuffer, writeIndex, mlInputBuffer, 0, totalSamples - writeIndex)
                        System.arraycopy(audioBuffer, 0, mlInputBuffer, totalSamples - writeIndex, writeIndex)
                        mlInputBuffer
                    } else {
                        // Mirrored padding to fill the 10s buffer for ML
                        System.arraycopy(audioBuffer, 0, mlInputBuffer, totalSamples - validSamples, validSamples)
                        fillPaddedBuffer(paddedMLBuffer, mlInputBuffer, validSamples)
                        paddedMLBuffer
                    }

                    processAndClassify(finalMLInput, mlWindowStartMillis)
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

    /**
     * Fills the [dest] buffer by taking the [validCount] real samples from the end of [source]
     * and mirroring them back and forth until [dest] is full.
     */
    private fun fillPaddedBuffer(dest: FloatArray, source: FloatArray, validCount: Int) {
        val total = dest.size
        // Real samples are at the end of source
        val sourceOffset = total - validCount
        
        // Copy real samples to the end of dest
        System.arraycopy(source, sourceOffset, dest, total - validCount, validCount)
        
        var filled = validCount
        var forward = false // Next chunk to fill (working backwards) will be reversed (backward)
        
        while (filled < total) {
            val toFill = minOf(validCount, total - filled)
            val destPos = total - filled - toFill
            
            if (forward) {
                // Copy normally
                System.arraycopy(source, sourceOffset, dest, destPos, toFill)
            } else {
                // Copy reversed
                for (i in 0 until toFill) {
                    dest[destPos + i] = source[total - 1 - i]
                }
            }
            
            filled += toFill
            forward = !forward
        }
    }

    private fun processAndClassify(buffer: FloatArray, windowStartMillis: Long) {
        val start = System.currentTimeMillis()
        try {
            // 1. DSP
            val t0 = System.nanoTime()
            val melTensor = audioProcessor.process(buffer)
            val t1 = System.nanoTime()

            // 2. Inference
            val relevantResults = classifier.classifyRelevant(
                melTensor,
                RecordedEventCatalog.relevantClassIndices
            )
            val groupResults = RecordedEventCatalog.aggregate(
                relevantResults,
                audioEventRecorder.config
            )
            val occurringGroupResults = groupResults.filter { it.isOccurring }
            val t2 = System.nanoTime()
            audioEventRecorder.onClassificationWindow(windowStartMillis, occurringGroupResults)
            Log.d(
                "Timing",
                "Preprocessing: ${(t1 - t0) / 1_000_000}ms, Inference: ${(t2 - t1) / 1_000_000}ms"
            )

            // 3. Log results
            if (groupResults.isNotEmpty()) {
                val top1 = groupResults[0]
                val top2 = if (groupResults.size > 1) groupResults[1] else null

                val logMsg = buildString {
                    append("Inference ${System.currentTimeMillis() - start}ms | ")
                    append("Top relevant: ${top1.group.displayLabel()} via ${top1.sourceLabel} (${"%.3f".format(top1.probability)})")
                    if (top2 != null) {
                        append(" | 2nd: ${top2.group.displayLabel()} via ${top2.sourceLabel} (${"%.3f".format(top2.probability)})")
                    }
                }
                Log.d("SleepTracker", logMsg)
                _latestStatus.value = if (occurringGroupResults.isEmpty()) {
                    "Listening for sleep events..."
                } else {
                    ""
                }
                _latestResults.value = occurringGroupResults.map { result ->
                    DetectedClassUi(
                        label = result.group.displayLabel(),
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
        audioProcessor.reset()
        audioGate.reset()
        audioEventRecorder.stopSession(System.currentTimeMillis())
        audioEventRecorder.reset()
        scope.cancel()
        classifier.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
