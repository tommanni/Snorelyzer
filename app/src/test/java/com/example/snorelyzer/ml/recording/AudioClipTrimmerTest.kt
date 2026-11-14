package com.example.snorelyzer.ml.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioClipTrimmerTest {
    private val trimmer = AudioClipTrimmer(
        sampleRate = 1_000,
        edgePadMillis = 1_000L,
        minDurationMillis = 5_000L
    )

    @Test
    fun coughWithNineSecondsOfBackgroundOnEachSideHasOneSecondMargins() {
        val audio = audioWithBackground(21_000)
        addSound(audio, 9_000, 12_000, 0.3f)

        assertEquals(AudioClipTrimmer.Bounds(8_000, 13_000), trimmer.findBounds(audio))
    }

    @Test
    fun quietSnoringWithSixSecondsOfBackgroundOnEachSideIsTrimmed() {
        val audio = audioWithBackground(16_000)
        addSound(audio, 6_000, 10_000, 0.002f)

        assertEquals(AudioClipTrimmer.Bounds(5_000, 11_000), trimmer.findBounds(audio))
    }

    @Test
    fun quietSnoreAfterLoudCoughAndGapIsPreserved() {
        val audio = audioWithBackground(25_000)
        addSound(audio, 5_000, 6_000, 0.5f)
        // 48 dB quieter than the cough, but still above the room background.
        addSound(audio, 15_000, 18_000, 0.002f)

        assertEquals(AudioClipTrimmer.Bounds(4_000, 19_000), trimmer.findBounds(audio))
    }

    @Test
    fun uncertainBackgroundSeparationKeepsFullClip() {
        val audio = audioWithBackground(20_000)
        addSound(audio, 8_000, 10_000, 0.00012f)

        assertEquals(AudioClipTrimmer.Bounds(0, 20_000), trimmer.findBounds(audio))
    }

    @Test
    fun shortCoughKeepsFiveSecondMinimumCenteredOnSound() {
        val audio = audioWithBackground(20_000)
        addSound(audio, 9_500, 10_500, 0.3f)

        assertEquals(AudioClipTrimmer.Bounds(7_500, 12_500), trimmer.findBounds(audio))
    }

    @Test
    fun soundAtStartKeepsMinimumDurationWithoutLosingOnset() {
        val audio = audioWithBackground(20_000)
        addSound(audio, 0, 1_000, 0.002f)

        assertEquals(AudioClipTrimmer.Bounds(0, 5_000), trimmer.findBounds(audio))
    }

    @Test
    fun soundAtEndKeepsMinimumDurationWithoutLosingTail() {
        val audio = audioWithBackground(20_000)
        addSound(audio, 19_000, 20_000, 0.002f)

        assertEquals(AudioClipTrimmer.Bounds(15_000, 20_000), trimmer.findBounds(audio))
    }

    @Test
    fun continuousQuietSoundIsNotTrimmedAsBackground() {
        val audio = FloatArray(20_000) { if (it % 2 == 0) 0.002f else -0.002f }

        assertEquals(AudioClipTrimmer.Bounds(0, 20_000), trimmer.findBounds(audio))
    }

    @Test
    fun shortClipAndSilenceAreKept() {
        assertEquals(AudioClipTrimmer.Bounds(0, 500), trimmer.findBounds(FloatArray(500)))
        assertEquals(AudioClipTrimmer.Bounds(0, 0), trimmer.findBounds(floatArrayOf()))
    }

    private fun audioWithBackground(samples: Int) =
        FloatArray(samples) { if (it % 2 == 0) 0.0001f else -0.0001f }

    private fun addSound(audio: FloatArray, start: Int, end: Int, amplitude: Float) {
        for (index in start until end) {
            audio[index] = if (index % 2 == 0) amplitude else -amplitude
        }
    }
}
