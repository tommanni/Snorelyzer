package com.example.snorelyzer.ml.recording

import android.util.Log
import java.time.ZoneId

enum class RecordedEventGroup {
    Snoring,
    Gasp,
    Cough,
    SleepTalking
}

fun RecordedEventGroup.displayLabel(): String {
    return when (this) {
        RecordedEventGroup.Snoring -> "Snoring"
        RecordedEventGroup.Gasp -> "Gasp"
        RecordedEventGroup.Cough -> "Cough"
        RecordedEventGroup.SleepTalking -> "Sleep talking"
    }
}

data class RecordedEventConfig(
    val thresholds: Map<RecordedEventGroup, Float> = mapOf(
        RecordedEventGroup.Snoring to 0.15f,
        RecordedEventGroup.Gasp to 0.10f,
        RecordedEventGroup.Cough to 0.25f,
        RecordedEventGroup.SleepTalking to 0.25f
    ),
    val postEventPadMillis: Long = 4_000L,
    val rollingBufferDurationMillis: Long = 330_000L,
    val preEventPadMillis: Long = 2_000L,
    val clipEdgePadMillis: Long = 1_000L,
    val minClipDurationMillis: Long = 5_000L,
    val episodeQuietTimeoutMillis: Long = 20_000L,
    val maxClipDurationMillis: Long = 5 * 60_000L,
    val sampleRate: Int = 32_000
)

data class RecordingSessionMetadata(
    val sessionId: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long? = null,
    val completedEpisodeCount: Int = 0,
    val recordingZoneId: ZoneId = ZoneId.systemDefault()
)

data class EventSpanMetadata(
    val group: RecordedEventGroup,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val peakProbability: Float
)

data class RecordingEpisodeMetadata(
    val episodeId: String,
    val sessionId: String,
    val clipStartMillis: Long,
    val clipEndMillis: Long,
    val groups: Set<RecordedEventGroup>,
    val eventSpans: List<EventSpanMetadata>,
    val peakProbabilities: Map<RecordedEventGroup, Float>,
    val sampleRate: Int,
    val durationMillis: Long,
    val filePath: String? = null
)

interface RecordingMetadataSink {
    fun onSessionStarted(session: RecordingSessionMetadata, onFailure: () -> Unit)
    fun onEpisodeCompleted(episode: RecordingEpisodeMetadata, onFailure: () -> Unit)
    fun onSessionCompleted(session: RecordingSessionMetadata, onResult: (saved: Boolean) -> Unit)
    fun onSessionDiscarded(sessionId: String)
}

object NoOpRecordingMetadataSink : RecordingMetadataSink {
    override fun onSessionStarted(session: RecordingSessionMetadata, onFailure: () -> Unit) = Unit
    override fun onEpisodeCompleted(episode: RecordingEpisodeMetadata, onFailure: () -> Unit) = Unit
    override fun onSessionCompleted(session: RecordingSessionMetadata, onResult: (Boolean) -> Unit) = onResult(true)
    override fun onSessionDiscarded(sessionId: String) = Unit
}

class AudioEventRecorder(
    val config: RecordedEventConfig = RecordedEventConfig(),
    private val clipWriter: AudioClipWriter,
    private val metadataSink: RecordingMetadataSink = NoOpRecordingMetadataSink
) {
    private val rollingBuffer = RollingAudioBuffer(
        sampleRate = config.sampleRate,
        durationMillis = config.rollingBufferDurationMillis
    )

    private val clipTrimmer = AudioClipTrimmer(
        sampleRate = config.sampleRate,
        edgePadMillis = config.clipEdgePadMillis,
        minDurationMillis = config.minClipDurationMillis
    )

    private var session: RecordingSessionMetadata? = null
    private var onSaveFailure: () -> Unit = {}
    private var activeEpisode: ActiveEpisode? = null
    private var state: RecorderState = RecorderState.Idle
    private val activeSpans = mutableMapOf<RecordedEventGroup, ActiveSpan>()
    private val completedEpisodes = mutableListOf<RecordingEpisodeMetadata>()

    private var nextEpisodeNumber = 1
    private val segmentDurationMillis = minOf(
        config.maxClipDurationMillis,
        config.rollingBufferDurationMillis
    )

    init {
        require(segmentDurationMillis > 0) { "Clip duration must be positive." }
    }

    val currentSession: RecordingSessionMetadata?
        get() = session

    val completedEpisodeMetadata: List<RecordingEpisodeMetadata>
        get() = completedEpisodes.toList()

    // Keep classifying quiet continuations until the episode quiet timeout expires.
    val shouldForceInference: Boolean
        get() = activeEpisode != null

    fun startSession(startedAtMillis: Long, onSaveFailure: () -> Unit = {}) {
        reset()
        this.onSaveFailure = onSaveFailure
        session = RecordingSessionMetadata(
            sessionId = "session-$startedAtMillis",
            startedAtMillis = startedAtMillis
        ).also { metadataSink.onSessionStarted(it, onSaveFailure) }
    }

    fun onAudioChunk(chunk: FloatArray, chunkStartMillis: Long) {
        if (chunk.isEmpty()) return
        rollingBuffer.append(chunk, chunkStartMillis)
    }

    fun onClassificationWindow(
        windowStartMillis: Long,
        windowEndMillis: Long,
        occurringGroups: List<RecordedEventGroupResult>
    ) {
        require(windowEndMillis > windowStartMillis) { "Classification window must have positive duration." }
        if (session == null) return
        val hits = occurringGroups
            .filter { it.isOccurring }
            .associate { it.group to it.probability }

        if (hits.isNotEmpty()) {
            state = RecorderState.ActiveEvent
            val episode = activeEpisode ?: ActiveEpisode(
                clipStartMillis = maxOf(
                    windowStartMillis - config.preEventPadMillis,
                    rollingBuffer.availableStartMillis ?: Long.MIN_VALUE
                ),
                lastPositiveWindowEndMillis = windowEndMillis
            ).also {
                activeEpisode = it
            }

            episode.lastPositiveWindowEndMillis = windowEndMillis
            hits.forEach { (group, probability) ->
                val span = activeSpans[group]
                if (span == null) {
                    activeSpans[group] = ActiveSpan(
                        group = group,
                        startedAtMillis = maxOf(windowStartMillis, episode.clipStartMillis),
                        lastPositiveWindowEndMillis = windowEndMillis,
                        peakProbability = probability
                    )
                } else {
                    span.lastPositiveWindowEndMillis = windowEndMillis
                    span.peakProbability = maxOf(span.peakProbability, probability)
                }
            }
        } else if (activeEpisode != null && state == RecorderState.ActiveEvent) {
            state = RecorderState.PendingEpisodeClose
        }

        closeDroppedSpans(hits.keys)
        activeEpisode?.let { saveFullClips(it, hits) }
        closeEpisodeIfQuiet(windowEndMillis)
    }

    fun onCaptureInterrupted() {
        closeActiveEpisode()
        rollingBuffer.reset()
    }

    fun stopSession(endedAtMillis: Long, onResult: (saved: Boolean) -> Unit = {}) {
        closeActiveEpisode()
        val completedSession = session?.copy(
            endedAtMillis = endedAtMillis,
            completedEpisodeCount = completedEpisodes.size
        ) ?: return onResult(false)
        session = completedSession
        val reportSaveFailure = onSaveFailure
        metadataSink.onSessionCompleted(completedSession) { saved ->
            if (!saved) reportSaveFailure()
            onResult(saved)
        }
    }

    fun discardSession() {
        val sessionId = session?.sessionId ?: return
        completedEpisodes
            .mapNotNull { it.filePath }
            .forEach { clipWriter.deleteClip(it) }
        metadataSink.onSessionDiscarded(sessionId)
        reset()
    }

    fun reset() {
        session = null
        onSaveFailure = {}
        activeEpisode = null
        state = RecorderState.Idle
        activeSpans.clear()
        completedEpisodes.clear()
        nextEpisodeNumber = 1
        rollingBuffer.reset()
    }

    internal fun extractBufferedAudio(startMillis: Long, endMillis: Long): FloatArray? {
        return rollingBuffer.extract(startMillis, endMillis)
    }

    private fun closeDroppedSpans(activeGroups: Set<RecordedEventGroup>) {
        val droppedGroups = activeSpans.keys.filterNot { it in activeGroups }
        droppedGroups.forEach { group ->
            val span = activeSpans.remove(group) ?: return@forEach
            activeEpisode?.eventSpans?.add(span.toMetadata(config.postEventPadMillis))
        }
    }

    private fun closeEpisodeIfQuiet(windowEndMillis: Long) {
        val episode = activeEpisode ?: return
        val quietForMillis = windowEndMillis - episode.lastPositiveWindowEndMillis
        if (quietForMillis >= config.episodeQuietTimeoutMillis && state != RecorderState.ActiveEvent) {
            closeActiveEpisode()
        }
    }

    private fun closeActiveEpisode() {
        val episode = activeEpisode ?: return
        saveFullClips(episode, emptyMap())

        val availableStart = rollingBuffer.availableStartMillis
        val availableEnd = rollingBuffer.availableEndMillis
        if (availableStart != null && availableEnd != null) {
            val start = maxOf(episode.clipStartMillis, availableStart)
            val end = minOf(
                maxOf(
                    episode.lastPositiveWindowEndMillis + config.postEventPadMillis,
                    start + config.minClipDurationMillis
                ),
                start + segmentDurationMillis,
                availableEnd
            )
            if (end > start) {
                saveClip(episode, ClipBoundary(start, end), trimEdges = !episode.isContinuation)
            }
        }
        activeSpans.clear()
        activeEpisode = null
        state = RecorderState.Idle
    }

    private fun saveFullClips(episode: ActiveEpisode, hits: Map<RecordedEventGroup, Float>) {
        val availableStart = rollingBuffer.availableStartMillis ?: return
        val availableEnd = rollingBuffer.availableEndMillis ?: return
        episode.clipStartMillis = maxOf(episode.clipStartMillis, availableStart)
        var splitAt = episode.clipStartMillis + segmentDurationMillis
        // Wait for a positive window beyond the boundary so padding alone cannot open a new clip.
        while (splitAt < episode.lastPositiveWindowEndMillis && splitAt <= availableEnd) {
            saveClip(episode, ClipBoundary(episode.clipStartMillis, splitAt), trimEdges = false)
            episode.clipStartMillis = splitAt
            episode.isContinuation = true
            episode.eventSpans.removeAll { it.endedAtMillis <= splitAt }
            activeSpans.values.forEach { span ->
                span.startedAtMillis = maxOf(span.startedAtMillis, splitAt)
                // The window that crossed the split is the first evidence in this segment.
                span.peakProbability = hits[span.group] ?: span.peakProbability
            }
            splitAt += segmentDurationMillis
        }
    }

    private fun saveClip(episode: ActiveEpisode, bufferedBoundary: ClipBoundary, trimEdges: Boolean) {
        val currentSession = session ?: return
        val bufferedSamples = rollingBuffer.extract(
            bufferedBoundary.startMillis,
            bufferedBoundary.endMillis
        ) ?: return
        val bounds = if (trimEdges) {
            clipTrimmer.findBounds(bufferedSamples)
        } else {
            AudioClipTrimmer.Bounds(0, bufferedSamples.size)
        }
        val clipBoundary = ClipBoundary(
            startMillis = bufferedBoundary.startMillis + bounds.startSample * 1_000L / config.sampleRate,
            endMillis = bufferedBoundary.startMillis + bounds.endSampleExclusive * 1_000L / config.sampleRate
        )
        val samples = if (bounds.startSample == 0 && bounds.endSampleExclusive == bufferedSamples.size) {
            bufferedSamples
        } else {
            bufferedSamples.copyOfRange(bounds.startSample, bounds.endSampleExclusive)
        }
        val episodeId = "${currentSession.sessionId}-episode-${nextEpisodeNumber++}"
        val writeResult = try {
            clipWriter.writeClip(
                AudioClipWriteRequest(
                    episodeId = episodeId,
                    sessionId = currentSession.sessionId,
                    samples = samples,
                    sampleRate = config.sampleRate,
                    clipStartMillis = clipBoundary.startMillis,
                    clipEndMillis = clipBoundary.endMillis
                )
            )
        } catch (error: Exception) {
            onSaveFailure()
            runCatching { Log.e("AudioEventRecorder", "Failed to write WAV for $episodeId", error) }
            return
        }

        val spans = (episode.eventSpans + activeSpans.values.map {
            it.toMetadata(config.postEventPadMillis)
        }).mapNotNull { it.clippedTo(bufferedBoundary) }
        val peakProbabilities = spans.groupingBy { it.group }.fold(0f) { peak, span ->
            maxOf(peak, span.peakProbability)
        }
        completedEpisodes += RecordingEpisodeMetadata(
            episodeId = episodeId,
            sessionId = currentSession.sessionId,
            clipStartMillis = clipBoundary.startMillis,
            clipEndMillis = clipBoundary.endMillis,
            groups = peakProbabilities.keys,
            eventSpans = spans.mapNotNull { it.clippedTo(clipBoundary) },
            peakProbabilities = peakProbabilities,
            sampleRate = writeResult.sampleRate,
            durationMillis = writeResult.durationMillis,
            filePath = writeResult.filePath
        ).also { metadataSink.onEpisodeCompleted(it, onSaveFailure) }
        session = currentSession.copy(completedEpisodeCount = completedEpisodes.size)
    }

    private fun EventSpanMetadata.clippedTo(boundary: ClipBoundary): EventSpanMetadata? {
        val start = maxOf(startedAtMillis, boundary.startMillis)
        val end = minOf(endedAtMillis, boundary.endMillis)
        return if (end > start) copy(startedAtMillis = start, endedAtMillis = end) else null
    }

    private enum class RecorderState {
        Idle,
        ActiveEvent,
        PendingEpisodeClose
    }

    private data class ActiveEpisode(
        var clipStartMillis: Long,
        var lastPositiveWindowEndMillis: Long,
        var isContinuation: Boolean = false,
        val eventSpans: MutableList<EventSpanMetadata> = mutableListOf()
    )

    private data class ClipBoundary(
        val startMillis: Long,
        val endMillis: Long
    )

    private data class ActiveSpan(
        val group: RecordedEventGroup,
        var startedAtMillis: Long,
        var lastPositiveWindowEndMillis: Long,
        var peakProbability: Float
    ) {
        fun toMetadata(postEventPadMillis: Long): EventSpanMetadata {
            return EventSpanMetadata(
                group = group,
                startedAtMillis = startedAtMillis,
                endedAtMillis = lastPositiveWindowEndMillis + postEventPadMillis,
                peakProbability = peakProbability
            )
        }
    }
}
