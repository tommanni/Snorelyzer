package com.example.snorelyzer.ml.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavAudioClipWriterTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun writesMonoPcmWavFileWithExpectedHeader() {
        val writer = WavAudioClipWriter(temporaryFolder.root)

        val result = writer.writeClip(
            AudioClipWriteRequest(
                episodeId = "episode-1",
                sessionId = "session-1",
                samples = floatArrayOf(-1f, 0f, 1f),
                sampleRate = 1_000,
                clipStartMillis = 10_000L,
                clipEndMillis = 13_000L
            )
        )

        val bytes = java.io.File(result.filePath).readBytes()
        val header = ByteBuffer.wrap(bytes)
            .order(ByteOrder.LITTLE_ENDIAN)

        assertEquals("RIFF", bytes.asAscii(0, 4))
        assertEquals(42, header.getInt(4))
        assertEquals("WAVE", bytes.asAscii(8, 4))
        assertEquals("fmt ", bytes.asAscii(12, 4))
        assertEquals(16, header.getInt(16))
        assertEquals(1, header.getShort(20).toInt())
        assertEquals(1, header.getShort(22).toInt())
        assertEquals(1_000, header.getInt(24))
        assertEquals(2_000, header.getInt(28))
        assertEquals(2, header.getShort(32).toInt())
        assertEquals(16, header.getShort(34).toInt())
        assertEquals("data", bytes.asAscii(36, 4))
        assertEquals(6, header.getInt(40))
        assertEquals(50, bytes.size)
        assertEquals(3L, result.durationMillis)
        assertTrue(result.filePath.endsWith("session-1_episode-1_10000_13000.wav"))
    }

    @Test
    fun clampsFloatSamplesToSignedSixteenBitPcm() {
        val writer = WavAudioClipWriter(temporaryFolder.root)

        val result = writer.writeClip(
            AudioClipWriteRequest(
                episodeId = "episode-2",
                sessionId = "session-1",
                samples = floatArrayOf(-2f, -1f, 0f, 1f, 2f),
                sampleRate = 1_000,
                clipStartMillis = 0L,
                clipEndMillis = 5L
            )
        )

        val pcm = ByteBuffer.wrap(java.io.File(result.filePath).readBytes())
            .order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(-32767, pcm.getShort(44).toInt())
        assertEquals(-32767, pcm.getShort(46).toInt())
        assertEquals(0, pcm.getShort(48).toInt())
        assertEquals(32767, pcm.getShort(50).toInt())
        assertEquals(32767, pcm.getShort(52).toInt())
    }

    private fun ByteArray.asAscii(offset: Int, length: Int): String {
        return String(this, offset, length, Charsets.US_ASCII)
    }
}
