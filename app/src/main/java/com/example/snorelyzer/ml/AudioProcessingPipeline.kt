package com.example.snorelyzer.ml

import android.util.Log
import com.example.snorelyzer.MicrophoneCapture
import com.example.snorelyzer.ml.recording.RecordedEventCatalog
import com.example.snorelyzer.ml.recording.RecordedEventConfig
import com.example.snorelyzer.ml.recording.RecordedEventGroupResult
import com.example.snorelyzer.ml.recording.displayLabel
import java.util.Locale

fun interface MelSpectrogramProcessor {
    fun process(audioData: FloatArray): FloatArray
}

interface RelevantSleepClassifier {
    fun classifyRelevant(
        melSpectrogram: FloatArray,
        relevantClassIndices: Set<Int>
    ): Map<Int, FloatArray>

    fun close()
}

sealed interface AudioProcessingResult {
    val windowStartMillis: Long
    val gateDecision: AudioGateDecision

    data class Skipped(
        override val windowStartMillis: Long,
        override val gateDecision: AudioGateDecision
    ) : AudioProcessingResult

    data class Classified(
        override val windowStartMillis: Long,
        override val gateDecision: AudioGateDecision,
        val groupResults: List<RecordedEventGroupResult>,
        val occurringGroupsByFrame: List<List<RecordedEventGroupResult>>
    ) : AudioProcessingResult

    data class Failed(
        override val windowStartMillis: Long,
        override val gateDecision: AudioGateDecision,
        val error: Throwable
    ) : AudioProcessingResult
}

interface SleepAudioProcessor {
    fun reset()
    fun processChunk(
        chunk: FloatArray,
        chunkStartMillis: Long,
        forceInference: Boolean
    ): AudioProcessingResult
    fun close()
}

class AudioProcessingPipeline(
    private val audioProcessor: MelSpectrogramProcessor,
    private val audioGate: AudioGate,
    private val classifier: RelevantSleepClassifier,
    private val recordedEventConfig: RecordedEventConfig = RecordedEventConfig()
) : SleepAudioProcessor {
    private val totalSamples = AudioModelConfig.CAPTURE_WINDOW_SAMPLES
    private val sampleRate = MicrophoneCapture.SAMPLE_RATE
    private val chunkSamples = MicrophoneCapture.SAMPLES_PER_CHUNK
    private val audioBuffer = FloatArray(totalSamples)
    private val mlInputBuffer = FloatArray(totalSamples)
    private val paddedMLBuffer = FloatArray(totalSamples)
    private val respiratoryScoreLabels = mapOf(
        RecordedEventCatalog.GASP_CLASS_INDEX to "Gasp",
        RecordedEventCatalog.SNORT_CLASS_INDEX to "Snort",
        RecordedEventCatalog.SNIFF_CLASS_INDEX to "Sniff",
        RecordedEventCatalog.BREATHING_CLASS_INDEX to "Breathing"
    )
    private val inferenceClassIndices = RecordedEventCatalog.relevantClassIndices + respiratoryScoreLabels.keys

    private var validSamples = 0
    private var writeIndex = 0

    override fun reset() {
        validSamples = 0
        writeIndex = 0
        audioGate.reset()
    }

    override fun processChunk(
        chunk: FloatArray,
        chunkStartMillis: Long,
        forceInference: Boolean
    ): AudioProcessingResult {
        require(chunk.size == chunkSamples) {
            "Expected $chunkSamples samples, got ${chunk.size}"
        }

        System.arraycopy(chunk, 0, audioBuffer, writeIndex, chunkSamples)
        writeIndex = (writeIndex + chunkSamples) % totalSamples
        validSamples = minOf(totalSamples, validSamples + chunkSamples)

        val windowEndMillis = chunkStartMillis + samplesToMillis(chunk.size)
        val windowStartMillis = windowEndMillis - samplesToMillis(validSamples)
        val gateDecision = audioGate.analyze(
            chunk,
            protectBackground = forceInference,
            minimumInferenceIntervalSeconds = AudioModelConfig.INFERENCE_HOP_SECONDS
        )
        logGateDecision(gateDecision, forceInference)

        if (!gateDecision.shouldInfer) {
            return AudioProcessingResult.Skipped(
                windowStartMillis = windowStartMillis,
                gateDecision = gateDecision
            )
        }

        val start = System.currentTimeMillis()
        return runCatching {
            val finalMLInput = if (validSamples >= totalSamples) {
                System.arraycopy(audioBuffer, writeIndex, mlInputBuffer, 0, totalSamples - writeIndex)
                System.arraycopy(audioBuffer, 0, mlInputBuffer, totalSamples - writeIndex, writeIndex)
                mlInputBuffer
            } else {
                System.arraycopy(audioBuffer, 0, mlInputBuffer, totalSamples - validSamples, validSamples)
                fillPaddedBuffer(paddedMLBuffer, mlInputBuffer, validSamples)
                paddedMLBuffer
            }

            val t0 = System.nanoTime()
            val melTensor = audioProcessor.process(finalMLInput)
            val t1 = System.nanoTime()
            val probabilities = classifier.classifyRelevant(melTensor, inferenceClassIndices)
            val realFrameCount = validSamples * AudioModelConfig.OUTPUT_FRAMES_PER_SECOND / sampleRate
            val firstRealFrame = AudioModelConfig.OUTPUT_FRAMES - realFrameCount
            // Warm-up mirrors audio on the left. Only score frames aligned with real samples.
            val groupsByFrame = (firstRealFrame until AudioModelConfig.OUTPUT_FRAMES).map { frame ->
                RecordedEventCatalog.aggregate(
                    probabilities.map { (index, scores) -> ClassificationResult(index, scores[frame]) },
                    recordedEventConfig
                )
            }
            val groupResults = peakGroupResults(groupsByFrame)
            // Protect below the recording threshold too. These sigmoid scores are
            // uncalibrated: a low target score alone is not proof of background.
            audioGate.observeClassification(
                windowStartMillis = windowStartMillis,
                windowEndMillis = windowEndMillis,
                hasTargetEvidence = groupResults.any { it.probability >= it.threshold * 0.5f },
                confidentBackground = groupResults.isNotEmpty() &&
                    groupResults.all { it.probability < it.threshold * 0.25f }
            )
            val t2 = System.nanoTime()
            logClassificationTiming(t0, t1, t2)
            logClassificationResults(start, groupResults)
            logRespiratoryScores(probabilities, firstRealFrame)
            AudioProcessingResult.Classified(
                windowStartMillis = windowStartMillis,
                gateDecision = gateDecision,
                groupResults = groupResults,
                occurringGroupsByFrame = groupsByFrame.map { results -> results.filter { it.isOccurring } }
            )
        }.getOrElse { error ->
            audioGate.observeClassification(windowStartMillis, windowEndMillis,
                hasTargetEvidence = false, confidentBackground = false)
            logError("SleepTracker", "Processing failed", error)
            AudioProcessingResult.Failed(
                windowStartMillis = windowStartMillis,
                gateDecision = gateDecision,
                error = error
            )
        }
    }

    override fun close() {
        classifier.close()
    }

    private fun logGateDecision(gateDecision: AudioGateDecision, forceInference: Boolean) {
        logDebug(
            "AudioGate",
            "state=${gateDecision.state} infer=${gateDecision.shouldInfer} " +
                "forceInference=$forceInference " +
                "trigger=${gateDecision.triggerType} " +
                "noiseFloorDb=${"%.1f".format(gateDecision.noiseFloorDb)} " +
                "rmsDb=${"%.1f".format(gateDecision.rmsDb)} " +
                "relativeDb=${"%.1f".format(gateDecision.relativeDb)} " +
                "maxFrameRelativeDb=${"%.1f".format(gateDecision.maxFrameRelativeDb)} " +
                "onsetDb=${"%.1f".format(gateDecision.onsetDb)} " +
                "zcr=${"%.3f".format(gateDecision.zcr)} " +
                "zcrDelta=${"%.3f".format(gateDecision.zcrDelta)} " +
                "crest=${"%.2f".format(gateDecision.crestFactor)} " +
                "crestDelta=${"%.2f".format(gateDecision.crestDelta)} " +
                "activityScore=${gateDecision.activityScore} " +
                "stable=${gateDecision.isStableBackground} " +
                "reasons=${gateDecision.reasons.joinToString("|")} " +
                "backgroundWindowSeconds=${gateDecision.backgroundWindowSeconds} " +
                "negativeWindows=${gateDecision.negativeWindowCount} " +
                "protected=${gateDecision.backgroundProtected}"
        )
    }

    private fun logClassificationTiming(
        preprocessingStartedAtNanos: Long,
        inferenceStartedAtNanos: Long,
        inferenceEndedAtNanos: Long
    ) {
        logDebug(
            "Timing",
            "Preprocessing: ${(inferenceStartedAtNanos - preprocessingStartedAtNanos) / 1_000_000}ms, " +
                "Inference: ${(inferenceEndedAtNanos - inferenceStartedAtNanos) / 1_000_000}ms"
        )
    }

    private fun logClassificationResults(
        inferenceStartedAtMillis: Long,
        groupResults: List<RecordedEventGroupResult>
    ) {
        if (groupResults.isEmpty()) return

        val top1 = groupResults[0]
        val top2 = if (groupResults.size > 1) groupResults[1] else null
        val logMsg = buildString {
            append("Inference ${System.currentTimeMillis() - inferenceStartedAtMillis}ms | ")
            append("Top relevant: ${top1.group.displayLabel()} via ${top1.sourceLabel} (${"%.3f".format(top1.probability)})")
            if (top2 != null) {
                append(" | 2nd: ${top2.group.displayLabel()} via ${top2.sourceLabel} (${"%.3f".format(top2.probability)})")
            }
        }
        logDebug("SleepTracker", logMsg)
    }

    private fun peakGroupResults(
        groupsByFrame: List<List<RecordedEventGroupResult>>
    ): List<RecordedEventGroupResult> {
        return groupsByFrame.asSequence()
            .flatten()
            .groupingBy { it.group }
            .reduce { _, peak, result -> if (result.probability > peak.probability) result else peak }
            .values
            .sortedByDescending { it.probability }
    }

    private fun logRespiratoryScores(probabilities: Map<Int, FloatArray>, firstRealFrame: Int) {
        val scores = respiratoryScoreLabels.entries.joinToString(" | ") { (index, label) ->
            val probability = probabilities[index]?.let { values ->
                (firstRealFrame until values.size).maxOf { values[it] }
            }
            val score = probability?.let { "%.4f".format(Locale.ROOT, it) } ?: "n/a"
            "$label=$score"
        }
        logDebug("SleepTracker", "Respiratory scores | $scores")
    }

    private fun logDebug(tag: String, message: String) {
        runCatching { Log.d(tag, message) }
    }

    private fun logError(tag: String, message: String, error: Throwable) {
        runCatching { Log.e(tag, message, error) }
    }

    private fun samplesToMillis(samples: Int): Long {
        return samples * 1_000L / sampleRate
    }

    private fun fillPaddedBuffer(dest: FloatArray, source: FloatArray, validCount: Int) {
        val total = dest.size
        val sourceOffset = total - validCount

        System.arraycopy(source, sourceOffset, dest, total - validCount, validCount)

        var filled = validCount
        var forward = false

        while (filled < total) {
            val toFill = minOf(validCount, total - filled)
            val destPos = total - filled - toFill

            if (forward) {
                System.arraycopy(source, sourceOffset, dest, destPos, toFill)
            } else {
                for (i in 0 until toFill) {
                    dest[destPos + i] = source[total - 1 - i]
                }
            }

            filled += toFill
            forward = !forward
        }
    }
}
