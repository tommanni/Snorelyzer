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
    val groups: Set<RecordedEventGroup>,
    val eventSpans: List<EventSpanMetadata>,
    val peakProbabilities: Map<RecordedEventGroup, Float>,
    val sampleRate: Int,
    val durationMillis: Long,
    val filePath: String? = null
)

interface RecordingMetadataSink {
    fun onSessionStarted(session: RecordingSessionMetadata)
    fun onEpisodeCompleted(episode: RecordingEpisodeMetadata)
    fun onSessionCompleted(session: RecordingSessionMetadata)
    fun onSessionDiscarded(sessionId: String)
}

object NoOpRecordingMetadataSink : RecordingMetadataSink {
    override fun onSessionStarted(session: RecordingSessionMetadata) = Unit
    override fun onEpisodeCompleted(episode: RecordingEpisodeMetadata) = Unit
    override fun onSessionCompleted(session: RecordingSessionMetadata) = Unit
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

    // Keep classifying quiet continuations until the episode quiet timeout expires.
    val shouldForceInference: Boolean
        get() = activeEpisode != null

    fun startSession(startedAtMillis: Long) {
        reset()
        session = RecordingSessionMetadata(
            sessionId = "session-$startedAtMillis",
            startedAtMillis = startedAtMillis
        ).also(metadataSink::onSessionStarted)
    }

    fun onAudioChunk(chunk: FloatArray, chunkStartMillis: Long) {
        if (chunk.isEmpty()) return
        rollingBuffer.append(chunk, chunkStartMillis)
        receivedAudioChunkCount++
        lastAudioChunkStartMillis = chunkStartMillis
    }

    fun onClassificationWindow(
        windowStartMillis: Long,
        windowEndMillis: Long,
        occurringGroups: List<RecordedEventGroupResult>
    ) {
        require(windowEndMillis > windowStartMillis) { "Classification window must have positive duration." }
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
                lastPositiveWindowEndMillis = windowEndMillis
            ).also {
                activeEpisode = it
                nextEpisodeNumber++
            }

            episode.lastPositiveWindowEndMillis = windowEndMillis
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
        closeEpisodeIfQuiet(windowEndMillis)
    }

    fun onCaptureInterrupted() {
        closeActiveEpisode()
        rollingBuffer.reset()
    }

    fun stopSession(endedAtMillis: Long) {
        closeActiveEpisode()
        session = session?.copy(
            endedAtMillis = endedAtMillis,
            completedEpisodeCount = completedEpisodes.size
        )?.also(metadataSink::onSessionCompleted)
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

    private fun closeEpisodeIfQuiet(windowEndMillis: Long) {
        val episode = activeEpisode ?: return
        val quietForMillis = windowEndMillis - episode.lastPositiveWindowEndMillis
        if (quietForMillis >= config.episodeQuietTimeoutMillis && state != RecorderState.ActiveEvent) {
            closeActiveEpisode()
        }
    }

    private fun closeActiveEpisode() {
        val episode = activeEpisode ?: return

        activeSpans.values.forEach { span ->
            episode.eventSpans += span.toMetadata(config.postEventPadMillis)
        }
        activeSpans.clear()

        val bufferedBoundary = calculateClipBoundary(episode) ?: run {
            activeEpisode = null
            state = RecorderState.Idle
            session = session?.copy(completedEpisodeCount = completedEpisodes.size)
            return
        }
        val bufferedSamples = rollingBuffer.extract(
            bufferedBoundary.startMillis,
            bufferedBoundary.endMillis
        ) ?: run {
            activeEpisode = null
            state = RecorderState.Idle
            session = session?.copy(completedEpisodeCount = completedEpisodes.size)
            return
        }
        val bounds = clipTrimmer.findBounds(bufferedSamples)
        val clipBoundary = ClipBoundary(
            startMillis = bufferedBoundary.startMillis + bounds.startSample * 1_000L / config.sampleRate,
            endMillis = bufferedBoundary.startMillis + bounds.endSampleExclusive * 1_000L / config.sampleRate
        )
        val samples = if (bounds.startSample == 0 && bounds.endSampleExclusive == bufferedSamples.size) {
            bufferedSamples
        } else {
            bufferedSamples.copyOfRange(bounds.startSample, bounds.endSampleExclusive)
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
            groups = episode.groups.toSet(),
            eventSpans = episode.eventSpans.mapNotNull { span ->
                val start = maxOf(span.startedAtMillis, clipBoundary.startMillis)
                val end = minOf(span.endedAtMillis, clipBoundary.endMillis)
                if (end > start) span.copy(startedAtMillis = start, endedAtMillis = end) else null
            },
            peakProbabilities = episode.peakProbabilities.toMap(),
            sampleRate = writeResult.sampleRate,
            durationMillis = writeResult.durationMillis,
            filePath = writeResult.filePath
        ).also(metadataSink::onEpisodeCompleted)
        activeEpisode = null
        state = RecorderState.Idle
        session = session?.copy(completedEpisodeCount = completedEpisodes.size)
    }

    private fun calculateClipBoundary(episode: ActiveEpisode): ClipBoundary? {
        // Classification identifies the whole window, not the position of the sound inside it.
        val rawClipStartMillis = episode.firstPositiveWindowStartMillis - config.preEventPadMillis
        val rawClipEndMillis = maxOf(
            episode.lastPositiveWindowEndMillis + config.postEventPadMillis,
            rawClipStartMillis + config.minClipDurationMillis
        )
        val availableStartMillis = rollingBuffer.availableStartMillis ?: return null
        val availableEndMillis = rollingBuffer.availableEndMillis ?: return null
        val clipStartMillis = maxOf(rawClipStartMillis, availableStartMillis)
        val finalClipEndMillis = minOf(
            maxOf(rawClipEndMillis, clipStartMillis + config.minClipDurationMillis),
            clipStartMillis + config.maxClipDurationMillis,
            availableEndMillis
        )

        return if (finalClipEndMillis > clipStartMillis) {
            ClipBoundary(clipStartMillis, finalClipEndMillis)
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
        var lastPositiveWindowEndMillis: Long,
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
