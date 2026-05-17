package com.example.snorelyzer.ml

import android.content.Context
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import kotlin.math.exp

data class ClassificationResult(
    val index: Int,
    val label: String,
    val probability: Float
)

class SleepClassifier(context: Context) {
    private val environment: Environment = Environment.create()
    private val model: CompiledModel
    private val classLabels = Array(527) { "Unknown" }

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
        loadLabels(context)
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

    private fun loadLabels(context: Context) {
        try {
            context.assets.open("class_labels_indices.csv").bufferedReader().useLines { lines ->
                // drop(1) skips the header row
                lines.drop(1).forEach { line ->
                    val firstComma = line.indexOf(',')
                    val secondComma = line.indexOf(',', firstComma + 1)

                    if (firstComma != -1 && secondComma != -1) {
                        val indexStr = line.substring(0, firstComma)
                        // Extracts the label and removes quotes
                        val labelStr = line.substring(secondComma + 1).removeSurrounding("\"")

                        val index = indexStr.toIntOrNull()

                        // Assign to array if valid
                        if (index != null && index in classLabels.indices) {
                            classLabels[index] = labelStr
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AudioClassifier", "Failed to load AudioSet labels from assets", e)
        }
    }

    /**
     * Classifies a 64x1000 Mel-spectrogram tensor (flattened to 64,000 elements).
     * Returns a list of the top classifications.
     */
    fun classify(melSpectrogram: FloatArray, topK: Int = 3): List<ClassificationResult> {
        require(melSpectrogram.size == AudioModelConfig.MEL_TENSOR_SIZE) {
            "Expected ${AudioModelConfig.MEL_TENSOR_SIZE} mel values for " +
                "${AudioModelConfig.N_MELS}x${AudioModelConfig.EXPECTED_FRAMES} input, got ${melSpectrogram.size}"
        }

        inputBuffers[0].writeFloat(melSpectrogram)
        model.run(inputBuffers, outputBuffers)
        val logits = outputBuffers[0].readFloat()

        // Apply sigmoid to convert logits to probabilities
        val probabilities = logits.map { 1.0f / (1.0f + exp(-it)) }

        // Debug: Log more probabilities with higher precision
        Log.d("SleepClassifier", "Probabilities (first 10): ${probabilities.take(10).joinToString { "%.6f".format(it) }}")

        return probabilities.mapIndexed { index, prob ->
            ClassificationResult(index, classLabels.getOrElse(index) { "Unknown" }, prob)
        }.sortedByDescending { it.probability }.take(topK)
    }

    fun close() {
        inputBuffers.forEach { it.close() }
        outputBuffers.forEach { it.close() }

        model.close()
        environment.close()
    }
}
