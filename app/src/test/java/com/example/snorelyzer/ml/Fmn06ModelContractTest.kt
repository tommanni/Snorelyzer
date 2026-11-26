package com.example.snorelyzer.ml

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class Fmn06ModelContractTest {
    @Test
    fun frontendMatchesReferenceLogMelValues() {
        val waveform = FloatArray(AudioModelConfig.CAPTURE_WINDOW_SAMPLES) { index ->
            val time = index / 32_000.0
            (
                0.2 * sin(2.0 * PI * 250 * time) +
                    0.1 * sin(2.0 * PI * 1000 * time) +
                    0.07 * sin(2.0 * PI * 3500 * time)
                ).toFloat()
        }
        val processor = AudioProcessor()
        val mel = processor.process(waveform)

        assertEquals(128_000, mel.size)
        // Reference values use a direct DFT of the corresponding 16 kHz tones
        // with EfficientSED's pre-emphasis, Hann window, and Kaldi filters.
        val frame = 499
        assertEquals(1.069045f, mel[15 * 1000 + frame], 0.0002f)
        assertEquals(1.408687f, mel[47 * 1000 + frame], 0.0002f)
        assertEquals(1.794586f, mel[95 * 1000 + frame], 0.0002f)
        assertEquals(-2.323619f, mel[127 * 1000 + frame], 0.0002f)

        val silence = processor.process(FloatArray(waveform.size))
        assertEquals(-2.323619f, silence.first(), 0.0002f)
        assertEquals(-2.323619f, silence.last(), 0.0002f)
    }

    @Test
    fun preservesEachFrameWithinItsRequestedClass() {
        val logits = FloatArray(447 * 250) { -8f }
        logits[104 * 250 + 249] = 12f
        logits[105 * 250 + 249] = 0f
        logits[176 * 250] = 2f
        logits[176 * 250 + 249] = 1f

        val scores = frameProbabilities(logits, setOf(105, 176))
        val cough = scores.getValue(105)
        val gasp = scores.getValue(176)

        assertEquals(setOf(105, 176), scores.keys)
        assertEquals(250, cough.size)
        assertEquals(0.000335f, cough[0], 0.000001f)
        assertEquals(0.5f, cough[249], 0.000001f)
        assertEquals(0.880797f, gasp[0], 0.000001f)
        assertEquals(0.731059f, gasp[249], 0.000001f)
    }
}
