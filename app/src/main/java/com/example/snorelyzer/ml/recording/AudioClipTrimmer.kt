package com.example.snorelyzer.ml.recording

/** Trims only outer background; gaps between sounds remain part of the episode. */
internal class AudioClipTrimmer(
    private val sampleRate: Int,
    private val edgePadMillis: Long,
    private val minDurationMillis: Long
) {
    data class Bounds(val startSample: Int, val endSampleExclusive: Int)

    fun findBounds(samples: FloatArray): Bounds {
        val fullBounds = Bounds(0, samples.size)
        if (samples.isEmpty()) return fullBounds
        val frameSamples = maxOf(1, sampleRate / 20) // 50 ms frames
        val energies = DoubleArray((samples.size + frameSamples - 1) / frameSamples) { frame ->
            val start = frame * frameSamples
            val end = minOf(start + frameSamples, samples.size)
            var energy = 0.0
            for (index in start until end) {
                val sample = samples[index].toDouble()
                energy += sample * sample
            }
            energy / (end - start)
        }
        if (energies.any { !it.isFinite() }) return fullBounds
        val sorted = energies.sortedArray()
        val backgroundEnergy = maxOf(sorted[sorted.lastIndex / 5], 1e-16)
        // Without a clear separation from background, keep the full clip. Avoid
        // thresholds relative to the loudest sound: they can erase a quiet snore.
        if (sorted.last() < backgroundEnergy * 4.0) return fullBounds
        val activityThreshold = backgroundEnergy * 2.0 // about 3 dB above background
        val firstFrame = energies.indexOfFirst { it > activityThreshold }
        val lastFrame = energies.indexOfLast { it > activityThreshold }
        if (firstFrame < 0) return fullBounds

        val padSamples = (edgePadMillis * sampleRate / 1_000L).toInt()
        var start = maxOf(0, firstFrame * frameSamples - padSamples)
        var end = minOf(samples.size, (lastFrame + 1) * frameSamples + padSamples)
        val minimumSamples = minOf(samples.size.toLong(), minDurationMillis * sampleRate / 1_000L).toInt()
        if (end - start < minimumSamples) {
            val missing = minimumSamples - (end - start)
            start = maxOf(0, start - missing / 2)
            end = minOf(samples.size, maxOf(end, start + minimumSamples))
            start = maxOf(0, minOf(start, end - minimumSamples))
        }
        return Bounds(start, end)
    }
}
