package com.example.snorelyzer.ml

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioGateTest {
    private val chunkSize = 32_000

    @Test
    fun steadyBackgroundClosesAfterWarmup() {
        val gate = AudioGate()

        val decisions = List(9) {
            gate.analyze(sineChunk(amplitude = 0.02f, frequencyHz = 120.0f))
        }

        assertTrue(decisions.take(3).all { it.shouldInfer })
        assertFalse(decisions.last().shouldInfer)
        assertEquals("stableBackground", decisions.last().triggerType)
    }

    @Test
    fun silenceClosesAfterWarmup() {
        val gate = AudioGate()

        repeat(3) {
            assertTrue(gate.analyze(FloatArray(chunkSize)).shouldInfer)
        }

        val decision = gate.analyze(FloatArray(chunkSize))

        assertFalse(decision.toString(), decision.shouldInfer)
        assertTrue(decision.isStableBackground)
    }

    @Test
    fun weakOnsetAloneStaysClosed() {
        val gate = primedGate()

        val decision = gate.analyze(
            steppedAmplitudeChunk(
                quietAmplitude = 0.004f,
                normalAmplitude = 0.02f,
                frequencyHz = 120.0f
            )
        )

        assertFalse(decision.toString(), decision.shouldInfer)
        assertEquals("stableBackground", decision.triggerType)
    }

    @Test
    fun weakFrameEnergyAndZcrStaysClosed() {
        val gate = primedGate()

        val event = sineChunk(amplitude = 0.02f, frequencyHz = 120.0f)
        for (i in 14_000 until 15_024) {
            event[i] += 0.012f * sin(2.0 * PI * 220.0 * i / 32_000.0).toFloat()
        }

        val decision = gate.analyze(event)

        assertFalse(decision.toString(), decision.shouldInfer)
        assertEquals("stableBackground", decision.triggerType)
    }

    @Test
    fun bedroomBackgroundFrameEnergyAroundSevenDbStaysClosed() {
        val gate = primedGate()

        val event = sineChunk(amplitude = 0.02f, frequencyHz = 120.0f)
        for (i in 14_000 until 15_024) {
            event[i] = (0.036f * sin(2.0 * PI * 120.0 * i / 32_000.0)).toFloat()
        }

        val decision = gate.analyze(event)

        assertFalse(decision.toString(), decision.shouldInfer)
        assertEquals("stableBackground", decision.triggerType)
    }

    @Test
    fun strongRelativeEnergyInfers() {
        val gate = primedGate()

        val event = sineChunk(amplitude = amplitudeForRelativeDb(0.02f, 10.5f), frequencyHz = 120.0f)

        val decision = gate.analyze(event)

        assertTrue(decision.shouldInfer)
        assertEquals("activity", decision.triggerType)
    }

    @Test
    fun strongShortBurstInfersThroughFrameRelativeEnergy() {
        val gate = primedGate()

        val event = sineChunk(amplitude = 0.02f, frequencyHz = 120.0f)
        for (i in 14_000 until 15_024) {
            event[i] += 0.09f * sin(2.0 * PI * 180.0 * i / 32_000.0).toFloat()
        }

        val decision = gate.analyze(event)

        assertTrue(decision.shouldInfer)
        assertEquals("activity", decision.triggerType)
    }

    @Test
    fun transientWithSupportingShapeCuesInfers() {
        val gate = primedGate()

        val event = sineChunk(amplitude = amplitudeForRelativeDb(0.02f, 1.4f), frequencyHz = 120.0f)
        for (i in 12_000 until 12_700) {
            event[i] += 0.08f * sin(2.0 * PI * 1000.0 * i / 32_000.0).toFloat()
        }

        val decision = gate.analyze(event)

        assertTrue(decision.shouldInfer)
        assertEquals("activity", decision.triggerType)
    }

    @Test
    fun tailRunsAfterActivity() {
        val gate = primedGate()

        assertTrue(
            gate.analyze(
                sineChunk(amplitude = amplitudeForRelativeDb(0.02f, 10.5f), frequencyHz = 120.0f)
            ).shouldInfer
        )
        assertTrue(gate.analyze(sineChunk(amplitude = 0.02f, frequencyHz = 120.0f)).shouldInfer)
        assertTrue(gate.analyze(sineChunk(amplitude = 0.02f, frequencyHz = 120.0f)).shouldInfer)
        assertFalse(gate.analyze(sineChunk(amplitude = 0.02f, frequencyHz = 120.0f)).shouldInfer)
    }

    @Test
    fun repeatedStableChunksStayClosed() {
        val gate = primedGate()

        repeat(4) {
            val decision = gate.analyze(sineChunk(amplitude = 0.02f, frequencyHz = 120.0f))
            assertFalse(decision.shouldInfer)
            assertTrue(decision.isStableBackground)
        }
    }

    @Test
    fun sustainedActivityNeverBecomesBackgroundWithoutClassifierEvidence() {
        val gate = primedGate()
        val event = sineChunk(0.15f, 120f)
        val floor = gate.analyze(event).noiseFloorDb
        repeat(180) {
            val decision = gate.analyze(event)
            assertTrue(decision.toString(), decision.shouldInfer)
            assertEquals(floor, decision.noiseFloorDb, 0.01f)
        }
    }

    @Test
    fun closedGatePeriodicallyProbes() {
        val gate = primedGate()
        val decisions = List(15) { gate.analyze(sineChunk(0.02f, 120f)) }
        assertTrue(decisions.any { it.triggerType == "probe" && it.shouldInfer })
        assertTrue(decisions.any { !it.shouldInfer })
    }

    @Test
    fun forcedRecorderActivityCannotRaiseBackground() {
        val gate = primedGate()
        val floor = gate.analyze(sineChunk(0.02f, 120f)).noiseFloorDb
        repeat(90) {
            val decision = gate.analyze(sineChunk(0.15f, 120f), protectBackground = true)
            assertTrue(decision.shouldInfer)
            assertEquals(floor, decision.noiseFloorDb, 0.01f)
        }
    }

    @Test
    fun stableNewBackgroundRequiresIndependentNegativeWindows() {
        val gate = primedGate()
        val loud = sineChunk(0.15f, 120f)
        repeat(40) {
            gate.analyze(loud)
            // Every result describes the SAME ten-second interval.
            gate.observeClassification(0, 10_000, false, true)
        }
        assertTrue(gate.analyze(loud).shouldInfer)
        gate.observeClassification(10_000, 20_000, false, true)
        gate.observeClassification(20_000, 30_000, false, true)
        val decisions = List(10) { gate.analyze(loud) }
        assertTrue(decisions.any { !it.shouldInfer })
        assertTrue(decisions.last().noiseFloorDb > decisions.first().noiseFloorDb)
        assertTrue(decisions.last().noiseFloorDb < decisions.first().noiseFloorDb + 4f)
    }

    @Test
    fun positiveClassificationProtectsSnoringPresentAtStartup() {
        val gate = AudioGate()
        repeat(90) { second ->
            val decision = gate.analyze(sineChunk(0.1f, 120f))
            assertTrue(decision.toString(), decision.shouldInfer)
            gate.observeClassification(second * 1000L, (second + 1) * 1000L, true, false)
        }
    }

    @Test
    fun dropoutDoesNotLowerLearnedFloorAndResetRestartsWarmup() {
        val gate = primedGate()
        val floor = gate.analyze(sineChunk(0.02f, 120f)).noiseFloorDb
        repeat(20) { assertEquals(floor, gate.analyze(FloatArray(chunkSize)).noiseFloorDb, 0.01f) }
        gate.reset()
        assertEquals(AudioGateState.WarmingUp, gate.analyze(FloatArray(chunkSize)).state)
    }

    @Test
    fun quieterEnvironmentLowersFloorQuickly() {
        val gate = primedGate()
        val decisions = List(8) { gate.analyze(sineChunk(0.002f, 120f)) }
        assertTrue(decisions.last().noiseFloorDb < decisions.first().noiseFloorDb - 15f)
    }

    @Test
    fun uncertainOrFailedClassificationCannotAuthorizeNewBackground() {
        val gate = primedGate()
        repeat(90) { second ->
            val decision = gate.analyze(sineChunk(0.15f, 120f))
            assertTrue(decision.shouldInfer)
            gate.observeClassification(second * 1000L, (second + 1) * 1000L, false, false)
        }
    }

    private fun primedGate(): AudioGate {
        val gate = AudioGate()
        repeat(8) {
            gate.analyze(sineChunk(amplitude = 0.02f, frequencyHz = 120.0f))
        }
        return gate
    }

    private fun sineChunk(amplitude: Float, frequencyHz: Float): FloatArray {
        return FloatArray(chunkSize) { index ->
            (amplitude * sin(2.0 * PI * frequencyHz * index / 32_000.0)).toFloat()
        }
    }

    private fun steppedAmplitudeChunk(
        quietAmplitude: Float,
        normalAmplitude: Float,
        frequencyHz: Float
    ): FloatArray {
        return FloatArray(chunkSize) { index ->
            val amplitude = if (index < 1_024) quietAmplitude else normalAmplitude
            (amplitude * sin(2.0 * PI * frequencyHz * index / 32_000.0)).toFloat()
        }
    }

    private fun amplitudeForRelativeDb(baseAmplitude: Float, relativeDb: Float): Float {
        return baseAmplitude * 10.0.pow(relativeDb / 20.0).toFloat()
    }
}
