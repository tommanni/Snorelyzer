package com.example.snorelyzer

import com.example.snorelyzer.ml.AudioGateDecision
import com.example.snorelyzer.ml.AudioGateState
import com.example.snorelyzer.ml.ClassificationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightSummaryAccumulatorTest {
    @Test
    fun newSessionStartsWithZeroedCounters() {
        val accumulator = NightSummaryAccumulator()

        accumulator.startSession(startedAtMillis = 1_000L)
        val summary = accumulator.snapshot(nowMillis = 1_500L)

        assertTrue(summary.hasSession)
        assertTrue(summary.isRunning)
        assertEquals(0, summary.totalChunks)
        assertEquals(0, summary.inferenceChunks)
        assertEquals(0, summary.skippedBackgroundChunks)
    }

    @Test
    fun stableBackgroundIncrementsSkippedCountAndNoiseFloorRange() {
        val accumulator = NightSummaryAccumulator()
        accumulator.startSession(startedAtMillis = 1_000L)

        accumulator.recordGateDecision(decision(shouldInfer = false, triggerType = "stableBackground", noiseFloorDb = -40.0f))
        accumulator.recordGateDecision(decision(shouldInfer = false, triggerType = "stableBackground", noiseFloorDb = -44.0f))
        val summary = accumulator.snapshot(nowMillis = 2_000L)

        assertEquals(2, summary.totalChunks)
        assertEquals(0, summary.inferenceChunks)
        assertEquals(2, summary.skippedBackgroundChunks)
        assertEquals(-44.0f, summary.minNoiseFloorDb)
        assertEquals(-40.0f, summary.maxNoiseFloorDb)
    }

    @Test
    fun inferenceGateDecisionsIncrementTriggerCountsAndObservedMaximums() {
        val accumulator = NightSummaryAccumulator()
        accumulator.startSession(startedAtMillis = 1_000L)

        accumulator.recordGateDecision(
            decision(
                shouldInfer = true,
                triggerType = "activity",
                relativeDb = 2.0f,
                maxFrameRelativeDb = 6.0f,
                crestDelta = 0.8f,
                reasons = listOf("energy", "frameEnergy")
            )
        )
        accumulator.recordGateDecision(
            decision(
                shouldInfer = true,
                triggerType = "tail",
                relativeDb = 4.0f,
                maxFrameRelativeDb = 3.0f,
                crestDelta = 1.5f,
                reasons = listOf("crest")
            )
        )
        val summary = accumulator.snapshot(nowMillis = 2_000L)

        assertEquals(2, summary.inferenceChunks)
        assertEquals(1, summary.triggerCounts["activity"])
        assertEquals(1, summary.triggerCounts["tail"])
        assertEquals(1, summary.reasonCounts["energy"])
        assertEquals(1, summary.reasonCounts["frameEnergy"])
        assertEquals(1, summary.reasonCounts["crest"])
        assertEquals(4.0f, summary.maxObservedRelativeDb)
        assertEquals(6.0f, summary.maxObservedMaxFrameRelativeDb)
        assertEquals(1.5f, summary.maxObservedCrestDelta)
    }

    @Test
    fun classificationResultsTrackCountsAndRunningConfidence() {
        val accumulator = NightSummaryAccumulator()
        accumulator.startSession(startedAtMillis = 1_000L)

        accumulator.recordClassificationResults(
            listOf(
                ClassificationResult(index = 1, label = "Snoring", probability = 0.60f),
                ClassificationResult(index = 2, label = "Breathing", probability = 0.30f)
            )
        )
        accumulator.recordClassificationResults(
            listOf(
                ClassificationResult(index = 1, label = "Snoring", probability = 0.80f),
                ClassificationResult(index = 3, label = "Rustle", probability = 0.20f)
            )
        )
        val summary = accumulator.snapshot(nowMillis = 2_000L)

        val snoring = summary.topLabels.first { it.label == "Snoring" }
        assertEquals(2, snoring.top1Count)
        assertEquals(2, snoring.appearanceCount)
        assertEquals(70, snoring.averageConfidencePercent)
        assertEquals(80, snoring.maxConfidencePercent)
    }

    @Test
    fun startingNewSessionResetsPreviousSummary() {
        val accumulator = NightSummaryAccumulator()
        accumulator.startSession(startedAtMillis = 1_000L)
        accumulator.recordGateDecision(decision(shouldInfer = true))
        accumulator.stopSession(endedAtMillis = 2_000L)

        accumulator.startSession(startedAtMillis = 3_000L)
        val summary = accumulator.snapshot(nowMillis = 3_500L)

        assertTrue(summary.hasSession)
        assertTrue(summary.isRunning)
        assertEquals(0, summary.totalChunks)
        assertFalse(summary.topLabels.isNotEmpty())
    }

    private fun decision(
        shouldInfer: Boolean,
        triggerType: String = if (shouldInfer) "activity" else "stableBackground",
        noiseFloorDb: Float = -42.0f,
        relativeDb: Float = 0.0f,
        maxFrameRelativeDb: Float = 0.0f,
        crestDelta: Float = 0.0f,
        reasons: List<String> = listOf("stable")
    ): AudioGateDecision {
        return AudioGateDecision(
            shouldInfer = shouldInfer,
            state = if (shouldInfer) AudioGateState.Candidate else AudioGateState.Background,
            triggerType = triggerType,
            noiseFloorDb = noiseFloorDb,
            rmsDb = noiseFloorDb + relativeDb,
            relativeDb = relativeDb,
            maxFrameRelativeDb = maxFrameRelativeDb,
            onsetDb = 0.0f,
            zcr = 0.0f,
            zcrDelta = 0.0f,
            crestFactor = 1.0f,
            crestDelta = crestDelta,
            activityScore = 0,
            isStableBackground = !shouldInfer,
            reasons = reasons
        )
    }
}
