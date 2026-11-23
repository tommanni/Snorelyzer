package com.example.snorelyzer.ml.recording

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal const val WaveformPeakCount = 96
private const val BytesPerSample = 2

/** Reads the mono, 16-bit PCM format produced by [WavAudioClipWriter]. */
internal suspend fun readWavPeaks(file: File): List<Float>? = RandomAccessFile(file, "r").use { wav ->
    if (wav.length() < 12 || wav.readInt() != 0x52494646) return null // RIFF
    val riffEnd = 8 + wav.readChunkSize()
    if (riffEnd !in 12..wav.length() || wav.readInt() != 0x57415645) return null // WAVE

    val coroutineContext = currentCoroutineContext()
    var isSupportedPcm = false
    while (wav.filePointer + 8 <= riffEnd) {
        coroutineContext.ensureActive()
        val chunkId = wav.readInt()
        val chunkSize = wav.readChunkSize()
        val nextChunk = wav.filePointer + chunkSize + chunkSize % 2
        if (nextChunk > riffEnd) return null

        when (chunkId) {
            0x666D7420 -> { // fmt
                if (chunkSize < 16) return null
                val encoding = wav.readLittleEndianShort()
                val channels = wav.readLittleEndianShort()
                val sampleRate = Integer.reverseBytes(wav.readInt())
                wav.skipBytes(4) // Byte rate
                val blockAlign = wav.readLittleEndianShort()
                val bitsPerSample = wav.readLittleEndianShort()
                isSupportedPcm = encoding == 1 && channels == 1 && sampleRate > 0 &&
                    blockAlign == BytesPerSample && bitsPerSample == 16
            }
            0x64617461 -> { // data
                if (!isSupportedPcm || chunkSize == 0L || chunkSize % BytesPerSample != 0L) return null
                return wav.readPeaks(chunkSize / BytesPerSample)
            }
        }
        wav.seek(nextChunk)
    }
    null
}

private suspend fun RandomAccessFile.readPeaks(sampleCount: Long): List<Float> {
    val peaks = WaveformPeaks(sampleCount)
    val buffer = ByteArray(16 * 1024)
    val coroutineContext = currentCoroutineContext()
    var sampleIndex = 0L
    while (sampleIndex < sampleCount) {
        coroutineContext.ensureActive()
        val byteCount = minOf(buffer.size.toLong(), (sampleCount - sampleIndex) * BytesPerSample).toInt()
        readFully(buffer, 0, byteCount)
        for (offset in 0 until byteCount step BytesPerSample) {
            val sample = (buffer[offset].toInt() and 0xff) or (buffer[offset + 1].toInt() shl 8)
            peaks.add(sample)
            sampleIndex++
        }
    }
    return peaks.values()
}

internal class WaveformPeaks(private val sampleCount: Long) {
    private val peaks = IntArray(minOf(WaveformPeakCount.toLong(), sampleCount).toInt())
    private var index = 0L

    fun add(sample: Int) {
        if (index >= sampleCount) return
        val bucket = (index * peaks.size / sampleCount).toInt()
        peaks[bucket] = maxOf(peaks[bucket], abs(sample))
        index++
    }

    fun values(): List<Float> = peaks.map { it / 32_768f }
}

private fun RandomAccessFile.readChunkSize(): Long = Integer.reverseBytes(readInt()).toLong() and 0xffffffffL

private fun RandomAccessFile.readLittleEndianShort(): Int = java.lang.Short.reverseBytes(readShort()).toInt() and 0xffff
