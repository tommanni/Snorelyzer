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
    fun stableBackgroundSkipsPreprocessingAndInference() {
        val processor = FakeMelSpectrogramProcessor()
        val classifier = FakeRelevantSleepClassifier()
        val pipeline = pipeline(processor = processor, classifier = classifier)

        pipeline.reset()
        repeat(3) { index ->
            pipeline.processChunk(FloatArray(chunkSize), index * 1_000L, forceInference = false)
        }
        val result = pipeline.processChunk(FloatArray(chunkSize), 3_000L, forceInference = false)

        assertTrue(result is AudioProcessingResult.Skipped)
        assertEquals(2, processor.processedInputs.size)
        assertEquals(2, classifier.classifyCalls)

        val afterHop = pipeline.processChunk(FloatArray(chunkSize), 4_000L, forceInference = false)
        assertTrue(afterHop is AudioProcessingResult.Skipped)
    }

    @Test
    fun forceInferenceClassifiesStableBackground() {
        val classifier = FakeRelevantSleepClassifier()
        val pipeline = pipeline(classifier = classifier)

        pipeline.reset()
        repeat(3) { index ->
            pipeline.processChunk(FloatArray(chunkSize), index * 1_000L, forceInference = false)
        }
        val beforeHop = pipeline.processChunk(FloatArray(chunkSize), 3_000L, forceInference = true)
        val result = pipeline.processChunk(FloatArray(chunkSize), 4_000L, forceInference = true)

        assertTrue(beforeHop is AudioProcessingResult.Skipped)
        assertTrue(result is AudioProcessingResult.Classified)
        assertEquals(3, classifier.classifyCalls)
    }

    @Test
    fun inferenceIncludesAudioFromTheSkippedChunk() {
        val processor = FakeMelSpectrogramProcessor()
        val pipeline = pipeline(processor = processor)

        pipeline.processChunk(FloatArray(chunkSize) { 0.1f }, 0L, forceInference = true)
        pipeline.processChunk(FloatArray(chunkSize) { 0.2f }, 1_000L, forceInference = true)
        val result = pipeline.processChunk(FloatArray(chunkSize) { 0.3f }, 2_000L, forceInference = true)
            as AudioProcessingResult.Classified

        assertEquals(2, processor.processedInputs.size)
        val input = processor.processedInputs.last()
        assertEquals(0.2f, input[input.size - 2 * chunkSize])
        assertEquals(0.3f, input[input.size - chunkSize])
        assertEquals(0L, result.windowStartMillis)
        assertEquals(75, result.occurringGroupsByFrame.size)
    }

    @Test
    fun classifierOutputIsAggregatedToEventGroups() {
        val classifier = FakeRelevantSleepClassifier(
            results = listOf(
                ClassificationResult(index = 345, probability = 0.30f),
                ClassificationResult(index = 176, probability = 0.40f),
                ClassificationResult(index = 46, probability = 0.04f)
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
        assertTrue(classified.occurringGroupsByFrame.all { it == classified.groupResults })
    }

    @Test
    fun respiratoryDiagnosticsRequestIndividualScoresWithoutRecordingBreathing() {
        val classifier = FakeRelevantSleepClassifier(
            results = listOf(
                ClassificationResult(index = 46, probability = 0.2033f),
                ClassificationResult(index = 176, probability = 0.1027f),
                ClassificationResult(index = 346, probability = 0.1014f),
                ClassificationResult(index = 344, probability = 0.0005f)
            )
        )
        val pipeline = pipeline(classifier = classifier)

        val result = pipeline.processChunk(FloatArray(chunkSize), 0L, forceInference = false)
            as AudioProcessingResult.Classified

        assertTrue(classifier.requestedClassIndices.containsAll(setOf(46, 176, 344, 346)))
        assertTrue(result.groupResults.isEmpty())
        assertTrue(result.occurringGroupsByFrame.all { it.isEmpty() })
    }

    @Test
    fun quieterGaspOccursWhenItsScoreExceedsBreathing() {
        val classifier = FakeRelevantSleepClassifier(
            results = listOf(
                ClassificationResult(index = 46, probability = 0.0120f),
                ClassificationResult(index = 176, probability = 0.1299f),
                ClassificationResult(index = 346, probability = 0.0014f),
                ClassificationResult(index = 344, probability = 0.0002f)
            )
        )
        val pipeline = pipeline(classifier = classifier)

        val result = pipeline.processChunk(FloatArray(chunkSize), 0L, forceInference = false)
            as AudioProcessingResult.Classified

        assertEquals(RecordedEventGroup.Gasp, result.occurringGroupsByFrame.first().single().group)
    }

    @Test
    fun scoresOnlyRealFramesAndComparesGaspWithBreathingOnEachFrame() {
        val classifier = FakeRelevantSleepClassifier(
            frameScores = mapOf(
                105 to FloatArray(250).apply { this[224] = 0.99f },
                176 to FloatArray(250).apply {
                    this[225] = 0.15f
                    this[226] = 0.2f
                },
                46 to FloatArray(250) { 0.5f }.apply { this[226] = 0.04f }
            )
        )
        val result = pipeline(classifier = classifier)
            .processChunk(FloatArray(chunkSize), 1_000L, forceInference = true)
            as AudioProcessingResult.Classified

        assertEquals(1_000L, result.windowStartMillis)
        assertEquals(25, result.occurringGroupsByFrame.size)
        assertTrue(result.occurringGroupsByFrame[0].isEmpty())
        assertEquals(RecordedEventGroup.Gasp, result.occurringGroupsByFrame[1].single().group)
        assertEquals(1, result.occurringGroupsByFrame.count { it.isNotEmpty() })
    }

    @Test
    fun positiveClassifierFeedbackKeepsStartupSnoringEligible() {
        val pipeline = pipeline(classifier = FakeRelevantSleepClassifier(
            results = listOf(ClassificationResult(index = 345, probability = 0.9f))
        ))
        repeat(30) { second ->
            val result = pipeline.processChunk(sineChunk(0.1f), second * 1_000L, false)
            assertEquals("Chunk $second", second % 2 == 0, result is AudioProcessingResult.Classified)
        }
    }

    @Test
    fun fluctuatingBackgroundWithLowTargetScoresEventuallyUsesProbes() {
        val pipeline = pipeline(classifier = FakeRelevantSleepClassifier(
            results = listOf(ClassificationResult(index = 353, probability = 0.032f))
        ))
        val results = List(100) { second ->
            val scale = listOf(0.85f, 1.1f, 1f, 0.95f)[second % 4]
            val chunk = FloatArray(chunkSize) { index ->
                val amplitude = if (index < 12_000) 0.003f else 0.02f
                scale * amplitude * sin(2.0 * PI * 80.0 * index / 32_000.0).toFloat()
            }
            pipeline.processChunk(chunk, second * 1_000L, false)
        }
        assertTrue(results.any { it.gateDecision.onsetDb > 6f })
        assertTrue(results.takeLast(20).count { it is AudioProcessingResult.Skipped } >= 12)
        assertTrue(results.takeLast(20).any { it.gateDecision.triggerType == "probe" })
        // Acceptance of a fluctuating environment must not suppress a new loud event.
        val burst = pipeline.processChunk(sineChunk(0.2f), 100_000L, false)
        assertTrue(burst is AudioProcessingResult.Classified)
        assertEquals("activity", burst.gateDecision.triggerType)
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

        override fun process(audioData: FloatArray): FloatArray {
            processedInputs += audioData.copyOf()
            return FloatArray(AudioModelConfig.MEL_TENSOR_SIZE)
        }
    }

    private class FakeRelevantSleepClassifier(
        private val results: List<ClassificationResult> = emptyList(),
        private val frameScores: Map<Int, FloatArray> = emptyMap()
    ) : RelevantSleepClassifier {
        var classifyCalls = 0
        var closeCalls = 0
        var requestedClassIndices = emptySet<Int>()
            private set

        override fun classifyRelevant(
            melSpectrogram: FloatArray,
            relevantClassIndices: Set<Int>
        ): Map<Int, FloatArray> {
            classifyCalls++
            requestedClassIndices = relevantClassIndices
            return (results.associate { result ->
                result.index to FloatArray(AudioModelConfig.OUTPUT_FRAMES) { result.probability }
            } + frameScores).filterKeys { it in relevantClassIndices }
        }

        override fun close() {
            closeCalls++
        }
    }
}
