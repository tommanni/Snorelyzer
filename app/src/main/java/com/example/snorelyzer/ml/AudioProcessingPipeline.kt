package com.example.snorelyzer.ml

import com.example.snorelyzer.ml.recording.RecordedEventCatalog
import com.example.snorelyzer.ml.recording.RecordedEventConfig
import com.example.snorelyzer.ml.recording.RecordedEventGroupResult

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

        if (!shouldInfer) {
            audioProcessor.reset()
            return AudioProcessingResult.Skipped(
                windowStartMillis = windowStartMillis,
                gateDecision = gateDecision
            )
        }

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

            val melTensor = audioProcessor.process(finalMLInput)
            val relevantResults = classifier.classifyRelevant(
                melTensor,
                RecordedEventCatalog.relevantClassIndices
            )
            val groupResults = RecordedEventCatalog.aggregate(
                relevantResults,
                recordedEventConfig
            )
            AudioProcessingResult.Classified(
                windowStartMillis = windowStartMillis,
                gateDecision = gateDecision,
                groupResults = groupResults,
                occurringGroupResults = groupResults.filter { it.isOccurring }
            )
        }.getOrElse { error ->
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
