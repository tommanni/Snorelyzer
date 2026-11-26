package com.example.snorelyzer.ml.recording

import com.example.snorelyzer.ml.AudioModelConfig.OUTPUT_FRAME_MILLIS

internal class EventFrameTimeline(
    private val onInterval: (Long, Long, List<RecordedEventGroupResult>) -> Unit
) {
    private var nextFrameStartMillis: Long? = null
    private var latest: ScoredWindow? = null

    val hasPendingHits: Boolean
        get() {
            val window = latest ?: return false
            val first = window.firstFrameFrom(checkNotNull(nextFrameStartMillis))
            return (first until window.frames.size).any { window.frames[it].isNotEmpty() }
        }

    fun onClassified(windowStartMillis: Long, frames: List<List<RecordedEventGroupResult>>) {
        // Finish any cached scores that the new window can no longer cover.
        emitThrough(windowStartMillis)
        advancePastMissingAudio(windowStartMillis)
        val scored = ScoredWindow(windowStartMillis, frames)
        latest = scored
        emitThrough(scored.endMillis - LOOKAHEAD_MILLIS)
    }

    fun onUnclassified(windowStartMillis: Long) {
        emitThrough(windowStartMillis)
        // Keep unscored audio eligible for backfill until it leaves the model window.
        advancePastMissingAudio(windowStartMillis)
    }

    fun flush(throughMillis: Long = Long.MAX_VALUE) {
        emitThrough(throughMillis)
    }

    fun reset() {
        nextFrameStartMillis = null
        latest = null
    }

    private fun emitThrough(throughMillis: Long) {
        val window = latest ?: return
        val end = minOf(throughMillis, window.endMillis)
        if (end <= window.startMillis) return
        val first = window.firstFrameFrom(checkNotNull(nextFrameStartMillis))
        val afterLast = ((end - window.startMillis) / OUTPUT_FRAME_MILLIS).toInt()
        for (index in first until afterLast) {
            val start = window.startMillis + index * OUTPUT_FRAME_MILLIS
            val frameEnd = start + OUTPUT_FRAME_MILLIS
            onInterval(start, frameEnd, window.frames[index])
            nextFrameStartMillis = frameEnd
        }
    }

    private fun advancePastMissingAudio(windowStartMillis: Long) {
        val next = nextFrameStartMillis
        if (next == null || next < windowStartMillis) {
            if (next != null) onInterval(next, windowStartMillis, emptyList())
            nextFrameStartMillis = windowStartMillis
        }
    }

    private data class ScoredWindow(
        val startMillis: Long,
        val frames: List<List<RecordedEventGroupResult>>
    ) {
        val endMillis: Long get() = startMillis + frames.size * OUTPUT_FRAME_MILLIS

        fun firstFrameFrom(millis: Long): Int {
            val elapsed = (millis - startMillis).coerceIn(0L, endMillis - startMillis)
            return ((elapsed + OUTPUT_FRAME_MILLIS - 1) / OUTPUT_FRAME_MILLIS).toInt()
        }
    }

    private companion object {
        const val LOOKAHEAD_MILLIS = 1_000L
    }
}
