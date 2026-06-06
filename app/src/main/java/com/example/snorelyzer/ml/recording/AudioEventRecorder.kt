package com.example.snorelyzer.ml.recording

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
    val clipStartOffsetMillis: Long = 7_000L,
    val clipEndOffsetMillis: Long = 3_000L,
    val minClipDurationMillis: Long = 5_000L,
    val episodeQuietTimeoutMillis: Long = 20_000L,
    val maxClipDurationMillis: Long = 5 * 60_000L,
    val sampleRate: Int = 32_000
)

data class RecordingSessionMetadata(
    val sessionId: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long? = null,
    val completedEpisodeCount: Int = 0
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
    val dominantGroup: RecordedEventGroup,
    val groups: Set<RecordedEventGroup>,
    val eventSpans: List<EventSpanMetadata>,
    val peakProbabilities: Map<RecordedEventGroup, Float>,
    val sampleRate: Int,
    val durationMillis: Long,
    val filePath: String? = null
)

class AudioEventRecorder(
    val config: RecordedEventConfig = RecordedEventConfig(),
    private val clipWriter: AudioClipWriter
) {
    private val rollingBuffer = RollingAudioBuffer(
        sampleRate = config.sampleRate,
        durationMillis = config.rollingBufferDurationMillis
    )

    private var session: RecordingSessionMetadata? = null
    private var activeEpisode: ActiveEpisode? = null
    private var state: RecorderState = RecorderState.Idle
    private val activeSpans = mutableMapOf<RecordedEventGroup, ActiveSpan>()
    private val completedEpisodes = mutableListOf<RecordingEpisodeMetadata>()

    private var nextEpisodeNumber = 1
    private var receivedAudioChunkCount = 0
    private var lastAudioChunkStartMillis: Long? = null

    val currentSession: RecordingSessionMetadata?
        get() = session

    val completedEpisodeMetadata: List<RecordingEpisodeMetadata>
        get() = completedEpisodes.toList()

    val shouldForceInference: Boolean
        get() = state == RecorderState.ActiveEvent

    fun startSession(startedAtMillis: Long) {
        reset()
        session = RecordingSessionMetadata(
            sessionId = "session-$startedAtMillis",
            startedAtMillis = startedAtMillis
        )
    }

    fun onAudioChunk(chunk: FloatArray, chunkStartMillis: Long) {
        if (chunk.isEmpty()) return
        rollingBuffer.append(chunk, chunkStartMillis)
        receivedAudioChunkCount++
        lastAudioChunkStartMillis = chunkStartMillis
    }

    fun onClassificationWindow(
        windowStartMillis: Long,
        occurringGroups: List<RecordedEventGroupResult>
    ) {
        val currentSession = session ?: return
        val hits = occurringGroups
            .filter { it.isOccurring }
            .associate { it.group to it.probability }

        if (hits.isNotEmpty()) {
            state = RecorderState.ActiveEvent
            val episode = activeEpisode ?: ActiveEpisode(
                episodeId = "${currentSession.sessionId}-episode-$nextEpisodeNumber",
                sessionId = currentSession.sessionId,
                firstPositiveWindowStartMillis = windowStartMillis,
                lastPositiveWindowStartMillis = windowStartMillis
            ).also {
                activeEpisode = it
                nextEpisodeNumber++
            }

            episode.lastPositiveWindowStartMillis = windowStartMillis
            hits.forEach { (group, probability) ->
                episode.groups += group
                episode.peakProbabilities[group] = maxOf(
                    episode.peakProbabilities[group] ?: 0f,
                    probability
                )

                val span = activeSpans[group]
                if (span == null) {
                    activeSpans[group] = ActiveSpan(
                        group = group,
                        startedAtMillis = windowStartMillis,
                        lastPositiveWindowStartMillis = windowStartMillis,
                        peakProbability = probability
                    )
                } else {
                    span.lastPositiveWindowStartMillis = windowStartMillis
                    span.peakProbability = maxOf(span.peakProbability, probability)
                }
            }
        } else if (activeEpisode != null && state == RecorderState.ActiveEvent) {
            state = RecorderState.PendingEpisodeClose
        }

        closeDroppedSpans(hits.keys)
        closeEpisodeIfQuiet(windowStartMillis)
    }

    fun stopSession(endedAtMillis: Long) {
        closeActiveEpisode()
        session = session?.copy(
            endedAtMillis = endedAtMillis,
            completedEpisodeCount = completedEpisodes.size
        )
    }

    fun reset() {
        session = null
        activeEpisode = null
        state = RecorderState.Idle
        activeSpans.clear()
        completedEpisodes.clear()
        nextEpisodeNumber = 1
        receivedAudioChunkCount = 0
        lastAudioChunkStartMillis = null
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

    private fun closeEpisodeIfQuiet(windowStartMillis: Long) {
        val episode = activeEpisode ?: return
        val quietForMillis = windowStartMillis - episode.lastPositiveWindowStartMillis
        if (quietForMillis >= config.episodeQuietTimeoutMillis) {
            closeActiveEpisode()
        }
    }

    private fun closeActiveEpisode() {
        val episode = activeEpisode ?: return

        activeSpans.values.forEach { span ->
            episode.eventSpans += span.toMetadata(config.postEventPadMillis)
        }
        activeSpans.clear()

        val dominantGroup = episode.peakProbabilities.maxByOrNull { it.value }?.key ?: return
        val clipBoundary = calculateClipBoundary(episode) ?: run {
            activeEpisode = null
            state = RecorderState.Idle
            session = session?.copy(completedEpisodeCount = completedEpisodes.size)
            return
        }
        val samples = rollingBuffer.extract(
            clipBoundary.startMillis,
            clipBoundary.endMillis
        ) ?: run {
            activeEpisode = null
            state = RecorderState.Idle
            session = session?.copy(completedEpisodeCount = completedEpisodes.size)
            return
        }
        val writeResult = runCatching {
            clipWriter.writeClip(
                AudioClipWriteRequest(
                    episodeId = episode.episodeId,
                    sessionId = episode.sessionId,
                    samples = samples,
                    sampleRate = config.sampleRate,
                    clipStartMillis = clipBoundary.startMillis,
                    clipEndMillis = clipBoundary.endMillis
                )
            )
        }.getOrNull() ?: run {
            activeEpisode = null
            state = RecorderState.Idle
            session = session?.copy(completedEpisodeCount = completedEpisodes.size)
            return
        }

        completedEpisodes += RecordingEpisodeMetadata(
            episodeId = episode.episodeId,
            sessionId = episode.sessionId,
            clipStartMillis = clipBoundary.startMillis,
            clipEndMillis = clipBoundary.endMillis,
            dominantGroup = dominantGroup,
            groups = episode.groups.toSet(),
            eventSpans = episode.eventSpans.toList(),
            peakProbabilities = episode.peakProbabilities.toMap(),
            sampleRate = writeResult.sampleRate,
            durationMillis = writeResult.durationMillis,
            filePath = writeResult.filePath
        )
        activeEpisode = null
        state = RecorderState.Idle
        session = session?.copy(completedEpisodeCount = completedEpisodes.size)
    }

    private fun calculateClipBoundary(episode: ActiveEpisode): ClipBoundary? {
        // Discard first clipStartOffsetMillis of silent audio from the start
        val rawClipStartMillis = episode.firstPositiveWindowStartMillis + config.clipStartOffsetMillis
        val rawClipEndMillis = maxOf(
            episode.lastPositiveWindowStartMillis + config.clipEndOffsetMillis,
            rawClipStartMillis + config.minClipDurationMillis
        )
        val cappedClipEndMillis = minOf(
            rawClipEndMillis,
            rawClipStartMillis + config.maxClipDurationMillis
        )

        val availableStartMillis = rollingBuffer.availableStartMillis ?: return null
        val availableEndMillis = rollingBuffer.availableEndMillis ?: return null
        val clipStartMillis = maxOf(rawClipStartMillis, availableStartMillis)
        val clipEndMillis = minOf(cappedClipEndMillis, availableEndMillis)

        return if (clipEndMillis > clipStartMillis) {
            ClipBoundary(clipStartMillis, clipEndMillis)
        } else {
            null
        }
    }

    private enum class RecorderState {
        Idle,
        ActiveEvent,
        PendingEpisodeClose
    }

    private data class ActiveEpisode(
        val episodeId: String,
        val sessionId: String,
        val firstPositiveWindowStartMillis: Long,
        var lastPositiveWindowStartMillis: Long,
        val groups: MutableSet<RecordedEventGroup> = mutableSetOf(),
        val eventSpans: MutableList<EventSpanMetadata> = mutableListOf(),
        val peakProbabilities: MutableMap<RecordedEventGroup, Float> = mutableMapOf()
    )

    private data class ClipBoundary(
        val startMillis: Long,
        val endMillis: Long
    )

    private data class ActiveSpan(
        val group: RecordedEventGroup,
        val startedAtMillis: Long,
        var lastPositiveWindowStartMillis: Long,
        var peakProbability: Float
    ) {
        fun toMetadata(postEventPadMillis: Long): EventSpanMetadata {
            return EventSpanMetadata(
                group = group,
                startedAtMillis = startedAtMillis,
                endedAtMillis = lastPositiveWindowStartMillis + postEventPadMillis,
                peakProbability = peakProbability
            )
        }
    }
}
