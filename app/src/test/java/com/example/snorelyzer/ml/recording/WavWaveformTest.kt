package com.example.snorelyzer.ml.recording

import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WavWaveformTest {
    @get:Rule
    val clips = TemporaryFolder()

    @Test
    fun `extracts peaks in time order and includes the final partial buffer`() = runTest {
        val samples = FloatArray(9_600).also {
            it[0] = -0.5f
            it[4_850] = 0.25f
            it[it.lastIndex] = -1f
        }

        val peaks = readWavPeaks(writeClip(samples))!!

        assertEquals(96, peaks.size)
        assertEquals(0.5f, peaks[0], 0.0001f)
        assertEquals(0.25f, peaks[48], 0.0001f)
        assertEquals(1f, peaks[95], 0.0001f)
        assertEquals(93, peaks.count { it == 0f })
    }

    @Test
    fun `keeps silence and rejects truncated PCM`() = runTest {
        val clip = writeClip(FloatArray(3))
        assertEquals(listOf(0f, 0f, 0f), readWavPeaks(clip))

        RandomAccessFile(clip, "rw").use { it.setLength(it.length() - 1) }

        assertNull(readWavPeaks(clip))
    }

    @Test
    fun `skips a padded metadata chunk before audio`() = runTest {
        val clip = writeClip(floatArrayOf(0.5f))
        val pcm = clip.readBytes()
        val metadata = byteArrayOf(74, 85, 78, 75, 1, 0, 0, 0, 42, 0) // JUNK with one byte of padding
        val withMetadata = pcm.copyOfRange(0, 36) + metadata + pcm.copyOfRange(36, pcm.size)
        withMetadata[4] = (withMetadata.size - 8).toByte()
        clip.writeBytes(withMetadata)

        val peaks = readWavPeaks(clip)

        assertNotNull(peaks)
        assertEquals(0.5f, peaks!!.single(), 0.0001f)
    }

    private fun writeClip(samples: FloatArray): File {
        val result = WavAudioClipWriter(clips.root).writeClip(
            AudioClipWriteRequest(
                samples = samples,
                sampleRate = 16_000,
                sessionId = "session",
                episodeId = "episode",
                clipStartMillis = 0L,
                clipEndMillis = samples.size * 1_000L / 16_000
            )
        )
        return File(result.filePath)
    }
}
