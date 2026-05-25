package com.example.snorelyzer.ml.recording

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RollingAudioBufferTest {

    @Test
    fun storesAndExtractsSingleAudioChunk() {
        val buffer = testBuffer(durationMillis = 10_000L)

        buffer.append(floatArrayOf(1f, 2f, 3f), chunkStartMillis = 0L)

        assertArrayEquals(
            floatArrayOf(1f, 2f, 3f),
            buffer.extract(0L, 3L),
            0f
        )
    }

    @Test
    fun appendsMultipleAudioChunksInChronologicalOrder() {
        val buffer = testBuffer(durationMillis = 10_000L)

        buffer.append(floatArrayOf(1f, 2f), chunkStartMillis = 0L)
        buffer.append(floatArrayOf(3f, 4f), chunkStartMillis = 2L)

        assertArrayEquals(
            floatArrayOf(1f, 2f, 3f, 4f),
            buffer.extract(0L, 4L),
            0f
        )
    }

    @Test
    fun extractsAudioAfterBufferWraps() {
        val buffer = testBuffer(durationMillis = 5L)

        buffer.append(floatArrayOf(1f, 2f, 3f), chunkStartMillis = 0L)
        buffer.append(floatArrayOf(4f, 5f, 6f, 7f), chunkStartMillis = 3L)

        assertArrayEquals(
            floatArrayOf(3f, 4f, 5f, 6f, 7f),
            buffer.extract(0L, 10L),
            0f
        )
    }

    @Test
    fun clampsExtractionToAvailableAudio() {
        val buffer = testBuffer(durationMillis = 5L)

        buffer.append(floatArrayOf(10f, 11f, 12f, 13f, 14f), chunkStartMillis = 0L)

        assertArrayEquals(
            floatArrayOf(10f, 11f, 12f, 13f, 14f),
            buffer.extract(-5L, 10L),
            0f
        )
    }

    @Test
    fun oversizedChunkKeepsNewestSamples() {
        val buffer = testBuffer(durationMillis = 4L)

        buffer.append(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f), chunkStartMillis = 0L)

        assertArrayEquals(
            floatArrayOf(3f, 4f, 5f, 6f),
            buffer.extract(0L, 10L),
            0f
        )
    }

    @Test
    fun appendCopiesCallerArray() {
        val buffer = testBuffer(durationMillis = 10_000L)
        val chunk = floatArrayOf(1f, 2f, 3f)

        buffer.append(chunk, chunkStartMillis = 0L)
        chunk.fill(9f)

        assertArrayEquals(
            floatArrayOf(1f, 2f, 3f),
            buffer.extract(0L, 3L),
            0f
        )
    }

    @Test
    fun exposesAvailableAudioBounds() {
        val buffer = testBuffer(durationMillis = 5L)

        buffer.append(floatArrayOf(1f, 2f, 3f), chunkStartMillis = 10_000L)
        buffer.append(floatArrayOf(4f, 5f, 6f, 7f), chunkStartMillis = 10_003L)

        assertEquals(10_002L, buffer.availableStartMillis)
        assertEquals(10_007L, buffer.availableEndMillis)
    }

    @Test
    fun resetClearsBufferedAudio() {
        val buffer = testBuffer(durationMillis = 10_000L)

        buffer.append(floatArrayOf(1f, 2f, 3f), chunkStartMillis = 0L)
        buffer.reset()

        assertNull(buffer.availableStartMillis)
        assertNull(buffer.availableEndMillis)
        assertNull(buffer.extract(0L, 3L))
    }

    private fun testBuffer(durationMillis: Long): RollingAudioBuffer {
        return RollingAudioBuffer(
            sampleRate = 1_000,
            durationMillis = durationMillis
        )
    }
}
