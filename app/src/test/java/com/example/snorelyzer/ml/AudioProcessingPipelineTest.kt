package com.example.snorelyzer.ml

import com.example.snorelyzer.ml.recording.RecordedEventGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AudioProcessingPipelineTest {
    private val chunkSize = 32_000

    @Test
    fun startupChunkIsMirroredIntoFullMlInput() {
        val processor = FakeMelSpectrogramProcessor()
        val pipeline = pipeline(processor = processor)
        val chunk = FloatArray(chunkSize) { it.toFloat() }

        pipeline.reset()
        pipeline.processChunk(chunk, chunkStartMillis = 1_000L, forceInference = true)

        val input = processor.processedInputs.single()
        assertEquals(320_000, input.size)
        assertEquals(chunk.last(), input[256_000])
        assertEquals(chunk[chunk.lastIndex - 1], input[256_001])
        assertEquals(chunk.first(), input[288_000])
        assertEquals(chunk.last(), input.last())
    }

    @Test
    fun fullBufferIsChronologicalAfterWraparound() {
        val processor = FakeMelSpectrogramProcessor()
        val pipeline = pipeline(processor = processor)

        pipeline.reset()
        repeat(11) { index ->
            pipeline.processChunk(
                chunk = FloatArray(chunkSize) { index.toFloat() },
                chunkStartMillis = index * 1_000L,
                forceInference = true
            )
        }

        val input = processor.processedInputs.last()
        assertEquals(1.0f, input.first())
        assertEquals(1.0f, input[chunkSize - 1])
        assertEquals(2.0f, input[chunkSize])
        assertEquals(10.0f, input.last())
    }

    @Test
    fun stableBackgroundSkipsInferenceAndResetsProcessor() {
        val processor = FakeMelSpectrogramProcessor()
        val classifier = FakeRelevantSleepClassifier()
        val pipeline = pipeline(processor = processor, classifier = classifier)

        pipeline.reset()
        repeat(3) { index ->
            pipeline.processChunk(FloatArray(chunkSize), index * 1_000L, forceInference = false)
        }
        val result = pipeline.processChunk(FloatArray(chunkSize), 3_000L, forceInference = false)

        assertTrue(result is AudioProcessingResult.Skipped)
        assertEquals(2, processor.resetCount)
        assertEquals(3, classifier.classifyCalls)
    }

    @Test
    fun forceInferenceClassifiesStableBackground() {
        val classifier = FakeRelevantSleepClassifier()
        val pipeline = pipeline(classifier = classifier)

        pipeline.reset()
        repeat(3) { index ->
            pipeline.processChunk(FloatArray(chunkSize), index * 1_000L, forceInference = false)
        }
        val result = pipeline.processChunk(FloatArray(chunkSize), 3_000L, forceInference = true)

        assertTrue(result is AudioProcessingResult.Classified)
        assertEquals(4, classifier.classifyCalls)
    }

    @Test
    fun classifierOutputIsAggregatedToEventGroups() {
        val classifier = FakeRelevantSleepClassifier(
            results = listOf(
                ClassificationResult(index = 43, probability = 0.30f),
                ClassificationResult(index = 44, probability = 0.40f)
            )
        )
        val pipeline = pipeline(classifier = classifier)

        pipeline.reset()
        val result = pipeline.processChunk(
            chunk = sineChunk(amplitude = 0.1f),
            chunkStartMillis = 0L,
            forceInference = true
        )

        val classified = result as AudioProcessingResult.Classified
        assertEquals(
            listOf(RecordedEventGroup.Gasp, RecordedEventGroup.Snoring),
            classified.groupResults.map { it.group }
        )
        assertEquals(classified.groupResults, classified.occurringGroupResults)
    }

    private fun pipeline(
        processor: FakeMelSpectrogramProcessor = FakeMelSpectrogramProcessor(),
        classifier: FakeRelevantSleepClassifier = FakeRelevantSleepClassifier()
    ): AudioProcessingPipeline {
        return AudioProcessingPipeline(
            audioProcessor = processor,
            audioGate = AudioGate(),
            classifier = classifier
        )
    }

    private fun sineChunk(amplitude: Float): FloatArray {
        return FloatArray(chunkSize) { index ->
            (amplitude * sin(2.0 * PI * 120.0 * index / 32_000.0)).toFloat()
        }
    }

    private class FakeMelSpectrogramProcessor : MelSpectrogramProcessor {
        val processedInputs = mutableListOf<FloatArray>()
        var resetCount = 0

        override fun reset() {
            resetCount++
        }

        override fun process(audioData: FloatArray): FloatArray {
            processedInputs += audioData.copyOf()
            return FloatArray(AudioModelConfig.MEL_TENSOR_SIZE)
        }
    }

    private class FakeRelevantSleepClassifier(
        private val results: List<ClassificationResult> = emptyList()
    ) : RelevantSleepClassifier {
        var classifyCalls = 0
        var closeCalls = 0

        override fun classifyRelevant(
            melSpectrogram: FloatArray,
            relevantClassIndices: Set<Int>
        ): List<ClassificationResult> {
            classifyCalls++
            return results
        }

        override fun close() {
            closeCalls++
        }
    }
}
