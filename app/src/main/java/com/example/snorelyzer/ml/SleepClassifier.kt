package com.example.snorelyzer.ml

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import kotlin.math.exp
import kotlin.math.max

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
        require(melSpectrogram.size == AudioModelConfig.MEL_TENSOR_SIZE) {
            "Expected ${AudioModelConfig.MEL_TENSOR_SIZE} mel values, got ${melSpectrogram.size}"
        }
        inputBuffers[0].writeFloat(melSpectrogram)
        model.run(inputBuffers, outputBuffers)
        return poolFrameLogits(outputBuffers[0].readFloat(), relevantClassIndices)
    }

    private fun validateInputShape() {
        try {
            inputBuffers[0].writeFloat(FloatArray(AudioModelConfig.MEL_TENSOR_SIZE))
        } catch (e: Exception) {
            throw IllegalStateException(
                "Expected ${AudioModelConfig.MODEL_ASSET} to accept " +
                    "[1, 1, ${AudioModelConfig.N_MELS}, ${AudioModelConfig.MEL_FRAMES}] float input",
                e
            )
        }
    }

    override fun close() {
        inputBuffers.forEach { it.close() }
        outputBuffers.forEach { it.close() }

        model.close()
        environment.close()
    }
}

internal fun poolFrameLogits(
    logits: FloatArray,
    relevantClassIndices: Set<Int>
): List<ClassificationResult> {
    val classes = AudioModelConfig.OUTPUT_CLASSES
    val frames = AudioModelConfig.OUTPUT_FRAMES
    require(logits.size == classes * frames) {
        "Expected [1, $classes, $frames] frame logits, got ${logits.size} values"
    }

    // A short cough or gasp can occupy only a few frames in a ten-second window.
    return relevantClassIndices
        .filter { it in 0 until classes }
        .map { index ->
            val start = index * frames
            var peak = logits[start]
            for (position in start + 1 until start + frames) {
                peak = max(peak, logits[position])
            }
            ClassificationResult(index, 1f / (1f + exp(-peak)))
        }
        .sortedByDescending { it.probability }
}
