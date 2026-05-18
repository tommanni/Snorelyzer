package com.example.snorelyzer.ml

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
    val reasons: List<String>
)

class AudioGate {
    private val frameSize = 1024
    private val hopSize = 512
    private val warmupChunks = 3
    private val tailChunks = 2
    private val minRms = 1.0e-8f

    private var chunkCount = 0
    private var tailRemaining = 0
    private var noiseFloorDb = Float.NaN
    private var backgroundZcr = Float.NaN
    private var backgroundCrestFactor = Float.NaN

    fun reset() {
        chunkCount = 0
        tailRemaining = 0
        noiseFloorDb = Float.NaN
        backgroundZcr = Float.NaN
        backgroundCrestFactor = Float.NaN
    }

    fun analyze(audioData: FloatArray): AudioGateDecision {
        require(audioData.isNotEmpty()) { "Audio data must not be empty" }

        val features = extractFeatures(audioData)
        initializeBackgroundIfNeeded(features)

        val relativeDb = features.rmsDb - noiseFloorDb
        val maxFrameRelativeDb = features.maxFrameDb - noiseFloorDb
        val zcrDelta = abs(features.zcr - backgroundZcr)
        val crestDelta = max(0.0f, features.crestFactor - backgroundCrestFactor)
        val activityScore = calculateActivityScore(
            relativeDb = relativeDb,
            maxFrameRelativeDb = maxFrameRelativeDb,
            onsetDb = features.onsetDb,
            crestDelta = crestDelta,
            zcrDelta = zcrDelta
        )
        val reasons = buildReasons(
            relativeDb = relativeDb,
            maxFrameRelativeDb = maxFrameRelativeDb,
            onsetDb = features.onsetDb,
            crestDelta = crestDelta,
            zcrDelta = zcrDelta
        )

        val hasStrongActivity = hasStrongActivity(
            relativeDb = relativeDb,
            maxFrameRelativeDb = maxFrameRelativeDb,
            crestDelta = crestDelta,
            zcrDelta = zcrDelta
        )
        val isStableBackground = !hasStrongActivity && activityScore < 4

        val isWarmingUp = chunkCount < warmupChunks
        val shouldInfer: Boolean
        val state: AudioGateState
        val triggerType: String

        when {
            isWarmingUp -> {
                shouldInfer = true
                state = AudioGateState.WarmingUp
                triggerType = "warmup"
                updateBackground(features, upwardAlpha = 0.20f, downwardAlpha = 0.35f)
            }
            !isStableBackground -> {
                shouldInfer = true
                state = AudioGateState.Candidate
                triggerType = "activity"
                tailRemaining = tailChunks
            }
            tailRemaining > 0 -> {
                shouldInfer = true
                state = AudioGateState.Tail
                triggerType = "tail"
                tailRemaining--
            }
            else -> {
                shouldInfer = false
                state = AudioGateState.Background
                triggerType = "stableBackground"
                updateBackground(features, upwardAlpha = 0.01f, downwardAlpha = 0.12f)
            }
        }

        chunkCount++

        return AudioGateDecision(
            shouldInfer = shouldInfer,
            state = state,
            triggerType = triggerType,
            rmsDb = features.rmsDb,
            relativeDb = relativeDb,
            maxFrameRelativeDb = maxFrameRelativeDb,
            onsetDb = features.onsetDb,
            zcr = features.zcr,
            zcrDelta = zcrDelta,
            crestFactor = features.crestFactor,
            crestDelta = crestDelta,
            activityScore = activityScore,
            isStableBackground = isStableBackground,
            reasons = reasons
        )
    }

    private fun hasStrongActivity(
        relativeDb: Float,
        maxFrameRelativeDb: Float,
        crestDelta: Float,
        zcrDelta: Float
    ): Boolean {
        return relativeDb >= 3.0f ||
            maxFrameRelativeDb >= 8.0f ||
            crestDelta >= 1.4f ||
            zcrDelta >= 0.025f
    }

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

        var previousFrameDb = Float.NaN
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
            maxFrameDb = maxFrameDb,
            onsetDb = onsetDb,
            zcr = zeroCrossings.toFloat() / audioData.size,
            crestFactor = peak / safeRms
        )
    }

    private fun initializeBackgroundIfNeeded(features: GateFeatures) {
        if (noiseFloorDb.isNaN()) {
            noiseFloorDb = features.rmsDb
            backgroundZcr = features.zcr
            backgroundCrestFactor = features.crestFactor
        }
    }

    private fun updateBackground(features: GateFeatures, upwardAlpha: Float, downwardAlpha: Float) {
        val floorAlpha = if (features.rmsDb > noiseFloorDb) upwardAlpha else downwardAlpha
        noiseFloorDb = lerp(noiseFloorDb, features.rmsDb, floorAlpha)
        backgroundZcr = lerp(backgroundZcr, features.zcr, 0.05f)
        backgroundCrestFactor = lerp(backgroundCrestFactor, features.crestFactor, 0.05f)
    }

    private fun buildReasons(
        relativeDb: Float,
        maxFrameRelativeDb: Float,
        onsetDb: Float,
        crestDelta: Float,
        zcrDelta: Float
    ): List<String> {
        val reasons = mutableListOf<String>()
        if (relativeDb >= 1.2f) reasons += "energy"
        if (maxFrameRelativeDb >= 5.0f) reasons += "frameEnergy"
        if (onsetDb >= 5.5f) reasons += "onset"
        if (crestDelta >= 0.7f) reasons += "crest"
        if (zcrDelta >= 0.016f) reasons += "zcr"
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
        if (relativeDb >= 1.2f) score++
        if (maxFrameRelativeDb >= 5.0f) score++
        if (onsetDb >= 5.5f) score++
        if (crestDelta >= 0.7f) score++
        if (zcrDelta >= 0.016f) score++
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
        val maxFrameDb: Float,
        val onsetDb: Float,
        val zcr: Float,
        val crestFactor: Float
    )
}
