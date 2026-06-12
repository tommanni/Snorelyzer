package com.example.snorelyzer.ml

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import kotlin.math.exp

data class ClassificationResult(
    val index: Int,
    val probability: Float
)

class SleepClassifier(context: Context) : RelevantSleepClassifier {
    private val environment: Environment = Environment.create()
    private val model: CompiledModel

    // Allocate buffers when they are accessed the first time using lazy
    private val inputBuffers by lazy { model.createInputBuffers() }
    private val outputBuffers by lazy { model.createOutputBuffers() }

    init {
        val options = CompiledModel.Options(Accelerator.CPU)

        model = CompiledModel.create(
            context.assets,
            AudioModelConfig.MODEL_ASSET,
            options,
            environment
        )

        validateInputShape()
    }

    override fun classifyRelevant(
        melSpectrogram: FloatArray,
        relevantClassIndices: Set<Int>
    ): List<ClassificationResult> {
        val probabilities = runInference(melSpectrogram)

        return relevantClassIndices
            .filter { it in probabilities.indices }
            .map { index ->
                ClassificationResult(index, probabilities[index])
            }
            .sortedByDescending { it.probability }
    }

    private fun validateInputShape() {
        try {
            inputBuffers[0].writeFloat(FloatArray(AudioModelConfig.MEL_TENSOR_SIZE))
        } catch (e: Exception) {
            throw IllegalStateException(
                "Expected ${AudioModelConfig.MODEL_ASSET} to accept " +
                    "${AudioModelConfig.N_MELS}x${AudioModelConfig.EXPECTED_FRAMES} float input " +
                    "(${AudioModelConfig.MEL_TENSOR_BYTES} bytes)",
                e
            )
        }
    }

    private fun runInference(melSpectrogram: FloatArray): List<Float> {
        require(melSpectrogram.size == AudioModelConfig.MEL_TENSOR_SIZE) {
            "Expected ${AudioModelConfig.MEL_TENSOR_SIZE} mel values for " +
                "${AudioModelConfig.N_MELS}x${AudioModelConfig.EXPECTED_FRAMES} input, got ${melSpectrogram.size}"
        }

        inputBuffers[0].writeFloat(melSpectrogram)
        model.run(inputBuffers, outputBuffers)
        val logits = outputBuffers[0].readFloat()

        // Apply sigmoid to convert logits to probabilities
        return logits.map { 1.0f / (1.0f + exp(-it)) }
    }

    override fun close() {
        inputBuffers.forEach { it.close() }
        outputBuffers.forEach { it.close() }

        model.close()
        environment.close()
    }
}
