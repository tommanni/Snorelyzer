package com.example.snorelyzer

import com.example.snorelyzer.ml.AudioGateDecision
import com.example.snorelyzer.ml.ClassificationResult
import kotlin.math.max
import kotlin.math.min

data class LabelSummaryUi(
    val label: String,
    val top1Count: Int,
    val appearanceCount: Int,
    val averageConfidencePercent: Int,
    val maxConfidencePercent: Int
)

data class NightSummaryUi(
    val hasSession: Boolean = false,
    val isRunning: Boolean = false,
    val startedAtMillis: Long = 0L,
    val endedAtMillis: Long? = null,
    val totalChunks: Int = 0,
    val inferenceChunks: Int = 0,
    val skippedBackgroundChunks: Int = 0,
    val triggerCounts: Map<String, Int> = emptyMap(),
    val reasonCounts: Map<String, Int> = emptyMap(),
    val minNoiseFloorDb: Float? = null,
    val maxNoiseFloorDb: Float? = null,
    val maxObservedRelativeDb: Float? = null,
    val maxObservedMaxFrameRelativeDb: Float? = null,
    val maxObservedCrestDelta: Float? = null,
    val topLabels: List<LabelSummaryUi> = emptyList()
) {
    val gateOpenPercent: Int
        get() = if (totalChunks == 0) 0 else (inferenceChunks * 100) / totalChunks

    fun durationMillis(nowMillis: Long): Long {
        if (!hasSession) return 0L
        val end = endedAtMillis ?: nowMillis
        return max(0L, end - startedAtMillis)
    }
}

class NightSummaryAccumulator {
    private var hasSession = false
    private var isRunning = false
    private var startedAtMillis = 0L
    private var endedAtMillis: Long? = null
    private var totalChunks = 0
    private var inferenceChunks = 0
    private var skippedBackgroundChunks = 0
    private val triggerCounts = mutableMapOf<String, Int>()
    private val reasonCounts = mutableMapOf<String, Int>()
    private var minNoiseFloorDb: Float? = null
    private var maxNoiseFloorDb: Float? = null
    private var maxObservedRelativeDb: Float? = null
    private var maxObservedMaxFrameRelativeDb: Float? = null
    private var maxObservedCrestDelta: Float? = null
    private val labelStats = mutableMapOf<String, MutableLabelStats>()

    fun startSession(startedAtMillis: Long) {
        hasSession = true
        isRunning = true
        this.startedAtMillis = startedAtMillis
        endedAtMillis = null
        totalChunks = 0
        inferenceChunks = 0
        skippedBackgroundChunks = 0
        triggerCounts.clear()
        reasonCounts.clear()
        minNoiseFloorDb = null
        maxNoiseFloorDb = null
        maxObservedRelativeDb = null
        maxObservedMaxFrameRelativeDb = null
        maxObservedCrestDelta = null
        labelStats.clear()
    }

    fun stopSession(endedAtMillis: Long) {
        if (!hasSession) return
        isRunning = false
        this.endedAtMillis = endedAtMillis
    }

    fun recordGateDecision(decision: AudioGateDecision) {
        if (!hasSession) return

        totalChunks++
        if (decision.shouldInfer) {
            inferenceChunks++
        } else {
            skippedBackgroundChunks++
        }

        triggerCounts.increment(decision.triggerType)
        decision.reasons.forEach { reasonCounts.increment(it) }

        minNoiseFloorDb = minNullable(minNoiseFloorDb, decision.noiseFloorDb)
        maxNoiseFloorDb = maxNullable(maxNoiseFloorDb, decision.noiseFloorDb)
        maxObservedRelativeDb = maxNullable(maxObservedRelativeDb, decision.relativeDb)
        maxObservedMaxFrameRelativeDb = maxNullable(
            maxObservedMaxFrameRelativeDb,
            decision.maxFrameRelativeDb
        )
        maxObservedCrestDelta = maxNullable(maxObservedCrestDelta, decision.crestDelta)
    }

    fun recordClassificationResults(results: List<ClassificationResult>) {
        if (!hasSession || results.isEmpty()) return

        results.forEachIndexed { index, result ->
            val stats = labelStats.getOrPut(result.label) { MutableLabelStats() }
            stats.recordAppearance(result.probability, isTop1 = index == 0)
        }
    }

    fun snapshot(nowMillis: Long): NightSummaryUi {
        return NightSummaryUi(
            hasSession = hasSession,
            isRunning = isRunning,
            startedAtMillis = startedAtMillis,
            endedAtMillis = endedAtMillis,
            totalChunks = totalChunks,
            inferenceChunks = inferenceChunks,
            skippedBackgroundChunks = skippedBackgroundChunks,
            triggerCounts = triggerCounts.toSortedMap(),
            reasonCounts = reasonCounts.toList()
                .sortedByDescending { it.second }
                .toMap(),
            minNoiseFloorDb = minNoiseFloorDb,
            maxNoiseFloorDb = maxNoiseFloorDb,
            maxObservedRelativeDb = maxObservedRelativeDb,
            maxObservedMaxFrameRelativeDb = maxObservedMaxFrameRelativeDb,
            maxObservedCrestDelta = maxObservedCrestDelta,
            topLabels = labelStats.map { (label, stats) ->
                LabelSummaryUi(
                    label = label,
                    top1Count = stats.top1Count,
                    appearanceCount = stats.appearanceCount,
                    averageConfidencePercent = (stats.averageConfidence * 100).toInt(),
                    maxConfidencePercent = (stats.maxConfidence * 100).toInt()
                )
            }.sortedWith(
                compareByDescending<LabelSummaryUi> { it.top1Count }
                    .thenByDescending { it.appearanceCount }
                    .thenByDescending { it.averageConfidencePercent }
                    .thenBy { it.label }
            ).take(5)
        )
    }

    private fun MutableMap<String, Int>.increment(key: String) {
        this[key] = (this[key] ?: 0) + 1
    }

    private fun minNullable(current: Float?, value: Float): Float {
        return current?.let { min(it, value) } ?: value
    }

    private fun maxNullable(current: Float?, value: Float): Float {
        return current?.let { max(it, value) } ?: value
    }

    private class MutableLabelStats {
        var top1Count = 0
            private set
        var appearanceCount = 0
            private set
        var averageConfidence = 0.0f
            private set
        var maxConfidence = 0.0f
            private set

        fun recordAppearance(confidence: Float, isTop1: Boolean) {
            if (isTop1) top1Count++
            appearanceCount++
            averageConfidence += (confidence - averageConfidence) / appearanceCount
            maxConfidence = max(maxConfidence, confidence)
        }
    }
}
