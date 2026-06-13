package com.example.snorelyzer.ml

import android.util.Log
import com.example.snorelyzer.ml.recording.RecordedEventCatalog
import com.example.snorelyzer.ml.recording.RecordedEventConfig
import com.example.snorelyzer.ml.recording.RecordedEventGroupResult
import com.example.snorelyzer.ml.recording.displayLabel

interface MelSpectrogramProcessor {
    fun reset()
    fun process(audioData: FloatArray): FloatArray
}

interface RelevantSleepClassifier {
    fun classifyRelevant(
        melSpectrogram: FloatArray,
        relevantClassIndices: Set<Int>
    ): List<ClassificationResult>

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
        val occurringGroupResults: List<RecordedEventGroupResult>
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
    private val totalSamples = 320_000
    private val stepSamples = 32_000
    private val sampleRate = 32_000
    private val audioBuffer = FloatArray(totalSamples)
    private val mlInputBuffer = FloatArray(totalSamples)
    private val paddedMLBuffer = FloatArray(totalSamples)

    private var validSamples = 0
    private var writeIndex = 0

    override fun reset() {
        validSamples = 0
        writeIndex = 0
        audioProcessor.reset()
        audioGate.reset()
    }

    override fun processChunk(
        chunk: FloatArray,
        chunkStartMillis: Long,
        forceInference: Boolean
    ): AudioProcessingResult {
        require(chunk.size == stepSamples) {
            "Expected $stepSamples samples, got ${chunk.size}"
        }

        System.arraycopy(chunk, 0, audioBuffer, writeIndex, stepSamples)
        writeIndex = (writeIndex + stepSamples) % totalSamples
        validSamples = minOf(totalSamples, validSamples + stepSamples)

        val windowEndMillis = chunkStartMillis + samplesToMillis(chunk.size)
        val windowStartMillis = windowEndMillis - samplesToMillis(validSamples)
        val gateDecision = audioGate.analyze(chunk)
        val shouldInfer = gateDecision.shouldInfer || forceInference
        logGateDecision(gateDecision, forceInference)

        if (!shouldInfer) {
            audioProcessor.reset()
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
            val relevantResults = classifier.classifyRelevant(
                melTensor,
                RecordedEventCatalog.relevantClassIndices
            )
            val groupResults = RecordedEventCatalog.aggregate(
                relevantResults,
                recordedEventConfig
            )
            val t2 = System.nanoTime()
            logClassificationTiming(t0, t1, t2)
            logClassificationResults(start, groupResults)
            AudioProcessingResult.Classified(
                windowStartMillis = windowStartMillis,
                gateDecision = gateDecision,
                groupResults = groupResults,
                occurringGroupResults = groupResults.filter { it.isOccurring }
            )
        }.getOrElse { error ->
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
                "reasons=${gateDecision.reasons.joinToString("|")}"
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
