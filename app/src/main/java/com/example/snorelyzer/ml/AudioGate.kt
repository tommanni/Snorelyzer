package com.example.snorelyzer.ml

import kotlin.math.exp
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

enum class AudioGateState {
    WarmingUp,
    Background,
    Candidate,
    Tail
}

data class AudioGateDecision(
    val shouldInfer: Boolean,
    val state: AudioGateState,
    val triggerType: String,
    val noiseFloorDb: Float,
    val rmsDb: Float,
    val relativeDb: Float,
    val maxFrameRelativeDb: Float,
    val onsetDb: Float,
    val zcr: Float,
    val zcrDelta: Float,
    val crestFactor: Float,
    val crestDelta: Float,
    val activityScore: Int,
    val isStableBackground: Boolean,
    val reasons: List<String>,
    val backgroundWindowSeconds: Float = 0f,
    val negativeWindowCount: Int = 0,
    val backgroundProtected: Boolean = false
)

/**
 * Conservative inference scheduler for 32 kHz PCM, not a sleep-event classifier.
 * Uses each chunk's lower-quintile frame energy, with 2s downward / 60s upward
 * smoothing. Upward learning requires 20s acoustic stability and three independent
 * negative classifier windows. Suspected events and recorder activity protect learning.
 * Thresholds are initial tuning values; validate recall against continuous inference.
 */
class AudioGate {
    private val frameSize = 1024
    private val hopSize = 512
    private val minRms = 1.0e-8f
    private var elapsedSeconds = 0.0
    private var lastInferenceSeconds = 0.0
    private var tailSeconds = 0.0
    private var protectedUntilSeconds = 0.0
    private var noiseFloorDb = Float.NaN
    private var backgroundZcr = Float.NaN
    private var backgroundCrestFactor = Float.NaN
    private var previousFrameDb = Float.NaN
    private data class BackgroundSample(
        val quietDb: Float, val maxDb: Float, val zcr: Float, val seconds: Double
    )
    private val backgroundWindow = ArrayDeque<BackgroundSample>()
    private var backgroundWindowSeconds = 0.0
    private var negativeWindows = 0
    private var lastNegativeEndMillis = Long.MIN_VALUE

    fun reset() {
        elapsedSeconds = 0.0
        lastInferenceSeconds = 0.0
        tailSeconds = 0.0
        protectedUntilSeconds = 0.0
        noiseFloorDb = Float.NaN
        backgroundZcr = Float.NaN
        backgroundCrestFactor = Float.NaN
        previousFrameDb = Float.NaN
        backgroundWindow.clear()
        backgroundWindowSeconds = 0.0
        negativeWindows = 0
        lastNegativeEndMillis = Long.MIN_VALUE
    }

    /** Negative evidence is counted only for non-overlapping classifier windows. */
    fun observeClassification(
        windowStartMillis: Long,
        windowEndMillis: Long,
        hasTargetEvidence: Boolean,
        confidentBackground: Boolean
    ) {
        require(windowEndMillis > windowStartMillis)
        when {
            hasTargetEvidence -> {
                protectedUntilSeconds = elapsedSeconds + 10.0
                negativeWindows = 0
                lastNegativeEndMillis = windowEndMillis
            }
            !confidentBackground -> {
                negativeWindows = 0
                lastNegativeEndMillis = windowEndMillis
            }
            windowStartMillis >= lastNegativeEndMillis &&
                windowEndMillis - windowStartMillis <= backgroundWindowSeconds * 1_000.0 -> {
                negativeWindows = (negativeWindows + 1).coerceAtMost(3)
                lastNegativeEndMillis = windowEndMillis
            }
        }
    }

    fun analyze(
        audioData: FloatArray,
        protectBackground: Boolean = false,
        minimumInferenceIntervalSeconds: Int = 0
    ): AudioGateDecision {
        require(audioData.size >= frameSize) { "At least $frameSize audio samples required" }
        require(audioData.all { it.isFinite() }) { "Audio samples must be finite" }
        val seconds = audioData.size / 32_000.0
        val features = extractFeatures(audioData)
        initializeBackgroundIfNeeded(features)
        val floorBeforeUpdate = noiseFloorDb
        val relativeDb = features.rmsDb - floorBeforeUpdate
        val maxFrameRelativeDb = features.maxFrameDb - floorBeforeUpdate
        val zcrDelta = abs(features.zcr - backgroundZcr)
        val crestDelta = max(0.0f, features.crestFactor - backgroundCrestFactor)
        val activityScore = calculateActivityScore(
            relativeDb, maxFrameRelativeDb, features.onsetDb, crestDelta, zcrDelta
        )
        val reasons = buildReasons(
            relativeDb, maxFrameRelativeDb, features.onsetDb, crestDelta, zcrDelta
        )
        // Shape changes alone are unreliable at the microphone's noise floor.
        val activity = relativeDb >= 6f || maxFrameRelativeDb >= 10f ||
            (activityScore >= 2 && maxFrameRelativeDb >= 3f)
        val protected = protectBackground || elapsedSeconds < protectedUntilSeconds
        val sustaining = tailSeconds > 0 && relativeDb >= 3f
        val warmingUp = elapsedSeconds < 3.0
        elapsedSeconds += seconds

        // Within-chunk onsets are common in real background audio. Judge its
        // distribution across 20 seconds instead of demanding 20 flawless chunks.
        val previousQuiet = backgroundWindow.map { it.quietDb }.sorted()
        val previousMax = backgroundWindow.map { it.maxDb }.sorted()
        val previousZcr = backgroundWindow.map { it.zcr }.sorted()
        val changed = previousQuiet.isNotEmpty() && (
            abs(features.quietFrameDb - previousQuiet[previousQuiet.size / 2]) > 6f ||
                features.maxFrameDb - previousMax[previousMax.size / 2] > 6f ||
                abs(features.zcr - previousZcr[previousZcr.size / 2]) > 0.03f
            )
        if (changed) {
            backgroundWindow.clear()
            backgroundWindowSeconds = 0.0
            negativeWindows = 0
            // Do not count a classifier window containing the previous environment.
            lastNegativeEndMillis = Long.MIN_VALUE
        }
        backgroundWindow.addLast(BackgroundSample(
            features.quietFrameDb, features.maxFrameDb, features.zcr, seconds
        ))
        backgroundWindowSeconds += seconds
        while (backgroundWindow.size > 1 &&
            backgroundWindowSeconds - backgroundWindow.first().seconds >= 20.0) {
            backgroundWindowSeconds -= backgroundWindow.removeFirst().seconds
        }
        val quietLevels = backgroundWindow.map { it.quietDb }.sorted()
        val zcrLevels = backgroundWindow.map { it.zcr }.sorted()
        val low = quietLevels.size / 10
        val high = (quietLevels.size - 1) * 9 / 10
        val stable = backgroundWindowSeconds >= 20.0 &&
            quietLevels[high] - quietLevels[low] <= 6f &&
            zcrLevels[high] - zcrLevels[low] <= 0.02f

        // Zero input/clipping are capture-quality problems, never background training data.
        val validBackground = features.rmsDb > -100f && features.peak < 0.999f
        val backgroundAccepted = validBackground && stable &&
            negativeWindows >= 3 && !protected
        val active = ((activity || sustaining) && !backgroundAccepted) || protected
        val state: AudioGateState
        val trigger: String
        when {
            warmingUp -> {
                state = AudioGateState.WarmingUp
                trigger = "warmup"
            }
            active -> {
                state = AudioGateState.Candidate
                trigger = "activity"
                tailSeconds = 2.0
            }
            tailSeconds > 0 -> {
                state = AudioGateState.Tail
                trigger = "tail"
                tailSeconds = (tailSeconds - seconds).coerceAtLeast(0.0)
            }
            else -> {
                state = AudioGateState.Background
                trigger = "stableBackground"
            }
        }
        val sinceInferenceSeconds = elapsedSeconds - lastInferenceSeconds
        val probe = sinceInferenceSeconds >= 5.0
        val hopReady = lastInferenceSeconds == 0.0 || sinceInferenceSeconds >= minimumInferenceIntervalSeconds
        val shouldInfer = hopReady && (state != AudioGateState.Background || probe)
        if (shouldInfer) lastInferenceSeconds = elapsedSeconds

        if (validBackground && !protected) {
            // Quieter frames may lower the floor even during an event; never raise it there.
            if (features.quietFrameDb < noiseFloorDb) {
                noiseFloorDb = smooth(noiseFloorDb, features.quietFrameDb, seconds, 2.0)
            } else if (backgroundAccepted && state == AudioGateState.Background) {
                noiseFloorDb = smooth(noiseFloorDb, features.quietFrameDb, seconds, 60.0)
            }
            if (backgroundAccepted && state == AudioGateState.Background) {
                backgroundZcr = smooth(backgroundZcr, features.zcr, seconds, 60.0)
                backgroundCrestFactor = smooth(backgroundCrestFactor, features.crestFactor, seconds, 60.0)
            }
        }
        return AudioGateDecision(
            shouldInfer, state, if (probe && state == AudioGateState.Background) "probe" else trigger,
            floorBeforeUpdate, features.rmsDb, relativeDb, maxFrameRelativeDb,
            features.onsetDb, features.zcr, zcrDelta, features.crestFactor, crestDelta,
            activityScore, (!activity || backgroundAccepted) && !protected, reasons,
            backgroundWindowSeconds.toFloat(), negativeWindows, protected
        )
    }

    private fun smooth(current: Float, target: Float, seconds: Double, tau: Double): Float =
        lerp(current, target, (1.0 - exp(-seconds / tau)).toFloat())

    private fun extractFeatures(audioData: FloatArray): GateFeatures {
        var totalSquare = 0.0
        var peak = 0.0f
        var zeroCrossings = 0

        var previousSample = audioData[0]
        for (sample in audioData) {
            totalSquare += (sample * sample).toDouble()
            peak = max(peak, abs(sample))
            if (hasZeroCrossing(previousSample, sample)) {
                zeroCrossings++
            }
            previousSample = sample
        }

        val frameLevels = mutableListOf<Float>()
        var onsetDb = 0.0f
        var maxFrameDb = -160.0f
        var offset = 0
        while (offset + frameSize <= audioData.size) {
            var frameSquare = 0.0
            for (i in offset until offset + frameSize) {
                frameSquare += (audioData[i] * audioData[i]).toDouble()
            }

            val frameRms = sqrt(frameSquare / frameSize).toFloat()
            val frameDb = toDb(frameRms)
            frameLevels += frameDb
            maxFrameDb = max(maxFrameDb, frameDb)
            if (!previousFrameDb.isNaN()) {
                onsetDb = max(onsetDb, frameDb - previousFrameDb)
            }
            previousFrameDb = frameDb
            offset += hopSize
        }

        val rms = sqrt(totalSquare / audioData.size).toFloat()
        val safeRms = max(rms, minRms)

        return GateFeatures(
            rmsDb = toDb(safeRms),
            quietFrameDb = frameLevels.sorted()[frameLevels.size / 5],
            peak = peak,
            maxFrameDb = maxFrameDb,
            onsetDb = onsetDb,
            zcr = zeroCrossings.toFloat() / audioData.size,
            crestFactor = peak / safeRms
        )
    }

    private fun initializeBackgroundIfNeeded(features: GateFeatures) {
        if (noiseFloorDb.isNaN()) {
            noiseFloorDb = features.quietFrameDb.coerceAtLeast(-90f)
            backgroundZcr = features.zcr
            backgroundCrestFactor = features.crestFactor
        }
    }

    private fun buildReasons(
        relativeDb: Float,
        maxFrameRelativeDb: Float,
        onsetDb: Float,
        crestDelta: Float,
        zcrDelta: Float
    ): List<String> {
        val reasons = mutableListOf<String>()
        if (relativeDb >= 6.0f) reasons += "energy"
        if (maxFrameRelativeDb >= 10.0f) reasons += "frameEnergy"
        if (onsetDb >= 12.0f) reasons += "onset"
        if (crestDelta >= 1.5f) reasons += "crest"
        if (zcrDelta >= 0.030f) reasons += "zcr"
        if (reasons.isEmpty()) reasons += "stable"
        return reasons
    }

    private fun calculateActivityScore(
        relativeDb: Float,
        maxFrameRelativeDb: Float,
        onsetDb: Float,
        crestDelta: Float,
        zcrDelta: Float
    ): Int {
        var score = 0
        if (relativeDb >= 8.5f) score++
        if (maxFrameRelativeDb >= 16.0f) score++
        if (onsetDb >= 12.0f) score++
        if (crestDelta >= 1.5f) score++
        if (zcrDelta >= 0.030f) score++
        return score
    }

    private fun toDb(rms: Float): Float {
        return 20.0f * log10(max(rms, minRms))
    }

    private fun hasZeroCrossing(previous: Float, current: Float): Boolean {
        return (previous < 0.0f && current >= 0.0f) || (previous >= 0.0f && current < 0.0f)
    }

    private fun lerp(current: Float, target: Float, alpha: Float): Float {
        return current + (target - current) * alpha
    }

    private data class GateFeatures(
        val rmsDb: Float,
        val quietFrameDb: Float,
        val peak: Float,
        val maxFrameDb: Float,
        val onsetDb: Float,
        val zcr: Float,
        val crestFactor: Float
    )
}
