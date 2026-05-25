package com.example.snorelyzer.ml.recording

internal class RollingAudioBuffer(
    private val sampleRate: Int,
    durationMillis: Long
) {
    private val capacity = samplesForDuration(durationMillis, sampleRate)
    private val buffer = FloatArray(capacity)

    private var firstChunkStartMillis: Long? = null
    private var absoluteSamplesWritten = 0L
    private var validSampleCount = 0
    private var writeIndex = 0

    val availableStartMillis: Long?
        get() {
            val firstStartMillis = firstChunkStartMillis ?: return null
            if (validSampleCount == 0) return null
            return firstStartMillis + absoluteSampleToMillis(absoluteSamplesWritten - validSampleCount)
        }

    val availableEndMillis: Long?
        get() {
            val firstStartMillis = firstChunkStartMillis ?: return null
            if (validSampleCount == 0) return null
            return firstStartMillis + absoluteSampleToMillis(absoluteSamplesWritten)
        }

    fun append(chunk: FloatArray, chunkStartMillis: Long) {
        if (chunk.isEmpty()) return
        if (firstChunkStartMillis == null) {
            firstChunkStartMillis = chunkStartMillis
        }

        val sourceOffset = maxOf(0, chunk.size - capacity)
        val samplesToCopy = chunk.size - sourceOffset
        if (samplesToCopy <= 0) return

        writeIndex = ((absoluteSamplesWritten + sourceOffset) % capacity).toInt()
        var copied = 0
        while (copied < samplesToCopy) {
            val samplesUntilWrap = capacity - writeIndex
            val copyCount = minOf(samplesToCopy - copied, samplesUntilWrap)
            System.arraycopy(chunk, sourceOffset + copied, buffer, writeIndex, copyCount)
            writeIndex = (writeIndex + copyCount) % capacity
            copied += copyCount
        }

        absoluteSamplesWritten += chunk.size.toLong()
        writeIndex = (absoluteSamplesWritten % capacity).toInt()
        validSampleCount = minOf(
            capacity.toLong(),
            validSampleCount.toLong() + chunk.size.toLong()
        ).toInt()
    }

    fun extract(startMillis: Long, endMillis: Long): FloatArray? {
        val firstStartMillis = firstChunkStartMillis ?: return null
        if (endMillis <= startMillis || validSampleCount == 0) return null

        val requestedStartSample = millisToAbsoluteSample(startMillis, firstStartMillis)
        val requestedEndSample = millisToAbsoluteSample(endMillis, firstStartMillis)
        val availableStartSample = absoluteSamplesWritten - validSampleCount
        val availableEndSample = absoluteSamplesWritten

        val clampedStartSample = maxOf(requestedStartSample, availableStartSample)
        val clampedEndSample = minOf(requestedEndSample, availableEndSample)
        if (clampedEndSample <= clampedStartSample) return null

        val sampleCount = (clampedEndSample - clampedStartSample).toInt()
        val result = FloatArray(sampleCount)
        copyFromBuffer(clampedStartSample, result, sampleCount)
        return result
    }

    fun reset() {
        firstChunkStartMillis = null
        absoluteSamplesWritten = 0L
        validSampleCount = 0
        writeIndex = 0
    }

    private fun copyFromBuffer(
        absoluteStartSample: Long,
        destination: FloatArray,
        sampleCount: Int
    ) {
        var copied = 0
        while (copied < sampleCount) {
            val sourceIndex = ((absoluteStartSample + copied) % capacity).toInt()
            val samplesUntilWrap = capacity - sourceIndex
            val copyCount = minOf(sampleCount - copied, samplesUntilWrap)
            System.arraycopy(buffer, sourceIndex, destination, copied, copyCount)
            copied += copyCount
        }
    }

    private fun millisToAbsoluteSample(millis: Long, firstStartMillis: Long): Long {
        val relativeMillis = millis - firstStartMillis
        return relativeMillis * sampleRate / 1_000L
    }

    private fun absoluteSampleToMillis(absoluteSample: Long): Long {
        return absoluteSample * 1_000L / sampleRate
    }

    private companion object {
        fun samplesForDuration(durationMillis: Long, sampleRate: Int): Int {
            val samples = durationMillis * sampleRate / 1_000L
            require(samples > 0) { "Rolling buffer must contain at least one sample." }
            require(samples <= Int.MAX_VALUE) { "Rolling buffer is too large." }
            return samples.toInt()
        }
    }
}
