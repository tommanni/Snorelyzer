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

        val decisions = List(8) {
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

        val event = sineChunk(amplitude = amplitudeForRelativeDb(0.02f, 3.2f), frequencyHz = 120.0f)

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
    fun combinedMediumCuesInfer() {
        val gate = primedGate()

        val event = sineChunk(amplitude = amplitudeForRelativeDb(0.02f, 1.4f), frequencyHz = 120.0f)
        for (i in 12_000 until 12_700) {
            event[i] += 0.05f * sin(2.0 * PI * 1000.0 * i / 32_000.0).toFloat()
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
                sineChunk(amplitude = amplitudeForRelativeDb(0.02f, 3.2f), frequencyHz = 120.0f)
            ).shouldInfer
        )
        assertTrue(gate.analyze(sineChunk(amplitude = 0.02f, frequencyHz = 120.0f)).shouldInfer)
        assertTrue(gate.analyze(sineChunk(amplitude = 0.02f, frequencyHz = 120.0f)).shouldInfer)
        assertFalse(gate.analyze(sineChunk(amplitude = 0.02f, frequencyHz = 120.0f)).shouldInfer)
    }

    @Test
    fun repeatedStableChunksStayClosed() {
        val gate = primedGate()

        repeat(10) {
            val decision = gate.analyze(sineChunk(amplitude = 0.02f, frequencyHz = 120.0f))
            assertFalse(decision.shouldInfer)
            assertTrue(decision.isStableBackground)
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
