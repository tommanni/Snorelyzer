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
    ): Map<Int, FloatArray> {
        require(melSpectrogram.size == AudioModelConfig.MEL_TENSOR_SIZE) {
            "Expected ${AudioModelConfig.MEL_TENSOR_SIZE} mel values, got ${melSpectrogram.size}"
        }
        inputBuffers[0].writeFloat(melSpectrogram)
        model.run(inputBuffers, outputBuffers)
        return frameProbabilities(outputBuffers[0].readFloat(), relevantClassIndices)
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

internal fun frameProbabilities(
    logits: FloatArray,
    relevantClassIndices: Set<Int>
): Map<Int, FloatArray> {
    val classes = AudioModelConfig.OUTPUT_CLASSES
    val frames = AudioModelConfig.OUTPUT_FRAMES
    require(logits.size == classes * frames) {
        "Expected [1, $classes, $frames] frame logits, got ${logits.size} values"
    }

    return relevantClassIndices
        .associateWith { index ->
            require(index in 0 until classes) { "Class index $index is outside the fmn06 output" }
            val start = index * frames
            FloatArray(frames) { frame -> 1f / (1f + exp(-logits[start + frame])) }
        }
}
